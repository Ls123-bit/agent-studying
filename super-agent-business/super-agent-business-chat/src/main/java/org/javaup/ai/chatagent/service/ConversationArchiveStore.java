package org.javaup.ai.chatagent.service;

import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.enums.ChatQueryMode;
import org.javaup.enums.ChatTurnStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 业务对话归档存储抽象。
 *
 * <p>这里持久化的是“产品层会话数据”，也就是前端需要展示和查询的内容：
 * 会话列表、单轮问答、思考过程、引用来源、推荐问题、耗时等。
 * 这层和 Spring AI Alibaba 的 checkpoint 是互补关系：
 * checkpoint 负责 ReactAgent 的运行记忆，
 * 这里负责业务系统自己的会话视图。</p>
 */
public interface ConversationArchiveStore {

    /**
     * 创建一轮新的业务问答记录，并默认以 RUNNING 状态入库。
     */
    ConversationExchangeView startExchange(String conversationId,
                                           String question,
                                           ChatQueryMode chatMode,
                                           Long selectedDocumentId,
                                           String selectedDocumentName);

    void refreshSessionScope(String conversationId,
                             ChatQueryMode chatMode,
                             Long selectedDocumentId,
                             String selectedDocumentName);

    /**
     * 回填一轮问答的最终结果。
     *
     * <p>这里一次性写入正文、thinking、引用、推荐问题、工具轨迹、调试轨迹和耗时指标，
     * 让会话详情查询时不需要再拼多份来源。</p>
     */
    void completeExchange(String conversationId,
                          long exchangeId,
                          String answer,
                          List<String> thinkingSteps,
                          List<SearchReference> references,
                          List<String> recommendations,
                          List<String> usedTools,
                          ChatDebugTrace debugTrace,
                          ChatTurnStatus status,
                          String errorMessage,
                          Long firstResponseTimeMs,
                          Long totalResponseTimeMs);

    /**
     * 查询单个会话完整记录。
     */
    Optional<ConversationArchiveRecord> getSessionRecord(String conversationId);

    /**
     * 查询某个会话的全部轮次明细。
     *
     * <p>这层和 getSessionRecord(...) 的区别在于：
     * 这里专门服务“只需要轮次列表，不需要主会话状态”的场景，
     * 比如会话压缩、推荐问题、增量摘要等。</p>
     */
    List<ConversationExchangeView> listExchanges(String conversationId);

    /**
     * 查询某个会话在指定 exchangeId 之后新增的轮次。
     *
     * <p>生产级会话压缩不会每次都从第一轮开始全量重扫，
     * 而是会记住“摘要已经覆盖到哪一条 exchange”，
     * 然后只增量读取后续新增部分。</p>
     */
    List<ConversationExchangeView> listExchangesAfter(String conversationId, long afterExchangeId);

    /**
     * 查询某个会话最近 N 轮明细。
     *
     * <p>这个接口主要服务“短期原文窗口”类能力，
     * 例如推荐追问、最近上下文回看、长期摘要之外的细节保留。</p>
     */
    List<ConversationExchangeView> listRecentExchanges(String conversationId, int limit);

    /**
     * 查询所有会话记录，用于会话列表页。
     */
    List<ConversationArchiveRecord> listSessionRecords();

    /**
     * 分页查询会话主记录，用于列表页。
     */
    ConversationArchivePage listSessionRecordPage(int pageNo,
                                                  int pageSize,
                                                  String keyword,
                                                  ChatQueryMode chatMode,
                                                  ChatTurnStatus latestTurnStatus);

    /**
     * 删除一个会话及其轮次数据。
     */
    ConversationRemovalResult deleteSession(String conversationId);

    record ConversationArchiveRecord(
        String conversationId,
        boolean running,
        ChatQueryMode chatMode,
        Long selectedDocumentId,
        String selectedDocumentName,
        Instant createdAt,
        Instant updatedAt,
        List<ConversationExchangeView> exchanges
    ) {
    }

    record ConversationRemovalResult(
        int removedDialogueCount,
        int removedExchangeCount
    ) {
    }

    record ConversationArchivePage(
        long pageNo,
        long pageSize,
        long totalSize,
        List<ConversationArchiveRecord> records
    ) {
    }
}
