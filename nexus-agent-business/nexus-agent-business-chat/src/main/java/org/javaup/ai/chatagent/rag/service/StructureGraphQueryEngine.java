package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.model.graph.GraphItem;
import org.javaup.ai.manage.model.graph.GraphItemWithContext;
import org.javaup.ai.manage.model.graph.GraphQueryResult;
import org.javaup.ai.manage.model.graph.GraphSection;
import org.javaup.ai.manage.model.graph.GraphSectionWithChildren;
import org.javaup.ai.manage.model.graph.GraphSectionWithSiblings;
import org.javaup.ai.manage.service.DocumentStructureGraphService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/*
这是图查询的高层封装，提供面向业务的查询方法。
底层图服务：
StructureGraphQueryEngine 内部委托给 DocumentStructureGraphService 做实际的图操作：
____________________________________________________
StructureGraphQueryEngine 是一个服务层组件，它封装了 DocumentStructureGraphService（底层的图数据访问层），对外提供更高层次的查询能力。你可以把它理解为一个"查询门面"：
底层的 DocumentStructureGraphService 提供的是原子操作（查单个节点、查父节点、查兄弟...）
StructureGraphQueryEngine 把这些原子操作组合起来，返回业务需要的完整结构
在 GRAPH_ONLY 模式下，执行器只用到了两个方法：findSectionWithSiblings 和 findSectionWithChildren。
 */
@Service
@Slf4j
public class StructureGraphQueryEngine {

    private final DocumentStructureGraphService graphService;

    public StructureGraphQueryEngine(DocumentStructureGraphService graphService) {
        this.graphService = graphService;
    }


    /*还有一个重载方法：按主题查子章节
    除了按 sectionNodeId 查询，StructureGraphQueryEngine 还提供了一个按主题文本查询的重载版本：
    这个方法先通过 findBestSection 做一次模糊匹配（根据主题文本找到最相关的章节），然后复用 nodeId 版本的查询逻辑。
    不过在 GRAPH_ONLY 执行器中，因为路由阶段已经解析出了具体的 sectionNodeId，所以直接走的是 nodeId 版本，不需要再做模糊匹配。
     */
    //按话题名查找章节及其子节点
    public GraphSectionWithChildren findSectionWithChildren(Long documentId, String topic) {
        // 先按章节主题在文档里定位最匹配的章节节点。
        GraphSection section = graphService.findBestSection(documentId, topic, "");
        // 找到后再复用 nodeId 版本，避免两套查询逻辑分叉。
        return findSectionWithChildren(documentId, section == null ? null : section.getNodeId());
    }



    /*
    查询子章节
    当用户问"这章包含哪些小节"、"3.1 下面有什么"这类问题时，执行器走这条路径：
     */
    //按节点ID查找章节及其子节点
    public GraphSectionWithChildren findSectionWithChildren(Long documentId, Long sectionNodeId) {
        // 先把目标章节查出来，后续才能沿着它往下拿子章节。
        GraphSection section = graphService.findSectionById(documentId, sectionNodeId);
        // 如果章节存在，就列出它的直接子章节；否则返回空列表。
        List<GraphSection> children = section == null ? List.of() : graphService.listChildren(documentId, section.getNodeId());
        // 打印查询日志，方便观察具体命中了哪个章节以及返回了多少个子章节。
        log.info("结构图查询子章节: documentId={}, sectionNodeId={}, targetSection='{}', childCount={}",
            documentId,
            sectionNodeId,
            section == null ? "" : section.displayTitle(),
            children.size());
        // 把章节和子章节组装成统一结构，交给上层渲染器使用。
        return GraphSectionWithChildren.builder()
            .section(section)
            .children(children)
            .build();
    }




