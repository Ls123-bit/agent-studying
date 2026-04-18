package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 文档导航索引服务。
 *
 * <p>职责分两部分：</p>
 * <p>1. 把结构节点同步到 ES 导航索引。</p>
 * <p>2. 在导航层基于中文分词结果做章节召回。</p>
 */
public interface DocumentNavigationIndexService {

    /**
     * 重建某个文档的导航索引。
     */
    void reindexDocumentNodes(Long documentId, Long parseTaskId, List<SuperAgentDocumentStructureNode> nodes);

    /**
     * 删除某个文档的导航索引数据。
     */
    void deleteByDocumentId(Long documentId);

    /**
     * 搜索最相关的章节候选。
     */
    List<NavigationSectionHit> searchSections(Long documentId,
                                              String topic,
                                              String facet,
                                              String informationNeed,
                                              String question,
                                              int size);

    /**
     * 导航索引命中的章节候选。
     */
    record NavigationSectionHit(
        Long nodeId,
        String nodeCode,
        String title,
        String sectionPath,
        String canonicalPath,
        double score
    ) {
    }
}
