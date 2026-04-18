package org.javaup.ai.manage.dto;

import lombok.Data;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 数据传输对象
 * @author: 阿星不是程序员
 **/
/**
 * 分页查询文档列表入参。
 *
 * <p>后台管理台需要先拿到当前文档池的概览，
 * 再对单个文档执行“查看策略 / 确认策略 / 构建索引 / 查看日志”等动作。</p>
 *
 * <p>这里先保持入参轻量，只开放分页和关键字查询，
 * 避免第一期管理台被大量筛选项拖复杂。</p>
 */
@Data
public class DocumentPageQueryDto {

    /**
     * 页码，从 1 开始。
     */
    private Integer pageNo;

    /**
     * 每页条数。
     */
    private Integer pageSize;

    /**
     * 搜索关键字，支持按文档名称或原始文件名模糊查询。
     */
    private String keyword;
}
