package org.javaup.enums;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 枚举定义
 * @author: 阿星不是程序员
 **/
/**
 * 文档管理业务错误码。
 *
 * <p>这一组错误码专门给 RAG 文档接入和索引构建链路使用，
 * 便于前后端在“文档状态不合法”“策略未确认”“任务重复触发”等场景下统一收口。</p>
 */
public enum DocumentManageCode {
    /**
     * 文档不存在。
     */
    DOCUMENT_NOT_FOUND(20001, "文档不存在"),
    /**
     * 文件类型暂不支持。
     */
    UNSUPPORTED_FILE_TYPE(20002, "当前文件类型暂不支持"),
    /**
     * 文件内容为空。
     */
    EMPTY_FILE_CONTENT(20003, "文件内容不能为空"),
    /**
     * 文档当前状态不允许执行该操作。
     */
    DOCUMENT_STATUS_INVALID(20004, "文档当前状态不允许执行该操作"),
    /**
     * 策略方案不存在。
     */
    STRATEGY_PLAN_NOT_FOUND(20005, "策略方案不存在"),
    /**
     * 当前没有可执行的策略步骤。
     */
    STRATEGY_STEP_EMPTY(20006, "当前没有可执行的策略步骤"),
    /**
     * 索引任务正在执行中。
     */
    INDEX_TASK_RUNNING(20007, "当前文档已有索引任务正在执行"),
    /**
     * Kafka 消息发送失败。
     */
    KAFKA_SEND_FAILED(20008, "异步任务投递失败"),
    /**
     * 文件解析失败。
     */
    DOCUMENT_PARSE_FAILED(20009, "文件解析失败"),
    /**
     * MinIO 文件存储失败。
     */
    DOCUMENT_STORAGE_FAILED(20010, "文件存储失败"),
    /**
     * 向量化处理失败。
     */
    DOCUMENT_VECTOR_FAILED(20011, "向量化处理失败"),
    /**
     * 文档当前没有可用的已构建索引。
     */
    DOCUMENT_INDEX_UNAVAILABLE(20012, "文档当前没有可用索引"),
    /**
     * 当前问题未检索到可用资料。
     */
    DOCUMENT_RETRIEVE_EMPTY(20013, "未检索到可用资料");

    private final Integer code;

    private final String msg;

    DocumentManageCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }

    public String getMsg() {
        return msg == null ? "" : msg;
    }

    /**
     * 根据错误码获取错误文案。
     */
    public static String getMsg(Integer code) {
        for (DocumentManageCode item : DocumentManageCode.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item.msg;
            }
        }
        return "";
    }

    /**
     * 根据错误码获取枚举对象。
     */
    public static DocumentManageCode getRc(Integer code) {
        for (DocumentManageCode item : DocumentManageCode.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}
