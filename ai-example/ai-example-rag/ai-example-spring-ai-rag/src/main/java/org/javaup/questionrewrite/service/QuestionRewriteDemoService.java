package org.javaup.questionrewrite.service;


import org.javaup.questionrewrite.model.QuestionRewriteAnswerResponse;
import org.javaup.questionrewrite.model.QuestionRewriteChatTurn;
import org.javaup.questionrewrite.model.QuestionRewritePreviewResponse;
import org.javaup.questionrewrite.model.QuestionRewriteRequest;
import org.javaup.questionrewrite.model.RetrievedDocumentView;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Spring AI 问题改写实战示例服务。
 */
@Service
public class QuestionRewriteDemoService {

    private static final String DEFAULT_TARGET_SEARCH_SYSTEM = "向量数据库";
    private static final int DEFAULT_TOP_K = 4;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.15D;
    private static final String ANSWER_SYSTEM_PROMPT = """
        你是一名 Spring AI RAG 示例助手。
        请优先根据检索到的上下文回答问题，不要脱离上下文臆造结论。
        如果上下文不足以支持答案，请明确告诉用户信息不足。
        回答尽量简洁，并在合适时点明“问题改写”为什么有助于命中知识库。
        """;

    private final ChatClient.Builder chatClientBuilder;
    private final QuestionRewriteDemoKnowledgeBase knowledgeBase;
    private final String defaultChatModel;

    public QuestionRewriteDemoService(
        ChatClient.Builder chatClientBuilder,
        QuestionRewriteDemoKnowledgeBase knowledgeBase,
        @Value("${app.rag.question-rewrite.chat-model:deepseek-chat}") String defaultChatModel
    ) {
        this.chatClientBuilder = chatClientBuilder;
        this.knowledgeBase = knowledgeBase;
        this.defaultChatModel = defaultChatModel;
    }

    public QuestionRewritePreviewResponse preview(QuestionRewriteRequest request) {
        QuestionRewriteRequest validatedRequest = validate(request);
        Query originalQuery = buildQuery(validatedRequest);
        RewriteQueryTransformer transformer = buildRewriteTransformer(validatedRequest);
        Query rewrittenQuery = transformer.transform(originalQuery);
        List<RetrievedDocumentView> retrievedDocuments = toDocumentViews(buildDocumentRetriever(validatedRequest).retrieve(rewrittenQuery));
        return new QuestionRewritePreviewResponse(
            validatedRequest.question(),
            rewrittenQuery.text(),
            resolveChatModel(validatedRequest),
            resolveTargetSearchSystem(validatedRequest),
            retrievedDocuments
        );
    }

    public QuestionRewriteAnswerResponse ask(QuestionRewriteRequest request) {
        QuestionRewriteRequest validatedRequest = validate(request);
        TrackingQueryTransformer trackingRewriteTransformer = new TrackingQueryTransformer(buildRewriteTransformer(validatedRequest));
        RetrievalAugmentationAdvisor retrievalAdvisor = RetrievalAugmentationAdvisor.builder()
            .queryTransformers(trackingRewriteTransformer)
            .documentRetriever(buildDocumentRetriever(validatedRequest))
            .build();

        ChatClient.CallResponseSpec responseSpec = buildChatClient(validatedRequest).prompt()
            .advisors(retrievalAdvisor)
            .system(ANSWER_SYSTEM_PROMPT)
            .messages(toMessages(validatedRequest.safeHistory()))
            .user(validatedRequest.question())
            .call();

        ChatClientResponse chatClientResponse = responseSpec.chatClientResponse();
        List<RetrievedDocumentView> retrievedDocuments = extractRetrievedDocuments(chatClientResponse);

        return new QuestionRewriteAnswerResponse(
            validatedRequest.question(),
            trackingRewriteTransformer.lastTransformedText(validatedRequest.question()),
            responseSpec.content(),
            resolveChatModel(validatedRequest),
            resolveTargetSearchSystem(validatedRequest),
            retrievedDocuments
        );
    }

