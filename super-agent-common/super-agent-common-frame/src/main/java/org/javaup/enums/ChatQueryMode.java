package org.javaup.enums;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 枚举定义
 * @author: 阿星不是程序员
 **/
/**
 * 聊天请求的显式提问模式。
 *
 * <p>前端在发起一轮对话时，会用这个枚举明确告诉后端：
 * 本轮到底要走哪一种产品能力。</p>
 *
 * <p>把模式做成显式字段有两个教学上的好处：</p>
 * <p>1. 学习者可以清楚观察“文档问答”和“开放式提问”各自对应的链路。</p>
 * <p>2. 整条链路没有隐藏分流，接口契约会非常清楚。</p>
 */
public enum ChatQueryMode {

    /**
     * 当前问题只允许围绕“当前选中的那一份文档”做 RAG 问答。
     */
    DOCUMENT(1, "当前文档问答"),

    /**
     * 当前问题由系统自动决定优先查哪些文档，再进入文档问答链。
     */
    AUTO_DOCUMENT(3, "自动知识问答"),

    /**
     * 当前问题不使用业务知识库文档，直接走开放式 Agent 能力。
     */
    OPEN_CHAT(2, "开放式提问");

    private final int code;
    private final String label;

    ChatQueryMode(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static ChatQueryMode fromCode(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("提问模式 code 不能为空");
        }
        for (ChatQueryMode mode : values()) {
            if (mode.code == code) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知的提问模式 code: " + code);
    }
}