    /*查询相邻章节：
    当用户问"上一节是什么"、"这章属于哪个章节"这类问题时，执行器会走这条路径：
     */
    //查找章节的兄弟节点（上一章/下一章）
    public GraphSectionWithSiblings findSectionWithSiblings(Long documentId, Long sectionNodeId) {
        // 先查出当前章节本身，后续的父章节和兄弟章节都基于它展开。
        GraphSection section = graphService.findSectionById(documentId, sectionNodeId);
        // 当前章节存在时，向上找到父章节。
        GraphSection parent = section == null ? null : graphService.parentSection(documentId, section.getNodeId());
        // 当前章节存在时，向前找到前一个兄弟章节。
        GraphSection previousSibling = section == null ? null : graphService.previousSibling(documentId, section.getNodeId());
        // 当前章节存在时，向后找到后一个兄弟章节。
        GraphSection nextSibling = section == null ? null : graphService.nextSibling(documentId, section.getNodeId());
        // 打印相邻章节查询日志，便于排查上下文关系是否正确。
        log.info("结构图查询相邻章节: documentId={}, sectionNodeId={}, targetSection='{}', parent='{}', previous='{}', next='{}'",
            documentId,
            sectionNodeId,
            section == null ? "" : section.displayTitle(),
            parent == null ? "" : parent.displayTitle(),
            previousSibling == null ? "" : previousSibling.displayTitle(),
            nextSibling == null ? "" : nextSibling.displayTitle());
        // 把相邻章节关系封装成统一结构返回。
        return GraphSectionWithSiblings.builder()
            .section(section)
            .parent(parent)
            .previousSibling(previousSibling)
            .nextSibling(nextSibling)
            .build();
    }
//按话题名+序号查找具体条目
    public GraphItemWithContext findItemInSection(Long documentId, String sectionTopic, Integer itemIndex) {
        GraphSection section = graphService.findBestSection(documentId, sectionTopic, "");
        return findItemInSection(documentId, section == null ? null : section.getNodeId(), itemIndex);
    }
//按节点ID+序号查找具体条目
    public GraphItemWithContext findItemInSection(Long documentId, Long sectionNodeId, Integer itemIndex) {
        GraphSection section = graphService.findSectionById(documentId, sectionNodeId);
        GraphItem item = findItemInSectionTree(documentId, sectionNodeId, itemIndex);
        List<GraphItem> siblingItems = sectionNodeId == null ? List.of() : listItemsInSectionTree(documentId, sectionNodeId);
        log.info("结构图查询编号项: documentId={}, sectionNodeId={}, itemIndex={}, targetSection='{}', itemFound={}, siblingItemCount={}",
            documentId,
            sectionNodeId,
            itemIndex,
            section == null ? "" : section.displayTitle(),
            item != null,
            siblingItems.size());
        return GraphItemWithContext.builder()
            .section(section)
            .item(item)
            .siblingItems(siblingItems)
            .build();
    }
//在章节内按关键词搜索条目
    public List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword) {
        List<GraphItem> items = searchItemsInSectionTree(documentId, sectionNodeId, StrUtil.blankToDefault(keyword, ""));
        log.info("结构图搜索章节内 item: documentId={}, sectionNodeId={}, keyword='{}', matchedCount={}",
            documentId,
            sectionNodeId,
            StrUtil.blankToDefault(keyword, ""),
            items.size());
        return items;
    }
