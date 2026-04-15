package org.javaup.ai.manage.graph;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.graph.node.DocumentGraphNode;
import org.javaup.ai.manage.graph.node.ItemGraphNode;
import org.javaup.ai.manage.graph.node.SectionGraphNode;
import org.javaup.ai.manage.graph.repository.ItemGraphNodeRepository;
import org.javaup.ai.manage.graph.repository.SectionGraphNodeRepository;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.javaup.enums.DocumentIndexStatusEnum;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文档结构图投影服务。
 *
 * <p>把 MySQL 结构节点同步到 Neo4j，建立父子、兄弟、item 关系边。</p>
 */
@Slf4j
@Service
public class DocumentStructureGraphProjectionService {

    private final DocumentStructureNodeService documentStructureNodeService;
    private final SectionGraphNodeRepository sectionRepository;
    private final ItemGraphNodeRepository itemRepository;
    private final Neo4jTemplate neo4jTemplate;
    private final SuperAgentDocumentMapper documentMapper;

    public DocumentStructureGraphProjectionService(
        DocumentStructureNodeService documentStructureNodeService,
        SectionGraphNodeRepository sectionRepository,
        ItemGraphNodeRepository itemRepository,
        Neo4jTemplate neo4jTemplate,
        SuperAgentDocumentMapper documentMapper) {
        this.documentStructureNodeService = documentStructureNodeService;
        this.sectionRepository = sectionRepository;
        this.itemRepository = itemRepository;
        this.neo4jTemplate = neo4jTemplate;
        this.documentMapper = documentMapper;
    }

    /**
     * 把指定文档的结构节点投影到 Neo4j。
     */
    @Transactional("neo4jTransactionManager")
    public void projectToGraph(Long documentId, Long parseTaskId) {
        log.info("开始图投影: documentId={}, parseTaskId={}", documentId, parseTaskId);
        updateGraphIndexStatus(documentId, DocumentIndexStatusEnum.BUILDING);

        try {
            doProjectToGraph(documentId, parseTaskId);
            updateGraphIndexStatus(documentId, DocumentIndexStatusEnum.BUILD_SUCCESS);
        }
        catch (Exception e) {
            log.error("图投影失败: documentId={}, error={}", documentId, e.getMessage(), e);
            updateGraphIndexStatus(documentId, DocumentIndexStatusEnum.BUILD_FAILED);
            throw e;
        }
    }

