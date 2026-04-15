package org.javaup.ai.manage.service.navigation;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.manage.graph.node.ItemGraphNode;
import org.javaup.ai.manage.graph.node.SectionGraphNode;
import org.javaup.ai.manage.graph.repository.ItemGraphNodeRepository;
import org.javaup.ai.manage.graph.repository.SectionGraphNodeRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 基于 Neo4j 的结构图查询实现。
 *
 * <p>当 Neo4j 可用时，此实现作为 {@link DocumentStructureGraphService} 的主实现，
 * 替代 {@link MysqlDocumentStructureGraphService}。</p>
 */
@Primary
@Service
@ConditionalOnBean(Neo4jTemplate.class)
public class Neo4jDocumentStructureGraphService implements DocumentStructureGraphService {

    private final SectionGraphNodeRepository sectionRepository;
    private final ItemGraphNodeRepository itemRepository;

    public Neo4jDocumentStructureGraphService(SectionGraphNodeRepository sectionRepository,
                                               ItemGraphNodeRepository itemRepository) {
        this.sectionRepository = sectionRepository;
        this.itemRepository = itemRepository;
    }

    @Override
    public GraphSection findSectionByNodeId(Long documentId, Long nodeId) {
        if (documentId == null || nodeId == null) {
            return null;
        }
        return sectionRepository.findAllByDocumentId(documentId).stream()
            .filter(s -> Objects.equals(nodeId, s.getNodeId()))
            .findFirst()
            .map(this::toGraphSection)
            .orElse(null);
    }
    @Override
    public GraphSection findSectionByCode(Long documentId, String nodeCode) {
        if (documentId == null || StrUtil.isBlank(nodeCode)) {
            return null;
        }
        return sectionRepository.findByDocumentIdAndNodeCode(documentId, nodeCode.trim())
            .map(this::toGraphSection)
            .orElse(null);
    }

    @Override
    public GraphSection findSectionByTitle(Long documentId, String title) {
        if (documentId == null || StrUtil.isBlank(title)) {
            return null;
        }
        return sectionRepository.findByDocumentIdAndTitleContaining(documentId, title.trim())
            .map(this::toGraphSection)
            .orElse(null);
    }

    @Override
    public List<GraphSection> listTopSections(Long documentId) {
        if (documentId == null) {
            return List.of();
        }
        return sectionRepository.findTopSectionsByDocumentId(documentId).stream()
            .map(this::toGraphSection)
            .toList();
    }

    @Override
    public List<GraphSection> listChildren(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        return sectionRepository.findChildrenByParentNodeId(sectionNodeId).stream()
            .map(this::toGraphSection)
            .toList();
    }

    @Override
    public GraphSection parentSection(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        return sectionRepository.findParent(sectionNodeId)
            .map(this::toGraphSection)
            .orElse(null);
    }
    @Override
    public GraphSection previousSibling(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        return sectionRepository.findPrevSibling(sectionNodeId)
            .map(this::toGraphSection)
            .orElse(null);
    }

    @Override
    public GraphSection nextSibling(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        return sectionRepository.findNextSibling(sectionNodeId)
            .map(this::toGraphSection)
            .orElse(null);
    }

    @Override
    public GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) {
        if (documentId == null || sectionNodeId == null || itemIndex == null) {
            return null;
        }
        return itemRepository.findBySectionNodeIdAndItemIndex(sectionNodeId, itemIndex)
            .map(this::toGraphItem)
            .orElse(null);
    }

    @Override
    public List<GraphItem> listItems(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        return itemRepository.findBySectionNodeId(sectionNodeId).stream()
            .map(this::toGraphItem)
            .toList();
    }

    @Override
    public List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword) {
        if (documentId == null || sectionNodeId == null || StrUtil.isBlank(keyword)) {
            return List.of();
        }
        return itemRepository.searchBySectionNodeIdAndKeyword(sectionNodeId, keyword.trim()).stream()
            .map(this::toGraphItem)
            .toList();
    }

    private GraphSection toGraphSection(SectionGraphNode node) {
        return new GraphSection(
            node.getNodeId(),
            node.getDocumentId(),
            safe(node.getNodeCode()),
            safe(node.getTitle()),
            safe(node.getAnchorText()),
            safe(node.getSectionPath()),
            safe(node.getCanonicalPath()),
            node.getDepth(),
            null,
            null,
            null,
            safe(node.getContentText())
        );
    }

    private GraphItem toGraphItem(ItemGraphNode node) {
        return new GraphItem(
            node.getNodeId(),
            node.getDocumentId(),
            safe(node.getNodeType()),
            node.getItemIndex(),
            safe(node.getTitle()),
            safe(node.getAnchorText()),
            safe(node.getSectionPath()),
            safe(node.getCanonicalPath()),
            safe(node.getContentText()),
            null
        );
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