//构建完整的图查询结果（供执行器使用）
    public GraphQueryResult buildGraphResult(Long documentId,
                                             Long targetSectionNodeId,
                                             Integer targetItemIndex,
                                             String itemKeyword) {
        GraphSection section = graphService.findSectionById(documentId, targetSectionNodeId);
        List<GraphSection> children = section == null ? List.of() : graphService.listChildren(documentId, section.getNodeId());
        List<GraphItem> allItems = section == null ? List.of() : listItemsInSectionTree(documentId, section.getNodeId());
        GraphItem targetItem = targetItemIndex == null || section == null
            ? null
            : findItemInSectionTree(documentId, section.getNodeId(), targetItemIndex);
        List<GraphItem> matchedItems = StrUtil.isNotBlank(itemKeyword) && section != null
            ? searchItemsInSection(documentId, section.getNodeId(), itemKeyword)
            : List.of();
        GraphSection resolvedSection = section;
        if (targetItem != null && targetItem.getSectionNodeId() != null) {
            GraphSection itemOwnerSection = graphService.findSectionById(documentId, targetItem.getSectionNodeId());
            if (itemOwnerSection != null) {
                resolvedSection = itemOwnerSection;
            }
        }
        else if (matchedItems.size() == 1 && matchedItems.get(0).getSectionNodeId() != null) {
            GraphSection itemOwnerSection = graphService.findSectionById(documentId, matchedItems.get(0).getSectionNodeId());
            if (itemOwnerSection != null) {
                resolvedSection = itemOwnerSection;
                targetItem = matchedItems.get(0);
            }
        }
        GraphQueryResult.GraphQueryResultBuilder builder = GraphQueryResult.builder()
            .targetSection(resolvedSection)
            .children(children)
            .allItems(allItems)
            .targetItem(targetItem)
            .matchedItems(matchedItems);
        if (resolvedSection != null) {
            builder.parentSection(graphService.parentSection(documentId, resolvedSection.getNodeId()))
                .previousSibling(graphService.previousSibling(documentId, resolvedSection.getNodeId()))
                .nextSibling(graphService.nextSibling(documentId, resolvedSection.getNodeId()));
        }
        GraphQueryResult result = builder.build();
        log.info("结构图结果汇总: documentId={}, targetSectionNodeId={}, targetItemIndex={}, itemKeyword='{}', targetSection='{}', targetItemFound={}, childCount={}, allItemCount={}, matchedItemCount={}",
            documentId,
            targetSectionNodeId,
            targetItemIndex,
            StrUtil.blankToDefault(itemKeyword, ""),
            result.getTargetSection() == null ? "" : result.getTargetSection().displayTitle(),
            result.getTargetItem() != null,
            result.getChildren() == null ? 0 : result.getChildren().size(),
            result.getAllItems() == null ? 0 : result.getAllItems().size(),
            result.getMatchedItems() == null ? 0 : result.getMatchedItems().size());
        return result;
    }

    private GraphItem findItemInSectionTree(Long documentId, Long sectionNodeId, Integer itemIndex) {
        if (documentId == null || sectionNodeId == null || itemIndex == null) {
            return null;
        }
        GraphItem item = graphService.findItemByIndex(documentId, sectionNodeId, itemIndex);
        if (item != null) {
            return item;
        }
        for (GraphSection child : graphService.listChildren(documentId, sectionNodeId)) {
            GraphItem descendant = findItemInSectionTree(documentId, child.getNodeId(), itemIndex);
            if (descendant != null) {
                return descendant;
            }
        }
        return null;
    }

    private List<GraphItem> listItemsInSectionTree(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        List<GraphItem> items = new ArrayList<>(graphService.listItems(documentId, sectionNodeId));
        for (GraphSection child : graphService.listChildren(documentId, sectionNodeId)) {
            items.addAll(listItemsInSectionTree(documentId, child.getNodeId()));
        }
        return items.stream()
            .sorted(Comparator.comparing(GraphItem::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .toList();
    }

    private List<GraphItem> searchItemsInSectionTree(Long documentId, Long sectionNodeId, String keyword) {
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        List<GraphItem> items = new ArrayList<>(graphService.searchItemsInSection(documentId, sectionNodeId, keyword));
        for (GraphSection child : graphService.listChildren(documentId, sectionNodeId)) {
            items.addAll(searchItemsInSectionTree(documentId, child.getNodeId(), keyword));
        }
        return items.stream()
            .distinct()
            .sorted(Comparator.comparing(GraphItem::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .toList();
    }
}
