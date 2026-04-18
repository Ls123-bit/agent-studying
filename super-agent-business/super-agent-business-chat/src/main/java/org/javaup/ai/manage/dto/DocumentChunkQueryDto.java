package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 数据传输对象
 * @author: 阿星不是程序员
 **/
/**
 * 查询文档 chunk 列表入参。
 */
@Data
public class DocumentChunkQueryDto {

    /**
     * 文档 id。
     */
    @NotNull(message = "文档id不能为空")
    private Long documentId;

    /**
     * 构建任务 id。
     *
     * <p>可选字段。
     * 不传时由后端自动选择当前最适合展示的 chunk 任务：</p>
     * <p>1. 优先使用 lastIndexTaskId。</p>
     * <p>2. 如果还没有成功构建过，则退到最近一次构建任务。</p>
     */
    private Long taskId;

    /**
     * 页码，从 1 开始。
     */
    private Integer pageNo;

    /**
     * 每页条数。
     */
    private Integer pageSize;
}
