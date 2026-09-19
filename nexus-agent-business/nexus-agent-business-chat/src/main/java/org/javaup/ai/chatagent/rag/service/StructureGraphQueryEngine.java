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
        // 先根据路由给出的章节节点 ID 查询目标章节，这是后续所有取证动作的根节点。
        GraphSection section = graphService.findSectionById(documentId, targetSectionNodeId);
        // 如果目标章节存在，查出它的直接子章节；如果不存在，子章节只能是空列表。
        List<GraphSection> children = section == null ? List.of() : graphService.listChildren(documentId, section.getNodeId());
        // 如果目标章节存在，递归列出章节树下所有条目，方便后续 trace 或上下文补充。
        List<GraphItem> allItems = section == null ? List.of() : listItemsInSectionTree(documentId, section.getNodeId());
        // 如果路由识别到了具体编号，就在目标章节树下递归查找该编号项。
        GraphItem targetItem = targetItemIndex == null || section == null
            ? null
            : findItemInSectionTree(documentId, section.getNodeId(), targetItemIndex);
        // 如果执行器抽取到了关键词，就在目标章节树下递归搜索相关条目。
        List<GraphItem> matchedItems = StrUtil.isNotBlank(itemKeyword) && section != null
            ? searchItemsInSection(documentId, section.getNodeId(), itemKeyword)
            : List.of();
        // 默认认为最终回答章节就是路由定位到的目标章节。
        GraphSection resolvedSection = section;
        // 如果精确编号项命中，并且该条目实际挂在某个子章节下，就把最终章节修正为条目所属章节。
        if (targetItem != null && targetItem.getSectionNodeId() != null) {
            // 查询条目真实所属章节，避免答案里出现父章节标题但正文来自子章节的错位。
            GraphSection itemOwnerSection = graphService.findSectionById(documentId, targetItem.getSectionNodeId());
            // 只有真实所属章节存在时才修正，避免因为脏数据把目标章节改成空。
            if (itemOwnerSection != null) {
                resolvedSection = itemOwnerSection;
            }
        }
        // 如果没有精确编号项，但关键词只命中了一个条目，也可以把这个条目视为目标条目。
        else if (matchedItems.size() == 1 && matchedItems.get(0).getSectionNodeId() != null) {
            // 查询唯一命中条目的所属章节，用于收窄最终回答范围。
            GraphSection itemOwnerSection = graphService.findSectionById(documentId, matchedItems.get(0).getSectionNodeId());
            // 所属章节存在时，同时修正目标章节和目标条目。
            if (itemOwnerSection != null) {
                resolvedSection = itemOwnerSection;
                targetItem = matchedItems.get(0);
            }
        }
        // 先构造基础结果，包含目标章节、直接子章节、所有条目、目标条目和关键词命中条目。
        GraphQueryResult.GraphQueryResultBuilder builder = GraphQueryResult.builder()
            .targetSection(resolvedSection)
            .children(children)
            .allItems(allItems)
            .targetItem(targetItem)
            .matchedItems(matchedItems);
        // 如果最终章节存在，再补齐它的父章节、前一个兄弟章节和后一个兄弟章节。
        if (resolvedSection != null) {
            // 这些相邻关系主要用于 trace 和可能的结构上下文展示。
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
        // 构建不可变结果对象，交给执行器做证据校验和答案渲染。
        return result;
    }


    /*
    按编号递归查找目标条目
    这个方法的职责是：在章节树中按深度优先的方式查找指定编号的条目。找到第一个匹配的就立即返回，不会继续扫描。
     */
    private GraphItem findItemInSectionTree(Long documentId, Long sectionNodeId, Integer itemIndex) {
        // 递归入口做参数保护，任意核心参数为空都无法定位具体编号项。
        if (documentId == null || sectionNodeId == null || itemIndex == null) {
            return null;
        }
        // 先在当前章节下查找目标编号项，这是递归的当前层命中判断。
        GraphItem item = graphService.findItemByIndex(documentId, sectionNodeId, itemIndex);
        // 当前章节已经找到目标编号项时，直接返回，避免继续扫描子章节。
        if (item != null) {
            return item;
        }
        // 当前章节未命中时，继续遍历每一个直接子章节。
        for (GraphSection child : graphService.listChildren(documentId, sectionNodeId)) {
            // 递归进入子章节树，查找同一个 itemIndex。
            GraphItem descendant = findItemInSectionTree(documentId, child.getNodeId(), itemIndex);
            // 子树中一旦命中目标条目，就立即向上返回。
            if (descendant != null) {
                return descendant;
            }
        }
        // 当前章节及所有子章节都没有命中该编号项。
        return null;
    }


    /*listItemsInSectionTree：递归收集章节树下所有条目
    这个方法的职责是：给定一个章节节点，把它自身以及所有后代章节下挂载的条目全部收集起来，按原文顺序排好。

     */
    private List<GraphItem> listItemsInSectionTree(Long documentId, Long sectionNodeId) {
        // 没有文档 ID 或章节节点 ID 时，无法展开章节树，直接返回空列表。
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        // 先收集当前章节直接挂载的条目。
        List<GraphItem> items = new ArrayList<>(graphService.listItems(documentId, sectionNodeId));
        // 再遍历直接子章节，把每棵子树下的条目递归追加进来。
        for (GraphSection child : graphService.listChildren(documentId, sectionNodeId)) {
            // 递归收集子章节及其后代章节中的条目。
            items.addAll(listItemsInSectionTree(documentId, child.getNodeId()));
        }
        // 最终按 nodeNo 排序，让跨层级收集到的条目尽量保持原文顺序。
        return items.stream()
            .sorted(Comparator.comparing(GraphItem::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .toList();
    }


    /*searchItemsInSectionTree：按关键词递归搜索条目
    这个方法的职责是：在章节树中递归搜索所有包含指定关键词的条目，去重后按原文顺序返回。

     */
    private List<GraphItem> searchItemsInSectionTree(Long documentId, Long sectionNodeId, String keyword) {
        // 参数不完整时，不做任何关键词搜索。
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        // 先搜索当前章节直接挂载的条目。
        List<GraphItem> items = new ArrayList<>(graphService.searchItemsInSection(documentId, sectionNodeId, keyword));
        // 再遍历子章节，递归搜索整棵章节树。
        for (GraphSection child : graphService.listChildren(documentId, sectionNodeId)) {
            // 子树命中的条目追加到当前结果集中。
            items.addAll(searchItemsInSectionTree(documentId, child.getNodeId(), keyword));
        }
        // 去重后按 nodeNo 排序，避免同一条目被多路径重复加入，同时保持展示顺序稳定。
        return items.stream()
            .distinct()
            .sorted(Comparator.comparing(GraphItem::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .toList();
    }
}
