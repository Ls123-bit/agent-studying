package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentProfile;
import org.javaup.ai.manage.data.SuperAgentKnowledgeRouteTrace;
import org.javaup.ai.manage.data.SuperAgentKnowledgeScopeNode;
import org.javaup.ai.manage.data.SuperAgentKnowledgeTopicNode;
import org.javaup.ai.manage.data.SuperAgentTopicDocumentRelation;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentProfileMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeRouteTraceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeScopeNodeMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeTopicNodeMapper;
import org.javaup.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.javaup.ai.manage.model.route.DocumentRouteCandidate;
import org.javaup.ai.manage.model.route.KnowledgeRouteDecision;
import org.javaup.ai.manage.model.route.ScopeRouteCandidate;
import org.javaup.ai.manage.model.route.TopicRouteCandidate;
import org.javaup.ai.manage.service.KnowledgeRouteIndexService;
import org.javaup.ai.manage.service.KnowledgeRouteService;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentIndexStatusEnum;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/
@Slf4j
@AllArgsConstructor
@Service
/*
知识路由（三层漏斗的核心实现）

 */
public class KnowledgeRouteServiceImpl implements KnowledgeRouteService {

    private static final int ROUTE_STATUS_SUCCESS = 1;
    private static final int ROUTE_STATUS_LOW_CONFIDENCE = 2;
    private static final int ROUTE_STATUS_FAILED = 3;
    private static final int ROUTE_EMBEDDING_BATCH_SIZE = 10;

    private final SuperAgentDocumentMapper documentMapper;
    private final SuperAgentDocumentProfileMapper documentProfileMapper;
    private final SuperAgentKnowledgeScopeNodeMapper scopeNodeMapper;
    private final SuperAgentKnowledgeTopicNodeMapper topicNodeMapper;
    private final SuperAgentTopicDocumentRelationMapper topicDocumentRelationMapper;
    private final SuperAgentKnowledgeRouteTraceMapper knowledgeRouteTraceMapper;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectProvider<KnowledgeRouteIndexService> knowledgeRouteIndexServiceProvider;
    private final UidGenerator uidGenerator;

















    /*
    route（）流程
1.构建查询上下文 — buildQueryContext(question, rewriteQuestion)
    拼接路由文本
    分词提取查询词
    计算查询向量（Embedding）
2.Scope 排名 — rankScopes(queryContext) /识别知识域候选
    对所有 Scope 节点计算混合分数
    混合分数 = 语义主分 + 词法辅助分 + 关键词实体辅助分
3.Topic 排名 — rankTopics(queryContext, scopeCandidates) /识别主题候选
    在 top Scope 下的 Topic 中计算混合分数
    可能从文档 Profile 反推 Topic
4.Document 排名 — rankDocuments(queryContext, scopeCandidates, topicCandidates)  /对具体文档打分排序
    对候选文档计算综合分数
    考虑 Scope/Topic 亲和度加成
5.置信度计算 — resolveConfidence(documents)  /计算置信度并生成最终决策
    根据 top-2 文档的分数比值计算置信度
     */
/*
混合评分算法，每一层的评分都是多信号融合
      信号                    方法                                    说明
    语义相似度         semanticScore() → semanticMainScore()         查询向量与候选文本向量的余弦相似度
    词法匹配           lexicalScore() → lexicalAssist()              基于 ES 的词法匹配分数（辅助信号）
    关键词实体          keywordEntityAssist()                        查询词在候选文本中的命中率
最终分数=语义主分+词法辅助分+关键词实体辅助分



 */
    @Override
    public KnowledgeRouteDecision route(String question, String rewriteQuestion) {
        // 第一步：构造路由查询上下文   先把原问题和改写问题整理成统一的路由查询上下文对象，后续所有打分都复用这份上下文。
        RouteQueryContext queryContext = buildQueryContext(question, rewriteQuestion);
        KnowledgeRouteDecision decision = new KnowledgeRouteDecision();

        /*
        空值检查 （如果连一个有效关键词都提取不出来，说明问题为空或格式异常，直接返回失败决策，不再继续后续的路由流程。）
         */
        if (queryContext.queryTerms().isEmpty()) {
            // 连有效关键词都提不出来时，直接返回失败决策，避免后续无意义打分。
            decision.setRouteStatus("FAILED");
            decision.setReason("问题为空或无法提取有效关键词");
            return decision;
        }

        /*
        第二步：三层路由决策-rankScopes（知识域路由）
        这是三层路由的第一层，负责从所有知识域中筛选出最相关的候选。知识域的作用是先粗筛，把问题定位到一个大的领域，然后在这个领域内再细化到具体的主题和文档。
         */
        // 路由采用"知识域 -> 主题 -> 文档"的逐层缩小策略，先粗筛再细排。
        List<ScopeRouteCandidate> scopeCandidates = rankScopes(queryContext);


        //主题路由
        List<TopicRouteCandidate> topicCandidates = rankTopics(queryContext, scopeCandidates);
        //问当路由
        List<DocumentRouteCandidate> documentCandidates = rankDocuments(queryContext, scopeCandidates, topicCandidates);
        decision.setScopes(scopeCandidates);
        decision.setTopics(topicCandidates);
        decision.setDocuments(documentCandidates);

       /*
       第三步：计算置信度
        */
        // 最终置信度主要由 top1 和 top2 的相对差距决定。
        BigDecimal confidence = resolveConfidence(documentCandidates);
        decision.setConfidence(confidence);
        /*
        第四步：判断路由状态
         */
        if (documentCandidates.isEmpty()) {
            decision.setRouteStatus("FAILED");
        }
        else if (confidence.compareTo(BigDecimal.valueOf(0.55D)) < 0) {
            // 候选存在但置信度不足时，保留结果但标记为低置信度，交给上层决定是否澄清。
            decision.setRouteStatus("LOW_CONFIDENCE");
        }
        else {
            decision.setRouteStatus("SUCCESS");
        }


        /*
        第五步：生成原因说明
         */
        // reason 用于解释"为什么是这些候选"，便于调试和前端展示。
        decision.setReason(documentCandidates.isEmpty()
            ? "没有找到可用候选文档"
            : resolveDecisionReason(documentCandidates, confidence));
        log.info("知识范围路由完成: question='{}', rewriteQuestion='{}', scopeCount={}, topicCount={}, documentCount={}, confidence={}, topDocument='{}'",
            StrUtil.blankToDefault(question, ""),
            StrUtil.blankToDefault(rewriteQuestion, ""),
            scopeCandidates.size(),
            topicCandidates.size(),
            documentCandidates.size(),
            confidence,
            documentCandidates.isEmpty() ? "" : documentCandidates.get(0).getDocumentName());
        return decision;//返回路由决策结果
    }




    /*
    影子路由（质量观测）
    这是一个很巧妙的设计：当用户手动选择了某个文档来问答时，系统会在后台静默地跑一次自动路由，然后对比"系统选的"和"用户选的"是不是一样。
    这样做的好处：
        不影响用户体验（后台静默执行）
        持续积累路由质量数据
        可以发现路由算法的薄弱环节
     */
    /*
    路由索引
    路由的词法匹配依赖 ElasticsearchKnowledgeRouteIndexService，
    它维护了 Scope、Topic、Document 三个层级的 ES 索引，支持快速的文本匹配查询。
     */
    @Override
    public void recordShadowRoute(String conversationId,
                                  long exchangeId,
                                  Long selectedDocumentId,
                                  String question,
                                  String rewriteQuestion) {
        try {
            KnowledgeRouteDecision decision = route(question, rewriteQuestion);
            saveTrace(conversationId, exchangeId, selectedDocumentId, question, rewriteQuestion, "shadow", decision);
        }
        catch (Exception exception) {
            log.warn("记录知识路由影子结果失败: conversationId={}, exchangeId={}", conversationId, exchangeId, exception);
        }
    }

    @Override
    public void recordAutoRoute(String conversationId,
                                long exchangeId,
                                String question,
                                String rewriteQuestion,
                                KnowledgeRouteDecision decision) {
        try {
            Long selectedDocumentId = decision == null || decision.topDocument() == null || StrUtil.isBlank(decision.topDocument().getDocumentId())
                ? null
                : Long.valueOf(decision.topDocument().getDocumentId());
            saveTrace(conversationId, exchangeId, selectedDocumentId, question, rewriteQuestion, "auto", decision);
        }
        catch (Exception exception) {
            log.warn("记录知识路由 AUTO 结果失败: conversationId={}, exchangeId={}", conversationId, exchangeId, exception);
        }
    }



    //保存路由追踪记录
    private void saveTrace(String conversationId,
                           long exchangeId,
                           Long selectedDocumentId,
                           String question,
                           String rewriteQuestion,
                           String mode,
                           KnowledgeRouteDecision decision) {
        SuperAgentKnowledgeRouteTrace trace = new SuperAgentKnowledgeRouteTrace();
        trace.setId(uidGenerator.getUid());
        trace.setConversationId(conversationId);
        trace.setExchangeId(exchangeId);
        trace.setQuestion(question);
        trace.setRewriteQuestion(rewriteQuestion);
        trace.setMode(mode);
        trace.setTopScopesJson(writeScopeJson(decision == null ? List.of() : decision.getScopes()));
        trace.setTopTopicsJson(writeTopicJson(decision == null ? List.of() : decision.getTopics()));
        trace.setTopDocumentsJson(writeDocumentJson(decision == null ? List.of() : decision.getDocuments()));
        trace.setSelectedDocumentId(selectedDocumentId);
        trace.setHitSelectedDocument(resolveHitSelectedDocument(selectedDocumentId, decision));
        trace.setConfidence(decision == null ? BigDecimal.ZERO : decision.getConfidence());
        trace.setRouteStatus(resolveRouteStatus(decision));
        trace.setErrorMsg(decision == null ? "" : StrUtil.blankToDefault(decision.getReason(), ""));
        trace.setStatus(BusinessStatus.YES.getCode());
        knowledgeRouteTraceMapper.insert(trace);
    }