    private void doProjectToGraph(Long documentId, Long parseTaskId) {
        // 1. 清除旧图
        sectionRepository.deleteAllByDocumentId(documentId);
        itemRepository.deleteAllByDocumentId(documentId);

        // 2. 从 MySQL 加载结构节点
        Map<Long, SuperAgentDocumentStructureNode> nodeMap = documentStructureNodeService.nodeMap(documentId, parseTaskId);
        if (nodeMap.isEmpty()) {
            log.info("图投影: documentId={}, 无结构节点，跳过。", documentId);
            return;
        }

        // 3. 分类节点
        Map<Long, SectionGraphNode> sectionNodeMap = new LinkedHashMap<>();
        Map<Long, ItemGraphNode> itemNodeMap = new LinkedHashMap<>();

        for (SuperAgentDocumentStructureNode node : nodeMap.values()) {
            if (node == null) continue;
            DocumentStructureNodeTypeEnum nodeType = DocumentStructureNodeTypeEnum.getRc(node.getNodeType());
            if (nodeType == DocumentStructureNodeTypeEnum.SECTION) {
                SectionGraphNode graphNode = toSectionGraphNode(node);
                sectionNodeMap.put(node.getId(), graphNode);
            }
            else if (nodeType == DocumentStructureNodeTypeEnum.STEP || nodeType == DocumentStructureNodeTypeEnum.LIST_ITEM) {
                ItemGraphNode graphNode = toItemGraphNode(node, nodeType);
                itemNodeMap.put(node.getId(), graphNode);
            }
        }

        // 4. 建立关系
        for (SuperAgentDocumentStructureNode node : nodeMap.values()) {
            if (node == null) continue;
            SectionGraphNode sectionGraphNode = sectionNodeMap.get(node.getId());
            if (sectionGraphNode == null) continue;

            // 父子关系
            for (SuperAgentDocumentStructureNode child : nodeMap.values()) {
                if (child == null || !Objects.equals(node.getId(), child.getParentNodeId())) continue;
                SectionGraphNode childSection = sectionNodeMap.get(child.getId());
                if (childSection != null) {
                    sectionGraphNode.getChildren().add(childSection);
                }
                ItemGraphNode childItem = itemNodeMap.get(child.getId());
                if (childItem != null) {
                    sectionGraphNode.getItems().add(childItem);
                }
            }

            // 兄弟关系
            if (node.getNextSiblingNodeId() != null) {
                SectionGraphNode nextSibling = sectionNodeMap.get(node.getNextSiblingNodeId());
                if (nextSibling != null) {
                    sectionGraphNode.setNextSibling(nextSibling);
                }
            }
        }

        // 5. 保存文档根节点（带级联）
        DocumentGraphNode documentNode = new DocumentGraphNode();
        documentNode.setDocumentId(documentId);
        documentNode.setParseTaskId(parseTaskId);
        List<SectionGraphNode> topSections = sectionNodeMap.values().stream()
            .filter(s -> {
                SuperAgentDocumentStructureNode original = nodeMap.values().stream()
                    .filter(n -> n != null && Objects.equals(n.getId(), s.getNodeId()))
                    .findFirst().orElse(null);
                return original != null && (original.getParentNodeId() == null
                    || !sectionNodeMap.containsKey(original.getParentNodeId()));
            })
            .toList();
        documentNode.setTopSections(new ArrayList<>(topSections));
        neo4jTemplate.save(documentNode);

        log.info("图投影完成: documentId={}, sectionCount={}, itemCount={}",
            documentId, sectionNodeMap.size(), itemNodeMap.size());
    }

    private void updateGraphIndexStatus(Long documentId, DocumentIndexStatusEnum status) {
        LambdaUpdateWrapper<SuperAgentDocument> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SuperAgentDocument::getId, documentId)
            .set(SuperAgentDocument::getGraphIndexStatus, status.getCode())
            .set(SuperAgentDocument::getLastGraphIndexTime, LocalDateTime.now());
        documentMapper.update(null, wrapper);
    }

    private SectionGraphNode toSectionGraphNode(SuperAgentDocumentStructureNode node) {
        SectionGraphNode graphNode = new SectionGraphNode();
        graphNode.setNodeId(node.getId());
        graphNode.setDocumentId(node.getDocumentId());
        graphNode.setNodeNo(node.getNodeNo());
        graphNode.setNodeCode(safe(node.getNodeCode()));
        graphNode.setTitle(safe(node.getTitle()));
        graphNode.setAnchorText(safe(node.getAnchorText()));
        graphNode.setSectionPath(safe(node.getSectionPath()));
        graphNode.setCanonicalPath(safe(node.getCanonicalPath()));
        graphNode.setDepth(node.getDepth());
        graphNode.setContentText(safe(node.getContentText()));
        return graphNode;
    }

    private ItemGraphNode toItemGraphNode(SuperAgentDocumentStructureNode node, DocumentStructureNodeTypeEnum nodeType) {
        ItemGraphNode graphNode = new ItemGraphNode();
        graphNode.setNodeId(node.getId());
        graphNode.setDocumentId(node.getDocumentId());
        graphNode.setNodeNo(node.getNodeNo());
        graphNode.setNodeType(nodeType.name());
        graphNode.setItemIndex(node.getItemIndex());
        graphNode.setTitle(safe(node.getTitle()));
        graphNode.setAnchorText(safe(node.getAnchorText()));
        graphNode.setSectionPath(safe(node.getSectionPath()));
        graphNode.setCanonicalPath(safe(node.getCanonicalPath()));
        graphNode.setContentText(safe(node.getContentText()));
        return graphNode;
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
