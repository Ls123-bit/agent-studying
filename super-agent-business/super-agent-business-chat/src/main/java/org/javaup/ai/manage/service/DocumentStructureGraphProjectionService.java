package org.javaup.ai.manage.service;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 文档结构图投影服务。
 *
 * <p>负责把 MySQL 结构节点同步到图数据库实现中。</p>
 */
public interface DocumentStructureGraphProjectionService {

    /**
     * 是否启用了图投影能力。
     */
    boolean enabled();

    /**
     * 把指定文档的结构节点投影到图存储。
     */
    void projectToGraph(Long documentId, Long parseTaskId);

    /**
     * 删除指定文档在图存储中的投影。
     */
    void deleteByDocumentId(Long documentId);

    /**
     * 返回图投影状态描述。
     */
    default List<String> statusNotes() {
        return List.of();
    }
}
