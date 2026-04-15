package org.javaup.ai.manage.service.navigation;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 MySQL 结构节点表的结构图查询实现。
 *
 * <p>当 Neo4j 可用时，{@link Neo4jDocumentStructureGraphService} 作为主实现，
 * 此类作为 fallback 保留。可通过 {@code @Qualifier("mysqlDocumentStructureGraphService")} 显式注入。</p>
 */
@Service
public class MysqlDocumentStructureGraphService implements DocumentStructureGraphService {

    private final DocumentStructureNodeService documentStructureNodeService;

    public MysqlDocumentStructureGraphService(DocumentStructureNodeService documentStructureNodeService) {
        this.documentStructureNodeService = documentStructureNodeService;
    }

    @Override
    public GraphSection findSectionByNodeId(Long documentId, Long nodeId) {
        if (documentId == null || nodeId == null) {
            return null;
        }
        Map<Long, SuperAgentDocumentStructureNode> nodeMap = documentStructureNodeService.nodeMap(documentId, null);
        SuperAgentDocumentStructureNode node = nodeMap.get(nodeId);
        return node != null && isSection(node) ? toGraphSection(node) : null;
    }

    @Override
    public GraphSection findSectionByCode(Long documentId, String nodeCode) {
        if (documentId == null || StrUtil.isBlank(nodeCode)) {
            return null;
        }
        return listAllSections(documentId).stream()
            .filter(node -> nodeCode.trim().equals(safe(node.getNodeCode())))
            .findFirst()
            .map(this::toGraphSection)
            .orElse(null);
    }

    @Override
    public GraphSection findSectionByTitle(Long documentId, String title) {
        if (documentId == null || StrUtil.isBlank(title)) {
            return null;
        }
        String normalizedTitle = title.trim().toLowerCase();
        return listAllSections(documentId).stream()
            .filter(node -> normalizedTitle.equals(safe(node.getTitle()).toLowerCase())
                || safe(node.getSectionPath()).toLowerCase().contains(normalizedTitle))
            .findFirst()
            .map(this::toGraphSection)
            .orElse(null);
    }

    @Override
    public List<GraphSection> listTopSections(Long documentId) {
        if (documentId == null) {
            return List.of();
        }
        return listAllSections(documentId).stream()
            .filter(node -> node.getParentNodeId() == null || node.getDepth() != null && node.getDepth() <= 1)
            .map(this::toGraphSection)
            .toList();
    }

    @Override
    public List<GraphSection> listChildren(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        return listAllSections(documentId).stream()
            .filter(node -> Objects.equals(sectionNodeId, node.getParentNodeId()))
            .map(this::toGraphSection)
            .toList();
    }

    @Override
    public GraphSection parentSection(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        Map<Long, SuperAgentDocumentStructureNode> nodeMap = documentStructureNodeService.nodeMap(documentId, null);
        SuperAgentDocumentStructureNode node = nodeMap.get(sectionNodeId);
        if (node == null || node.getParentNodeId() == null) {
            return null;
        }
        SuperAgentDocumentStructureNode parent = nodeMap.get(node.getParentNodeId());
        return parent != null && isSection(parent) ? toGraphSection(parent) : null;
    }

    @Override
    public GraphSection previousSibling(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        Map<Long, SuperAgentDocumentStructureNode> nodeMap = documentStructureNodeService.nodeMap(documentId, null);
        SuperAgentDocumentStructureNode node = nodeMap.get(sectionNodeId);
        if (node == null || node.getPrevSiblingNodeId() == null) {
            return null;
        }
        SuperAgentDocumentStructureNode prev = nodeMap.get(node.getPrevSiblingNodeId());
        return prev != null && isSection(prev) ? toGraphSection(prev) : null;
    }

    @Override
    public GraphSection nextSibling(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        Map<Long, SuperAgentDocumentStructureNode> nodeMap = documentStructureNodeService.nodeMap(documentId, null);
        SuperAgentDocumentStructureNode node = nodeMap.get(sectionNodeId);
        if (node == null || node.getNextSiblingNodeId() == null) {
            return null;
        }
        SuperAgentDocumentStructureNode next = nodeMap.get(node.getNextSiblingNodeId());
        return next != null && isSection(next) ? toGraphSection(next) : null;
    }

    @Override
    public GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) {
        if (documentId == null || sectionNodeId == null || itemIndex == null) {
            return null;
        }
        return listItems(documentId, sectionNodeId).stream()
            .filter(item -> Objects.equals(itemIndex, item.itemIndex()))
            .findFirst()
            .orElse(null);
    }

    @Override
    public List<GraphItem> listItems(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        Map<Long, SuperAgentDocumentStructureNode> nodeMap = documentStructureNodeService.nodeMap(documentId, null);
        return nodeMap.values().stream()
            .filter(node -> node != null
                && Objects.equals(sectionNodeId, node.getParentNodeId())
                && isItem(node))
            .sorted(Comparator.comparing(SuperAgentDocumentStructureNode::getItemIndex, Comparator.nullsLast(Integer::compareTo)))
            .map(this::toGraphItem)
            .toList();
    }

    @Override
    public List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword) {
        if (documentId == null || sectionNodeId == null || StrUtil.isBlank(keyword)) {
            return List.of();
        }
        String normalizedKeyword = keyword.trim().toLowerCase();
        return listItems(documentId, sectionNodeId).stream()
            .filter(item -> safe(item.contentText()).toLowerCase().contains(normalizedKeyword)
                || safe(item.title()).toLowerCase().contains(normalizedKeyword))
            .toList();
    }

    private List<SuperAgentDocumentStructureNode> listAllSections(Long documentId) {
        Map<Long, SuperAgentDocumentStructureNode> nodeMap = documentStructureNodeService.nodeMap(documentId, null);
        return nodeMap.values().stream()
            .filter(node -> node != null && isSection(node))
            .sorted(Comparator.comparing(SuperAgentDocumentStructureNode::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .toList();
    }

    private boolean isSection(SuperAgentDocumentStructureNode node) {
        return DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType());
    }

    private boolean isItem(SuperAgentDocumentStructureNode node) {
        DocumentStructureNodeTypeEnum nodeType = DocumentStructureNodeTypeEnum.getRc(node.getNodeType());
        return nodeType == DocumentStructureNodeTypeEnum.STEP || nodeType == DocumentStructureNodeTypeEnum.LIST_ITEM;
    }

    private GraphSection toGraphSection(SuperAgentDocumentStructureNode node) {
        return new GraphSection(
            node.getId(),
            node.getDocumentId(),
            safe(node.getNodeCode()),
            safe(node.getTitle()),
            safe(node.getAnchorText()),
            safe(node.getSectionPath()),
            safe(node.getCanonicalPath()),
            node.getDepth(),
            node.getParentNodeId(),
            node.getPrevSiblingNodeId(),
            node.getNextSiblingNodeId(),
            safe(node.getContentText())
        );
    }

    private GraphItem toGraphItem(SuperAgentDocumentStructureNode node) {
        DocumentStructureNodeTypeEnum nodeType = DocumentStructureNodeTypeEnum.getRc(node.getNodeType());
        return new GraphItem(
            node.getId(),
            node.getDocumentId(),
            nodeType == null ? "UNKNOWN" : nodeType.name(),
            node.getItemIndex(),
            safe(node.getTitle()),
            safe(node.getAnchorText()),
            safe(node.getSectionPath()),
            safe(node.getCanonicalPath()),
            safe(node.getContentText()),
            node.getParentNodeId()
        );
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }
}