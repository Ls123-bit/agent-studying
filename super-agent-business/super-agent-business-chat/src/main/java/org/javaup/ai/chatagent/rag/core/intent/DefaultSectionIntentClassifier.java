package org.javaup.ai.chatagent.rag.core.intent;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.service.ObservedChatModelService;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于文档章节结构的意图分类器。
 *
 * <p>对标 ragent 的 DefaultIntentClassifier。
 * 将文档的章节结构树作为意图节点，LLM 对每个章节打分，
 * 得分作为软路由信号引导检索优先搜索高分章节。</p>
 *
 * <p>关键设计：</p>
 * <ul>
 *   <li>章节树按 documentId 缓存，首次访问时加载</li>
 *   <li>只取 SECTION 类型节点，按 depth 过滤（避免节点过多导致 prompt 膨胀）</li>
 *   <li>LLM 调用使用低温度(0.1)保证输出稳定</li>
 *   <li>分数仅用于软路由，永远不用于硬过滤</li>
 * </ul>
 */
@Slf4j
@Service
public class DefaultSectionIntentClassifier implements SectionIntentClassifier {

    private static final String CLASSIFY_PROMPT = """
            你是文档章节分类助手。根据用户问题，判断最可能相关的文档章节。

            以下是文档的章节结构：
            {section_list}

            规则：
            1. 对每个可能相关的章节打分，范围 0.0~1.0。
            2. 只返回你认为相关的章节，不相关的不要返回。
            3. 如果没有明确相关的章节，返回空数组 []。
            4. 只返回合法 JSON 数组，不要附加解释。

            输出格式：
            [{"id": "章节ID", "score": 0.9}]

            用户问题：
            {question}
            """;

    /** 每个文档最多参与分类的章节数，避免 prompt 过长。 */
    private static final int MAX_CLASSIFY_NODES = 40;

    /** 参与分类的最大章节深度（depth <= 此值的 SECTION 节点）。 */
    private static final int MAX_CLASSIFY_DEPTH = 3;

    /** 按 documentId 缓存章节意图树。 */
    private final ConcurrentHashMap<Long, List<SectionIntentNode>> sectionTreeCache = new ConcurrentHashMap<>();

    private final DocumentStructureNodeService documentStructureNodeService;
    private final ObservedChatModelService observedChatModelService;
    private final ObjectMapper objectMapper;
    private final ChatRagProperties properties;

    public DefaultSectionIntentClassifier(DocumentStructureNodeService documentStructureNodeService,
                                          ObservedChatModelService observedChatModelService,
                                          ObjectMapper objectMapper,
                                          ChatRagProperties properties) {
        this.documentStructureNodeService = documentStructureNodeService;
        this.observedChatModelService = observedChatModelService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<SectionNodeScore> classifyTargets(Long documentId, String question) {
        if (documentId == null || StrUtil.isBlank(question)) {
            return List.of();
        }

        List<SectionIntentNode> sectionNodes = loadSectionTree(documentId);
        if (sectionNodes.isEmpty()) {
            log.info("[意图分类] 文档无章节结构: documentId={}", documentId);
            return List.of();
        }

        try {
            String sectionList = buildSectionListText(sectionNodes);
            String prompt = CLASSIFY_PROMPT
                    .replace("{section_list}", sectionList)
                    .replace("{question}", question);

            String content = observedChatModelService.callText("intent-classify", null, prompt, null);
            List<SectionNodeScore> scores = parseScores(content, sectionNodes);

            log.info("[意图分类] 完成: documentId={}, question='{}', matchedSections={}",
                    documentId, question,
                    scores.stream().map(s -> s.node().getDisplayPath() + "=" + s.score()).toList());
            return scores;
        } catch (Exception e) {
            log.warn("[意图分类] LLM调用失败，返回空结果: {}", e.getMessage());
            return List.of();
        }
    }

    // ── 章节树加载与缓存 ──

    private List<SectionIntentNode> loadSectionTree(Long documentId) {
        return sectionTreeCache.computeIfAbsent(documentId, this::buildSectionTree);
    }

    private List<SectionIntentNode> buildSectionTree(Long documentId) {
        Map<Long, SuperAgentDocumentStructureNode> nodeMap =
                documentStructureNodeService.nodeMap(documentId, null);
        if (nodeMap.isEmpty()) {
            return List.of();
        }

        // 只取 SECTION 类型、深度不超过阈值的节点
        List<SectionIntentNode> allSections = nodeMap.values().stream()
                .filter(n -> n != null
                        && DocumentStructureNodeTypeEnum.SECTION.getCode().equals(n.getNodeType())
                        && n.getDepth() != null
                        && n.getDepth() <= MAX_CLASSIFY_DEPTH)
                .sorted(Comparator.comparing(
                        SuperAgentDocumentStructureNode::getNodeNo,
                        Comparator.nullsLast(Integer::compareTo)))
                .map(SectionIntentNode::fromStructureNode)
                .toList();

        // 组装父子关系
        Map<Long, SectionIntentNode> intentNodeMap = allSections.stream()
                .collect(Collectors.toMap(SectionIntentNode::getId, n -> n, (a, b) -> a));
        for (SectionIntentNode node : allSections) {
            if (node.getParentId() != null) {
                SectionIntentNode parent = intentNodeMap.get(node.getParentId());
                if (parent != null) {
                    parent.getChildren().add(node);
                }
            }
        }

        log.info("[意图分类] 章节树构建完成: documentId={}, sectionCount={}", documentId, allSections.size());
        return allSections;
    }

    /**
     * 清除指定文档的章节树缓存（文档重新解析后调用）。
     */
    public void evictCache(Long documentId) {
        sectionTreeCache.remove(documentId);
    }

    // ── Prompt 构建 ──

    private String buildSectionListText(List<SectionIntentNode> nodes) {
        List<SectionIntentNode> candidates = nodes.stream()
                .limit(MAX_CLASSIFY_NODES)
                .toList();

        StringBuilder sb = new StringBuilder();
        for (SectionIntentNode node : candidates) {
            sb.append("- id: ").append(node.getId())
                    .append(", path: ").append(node.getDisplayPath());
            if (StrUtil.isNotBlank(node.getTitle())) {
                sb.append(", title: ").append(node.getTitle());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ── 结果解析 ──

    private List<SectionNodeScore> parseScores(String raw, List<SectionIntentNode> sectionNodes) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        try {
            String cleaned = stripMarkdownCodeFence(raw.trim());
            JsonNode root = objectMapper.readTree(cleaned);

            // 兼容 [{"id":..., "score":...}] 和 {"results": [...]} 两种格式
            JsonNode array = root.isArray() ? root : root.path("results");
            if (!array.isArray()) {
                return List.of();
            }

            Map<Long, SectionIntentNode> nodeById = sectionNodes.stream()
                    .collect(Collectors.toMap(SectionIntentNode::getId, n -> n, (a, b) -> a));

            double minScore = properties.getIntentMinScore();
            List<SectionNodeScore> results = new ArrayList<>();
            for (JsonNode item : array) {
                long id = item.path("id").asLong(0);
                double score = item.path("score").asDouble(0.0);
                SectionIntentNode node = nodeById.get(id);
                if (node != null && score >= minScore) {
                    results.add(new SectionNodeScore(node, score));
                }
            }

            results.sort(Comparator.comparingDouble(SectionNodeScore::score).reversed());
            return results;
        } catch (Exception e) {
            log.warn("[意图分类] JSON解析失败: raw={}", raw, e);
            return List.of();
        }
    }

    private String stripMarkdownCodeFence(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.trim();
    }
}
