package org.javaup.ai.chatagent.dto;

import lombok.Data;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 数据传输对象
 * @author: 阿星不是程序员
 **/
/**
 * 会话列表查询入参。
 *
 * <p>分页参数统一使用字符串，
 * 避免前端在 JSON 序列化和大整数处理时引入额外精度风险。</p>
 */
@Data
public class ConversationSessionListQueryDto {

    /**
     * 关键词，字符串类型。
     */
    private String keyword;

    /**
     * 提问模式，字符串类型。
     *
     * <p>约定值：DOCUMENT / OPEN_CHAT。</p>
     */
    private String chatMode;

    /**
     * 最近状态，字符串类型。
     *
     * <p>约定值：RUNNING / COMPLETED / FAILED / STOPPED。</p>
     */
    private String turnStatus;

    /**
     * 页码，字符串类型。
     */
    private String pageNo;

    /**
     * 每页条数，字符串类型。
     */
    private String pageSize;
}
