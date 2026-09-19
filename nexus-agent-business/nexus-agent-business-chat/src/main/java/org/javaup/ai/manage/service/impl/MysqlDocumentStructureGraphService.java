package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.util.StrUtil;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.model.graph.GraphItem;
import org.javaup.ai.manage.model.graph.GraphSection;
import org.javaup.ai.manage.service.DocumentStructureGraphService;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;


@AllArgsConstructor
@Service("mysqlDocumentStructureGraphService")
public class MysqlDocumentStructureGraphService implements DocumentStructureGraphService {

    private final DocumentStructureNodeService documentStructureNodeService;



    /*
    没有部署 Neo4j 时，MySQL 实现先把文档所有章节加载成一个 Map<nodeId, GraphSection>，再直接按 key 取值：

     */
    @Override
    public GraphSection findSectionById(Long documentId, Long sectionNodeId) {
        // 章节查询先做空值保护，避免把无效参数带进后面的表扫描和 Map 访问。
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        // 构建 nodeId → GraphSection 的映射后直接按 key 取值，避免遍历列表
        // 先把当前文档的章节节点全部装进 Map，再按 sectionNodeId 做 O(1) 定位。
        return loadSectionMap(documentId).get(sectionNodeId);
    }




    @Override
    public GraphSection findSectionByTitle(Long documentId, String title) {
        if (documentId == null || StrUtil.isBlank(title)) {
            return null;
        }
        String normalized = normalize(title);
        return listSections(documentId).stream()
            .filter(section -> normalized.equals(normalize(section.getTitle()))
                || normalized.equals(normalize(section.getAnchorText()))
                || normalized.equals(normalize(section.getSectionPath())))
            .findFirst()
            .orElse(null);
    }

