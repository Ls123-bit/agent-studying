package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.model.graph.GraphItem;
import org.javaup.ai.manage.model.graph.GraphSection;
import org.javaup.ai.manage.service.DocumentStructureGraphService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;




/*底层 graphService 的架构：Composite 委派模式
DocumentStructureGraphService 是真正跟数据库打交道的层。但它并不是只有一个实现，而是采用了 Composite 委派模式，根据当前文档是否有图数据库支持，自动选择走 Neo4j 还是 MySQL

 */
@Slf4j
@AllArgsConstructor
@Primary
@Service
public class CompositeDocumentStructureGraphService implements DocumentStructureGraphService {

    private final MysqlDocumentStructureGraphService mysqlGraphService;
    private final ObjectProvider<Neo4jDocumentStructureGraphService> neo4jGraphServiceProvider;

    @Override
    public boolean isGraphAvailable(Long documentId) {
        return delegate(documentId).isGraphAvailable(documentId);
    }

    @Override
    public GraphSection findSectionById(Long documentId, Long sectionNodeId) {
        return delegate(documentId).findSectionById(documentId, sectionNodeId);
    }


    // 组合服务本身只负责选路，不关心底层是图数据库查还是关系表查
    @Override
    public GraphSection findSectionByCode(Long documentId, String nodeCode) {
        return delegate(documentId).findSectionByCode(documentId, nodeCode);
    }

    @Override
    public GraphSection findSectionByTitle(Long documentId, String title) {
        return delegate(documentId).findSectionByTitle(documentId, title);
    }

    @Override
    public GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath) {
        return delegate(documentId).findSectionByCanonicalPath(documentId, canonicalPath);
    }

    @Override
    public GraphSection findBestSection(Long documentId, String topic, String facet) {
        return delegate(documentId).findBestSection(documentId, topic, facet);
    }

    @Override
    public List<GraphSection> listSections(Long documentId) {
        return delegate(documentId).listSections(documentId);
    }

    @Override
    public List<GraphSection> listChildren(Long documentId, Long sectionNodeId) {
        return delegate(documentId).listChildren(documentId, sectionNodeId);
    }

    @Override
    public GraphSection parentSection(Long documentId, Long sectionNodeId) {
        return delegate(documentId).parentSection(documentId, sectionNodeId);
    }

    @Override
    public GraphSection previousSibling(Long documentId, Long sectionNodeId) {
        return delegate(documentId).previousSibling(documentId, sectionNodeId);
    }

    @Override
    public GraphSection nextSibling(Long documentId, Long sectionNodeId) {
        return delegate(documentId).nextSibling(documentId, sectionNodeId);
    }

    @Override
    public GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) {
        return delegate(documentId).findItemByIndex(documentId, sectionNodeId, itemIndex);
    }

    @Override
    public List<GraphItem> listItems(Long documentId, Long sectionNodeId) {
        return delegate(documentId).listItems(documentId, sectionNodeId);
    }

    @Override
    public List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword) {
        return delegate(documentId).searchItemsInSection(documentId, sectionNodeId, keyword);
    }


    /*
    delegate 的选路逻辑很简单：
    Neo4j Bean 存在 且 当前文档的图数据已同步 → 走 Neo4j
    否则 → 回退到 MySQL
    这样设计的好处是：没有部署 Neo4j 的环境直接用 MySQL 兜底，调用方完全不感知底层差异。
     */
    private DocumentStructureGraphService delegate(Long documentId) {
        // 尝试从 Spring 容器获取 Neo4j 实现（未引入 Neo4j 依赖时该 Bean 不存在，返回 null）    // ObjectProvider 允许 Neo4j Bean 不存在时也能启动，避免强依赖图数据库。
        Neo4jDocumentStructureGraphService neo4jGraphService = neo4jGraphServiceProvider.getIfAvailable();
        // Neo4j Bean 存在，且当前文档的图数据已同步到 Neo4j，才走 Neo4j 路径    // 只有 Neo4j Bean 存在，并且当前文档已经投影出图数据，才优先使用 Neo4j 查询。
        if (neo4jGraphService != null && neo4jGraphService.isGraphAvailable(documentId)) {
            log.info("结构图服务选择 Neo4j: documentId={}", documentId);
            return neo4jGraphService;
        }
        // 否则回退到 MySQL 实现，neo4jBeanAvailable 用于区分"Bean 不存在"和"图数据未同步"两种情况   // 只有 Neo4j Bean 存在，并且当前文档已经投影出图数据，才优先使用 Neo4j 查询。
        log.info("结构图服务选择 MySQL fallback: documentId={}, neo4jBeanAvailable={}", documentId, neo4jGraphService != null);
        return mysqlGraphService;
    }
}
