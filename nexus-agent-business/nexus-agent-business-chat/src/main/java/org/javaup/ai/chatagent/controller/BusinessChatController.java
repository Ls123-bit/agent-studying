package org.javaup.ai.chatagent.controller;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.javaup.ai.chatagent.dto.ChatRequestDto;
import org.javaup.ai.chatagent.dto.ConversationExchangeDetailQueryDto;
import org.javaup.ai.chatagent.dto.ConversationIdentityDto;
import org.javaup.ai.chatagent.dto.ConversationSessionListQueryDto;
import org.javaup.ai.chatagent.dto.RetrievalObserveQueryDto;
import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.ConversationExchangeDetailView;
import org.javaup.ai.chatagent.model.ConversationMemorySummaryView;
import org.javaup.ai.chatagent.model.ConversationSessionView;
import org.javaup.ai.chatagent.model.KnowledgeDocumentOptionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;
import org.javaup.ai.chatagent.model.StageBenchmarkView;
import org.javaup.ai.chatagent.service.BusinessChatService;
import org.javaup.ai.chatagent.vo.ConversationResetVo;
import org.javaup.ai.chatagent.vo.ConversationSessionListVo;
import org.javaup.ai.chatagent.vo.ConversationStopVo;
import org.javaup.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/*
controller————请求入口
只有一个类 BusinessChatController，负责接收前端 HTTP 请求、参数校验，然后转交给 Service 层。这一层不做任何业务逻辑。
// 控制器只做两件事：接参数、转交 Service
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/chat")
public class BusinessChatController {//Controller就做两件事：接参数，转交给service

    private final BusinessChatService businessChatService;

    /**
     * 打开一个流式会话。
     * <p>
     * 该接口返回的是 SSE 文本流，前端可以持续接收“思考中”、“正文增量”、“引用”、“推荐追问”等事件。
     * </p>
     *
     * @param dto 前端提交的聊天请求，包含问题、会话 ID、聊天模式、选中文档等信息
     * @return SSE 字符串流，内容由服务层按事件格式持续输出
     * ——————————————————————————————————————————————————
     * 为什么返回的是Flux而不是普通的JSON
     * 因为聊天回答是流式生成的，模型每产出一小段文字就立刻推给前端，
     * 用户能看到"边想边写"的效果。这里用的是 Spring WebFlux 的 Flux<String>，配合 text/event-stream 内容类型，
     * 实现了 SSE（Server-Sent Events）协议。
     *
     */
    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> stream(@Valid @RequestBody ChatRequestDto dto) {//SSE流式对话主入口，返回text/event-stream
        // defer 的作用：把真正的启动逻辑延后到"客户端真正订阅流"的那一刻
        // 避免只是创建 Flux 对象时就提前占用租约、创建轮次
        /*
        这个设计很关键——如果不用 defer，Flux 对象一创建就会执行内部逻辑，但这时候前端可能还没准备好接收数据。用了 defer 之后，只有前端真正建立 SSE 连接（订阅 Flux）时，后端才会开始抢租约、创建轮次这些操作。
         */
        return businessChatService.openConversationStream(dto);//延迟启动：Flux.defer的设计意图：Controller调用的openConversationStream()不会立刻开始干活，而是Flux.defer包了一层
    }

    @PostMapping("/document/options")
    public ApiResponse<List<KnowledgeDocumentOptionView>> documentOptions() {//获取可选的知识文档列表
        return ApiResponse.ok(businessChatService.listKnowledgeDocumentOptions());
    }

    @PostMapping("/session/stop")
    public ApiResponse<ConversationStopVo> stop(@Valid @RequestBody ConversationIdentityDto dto) {//停止当前正在生成的回答
        return ApiResponse.ok(businessChatService.stopConversation(dto.getConversationId()));
    }

    @PostMapping("/session/detail")
    public ApiResponse<ConversationSessionView> session(@Valid @RequestBody ConversationIdentityDto dto) {//获取会话详情
        return ApiResponse.ok(businessChatService.getSession(dto.getConversationId()));
    }

    @PostMapping("/exchange/detail")
    public ApiResponse<ConversationExchangeDetailView> exchange(@Valid @RequestBody ConversationExchangeDetailQueryDto dto) {//获取单次问答的详细信息
        return ApiResponse.ok(businessChatService.getExchangeDetail(dto.getConversationId(), dto.getExchangeId()));
    }

    @PostMapping("/session/list")
    public ApiResponse<ConversationSessionListVo> sessions(@RequestBody(required = false) ConversationSessionListQueryDto dto) {//分页查询会话列表
        return ApiResponse.ok(businessChatService.listSessions(dto));
    }

    @PostMapping("/session/reset")
    public ApiResponse<ConversationResetVo> reset(@Valid @RequestBody ConversationIdentityDto dto) {//重置会话（清空历史）
        return ApiResponse.ok(businessChatService.resetConversation(dto.getConversationId()));
    }

    @PostMapping("/session/summary/rebuild")
    public ApiResponse<ConversationMemorySummaryView> rebuildSummary(@Valid @RequestBody ConversationIdentityDto dto) {//重建会话记忆摘要
        return ApiResponse.ok(businessChatService.rebuildConversationSummary(dto.getConversationId()));
    }

    @PostMapping("/exchange/retrieval/results")
    public ApiResponse<List<RetrievalResultView>> retrievalResults(@Valid @RequestBody RetrievalObserveQueryDto dto) {//查看检索观测结果
        return ApiResponse.ok(businessChatService.getRetrievalResults(dto.getConversationId(), Long.parseLong(dto.getExchangeId())));
    }

    @PostMapping("/exchange/channel/executions")
    public ApiResponse<List<ChannelExecutionView>> channelExecutions(@Valid @RequestBody RetrievalObserveQueryDto dto) {//查看通道执行记录
        return ApiResponse.ok(businessChatService.getChannelExecutions(dto.getConversationId(), Long.parseLong(dto.getExchangeId())));
    }

    @PostMapping("/stage/benchmarks")
    public ApiResponse<List<StageBenchmarkView>> stageBenchmarks() {//查看各阶段性能基准
        return ApiResponse.ok(businessChatService.getStageBenchmarks());
    }
}