    @Override
    public GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath) {
        if (documentId == null || StrUtil.isBlank(canonicalPath)) {
            return null;
        }
        return listSections(documentId).stream()
            .filter(section -> canonicalPath.trim().equals(section.getCanonicalPath()))
            .findFirst()
            .orElse(null);
    }



    /*Neo4j 和 MySQL 两个实现的评分逻辑完全一样，区别只在于 listSections 的数据来源不同（Neo4j 走 Cypher，MySQL 走关系表）。

     */
    @Override
    public GraphSection findBestSection(Long documentId, String topic, String facet) {
        // 对入参做规范化处理，去除空白和 Markdown 符号，统一小写，便于后续字符串包含匹配
        String normalizedTopic = normalize(topic);
        String normalizedFacet = normalize(facet);
        // topic 和 facet 都为空时无法评分，直接返回 null
        if (documentId == null || (normalizedTopic.isBlank() && normalizedFacet.isBlank())) {
            return null;
        }
        GraphSection bestSection = null;
        int bestScore = 0;
        // 遍历文档所有章节，对每个章节独立计算匹配得分
        for (GraphSection section : listSections(documentId)) {
            int score = 0;
            String sectionPath = normalize(section.getSectionPath());
            String title = normalize(section.getTitle());
            String anchorText = normalize(section.getAnchorText());
            String content = normalize(section.getContentText());
            if (StrUtil.isNotBlank(normalizedTopic)) {
                if (title.contains(normalizedTopic) || sectionPath.contains(normalizedTopic)) {
                    score += 8;// 标题/路径命中 topic，权重最高
                }
                else if (anchorText.contains(normalizedTopic)) {
                    score += 6;// 锚文本命中 topic，次高权重
                }
                else if (content.contains(normalizedTopic)) {
                    score += 2;// 正文命中 topic，权重最低
                }
            }
            if (StrUtil.isNotBlank(normalizedFacet)) {
                if (title.contains(normalizedFacet) || sectionPath.contains(normalizedFacet)) {
                    score += 5;// 标题/路径命中 facet
                }
                else if (content.contains(normalizedFacet)) {
                    score += 1;// 正文命中 facet
                }
            }
            // 保留当前最高分的章节，严格大于才替换，保证首次命中优先
            if (score > bestScore) {
                bestScore = score;
                bestSection = section;
            }
        }
        // 得分为 0 说明没有任何章节命中，返回 null 而非随机章节
        return bestScore > 0 ? bestSection : null;
    }

    @Override
    public List<GraphSection> listSections(Long documentId) {
        return loadSections(documentId);
    }



    /*
    MySQL 的实现逻辑是：从内存中已经加载好的所有章节列表里，过滤出 parentNodeId 等于当前章节 ID 的记录，再按 nodeNo 排序。本质上就是一次内存中的 WHERE parent_node_id = ? 过滤。
    和前面几个方法不同的是，listChildren 返回的是一个列表而不是单个节点——一个章节可能有多个子章节。
     */
    @Override
    public List<GraphSection> listChildren(Long documentId, Long sectionNodeId) {
        // 如果文档 ID 或父章节节点 ID 为空，直接返回空列表，避免后续过滤逻辑出现歧义。
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        // 先拿到文档内所有章节，再筛选出 parentNodeId 指向当前章节的直接子节点。
        return listSections(documentId).stream()
                // 这里比较的是子章节的父节点引用，而不是标题文本或顺序号。
            .filter(section -> Objects.equals(sectionNodeId, section.getParentNodeId()))
                // 子章节结果按照 nodeNo 排序，保证展示顺序和文档结构顺序一致。
            .sorted(Comparator.comparing(GraphSection::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .toList();
    }


    /*
    MySQL 的实现思路完全不同：它不依赖图关系，而是依赖表中的 parent_node_id 字段。每个章节记录在入库时就已经存好了它的父节点 ID，查父章节就是两步：
    1.先查出当前章节，拿到它的 parentNodeId
    2.再用这个 parentNodeId 去查父章节
    因为 findSectionById 底层是内存 Map 查找，所以这两步都是 O(1) 操作，非常快。
     */
    @Override
    public GraphSection parentSection(Long documentId, Long sectionNodeId) {
        // 先按节点 ID 找到当前章节，再沿 parentNodeId 回溯到父章节。
        GraphSection section = findSectionById(documentId, sectionNodeId);
        // 当前章节不存在，或者本身就是根章节时，都没有父章节可返回。
        if (section == null || section.getParentNodeId() == null) {
            return null;
        }
        // 再次复用 findSectionById，保持所有章节定位逻辑都收敛到同一个入口。
        return findSectionById(documentId, section.getParentNodeId());
    }



    /*找到前一个兄弟章节
    MySQL 的做法更直接：表里有一个 prev_sibling_node_id 字段，直接记录了前一个兄弟的节点 ID。查询就是读字段 + Map 查找，两步搞定。
     */
    @Override
    public GraphSection previousSibling(Long documentId, Long sectionNodeId) {
        // 先找到当前章节，再通过 prevSiblingNodeId 定位前一个兄弟章节。
        GraphSection section = findSectionById(documentId, sectionNodeId);
        // 没有当前章节，或者没有前置兄弟引用时，说明左边没有相邻章节可查。
        if (section == null || section.getPrevSiblingNodeId() == null) {
            return null;
        }
        // 兄弟章节同样复用章节定位方法，避免重复写 Map 查询逻辑。
        return findSectionById(documentId, section.getPrevSiblingNodeId());
    }


    /*对应到 MySQL 就是读不同的字段：前者读 prev_sibling_node_id，后者读 next_sibling_node_id。
    同样的套路：表里有 next_sibling_node_id 字段，直接读字段再查 Map。

     */
    @Override
    public GraphSection nextSibling(Long documentId, Long sectionNodeId) {
        // 先找到当前章节，再通过 nextSiblingNodeId 定位后一个兄弟章节。
        GraphSection section = findSectionById(documentId, sectionNodeId);
        // 没有当前章节，或者没有后置兄弟引用时，说明右边没有相邻章节可查。
        if (section == null || section.getNextSiblingNodeId() == null) {
            return null;
        }
        // 通过统一入口返回下一个兄弟章节，保证回收逻辑和父章节查询一致。
        return findSectionById(documentId, section.getNextSiblingNodeId());
    }

    @Override
    public GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) {
        if (documentId == null || sectionNodeId == null || itemIndex == null) {
            return null;
        }
        return listItems(documentId, sectionNodeId).stream()
            .filter(item -> Objects.equals(itemIndex, item.getItemIndex()))
            .findFirst()
            .orElse(null);
    }

    @Override
    public List<GraphItem> listItems(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        return loadItems(documentId).stream()
            .filter(item -> Objects.equals(sectionNodeId, item.getSectionNodeId()))
            .sorted(Comparator.comparing(GraphItem::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .toList();
    }

    @Override
    public List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword) {
        List<GraphItem> items = listItems(documentId, sectionNodeId);
        if (items.isEmpty()) {
            return List.of();
        }
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword.isBlank()) {
            return items;
        }
        List<GraphItem> matched = new ArrayList<>();
        for (GraphItem item : items) {
            String haystack = normalize(String.join(" ",
                StrUtil.blankToDefault(item.getTitle(), ""),
                StrUtil.blankToDefault(item.getAnchorText(), ""),
                StrUtil.blankToDefault(item.getContentText(), "")
            ));
            if (haystack.contains(normalizedKeyword)) {
                matched.add(item);
            }
        }
        return matched;
    }






    /*没有部署 Neo4j 时，回退到 MysqlDocumentStructureGraphService.findSectionByCode，从关系表里查：
  先把文档所有章节加载进来，再在内存里做严格相等过滤。listSections 底层从 DB 查出所有 SECTION 类型节点，按 nodeNo 排序后转成 GraphSection 对象。
  和 findSectionByCode 的 MySQL 实现思路一样：先全量加载文档所有章节到内存，再做 Map 查找。虽然每次都要全量加载，但逻辑简单稳定，作为 fallback 够用
   */
    @Override
    public GraphSection findSectionByCode(Long documentId, String nodeCode) {
        if (documentId == null || StrUtil.isBlank(nodeCode)) {
            return null;
        }
        return listSections(documentId).stream()
                // 这里走的是严格相等匹配，不做模糊匹配，因为章节编号本身就是强结构标识。
                .filter(section -> nodeCode.trim().equals(section.getNodeCode()))
                .findFirst()
                .orElse(null);
    }
    private Map<Long, GraphSection> loadSectionMap(Long documentId) {
        Map<Long, GraphSection> sectionMap = new LinkedHashMap<>();
        // 遍历所有章节，以 nodeId 为 key 构建索引
        for (GraphSection section : loadSections(documentId)) {
            sectionMap.put(section.getNodeId(), section);
        }
        return sectionMap;
    }













    private List<GraphSection> loadSections(Long documentId) {
        if (documentId == null) {
            return List.of();
        }
        return documentStructureNodeService.listDocumentNodes(documentId, null).stream()
            .filter(node -> node != null && Objects.equals(DocumentStructureNodeTypeEnum.SECTION.getCode(), node.getNodeType()))
            .sorted(Comparator.comparing(SuperAgentDocumentStructureNode::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .map(this::toGraphSection)
            .toList();
    }

    private List<GraphItem> loadItems(Long documentId) {
        if (documentId == null) {
            return List.of();
        }
        return documentStructureNodeService.listDocumentNodes(documentId, null).stream()
            .filter(node -> node != null
                && (Objects.equals(DocumentStructureNodeTypeEnum.STEP.getCode(), node.getNodeType())
                || Objects.equals(DocumentStructureNodeTypeEnum.LIST_ITEM.getCode(), node.getNodeType())))
            .sorted(Comparator.comparing(SuperAgentDocumentStructureNode::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .map(this::toGraphItem)
            .toList();
    }

    private GraphSection toGraphSection(SuperAgentDocumentStructureNode node) {
        return GraphSection.builder()
            .nodeId(node.getId())
            .documentId(node.getDocumentId())
            .parseTaskId(node.getParseTaskId())
            .nodeNo(node.getNodeNo())
            .depth(node.getDepth())
            .parentNodeId(node.getParentNodeId())
            .prevSiblingNodeId(node.getPrevSiblingNodeId())
            .nextSiblingNodeId(node.getNextSiblingNodeId())
            .nodeCode(safeText(node.getNodeCode()))
            .title(safeText(node.getTitle()))
            .anchorText(safeText(node.getAnchorText()))
            .sectionPath(safeText(node.getSectionPath()))
            .canonicalPath(safeText(node.getCanonicalPath()))
            .contentText(safeText(node.getContentText()))
            .build();
    }

    private GraphItem toGraphItem(SuperAgentDocumentStructureNode node) {
        DocumentStructureNodeTypeEnum nodeTypeEnum = DocumentStructureNodeTypeEnum.getRc(node.getNodeType());
        return GraphItem.builder()
            .nodeId(node.getId())
            .documentId(node.getDocumentId())
            .parseTaskId(node.getParseTaskId())
            .nodeNo(node.getNodeNo())
            .nodeType(nodeTypeEnum == null ? "" : nodeTypeEnum.name())
            .sectionNodeId(node.getParentNodeId())
            .prevSiblingNodeId(node.getPrevSiblingNodeId())
            .nextSiblingNodeId(node.getNextSiblingNodeId())
            .title(safeText(node.getTitle()))
            .anchorText(safeText(node.getAnchorText()))
            .sectionPath(safeText(node.getSectionPath()))
            .canonicalPath(safeText(node.getCanonicalPath()))
            .contentText(safeText(node.getContentText()))
            .itemIndex(node.getItemIndex())
            .build();
    }

    private String normalize(String text) {
        return safeText(text)
            .replaceAll("[\\s>`*#_\\-]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