    private Integer resolveRouteStatus(KnowledgeRouteDecision decision) {
        if (decision == null) {
            return ROUTE_STATUS_FAILED;
        }
        return switch (StrUtil.blankToDefault(decision.getRouteStatus(), "FAILED")) {
            case "SUCCESS" -> ROUTE_STATUS_SUCCESS;
            case "LOW_CONFIDENCE" -> ROUTE_STATUS_LOW_CONFIDENCE;
            default -> ROUTE_STATUS_FAILED;
        };
    }

    private Integer resolveHitSelectedDocument(Long selectedDocumentId, KnowledgeRouteDecision decision) {
        if (selectedDocumentId == null || decision == null || decision.getDocuments() == null || decision.getDocuments().isEmpty()) {
            return null;
        }
        boolean hit = decision.getDocuments().stream()
            .limit(3)
            .anyMatch(item -> Objects.equals(String.valueOf(selectedDocumentId), item.getDocumentId()));
        return hit ? 1 : 0;
    }


    /**
     * 计算知识域候选。
     *
     * @param queryContext 路由查询上下文
     * @return 知识域候选列表
     */
    private List<ScopeRouteCandidate> rankScopes(RouteQueryContext queryContext) {
        /*步骤一：加载知识域节点

         */
        List<SuperAgentKnowledgeScopeNode> nodes = scopeNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode()));
       /*
       兜底机制：从文档反推知识域
       如果系统中没有配置独立的知识域节点，就调用 deriveScopesFromDocuments 方法，从文档的元数据中反推出知识域。我们稍后会详细讲解这个兜底机制。
        */
        if (nodes.isEmpty()) {
            // 如果没有独立知识域节点，则退回到从文档元数据中"反推出知识域"。
            return deriveScopesFromDocuments(queryContext);
        }

        /*
        步骤二：构建路由文本
        对每个知识域节点，把它的名称、描述、别名、示例问题拼接成一个统一的路由文本。
         */
        List<String> routeTexts = nodes.stream()
            .map(node -> join(node.getScopeName(), node.getDescription(), node.getAliases(), node.getExamples()))
            .toList();
        // scope 层同时融合语义分数、倒排词法分数和关键词辅助分。
        /*
        步骤三：计算语义相似度
         */
        List<Double> semanticScores = computeSemanticScores(queryContext, routeTexts);
        /*
        步骤四：查询词法匹配分数
        这行代码调用 searchLexicalScores，拿到词法命中列表后，转换成 Map<知识域编码, 词法分数> 供后续打分使用。

         */
        Map<String, Double> lexicalScores = searchLexicalScores(queryContext.routingText(), "scope", 5).stream()
            .collect(Collectors.toMap(KnowledgeRouteIndexService.RouteLexicalHit::entityCode, KnowledgeRouteIndexService.RouteLexicalHit::score, (left, right) -> left));
        /*
        步骤五：构建知识域候选
         */
        return buildScopeCandidates(queryContext, nodes, routeTexts, semanticScores, lexicalScores);
    }





    /**
     * 在没有独立知识域节点时，从可检索文档反推出知识域候选。
     * 这是知识域路由的一个保守 fallback 路径：
     * 如果系统里没有维护单独的知识域节点表，或者当前环境下拿不到知识域节点，
     * 就只能退回到“从每一份可检索文档的元数据中，倒推出它所属的知识域”。
     *
     * 这条路径的核心思路是：
     * 1. 先遍历所有可检索文档；
     * 2. 从文档的 `knowledgeScopeCode / knowledgeScopeName / businessCategory / tags` 中构造知识域候选文本；
     * 3. 基于当前 query 对每份文档做一个 scope 级别的粗打分；
     * 4. 再按 `scopeCode` 聚合，把属于同一知识域的多份文档合并为一个知识域候选；
     * 5. 对每个知识域，只保留“得分最高的那份文档”提供的分数与 reason，作为该知识域的代表解释。
     *
     * 这样做的原因是：
     * 1. 同一个知识域下通常会挂很多文档，不能把每份文档都直接映射成一个 scope 候选；
     * 2. 路由阶段只需要知道“这个知识域大概是否相关”，不需要在 scope 层就把所有文档细节都展开；
     * 3. 保留最高分文档的解释，能让最终 reason 更具体、更可读。
     *
     * @param queryContext 路由查询上下文
     * @return 知识域候选列表
     */
    /*从文档反推知识域的兜底机制   当系统中没有配置独立知识域节点时，会调用这个方法从文档元数据中反推出知识域：
     */
    //从文档反推 Scope（兜底）
    private List<ScopeRouteCandidate> deriveScopesFromDocuments(RouteQueryContext queryContext) {
        // 这里使用的是“当前可检索文档全集”，因为 fallback 路由没有更高层的 scope 数据源可依赖。
        List<SuperAgentDocument> documents = listRetrievableDocuments();//步骤一：获取可检索文档
        Map<String, ScopeAccumulator> accumulatorMap = new LinkedHashMap<>();

        //步骤二：遍历文档并提取知识域信息
        for (SuperAgentDocument document : documents) {
            if (StrUtil.isBlank(document.getKnowledgeScopeCode()) && StrUtil.isBlank(document.getKnowledgeScopeName())) {
                // 连知识域编码和名称都缺失的文档无法参与 scope fallback，直接跳过。
                continue;
            }
            // 如果某些老数据没有完整的知识域字段，这里用保守默认值兜底，避免整条候选链断掉。
            String code = firstNonBlank(document.getKnowledgeScopeCode(), "general_document");
            String name = firstNonBlank(document.getKnowledgeScopeName(), "通用文档");
           //步骤三：构造路由文本
            // routeText 只拼接知识域相关元数据，不拼整份文档内容，因为这里做的是“scope 粗筛”而不是细粒度文档匹配。
            String routeText = join(code, name, document.getBusinessCategory(), document.getDocumentTags());
            //步骤四：计算文档级别的粗打分
            // fallback 路径里使用的分数更保守，主要由实体词命中和语义相似度组成。
            double score = keywordEntityAssist(queryContext.queryTerms(), routeText);
            double semanticScore = semanticScore(queryContext, routeText);


            /*
            步骤五：按知识域聚合，保留最高分
            computeIfAbsent 的作用：
            如果 accumulatorMap 中已经有这个 code 的累加器，直接返回
            如果没有，创建一个新的累加器并放入 map
             */
            // 同一个 scopeCode 只维护一个累加器，表示“这个知识域目前最强的候选解释”。
            ScopeAccumulator accumulator = accumulatorMap.computeIfAbsent(code, key -> new ScopeAccumulator(code, name));
            if (score + semanticMainScore(semanticScore) > accumulator.maxScore) {
                // 同一知识域可能来自多个文档，这里保留该知识域下得分最高的那条解释。
                accumulator.maxScore = score + semanticMainScore(semanticScore);
                accumulator.reason = buildReason(queryContext.queryTerms(), routeText, semanticScore);
            }
        }

        /*
        步骤六：过滤、排序、限制数量
         */
        return accumulatorMap.values().stream()
                // 纯词法路径下至少要有正分才保留；若语义能力开启，则允许 0 分候选继续参与后续排序。
            .filter(item -> item.maxScore > 0D || queryContext.semanticEnabled())
            .map(item -> new ScopeRouteCandidate(item.scopeCode, item.scopeName, scoreToBigDecimal(item.maxScore), item.reason))
                // 这里只保留最相关的前 5 个知识域候选，足够给下一层主题/文档路由使用。   最后按分数降序排列，保留前 8 个候选（比知识域路由的 5 个多，给文档层留足选择空间）：
            .sorted((left, right) -> right.getScore().compareTo(left.getScore()))
            .limit(5)
            .toList();
    }


    /**
     * 计算主题候选。
     * 这是知识范围路由里的第二层筛选：在 scope 粗筛之后，再继续往下定位更细粒度的 topic。
     *
     * 这一步的核心作用是：
     * 1. 把“问题大概属于哪个知识域”的结果，进一步细化成“更像哪个业务主题 / 子主题”；
     * 2. 为下一层文档路由提供更强的主题信号；
     * 3. 如果 topic 层命中较准，后续文档排序会明显更稳定。
     *
     * 评分思路和 scope 层类似，但会额外叠加“层级一致性加权”：
     * 1. 语义主分：来自 query 与 topic routeText 的向量相似度；
     * 2. 词法辅助分：来自倒排词法召回；
     * 3. 实体词辅助分：来自短词、代号、英文缩写、编号等高指向性词项；
     * 4. scope 一致性加权：如果某个 topic 属于上一步已经命中的优先知识域，则再加一笔分。
     *
     * 这意味着 topic 路由并不是孤立计算的，
     * 而是会显式利用 `scopeCandidates` 的结果，让“知识域 -> 主题”形成逐层收缩的路由链路。
     *
     * @param queryContext 路由查询上下文
     * @param scopeCandidates 已有知识域候选
     * @return 主题候选列表
     */
    private List<TopicRouteCandidate> rankTopics(RouteQueryContext queryContext, List<ScopeRouteCandidate> scopeCandidates) {
        List<SuperAgentKnowledgeTopicNode> nodes = topicNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode()));

        /*
       第一步：提取优先知识域
       从第一层路由的知识域候选中提取出所有知识域编码，作为"优先知识域"集合。如果系统里根本没有配置主题节点，就直接走 fallback 路径 deriveTopicsFromProfiles
        */
        // 从上一步 scope 候选中提炼出“优先知识域集合”，后续 topic 属于这些 scope 时会得到额外加权。
        Set<String> preferredScopes = scopeCandidates.stream().map(ScopeRouteCandidate::getScopeCode).collect(Collectors.toSet());
        if (nodes.isEmpty()) {
            // 没有显式主题节点时，退回到从文档画像里提炼主题。
            return deriveTopicsFromProfiles(queryContext, preferredScopes);
        }







        /*
        第二步：构建路由文本
        这里比知识域路由多了两个字段：
        answerShape：回答形式，比如"步骤列表"、"代码示例"、"对比表格"，帮助语义向量理解这个主题的回答风格
        executionPreference：执行偏好，比如"优先检索文档"、"优先调用工具"，帮助理解这个主题的处理方式
         */
        // 为每个主题节点构造 routeText，覆盖主题名、别名、示例、回答形态、执行偏好等多种语义线索。
        List<String> routeTexts = nodes.stream()
            .map(node -> join(//join 方法的实现很简单，就是把非空字段用空格拼在一起：
                node.getTopicName(),
                node.getDescription(),
                node.getAliases(),
                node.getExamples(),
                node.getAnswerShape(),
                node.getExecutionPreference()
            ))
            .toList();

        /*第三步：批量计算语义分和词法分
    这里有两路并行的打分：
    语义路径：computeSemanticScores
    批量向量化所有主题的 routeText，然后和 query 向量做余弦相似度。注意这里是批量计算，内部会按 ROUTE_EMBEDDING_BATCH_SIZE 分批调用 embedding 模型，避免一次性请求过大。
         */
        // 语义分与词法分都按 topic 节点列表顺序对齐，后面会按相同下标组合出最终 topic 分。
        List<Double> semanticScores = computeSemanticScores(queryContext, routeTexts);
        Map<String, Double> lexicalScores = searchLexicalScores(queryContext.routingText(), "topic", 8).stream()
            .collect(Collectors.toMap(KnowledgeRouteIndexService.RouteLexicalHit::entityCode, KnowledgeRouteIndexService.RouteLexicalHit::score, (left, right) -> left));
        List<TopicRouteCandidate> candidates = new ArrayList<>(nodes.size());

       /*
       第四步：遍历主题节点，组合三维打分
        */
        for (int index = 0; index < nodes.size(); index++) {
            // 当前 index 下的 topic 节点、routeText、semanticScore 必须保持严格对齐。
            SuperAgentKnowledgeTopicNode node = nodes.get(index);
            String routeText = routeTexts.get(index);
            // topic 层最终分由语义主分、词法辅助分和实体词辅助分共同组成。
            double score = semanticMainScore(semanticScores.get(index))
                + lexicalAssist(lexicalScores.get(node.getTopicCode()))
                + keywordEntityAssist(queryContext.queryTerms(), routeText);

          /*
          第五步：层级一致性加权   如果这个主题属于第一层路由命中的知识域，就额外加 8 分。
          为什么要加 8 分？
            这个加分是为了强化层级一致性。
            假设用户问题是"如何配置 Spring Boot 数据源"：
            第一层路由：java_dev 知识域得分最高
            第二层路由：
            spring_boot_config 主题（属于 java_dev）：基础分 15 + 层级加分 8 = 23
            react_hooks 主题（属于 frontend）：基础分 18 + 层级加分 0 = 18
            即使 react_hooks 的基础分更高，但因为它不属于 java_dev 知识域，最终得分还是低于 spring_boot_config。这就是层级路由的核心价值：上层的判断会约束下层的选择。
           */
            if (!preferredScopes.isEmpty() && preferredScopes.contains(node.getScopeCode())) {
                // 若主题属于已命中的知识域，额外加权，强化层级一致性。
                score += 8D;
            }


            /*第六步：过滤和排序
过滤条件：分数大于 0，或者开启了语义模式（语义模式下即使分数为 0 也保留，因为语义相似度本身就是一种信号）。
buildReason 会生成一段人类可读的原因说明，优先展示命中的关键词，其次是语义相似度描述：    buildReason
             */
            if (score > 0D || queryContext.semanticEnabled()) {
                // 满足保留条件的主题会进入候选集，并附带 reason 供调试和前端展示。
                candidates.add(new TopicRouteCandidate(
                    node.getTopicCode(),
                    node.getTopicName(),
                    node.getScopeCode(),
                    scoreToBigDecimal(score),
                    buildReason(queryContext.queryTerms(), routeText, semanticScores.get(index))//生成原因说明
                ));
            }
        }
        /*
        排序和截断   最后，按得分从高到低排序，取前 5 个作为知识域候选
         */
        return candidates.stream()
                // topic 层是比 scope 更细的一层，因此这里保留前 8 个候选，给文档层留足可选空间。
            .sorted((left, right) -> right.getScore().compareTo(left.getScore()))
            .limit(8)
            .toList();
    }



    /**从文档画像提炼主题    当没有配置主题节点时，会走 fallback 路径，从文档画像中提炼主题：
     * 在缺少主题节点时，从文档画像中提炼主题候选。
     * 这是 topic 路由的 fallback 路径，适用于"没有维护独立 topic 节点表"或"节点数据不可用"的场景。
     *
     * 核心思路是：
     * 1. 从每份文档画像中提取 coreTopics；
     * 2. 用 topic + 文档摘要 + 示例问题拼出 routeText；
     * 3. 对每个 topic 计算一份分数；
     * 4. 如果同一 topic 出现在多份文档画像中，只保留最强得分和最强 reason。
     *
     * 另外，这里也会参考上一层 scope 结果：
     * 如果某个 topic 所在文档属于 `preferredScopes`，则追加一笔 scope 一致性加权，
     * 让 fallback 路径也能尽量继承上层 scope 的路由信号。
     *
     * @param queryContext 路由查询上下文
     * @param preferredScopes 优先知识域集合
     * @return 主题候选列表
     */
    //从文档 Profile 反推 Topic
    private List<TopicRouteCandidate> deriveTopicsFromProfiles(RouteQueryContext queryContext, Set<String> preferredScopes) {
        List<SuperAgentDocumentProfile> profiles = documentProfileMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
            .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocumentProfile::getProfileStatus, 2));
        Map<String, TopicAccumulator> accumulatorMap = new LinkedHashMap<>();
        // 先把文档映射起来，方便从 profile 反查该画像所属文档的知识域。
        Map<Long, SuperAgentDocument> documentMap = listRetrievableDocuments().stream()
            .collect(Collectors.toMap(SuperAgentDocument::getId, item -> item));
        for (SuperAgentDocumentProfile profile : profiles) {
            SuperAgentDocument document = documentMap.get(profile.getDocumentId());
            String scopeCode = document == null ? "" : StrUtil.blankToDefault(document.getKnowledgeScopeCode(), "");
            for (String topic : parseJsonArray(profile.getCoreTopics())) {
                // fallback routeText 使用"主题 + 文档摘要 + 示例问题"，尽量保留文档画像中的主题信号。
                String routeText = join(topic, profile.getDocumentSummary(), profile.getExampleQuestions());
                double score = keywordEntityAssist(queryContext.queryTerms(), routeText);
                double semanticScore = semanticScore(queryContext, routeText);
                if (!preferredScopes.isEmpty() && preferredScopes.contains(scopeCode)) {
                    // 若主题来自优先知识域，则提升其得分。
                    score += 6D;
                }
                // 同名 topic 可能出现在多份文档画像中，这里用累加器保留"最能代表这个 topic 的那条解释"。
                TopicAccumulator accumulator = accumulatorMap.computeIfAbsent(topic, key -> new TopicAccumulator(topic, scopeCode));
                double finalScore = score + semanticMainScore(semanticScore);
                if (finalScore > accumulator.maxScore) {
                    // 同一主题可能出现在多个画像中，只保留最强解释
                    accumulator.maxScore = finalScore;
                    accumulator.reason = buildReason(queryContext.queryTerms(), routeText, semanticScore);
                }
            }
        }
        return accumulatorMap.values().stream()
            .filter(item -> item.maxScore > 0D || queryContext.semanticEnabled())
            .map(item -> new TopicRouteCandidate(normalizeCode(item.topicName), item.topicName, item.scopeCode, scoreToBigDecimal(item.maxScore), item.reason))
                // fallback 路径同样只保留前 8 个主题候选，和主路径的输出规模保持一致。
            .sorted((left, right) -> right.getScore().compareTo(left.getScore()))
            .limit(8)
            .toList();
    }


    /**
     * 计算文档候选。
     * 这是知识范围路由的最后一层精排阶段，目标不是再判断“属于哪个 scope / topic”，
     * 而是直接收敛到“最可能应该命中的具体文档”。
     *
     * 这一层会综合使用上两层的输出：
     * 1. `scopeCandidates`：提供 top1 知识域信号；
     * 2. `topicCandidates`：提供 top1 主题信号；
     * 3. 文档画像 `profile`：提供文档摘要、主题、示例问题等更细粒度的路由素材；
     * 4. 主题-文档关系表：如果某文档和 top1 topic 有显式关系，会获得额外加权。
     *
     * 整体流程可以理解为：
     * 1. 先拿到所有可检索文档；
     * 2. 补齐每份文档对应的画像数据和 topic 关系数据；
     * 3. 为每份文档构造 route material；
     * 4. 批量计算文档级语义分，并结合词法分、实体词分、scope/topic 一致性分；
     * 5. 最终按分数排序，只保留前 5 份文档。
     *
     * @param queryContext 路由查询上下文
     * @param scopeCandidates 知识域候选
     * @param topicCandidates 主题候选
     * @return 文档候选列表
     */
    private List<DocumentRouteCandidate> rankDocuments(RouteQueryContext queryContext,
                                                       List<ScopeRouteCandidate> scopeCandidates,
                                                       List<TopicRouteCandidate> topicCandidates) {
        List<SuperAgentDocument> documents = listRetrievableDocuments();
        if (documents.isEmpty()) {
            return List.of();
        }

        /*第一步：加载文档画像
        文档画像是由 AI 自动生成的文档元数据，包含：
        documentSummary：文档摘要，一句话概括文档内容
        coreTopics：核心主题列表，比如 ["Spring Boot配置", "数据源管理"]
        exampleQuestions：示例问题，用户可能会问的典型问题
        documentType：文档类型，比如"教程"、"API文档"、"故障排查"
         */

        // 文档层会综合画像、主题关系、知识域命中、词法召回和语义向量一起打分。
        Map<Long, SuperAgentDocumentProfile> profileMap = documentProfileMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
                .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
                .eq(SuperAgentDocumentProfile::getProfileStatus, 2))
            .stream()
            .collect(Collectors.toMap(SuperAgentDocumentProfile::getDocumentId, item -> item, (left, right) -> right));


        /*
        第二步：加载主题-文档关系         这个数据结构是一个两层嵌套的 Map：topicCode → (documentId → relation)。
        eg:
        {
  "spring_boot_config": {
    123: {relationScore: 0.95},  // 文档123与"Spring Boot配置"高度相关
    456: {relationScore: 0.60}   // 文档456与"Spring Boot配置"中等相关
  },
  "mybatis_usage": {
    789: {relationScore: 0.85}
  }
}
relationScore 是 0-1 之间的数值，表示主题和文档的相关程度。这个分数通常也是由 AI 自动评估生成的。
         */
        Map<String, Map<Long, SuperAgentTopicDocumentRelation>> topicRelationMap = topicDocumentRelationMapper.selectList(
                new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
                    .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .collect(Collectors.groupingBy(SuperAgentTopicDocumentRelation::getTopicCode,
                Collectors.toMap(SuperAgentTopicDocumentRelation::getDocumentId, item -> item, (left, right) -> right)));


        /*
        第三步：提取top1知识域和主题
        注意这里只取了 top1，而不是把所有候选都拿来加权。这是一个重要的设计决策：

            为什么只用 top1？
            如果把所有 scope 候选和 topic 候选都参与加权，会导致：
            1.加权信号过于分散：一个文档可能同时匹配多个 scope/topic，最终分数会被稀释
            2.计算复杂度增加：需要遍历所有候选组合
            3.置信度下降：如果连 top1 都不够准，后面的候选更不可靠
            只用 top1 是一种强约束策略：相信前两层路由的判断，用最强的信号来指导文档精排。
         */
        // 文档层默认只拿上层 top1 scope / top1 topic 作为强约束信号，不直接把全部候选展开参与加权。
        String topScopeCode = scopeCandidates.isEmpty() ? "" : scopeCandidates.get(0).getScopeCode();
        String topTopicCode = topicCandidates.isEmpty() ? "" : topicCandidates.get(0).getTopicCode();

       /*
       第四步：构建文档路由素材
        */
        // 每份文档先被转成一条 route material 文本，后续语义分就直接基于这份文本批量计算。
        List<DocumentRouteMaterial> materials = documents.stream()
            .map(document -> buildDocumentRouteMaterial(document, profileMap.get(document.getId())))
            .toList();
        /*
        第五步：批量计算语义分和词法分
        和前两层一样，这里也是语义路径和词法路径并行计算。注意词法召回这里只取最多 5 个（而不是 8 个），因为文档层已经是最后一层，不需要保留太多候选。
         */
        // 文档级语义分走批量计算，保证所有文档分数与 materials / documents 顺序严格对齐。
        List<Double> semanticScores = computeSemanticScores(queryContext, materials.stream().map(DocumentRouteMaterial::routeText).toList());
        Map<Long, Double> lexicalScores = searchLexicalScores(queryContext.routingText(), "document", 5).stream()
            .filter(hit -> hit.documentId() != null)
            .collect(Collectors.toMap(KnowledgeRouteIndexService.RouteLexicalHit::documentId, KnowledgeRouteIndexService.RouteLexicalHit::score, (left, right) -> left));
       /*
       第六步：遍历文档，调用 buildDocumentCandidate 计算最终分值
        */
        return documents.stream()
            .map(document -> buildDocumentCandidate(
                queryContext,
                document,
                profileMap.get(document.getId()),
                topScopeCode,
                topTopicCode,
                topicRelationMap,
                materials,
                semanticScores,
                lexicalScores
            ))
                // 如果没有任何正分且又没有语义能力，就不保留这类文档进入最终候选
            .filter(candidate -> candidate.getScore().compareTo(BigDecimal.ZERO) > 0 || queryContext.semanticEnabled())
                // 文档层已经是最终结果层，只保留最强的前 5 个候选即可。
            .sorted((left, right) -> right.getScore().compareTo(left.getScore()))
            .limit(5)
            .toList();
    }






    /**  五维度打分逻辑 buildDocumentCandidate 是文档层精排的最小单元，负责把一份文档的所有可用信号折叠成一个最终分数。
     * 为单个文档构造路由候选并计算最终分值。
     * 这是文档层精排的最小单元，负责把一份文档的所有可用信号折叠成一个最终分数。
     *
     * 这里的加权来源主要有四类：
     * 1. 文档 routeText 的语义主分；
     * 2. 文档级词法召回辅助分；
     * 3. 实体词命中辅助分；
     * 4. 与上层 top1 scope / top1 topic 一致时的层级加权。
     *
     * @param queryContext 路由查询上下文
     * @param document 文档
     * @param profile 文档画像
     * @param topScopeCode top1 知识域编码
     * @param topTopicCode top1 主题编码
     * @param topicRelationMap 主题-文档关系映射
     * @param materials 文档路由素材
     * @param semanticScores 语义分数列表
     * @param lexicalScores 倒排词法分数字典
     * @return 文档路由候选
     */
    //构建单个文档候选，综合所有评分信号
    private DocumentRouteCandidate buildDocumentCandidate(RouteQueryContext queryContext,
                                                          SuperAgentDocument document,
                                                          SuperAgentDocumentProfile profile,
                                                          String topScopeCode,
                                                          String topTopicCode,
                                                          Map<String, Map<Long, SuperAgentTopicDocumentRelation>> topicRelationMap,
                                                          List<DocumentRouteMaterial> materials,
                                                          List<Double> semanticScores,
                                                          Map<Long, Double> lexicalScores) {
        /*
        步骤一：定位文档在 materials 中的位置
        因为语义分是批量计算的，返回的 semanticScores 列表和 materials 列表是按下标对齐的。所以需要先找到当前文档在 materials 中的位置，才能取出对应的语义分。
        如果找不到（materialIndex < 0），就用文档的基础字段临时拼一个 routeText。
         */
        // 先找到当前文档在 materials 列表中的位置，这样才能从批量语义分结果里取出对应下标的分数。
        int materialIndex = findMaterialIndex(materials, document.getId());
        String routeText = materialIndex >= 0 ? materials.get(materialIndex).routeText() : join(
            document.getDocumentName(),
            document.getKnowledgeScopeName(),
            document.getKnowledgeScopeCode(),
            document.getBusinessCategory(),
            document.getDocumentTags()
        );
        /*
        步骤二：：提取语义分
         */
        // 若能找到对应 material，就直接复用批量计算好的语义分；找不到则退化为 0 分。
        double semanticScore = materialIndex >= 0 && materialIndex < semanticScores.size() ? semanticScores.get(materialIndex) : 0D;
       /*
       步骤三：计算基础三维分
       这三维和前两层路由一样：语义主分 + 词法辅助分 + 实体词辅助分。
        */
        // 基础分由语义主分、词法辅助分、实体词辅助分构成。
        double score = semanticMainScore(semanticScore)
            + lexicalAssist(lexicalScores.get(document.getId()))
            + keywordEntityAssist(queryContext.queryTerms(), routeText);
       /*
       步骤四：知识域一致性加权（+15 分）
       如果文档属于 top1 知识域，额外加 15 分。这是三层路由中最大的层级加权（scope 层没有层级加权，topic 层是 8 分，document 层是 15 分）。
        */
        if (StrUtil.isNotBlank(topScopeCode) && topScopeCode.equals(document.getKnowledgeScopeCode())) {
            // 文档若属于 top1 知识域，则大幅加权，体现层级路由的一致性。
            score += 15D;
        }

        /*
        步骤五：主题关系加权（最多 +20 分）
         */
        if (StrUtil.isNotBlank(topTopicCode)) {
            Map<Long, SuperAgentTopicDocumentRelation> relationMap = topicRelationMap.get(topTopicCode);
            if (relationMap != null) {
                SuperAgentTopicDocumentRelation relation = relationMap.get(document.getId());
                if (relation != null && relation.getRelationScore() != null) {
                    // 文档若与 top1 主题有显式关系，再根据关系强度额外加权。
                    score += relation.getRelationScore().doubleValue() * 20D;
                }
            }
        }
        if (score <= 0D && !queryContext.semanticEnabled()) {
            // 纯词法路径下若完全无命中，仍返回一条 0 分候选，方便解释"为何没选中"。
            return new DocumentRouteCandidate(
                String.valueOf(document.getId()),
                document.getDocumentName(),
                document.getLastIndexTaskId() == null ? "" : String.valueOf(document.getLastIndexTaskId()),
                StrUtil.blankToDefault(document.getKnowledgeScopeCode(), ""),
                StrUtil.blankToDefault(document.getKnowledgeScopeName(), ""),
                StrUtil.blankToDefault(document.getBusinessCategory(), ""),
                StrUtil.blankToDefault(document.getDocumentTags(), ""),
                BigDecimal.ZERO,
                "未命中路由关键词"
            );
        }
        // 正常情况下返回带完整元数据、最终得分和 reason 的文档候选。
        return new DocumentRouteCandidate(
            String.valueOf(document.getId()),
            document.getDocumentName(),
            document.getLastIndexTaskId() == null ? "" : String.valueOf(document.getLastIndexTaskId()),
            StrUtil.blankToDefault(document.getKnowledgeScopeCode(), ""),
            StrUtil.blankToDefault(document.getKnowledgeScopeName(), ""),
            StrUtil.blankToDefault(document.getBusinessCategory(), ""),
            StrUtil.blankToDefault(document.getDocumentTags(), ""),
            scoreToBigDecimal(score),
            buildReason(queryContext.queryTerms(), routeText, semanticScore)
        );
    }




    /*构造路由查询上下文。  这个方法做了三件事
     * @param question 原始问题
     * @param rewriteQuestion 改写问题
     * @return 路由查询上下文
     */
    //构建路由查询上下文（分词 + 向量）
    private RouteQueryContext buildQueryContext(String question, String rewriteQuestion) {

        String routingText = buildRoutingText(question, rewriteQuestion);//第一件事：构建路由文本

        List<String> queryTerms = tokenize(routingText);//第二件事：提取关键词
        // 若 embedding 可用，则提前把问题向量化，供后续 scope/topic/document 三层复用。

        float[] queryEmbedding = embedSingle(routingText);//第三件事：生成问题向量
        return new RouteQueryContext(
            StrUtil.blankToDefault(question, ""),
            StrUtil.blankToDefault(rewriteQuestion, ""),
            routingText,
            queryTerms,
            queryEmbedding
        );
    }



    /**
     * 组合原问题和改写问题，形成统一路由文本。
     *buildRoutingText 方法会把原问题和改写问题组合成一个统一的路由文本：
     * @param question 原始问题
     * @param rewriteQuestion 改写问题
     * @return 路由文本
     * ————————————————————————————————————————————————————————————
     * 为什么要组合原问题和改写问题？
     * 原问题包含用户的原始表达，可能有一些专有名词、缩写或特定术语
     * 改写问题经过模型处理，语义更清晰，指代更明确
     * 把两者组合起来，可以同时利用词面匹配和语义理解的优势。
     */
    //拼接用于路由的文本
    private String buildRoutingText(String question, String rewriteQuestion) {
        String original = StrUtil.blankToDefault(question, "").trim();
        String rewritten = StrUtil.blankToDefault(rewriteQuestion, "").trim();
        if (StrUtil.isBlank(original)) {
            return rewritten;
        }
        if (StrUtil.isBlank(rewritten) || Objects.equals(original, rewritten)) {
            // 如果改写为空或与原问题相同，则无需重复拼接。
            return original;
        }
        // 原问题 + 改写问题同时参与路由，兼顾用户原词面和改写后的语义清晰表达。
        return original + " " + rewritten;
    }


    /**
     *
     *     buildScopeCandidates 方法会综合语义分数、词法分数和关键词辅助分，计算每个知识域的最终得分：
     *
     * 构造知识域候选结果。
     * 这个方法会把“知识域节点本身”和“外部已经算好的多路分数”组装成最终的 `ScopeRouteCandidate` 列表。
     *
     * 这里有一个很重要的约束：
     * `nodes`、`routeTexts`、`semanticScores` 三个列表必须按相同顺序一一对应。
     * 也就是说：
     * 1. `nodes.get(index)` 是第 index 个知识域节点；
     * 2. `routeTexts.get(index)` 是这个节点对应的路由文本；
     * 3. `semanticScores.get(index)` 是这个节点对应的语义分。
     *
     * 最终得分由三部分组成：
     * 1. `semanticMainScore(...)`：语义相似度映射后的主分；
     * 2. `lexicalAssist(...)`：来自倒排检索的词法辅助分；
     * 3. `keywordEntityAssist(...)`：对短词、英文词、编号类实体词命中的额外加分。
     *
     * 候选保留策略也比较保守：
     * 1. 只要最终分大于 0，就保留；
     * 2. 即使最终分不大于 0，但只要原始语义分大于 0，也允许保留，
     *    这样可以避免某些“语义有一定相关，但映射后主分被截断”的候选被过早丢弃。
     *
     * 最后会按得分倒序排序，并只保留前 5 个知识域候选，
     * 因为知识域层的目标是“粗筛范围”，不需要保留过多低价值候选。
     *
     * @param queryContext 路由查询上下文
     * @param nodes 知识域节点列表
     * @param routeTexts 每个知识域节点对应的路由文本
     * @param semanticScores 每个知识域节点对应的语义分数
     * @param lexicalScores 词法分数字典，key 为 scopeCode
     * @return 知识域候选列表
     */
    private List<ScopeRouteCandidate> buildScopeCandidates(RouteQueryContext queryContext,
                                                           List<SuperAgentKnowledgeScopeNode> nodes,
                                                           List<String> routeTexts,
                                                           List<Double> semanticScores,
                                                           Map<String, Double> lexicalScores) {
        List<ScopeRouteCandidate> candidates = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            // 当前 index 下的节点、文本、语义分必须保持严格对齐。
            SuperAgentKnowledgeScopeNode node = nodes.get(index);
            String routeText = routeTexts.get(index);
            // 最终分由语义主分、词法辅助分和实体词辅助分组成。
            double finalScore = semanticMainScore(semanticScores.get(index))
                + lexicalAssist(lexicalScores.get(node.getScopeCode()))
                + keywordEntityAssist(queryContext.queryTerms(), routeText);
            if (finalScore > 0D || semanticScores.get(index) > 0D) {
                // 只有满足保留条件的知识域才会进入候选列表，并带上可解释 reason 供后续调试与展示。
                candidates.add(new ScopeRouteCandidate(
                    node.getScopeCode(),
                    node.getScopeName(),
                    scoreToBigDecimal(finalScore),
                    buildReason(queryContext.queryTerms(), routeText, semanticScores.get(index))
                ));
            }
        }
        return candidates.stream()
                // 知识域候选只保留最强的几个即可，后续主题层和文档层还会继续精排。
            .sorted((left, right) -> right.getScore().compareTo(left.getScore()))
            .limit(5)
            .toList();
    }

    /**buildDocumentRouteMaterial 方法会为每个文档构建一个路由素材对象：
     * 构造单个文档的路由素材文本。
     * @param document 文档
     * @param profile 文档画像
     * @return 路由素材
     * ——————————————————————————————————————————————————————————
     * 路由素材包含了文档的所有可用信息：文档名称、知识域、分类、标签、文档摘要、核心主题、示例问题、文档类型。这些信息会被拼成一条完整的 routeText，用于后续的语义向量计算。
     */
    private DocumentRouteMaterial buildDocumentRouteMaterial(SuperAgentDocument document, SuperAgentDocumentProfile profile) {
        return new DocumentRouteMaterial(
            document.getId(),
            join(
                document.getDocumentName(),
                document.getKnowledgeScopeName(),
                document.getKnowledgeScopeCode(),
                document.getBusinessCategory(),
                document.getDocumentTags(),
                profile == null ? "" : profile.getDocumentSummary(),
                profile == null ? "" : profile.getCoreTopics(),
                profile == null ? "" : profile.getExampleQuestions(),
                profile == null ? "" : profile.getDocumentType()
            )
        );
    }

    private int findMaterialIndex(List<DocumentRouteMaterial> materials, Long documentId) {
        for (int index = 0; index < materials.size(); index++) {
            if (Objects.equals(materials.get(index).documentId(), documentId)) {
                return index;
            }
        }
        return -1;
    }



    /**resolveConfidence 方法会根据文档候选列表计算置信度：
     * 根据文档候选列表估算路由置信度。
     *这个方法的核心思想是：置信度不是看 top1 的绝对分数，而是看 top1 相对 top2 的优势程度。
     * @param documents 文档候选
     * @return 置信度
     */
    //计算路由置信度
    private BigDecimal resolveConfidence(List<DocumentRouteCandidate> documents) {
        if (documents == null || documents.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double top = documents.get(0).getScore().doubleValue();
        double second = documents.size() > 1 ? documents.get(1).getScore().doubleValue() : 0D;
        // 置信度并不是绝对分，而是主要体现 top1 相对 top2 的优势程度。
        double normalized = top / Math.max(10D, top + second + 5D);
        return scoreToBigDecimal(normalized);
    }


    /*
    listRetrievableDocuments 方法会从数据库中查询所有满足以下条件的文档：
    这样可以确保只有已经成功索引、可以被检索的文档才会参与知识域反推。
     */
    private List<SuperAgentDocument> listRetrievableDocuments() {
        return documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())//文档状态为启用  禁用的文档不应该参与路由
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())//索引构建成功 索引失败的文档无法被检索
            .isNotNull(SuperAgentDocument::getLastIndexTaskId)//有索引任务 ID  确保文档已经被索引过
            .orderByAsc(SuperAgentDocument::getId));//按 ID 升序   保证结果顺序稳定
    }

    private double lexicalScore(List<String> queryTerms, String content) {
        String normalizedContent = normalize(content);
        if (queryTerms.isEmpty() || normalizedContent.isBlank()) {
            return 0D;
        }
        double score = 0D;
        List<String> sortedTerms = queryTerms.stream()
            .map(this::normalize)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
        List<String> matchedTerms = new ArrayList<>();
        for (String term : sortedTerms) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.length() < 2) {
                continue;
            }
            boolean coveredByLongerTerm = matchedTerms.stream().anyMatch(existing -> existing.contains(normalizedTerm));
            if (coveredByLongerTerm) {
                continue;
            }
            if (normalizedContent.contains(normalizedTerm)) {
                matchedTerms.add(normalizedTerm);
                score += lexicalWeight(normalizedTerm.length());
            }
        }
        return score;
    }



    /**
     * 通过倒排索引执行词法召回。
     *
     * @param routingText 路由文本
     * @param entityType 实体类型，如 scope/topic/document
     * @param size 返回条数
     * @return 词法命中列表
     * ————————————————————————————————
     * 这个方法是一个薄封装：先检查 KnowledgeRouteIndexService 是否可用，可用则委托给它执行真正的词法检索。
     */
    //调用 ES 搜索词法分数
    private List<KnowledgeRouteIndexService.RouteLexicalHit> searchLexicalScores(String routingText, String entityType, int size) {
        KnowledgeRouteIndexService routeIndexService = knowledgeRouteIndexServiceProvider.getIfAvailable();
        if (routeIndexService == null) {
            return List.of();
        }
        return routeIndexService.search(routingText, entityType, size);
    }



    /**
     * 将问题文本切分成一组路由关键词。
     *tokenize 方法会把路由文本切分成一组关键词：
     * @param text 路由文本
     * @return 关键词列表
     * ————————————————————————————————————————————
     * 这个方法做了几件事：
     * 用正则表达式 [\\s、，,；;：:（）()\\-的和及与或]+ 分词
     * 过滤掉长度小于 2 的词
     * 对较长的中文词补充 n-gram
     * 限制最多 40 个关键词
     */
    //中文分词
    private List<String> tokenize(String text) {
        String normalized = StrUtil.blankToDefault(text, "").trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String segment : normalized.split("[\\s、，,；;：:（）()\\-的和及与或]+")) {
            String trimmed = segment.trim();
            if (trimmed.length() >= 2) {
                terms.add(trimmed);
                // 对较长中文词补充 n-gram，增强与文档元数据模糊匹配的能力。
                expandChineseNgrams(terms, trimmed);
            }
        }
        return new ArrayList<>(terms).stream().limit(40).toList();
    }

    private List<String> parseJsonArray(String raw) {
        String normalized = StrUtil.blankToDefault(raw, "").trim();
        if (normalized.isBlank() || "[]".equals(normalized)) {
            return List.of();
        }
        String body = normalized.replace("[", "").replace("]", "");
        if (body.isBlank()) {
            return List.of();
        }
        return List.of(body.split(",")).stream()
            .map(item -> item.replace("\"", "").trim())
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    private BigDecimal scoreToBigDecimal(double score) {
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 生成候选解释原因。
     *buildReason 方法会生成一个人类可读的原因说明：
     * @param queryTerms 查询词
     * @param content 候选内容
     * @param semanticScore 语义分
     * @return 原因说明
     * ————————————————————————————————
     * 原因说明的优先级：
     * 1.词面命中 - 如果有关键词匹配，优先展示（最直观）
     * 2.高语义相似度 - 如果语义相似度 ≥ 0.55，说明"语义相似度高"
     * 3.中等语义相似度 - 如果语义相似度 ≥ 0.35，说明"语义相近"
     * 4.兜底说明 - 其他情况，说明"基于综合召回"
     */
    private String buildReason(List<String> queryTerms, String content, double semanticScore) {
        List<String> matched = queryTerms.stream()
            .filter(term -> normalize(content).contains(normalize(term)))
            .limit(3)
            .toList();
        if (!matched.isEmpty()) {
            // 优先给出词面命中解释，因为这类原因对人工最直观。
            return "命中关键词：" + String.join("、", matched);
        }
        if (semanticScore >= 0.55D) {
            return "语义相似度高，基于文档画像与元数据召回";
        }
        if (semanticScore >= 0.35D) {
            return "语义相近，采用保守扩范围召回";
        }
        return "基于文档画像与元数据综合召回";
    }


    /**join 方法会把多个字段拼接成一个字符串，用空格分隔：
     * 拼接多个非空文本段。
     * @param values 文本段
     * @return 拼接结果
     * ——————————————————————————————————————比如一个知识域节点的路由文本可能是：
     *eg：Java 开发 Java 后端开发相关的技术栈和框架 Java JDK Spring SpringBoot MyBatis 如何配置 Spring Boot 如何使用 MyBatis
     * 这个文本会被用于后续的语义匹配和词法匹配。
     */
    private String join(String... values) {
        return Arrays.stream(values)
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining(" "));
    }


    /*
    normalize 方法会对文本进行归一化处理：
     */
    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "")
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]]+", "")
            .toLowerCase(Locale.ROOT);
    }

    /*
    firstNonBlank 方法会优先使用文档的知识域字段，如果为空才使用默认值： 用 firstNonBlank 兜底
    这是一个保守的兜底策略：
    如果 knowledgeScopeCode 有值但 knowledgeScopeName 为空，用 "通用文档" 作为名称
    如果 knowledgeScopeName 有值但 knowledgeScopeCode 为空，用 "general_document" 作为编码
    这样即使数据不完整，也能继续参与路由，而不是直接跳过
     */
    private String firstNonBlank(String primary, String fallback) {
        if (StrUtil.isNotBlank(primary)) {
            return primary.trim();
        }
        return StrUtil.blankToDefault(fallback, "");
    }

    private String normalizeCode(String value) {
        return normalize(value).replaceAll("[^a-z0-9]+", "_");
    }




    /*embedSingle 方法会调用 Embedding 模型，把问题文本转换成向量表示：
    对单条文本生成向量。
 * @param text 原始文本
 * @return 向量；若不可用则返回 null
 ——————————————————————————————————————————————
 为什么要生成向量？
向量表示可以用于语义相似度匹配。即使问题和文档的词面不完全一致，只要语义相近，也能通过向量相似度匹配上。
比如用户问"如何配置 Spring Boot"，文档标题是"Spring Boot 配置指南"，虽然词面不完全匹配，但语义相似度很高，通过向量匹配就能找到。
     */
    //单条文本向量化
    private float[] embedSingle(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            return null;
        }
        try {
            return embeddingModel.embed(text.trim());
        }
        catch (Exception exception) {
            // 向量失败时自动退回纯词法路由，不中断主流程。
            log.warn("知识路由生成问题向量失败，退回词面匹配: text='{}'", StrUtil.maxLength(text, 120), exception);
            return null;
        }
    }




    /**
     * 批量计算候选文本的语义相似度。
     * 这是知识路由中最核心的“语义打分”入口之一，
     * 会拿“问题向量”和一批候选文本向量逐一计算余弦相似度，并保持结果顺序与 `routeTexts` 严格对齐。
     *
     * 设计上有几个关键点：
     * 1. 这里采用“批量 embedding + 顺序写回”的方式，而不是逐条 embedding，
     *    目的是减少 embedding 调用次数，提高路由阶段吞吐。
     * 2. 结果列表会预先按候选数量填充 0 分，
     *    这样后续可以按原下标直接回写分数，保证“第 N 个候选文本 -> 第 N 个语义分”始终成立。
     * 3. 只要语义链路不可用，就统一返回全 0 分，而不是抛错中断主流程。
     *    上层仍可继续依赖词法命中、实体词辅助分等机制完成保守路由。
     *
     * 语义链路会在以下场景直接退化：
     * 1. 当前 query 没有可用向量；
     * 2. 没有注入可用的 EmbeddingModel；
     * 3. 批量 embedding 返回结果数量与输入数量不一致；
     * 4. embedding 过程中抛出异常。
     *
     * @param queryContext 查询上下文，内部应已包含问题向量
     * @param routeTexts 候选文本列表
     * @return 与 `routeTexts` 等长、顺序一一对应的语义相似度列表
     */
    /*
    这个方法的关键点：
1. 批量向量化
为了避免一次性向量化太多文本导致调用压力过高，代码采用了批量处理策略：
每批最多处理 10 个文本（ROUTE_EMBEDDING_BATCH_SIZE = 10）
分批调用 embeddingModel.embed(currentBatch)
记录每批的处理进度，方便调试
2. 计算余弦相似度
对每个候选文本的向量，调用 cosineSimilarity 方法计算与问题向量的余弦相似度：
——————————————————————————————————————————————————————————————————————————————————————
这个方法有几个设计细节值得注意：
1.严格对齐：先用 0 填满整个结果列表，再按下标回写，保证输出顺序和输入 routeTexts 完全一致
2.分批处理：按 ROUTE_EMBEDDING_BATCH_SIZE 切分，避免一次性 embedding 太多文本超时
3.保守退化：任何异常（embedding 模型不可用、批量结果不完整、调用失败）都返回全 0，不中断主流程
     */
    //批量计算语义相似度    computeSemanticScores 方法会批量计算问题向量和每个知识域路由文本向量的余弦相似度。
    private List<Double> computeSemanticScores(RouteQueryContext queryContext, List<String> routeTexts) {
        if (!queryContext.semanticEnabled() || routeTexts == null || routeTexts.isEmpty()) {
            // 没有 query 向量或没有候选文本时，直接返回一组 0 分，占位但不中断后续路由。
            return routeTexts == null ? List.of() : routeTexts.stream().map(item -> 0D).toList();
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            // 运行环境未提供 embedding 能力时，整体退回词法路径。
            return routeTexts.stream().map(item -> 0D).toList();
        }
        try {
            // 先把候选文本归一化为非 null 字符串，避免 embedding SDK 因 null 值报错。
            List<String> normalizedRouteTexts = routeTexts.stream()
                .map(item -> StrUtil.blankToDefault(item, ""))
                .toList();
            // 预先按原候选个数填满 0 分，后续按下标回写，确保结果和输入严格对齐。
            List<Double> scores = new ArrayList<>(normalizedRouteTexts.size());
            for (int index = 0; index < normalizedRouteTexts.size(); index++) {
                scores.add(0D);
            }
            // 根据配置好的批大小切分候选，避免一次性 embedding 过多文本造成调用压力或超时。
            int totalBatchCount = (normalizedRouteTexts.size() + ROUTE_EMBEDDING_BATCH_SIZE - 1) / ROUTE_EMBEDDING_BATCH_SIZE;
            for (int startIndex = 0; startIndex < normalizedRouteTexts.size(); startIndex += ROUTE_EMBEDDING_BATCH_SIZE) {
                int endIndex = Math.min(startIndex + ROUTE_EMBEDDING_BATCH_SIZE, normalizedRouteTexts.size());
                List<String> currentBatch = normalizedRouteTexts.subList(startIndex, endIndex);
                int currentBatchIndex = (startIndex / ROUTE_EMBEDDING_BATCH_SIZE) + 1;
                // 候选文本按批次向量化，避免一次性 embedding 过大导致调用压力过高。
                log.debug("知识路由候选向量分批计算: batchIndex={}/{}, candidateRange=[{}, {}], batchSize={}",
                    currentBatchIndex, totalBatchCount, startIndex + 1, endIndex, currentBatch.size());
                List<float[]> embeddings = embeddingModel.embed(currentBatch);
                if (embeddings == null || embeddings.size() != currentBatch.size()) {
                    // 批量结果不完整时，统一回退为 0 分，避免误判。
                    return routeTexts.stream().map(item -> 0D).toList();
                }
                for (int batchIndex = 0; batchIndex < embeddings.size(); batchIndex++) {
                    // 当前批次里的第 batchIndex 个向量，要写回到全局 scores 的真实下标 startIndex + batchIndex 上。
                    // 这样即使分批处理，最终结果仍与 routeTexts 原顺序完全一致。
                    scores.set(startIndex + batchIndex, cosineSimilarity(queryContext.queryEmbedding(), embeddings.get(batchIndex)));
                }
            }
            // 所有批次都成功后，返回与原候选顺序完全对齐的相似度列表。
            return scores;
        }
        catch (Exception exception) {
            // 候选向量化失败也不阻断主流程，直接退回词法匹配。
            log.warn("知识路由生成候选向量失败，退回词面匹配: candidateCount={}", routeTexts.size(), exception);
            return routeTexts.stream().map(item -> 0D).toList();
        }
    }


    /**
     * 计算单条候选文本与查询的语义相似度。
     * 这个方法和 `computeSemanticScores(...)` 的区别在于：
     *
     * 1. `computeSemanticScores(...)` 适合”已经有一批候选文本，且希望批量算完后按原顺序对齐返回”的场景；
     * 2. `semanticScore(...)` 适合”当前只有一条临时拼装出来的候选文本，需要即时算一个语义分”的场景。
     *
     * 在本类里，这个方法通常用于 fallback 或派生候选路径，
     * 比如从文档元数据反推 scope、从画像反推 topic 这类”边构造 routeText 边打分”的逻辑。
     *
     * 设计上同样采用保守退化策略：
     * 1. query 没有可用向量时，直接返回 0；
     * 2. 当前候选文本向量生成失败时，直接返回 0；
     * 3. 返回 0 表示”不提供语义加分”，由词法或实体词辅助分继续支撑路由，而不是中断主流程。
     *
     * @param queryContext 查询上下文
     * @param routeText 候选文本
     * @return 语义相似度
     */
    private double semanticScore(RouteQueryContext queryContext, String routeText) {
        /*
        semanticEnabled() 方法检查查询上下文中是否有可用的问题向量。如果没有，说明：
        问题向量化失败
        或者 embedding 服务不可用
        此时直接返回 0，表示”不提供语义加分”，让词法或实体词辅助分继续支撑路由。
         */
        // 当前 query 没有可用向量时，单条语义评分自动失效。  1.检查查询向量是否可用
        if (!queryContext.semanticEnabled()) {
            return 0D;
        }
        // 对这条候选文本即时生成向量，用于和 query 向量做余弦相似度比较。 2.对候选文本即时生成向量
        float[] routeEmbedding = embedSingle(routeText);
        if (routeEmbedding == null) {
            // 候选文本向量不可用时，不报错，直接视为没有语义贡献。
            return 0D;
        }
        // 真正的语义相似度计算统一交给余弦相似度函数处理。  3.计算余弦相似度
        return cosineSimilarity(queryContext.queryEmbedding(), routeEmbedding);
    }

    /**1. 语义主分（semanticMainScore）
     * 将原始余弦相似度映射为主分。
     * @param semanticScore 余弦相似度
     * @return 语义主分
     * _______________________________
     * 语义主分的作用：作为路由决策的主要依据，占总分的最大比重。它基于向量语义相似度，能够理解同义表达，比如"配置数据源"和"设置数据库连接"虽然用词不同，但语义相似度高，都能正确路由到数据库相关的知识域。 把余弦相似度映射成实际分值
     */
    private double semanticMainScore(double semanticScore) {
        if (semanticScore <= 0.20D) {
            // 过低相似度直接视作无贡献，减少噪声召回影响。
            return 0D;
        }
        return (semanticScore - 0.20D) * 50D;//相似度低于 0.20 直接归零，高于 0.20 的部分乘以 50 放大。比如相似度 0.60，得分就是 (0.60 - 0.20) * 50 = 20。
    }


    /**词法辅助分 把倒排索引的命中分映射成辅助分，最多贡献 10 分。
     * 将倒排词法命中分映射为辅助分。
     *
     * @param lexicalScore 词法命中分
     * @return 辅助分
     */
    private double lexicalAssist(Double lexicalScore) {
        if (lexicalScore == null || lexicalScore <= 0D) {
            return 0D;
        }
        return Math.min(10D, lexicalScore * 1.6D);
    }

    /**关键词实体辅助分        ————————————————专门针对短词、英文缩写、编号等高指向性词项做额外加分，每命中一个加 6 分
     * 针对实体词做额外加分。
     *
     * @param queryTerms 查询词
     * @param routeText 候选文本
     * @return 实体词辅助分
     */
    /**
     * 针对实体词做额外加分。
     * 这个方法的存在，是为了补足”纯语义相似度 + 纯词法召回”在某些场景下的不足。
     *
     * 在知识路由里，像下面这些词往往特别有指向性：
     * 1. 文档编号、章节编号、产品型号、主题代码；
     * 2. 英文缩写、术语代号；
     * 3. 较短但高度特指的实体名。
     *
     * 这类词的共同特点是：
     * 1. 语义向量不一定能稳定放大它们的重要性；
     * 2. 倒排检索虽然能命中，但不一定足够突出；
     * 3. 一旦命中，通常对”路由到哪个 scope / topic / document”有很强的决定作用。
     *
     * 因此这里会先筛出”看起来像实体词”的 query term，
     * 再判断这些词是否直接包含在候选文本中；每命中一个，就追加一笔固定辅助分。
     *
     * @param queryTerms 查询词
     * @param routeText 候选文本
     * @return 实体词辅助分
     */
    /*
    为什么要单独处理实体词？
    语义向量对"Spring Boot"、"JVM"、"Redis"这类专有名词的区分能力有限，倒排索引虽然能命中但权重不够突出。实体词辅助分就是专门补这个缺口的——一旦 query 里出现了这类高指向性词，并且候选主题的 routeText 里也包含，就直接追加固定分值，让路由结果更精准。
     */
    private double keywordEntityAssist(List<String> queryTerms, String routeText) {
        if (queryTerms == null || queryTerms.isEmpty()) {//1.参数校验 如果查询词列表为空，直接返回 0 分。这是一个保守的兜底策略，避免空指针异常。
            return 0D;
        }
        double score = 0D;
        // 候选文本先做统一归一化，避免大小写、空白、标点差异影响包含判断。
        String normalizedContent = normalize(routeText);//2.归一化候选文本
        for (String term : queryTerms) {//3.遍历查询词，筛选实体词
            if (!looksLikeEntityTerm(term)) {
                // 只对”更像实体词”的词项加这类特殊分，普通长句词不在这里重复加权。
                continue;
            }
            /*4.归一化查询词并过滤
            对查询词也进行归一化，然后过滤掉：
归一化后为空的词（如纯标点符号）
归一化后长度 < 2 的词（如单个字母、数字）
为什么要过滤长度 < 2 的词？
归一化后长度 < 2 的词通常不具备稳定的路由指向性：
“a”、”b”、”1”、”2” 这类单字符在文本中出现频率很高，但没有实际意义
即使命中，也不能说明候选文本和问题相关
             */
            String normalizedTerm = normalize(term);
            if (StrUtil.isBlank(normalizedTerm) || normalizedTerm.length() < 2) {
                // 清洗后过短或为空的词项不具备稳定指向性，跳过。
                continue;
            }
            /*5. 检查是否包含，累加分数   如果归一化后的候选文本包含归一化后的查询词，就加 6 分。
            为什么是 6 分？
            这是一个经过权衡的固定值：
            不能太低（如 1-2 分）- 实体词的指向性很强，分数太低无法体现其重要性
            不能太高（如 10-15 分）- 避免单个实体词就主导整个路由决策
            6 分的权重：
            1 个实体词命中 = 6 分，相当于语义相似度 0.32（语义主分 6 分）
            2 个实体词命中 = 12 分，相当于语义相似度 0.44（语义主分 12 分）
            3 个实体词命中 = 18 分，相当于语义相似度 0.56（语义主分 18 分）
            这样的权重设计，让实体词在语义相似度接近时起到决胜作用，但不会完全主导路由决策。
             */
            if (normalizedContent.contains(normalizedTerm)) {
                // 文档名、代号、编号等"实体词"命中时，通常具有更强的路由指向性。
                score += 6D;
            }
        }
        return score;
    }


    /**三种打分协同工作，确保了路由的准确性和鲁棒性。
     * 判断一个词是否更像实体词。
     * @param term 词项
     * @return true 表示更像实体词
     * ————————————————————————————————
     * 为什么要单独处理实体词？
     * 实体词（如 "Spring"、"MySQL"、"K8s"）通常具有更强的指向性。如果用户问题里包含 "Spring"，那么 java_dev 知识域的相关性就很高。
     * 通过给实体词额外加分，可以提高路由的准确性。
     */
    private boolean looksLikeEntityTerm(String term) {
        if (StrUtil.isBlank(term)) {
            return false;
        }
        String trimmed = term.trim();
        return trimmed.matches(".*[A-Za-z].*") // 包含英文字母
            || trimmed.matches(".*\\d.*")  // 包含数字
            || trimmed.length() <= 4; // 长度 ≤ 4
    }



    /**
     * 计算两个向量的余弦相似度。
     * 余弦相似度关注的是两个向量“方向是否接近”，而不是绝对长度，
     * 因此很适合用来衡量“问题向量”和“候选文本向量”在语义空间中的接近程度。
     *
     * 计算公式为：
     * `dot(left, right) / (||left|| * ||right||)`
     *
     * 这里有几个防御性约束：
     * 1. 任一向量为 null、长度为 0，或两者维度不一致时，直接返回 0；
     * 2. 任一向量范数为 0 时，直接返回 0，避免分母为 0；
     * 3. 返回 0 表示“当前语义链路不给这个候选加分”，而不是直接抛异常中断路由主流程。
     *
     * @param left 左向量，通常是 query 向量
     * @param right 右向量，通常是候选文本向量
     * @return 余弦相似度，理论范围通常在 [-1, 1]，本场景下更常关注接近 0 到 1 的区间
     */
    //余弦相似度计算
    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            // 输入不合法时直接返回 0，相当于“不提供语义加分”。
            return 0D;
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int index = 0; index < left.length; index++) {
            // dot 是点积，衡量两个向量在同一方向上的重合程度。
            dot += left[index] * right[index];
            // leftNorm / rightNorm 分别累积平方和，后续开方得到向量长度。
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        /*
         完善的兜底机制
如果向量不可用，返回全 0 分数
如果 EmbeddingModel 不可用，返回全 0 分数
如果批量向量化失败，返回全 0 分数
如果批量结果不完整，返回全 0 分数
这样即使向量化失败，也不会中断主流程，会自动退回到纯词法匹配。
         */
        if (leftNorm <= 0D || rightNorm <= 0D) {
            // 任一向量长度为 0 时无法做归一化，直接视为没有可用语义相似度。
            return 0D;
        }
        // 先将点积除以两边向量长度，得到与长度无关、只体现方向接近程度的相似度。
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private double lexicalWeight(int termLength) {
        if (termLength >= 8) {
            return 12D;
        }
        if (termLength >= 5) {
            return 8D;
        }
        if (termLength >= 3) {
            return 4D;
        }
        return 2D;
    }

    private void expandChineseNgrams(Set<String> terms, String segment) {
        String normalized = segment.trim();
        if (normalized.length() < 4) {
            return;
        }
        int maxGramLength = Math.min(6, normalized.length());
        for (int gramLength = 2; gramLength <= maxGramLength; gramLength++) {
            for (int start = 0; start + gramLength <= normalized.length(); start++) {
                String gram = normalized.substring(start, start + gramLength);
                if (gram.length() >= 2) {
                    terms.add(gram);
                }
            }
        }
    }


    /**resolveDecisionReason 方法会生成一个人类可读的原因说明
     * 生成路由决策的原因说明。
     *
     * @param documentCandidates 文档候选
     * @param confidence 置信度
     * @return 原因说明
     */
    private String resolveDecisionReason(List<DocumentRouteCandidate> documentCandidates, BigDecimal confidence) {
        if (documentCandidates == null || documentCandidates.isEmpty()) {
            return "没有找到可用候选文档";
        }
        String topReason = StrUtil.blankToDefault(documentCandidates.get(0).getReason(), "");
        if (confidence == null) {
            return topReason;
        }
        if (confidence.compareTo(BigDecimal.valueOf(0.55D)) < 0) {
            return StrUtil.blankToDefault(topReason, "低置信度，已进入保守扩范围候选");
        }
        return topReason;
    }

    private String writeScopeJson(List<ScopeRouteCandidate> candidates) {
        return candidates == null || candidates.isEmpty() ? "[]" : candidates.stream()
            .map(item -> "{\"scopeCode\":\"" + item.getScopeCode() + "\",\"scopeName\":\"" + item.getScopeName() + "\",\"score\":\"" + item.getScore() + "\",\"reason\":\"" + escapeJson(item.getReason()) + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String writeTopicJson(List<TopicRouteCandidate> candidates) {
        return candidates == null || candidates.isEmpty() ? "[]" : candidates.stream()
            .map(item -> "{\"topicCode\":\"" + item.getTopicCode() + "\",\"topicName\":\"" + item.getTopicName() + "\",\"scopeCode\":\"" + item.getScopeCode() + "\",\"score\":\"" + item.getScore() + "\",\"reason\":\"" + escapeJson(item.getReason()) + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String writeDocumentJson(List<DocumentRouteCandidate> candidates) {
        return candidates == null || candidates.isEmpty() ? "[]" : candidates.stream()
            .map(item -> "{\"documentId\":\"" + item.getDocumentId() + "\",\"documentName\":\"" + escapeJson(item.getDocumentName()) + "\",\"lastIndexTaskId\":\"" + item.getLastIndexTaskId() + "\",\"score\":\"" + item.getScore() + "\",\"reason\":\"" + escapeJson(item.getReason()) + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String escapeJson(String text) {
        return StrUtil.blankToDefault(text, "").replace("\"", "\\\"");
    }


    /**累加器
     * 从文档反推知识域时使用的中间累加器。
     * 累加器的作用：
     * 聚合同一知识域在多个文档中的得分
     * 保留该知识域的最高得分和原因说明
     * ——————————————————————————————————————————
     * 什么要用累加器？
     *
     *
     * 同一个知识域可能对应多个文档，比如 java_dev 知识域下可能有 "Spring Boot 文档"、"MyBatis 文档"、"JVM 调优指南" 等多个文档。
     * 累加器的作用是：
     * 1.把同一知识域的文档聚合在一起
     * 2.保留该知识域下得分最高的那个文档的信息
     * 3.用这个最高分作为该知识域的得分
     */
    private static final class ScopeAccumulator {
        private final String scopeCode;
        private final String scopeName;
        private double maxScore;
        private String reason = "";

        private ScopeAccumulator(String scopeCode, String scopeName) {
            this.scopeCode = scopeCode;
            this.scopeName = scopeName;
        }
    }

    private static final class TopicAccumulator {
        private final String topicName;
        private final String scopeCode;
        private double maxScore;
        private String reason = "";

        private TopicAccumulator(String topicName, String scopeCode) {
            this.topicName = topicName;
            this.scopeCode = scopeCode;
        }
    }





    /*路由查询上下文
    内部数据结构  汇总原问题、改写问题、组合路由文本、关键词列表以及问题向量。
     */
    private record RouteQueryContext(String originalQuestion,//原始问题
                                     String rewriteQuestion,//改写后的问题
                                     String routingText,//拼接后的路由文本原问题 + 改写问题）
                                     List<String> queryTerms,//分词结果 提取出的关键词列表
                                     float[] queryEmbedding) {//查询向量  问题的向量表示（用于语义匹配）
        private boolean semanticEnabled() {
            return queryEmbedding != null && queryEmbedding.length > 0;
        }
    }

    private record DocumentRouteMaterial(Long documentId, String routeText) {
    }
}