    private QuestionRewriteRequest validate(QuestionRewriteRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        if (!StringUtils.hasText(request.question())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        for (QuestionRewriteChatTurn turn : request.safeHistory()) {
            if (turn == null || !StringUtils.hasText(turn.content())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "history 中的每条消息都必须包含 content");
            }
            if (!StringUtils.hasText(turn.role())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "history 中的每条消息都必须包含 role");
            }
        }
        return request;
    }

    private Query buildQuery(QuestionRewriteRequest request) {
        return Query.builder()
            .text(request.question())
            .history(toMessages(request.safeHistory()))
            .context(Map.of(
                "feature", "question-rewrite-demo",
                "targetSearchSystem", resolveTargetSearchSystem(request)
            ))
            .build();
    }

    private RewriteQueryTransformer buildRewriteTransformer(QuestionRewriteRequest request) {
        return RewriteQueryTransformer.builder()
            .chatClientBuilder(buildChatClientBuilder(request))
            .targetSearchSystem(resolveTargetSearchSystem(request))
            .build();
    }

    private DocumentRetriever buildDocumentRetriever(QuestionRewriteRequest request) {
        return VectorStoreDocumentRetriever.builder()
            .vectorStore(this.knowledgeBase.getVectorStore())
            .topK(resolveTopK(request))
            .similarityThreshold(resolveSimilarityThreshold(request))
            .build();
    }

    private ChatClient buildChatClient(QuestionRewriteRequest request) {
        return buildChatClientBuilder(request).build();
    }

    private ChatClient.Builder buildChatClientBuilder(QuestionRewriteRequest request) {
        return this.chatClientBuilder.clone()
            .defaultOptions(OpenAiChatOptions.builder()
                .model(resolveChatModel(request))
                .temperature(0.1D)
                .build());
    }

    private String resolveChatModel(QuestionRewriteRequest request) {
        return StringUtils.hasText(request.chatModel()) ? request.chatModel().trim() : this.defaultChatModel;
    }

    private String resolveTargetSearchSystem(QuestionRewriteRequest request) {
        return StringUtils.hasText(request.targetSearchSystem())
            ? request.targetSearchSystem().trim()
            : DEFAULT_TARGET_SEARCH_SYSTEM;
    }

    private int resolveTopK(QuestionRewriteRequest request) {
        return request.topK() != null && request.topK() > 0 ? request.topK() : DEFAULT_TOP_K;
    }

    private double resolveSimilarityThreshold(QuestionRewriteRequest request) {
        return request.similarityThreshold() != null
            ? request.similarityThreshold()
            : DEFAULT_SIMILARITY_THRESHOLD;
    }

    private List<Message> toMessages(List<QuestionRewriteChatTurn> history) {
        List<Message> messages = new ArrayList<>();
        for (QuestionRewriteChatTurn turn : history) {
            String role = turn.role().trim().toLowerCase();
            String content = turn.content().trim();
            switch (role) {
                case "user" -> messages.add(new UserMessage(content));
                case "assistant", "ai" -> messages.add(new AssistantMessage(content));
                case "system" -> messages.add(new SystemMessage(content));
                default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "暂不支持的 role: " + turn.role() + "，可选值为 user、assistant、system"
                );
            }
        }
        return messages;
    }

    private List<RetrievedDocumentView> extractRetrievedDocuments(ChatClientResponse chatClientResponse) {
        Object documentContext = chatClientResponse.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (!(documentContext instanceof List<?> rawDocuments)) {
            return List.of();
        }
        List<Document> documents = rawDocuments.stream()
            .filter(Document.class::isInstance)
            .map(Document.class::cast)
            .filter(Objects::nonNull)
            .toList();
        return toDocumentViews(documents);
    }

    private List<RetrievedDocumentView> toDocumentViews(List<Document> documents) {
        return documents.stream()
            .map(document -> new RetrievedDocumentView(
                document.getId(),
                document.getScore(),
                document.getText(),
                document.getMetadata()
            ))
            .toList();
    }

    /**
     * 包一层官方 QueryTransformer，记录当前请求真正产出的改写结果。
     */
    private static final class TrackingQueryTransformer implements QueryTransformer {

        private final QueryTransformer delegate;
        private final ThreadLocal<Query> lastTransformedQuery = new ThreadLocal<>();

        private TrackingQueryTransformer(QueryTransformer delegate) {
            this.delegate = delegate;
        }

        @Override
        public Query transform(Query query) {
            Query transformedQuery = this.delegate.transform(query);
            this.lastTransformedQuery.set(transformedQuery);
            return transformedQuery;
        }

        private String lastTransformedText(String fallbackQuestion) {
            Query transformedQuery = this.lastTransformedQuery.get();
            this.lastTransformedQuery.remove();
            return transformedQuery == null ? fallbackQuestion : transformedQuery.text();
        }
    }
}
