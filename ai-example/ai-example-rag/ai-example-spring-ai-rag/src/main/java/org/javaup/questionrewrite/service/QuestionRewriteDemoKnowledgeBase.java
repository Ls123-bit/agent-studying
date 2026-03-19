package org.javaup.questionrewrite.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 独立的问题改写演示知识库。
 * 这里使用内存向量库，并在首次调用时再初始化，避免影响现有模块启动流程。
 */
@Service
public class QuestionRewriteDemoKnowledgeBase {

    private static final List<Document> DEMO_DOCUMENTS = List.of(
        new Document(
            "rewrite-001",
            """
                问题改写（Query Rewrite）的核心目标，是把用户当前的口语化问题、代词化问题，
                改写成一个可以脱离上下文独立检索的完整问题。
                当用户说“它”“那个方案”“上面提到的做法”时，改写器会结合对话历史补全真实检索意图。
                """,
            Map.of("topic", "问题改写", "section", "概念")
        ),
        new Document(
            "rewrite-002",
            """
                在 Spring AI 1.1 里，可以使用 RewriteQueryTransformer 来完成问题改写。
                它通常和 RetrievalAugmentationAdvisor 组合使用：先改写查询，再去向量数据库或其他知识库检索上下文。
                """,
            Map.of("topic", "Spring AI", "section", "核心类")
        ),
        new Document(
            "rewrite-003",
            """
                问题改写特别适合四类场景：多轮追问、问题过短、口语表达强、用户关键词和知识库表述不一致。
                如果用户的问题已经完整且明确，是否启用改写要谨慎，因为过度改写可能引入新的歧义。
                """,
            Map.of("topic", "问题改写", "section", "适用场景")
        ),
        new Document(
            "rewrite-004",
            """
                MultiQueryExpander 和问题改写的侧重点不同。
                MultiQueryExpander 会把一个问题扩展成多个检索查询，目标是提升召回率；
                RewriteQueryTransformer 会把一个问题改写成更清晰的单一查询，目标是提升检索精度。
                """,
            Map.of("topic", "检索增强", "section", "问题改写 vs 多路召回")
        ),
        new Document(
            "rewrite-005",
            """
                CompressionQueryTransformer 更适合处理特别长、噪声较多的问题。
                它会压缩掉与检索无关的信息，保留核心检索意图；
                而问题改写更强调补全上下文、省略信息和口语化表达。
                """,
            Map.of("topic", "检索增强", "section", "问题改写 vs 查询压缩")
        ),
        new Document(
            "rewrite-006",
            """
                一个典型的改写案例是：
                历史问题：“Spring AI 里的问题改写是干什么的？”
                当前追问：“那它更适合什么时候用？”
                改写后可变成：“在 Spring AI RAG 场景中，问题改写适合在什么情况下使用？”
                这样的 query 更容易命中知识库中的正式表述。
                """,
            Map.of("topic", "问题改写", "section", "案例")
        ),
        new Document(
            "rewrite-007",
            """
                工程实践里，比较稳妥的顺序是：先问题改写，再做文档检索，最后把检索上下文拼进提示词。
                如果你的底层检索系统是向量数据库，可以把 targetSearchSystem 明确告诉改写器，
                让它优先生成适合语义检索的查询表达。
                """,
            Map.of("topic", "工程实践", "section", "推荐链路")
        )
    );

    private final EmbeddingModel embeddingModel;

    private volatile VectorStore vectorStore;

    public QuestionRewriteDemoKnowledgeBase(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public VectorStore getVectorStore() {
        VectorStore localVectorStore = this.vectorStore;
        if (localVectorStore != null) {
            return localVectorStore;
        }
        synchronized (this) {
            if (this.vectorStore == null) {
                SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(this.embeddingModel).build();
                simpleVectorStore.add(DEMO_DOCUMENTS);
                this.vectorStore = simpleVectorStore;
            }
            return this.vectorStore;
        }
    }
}
