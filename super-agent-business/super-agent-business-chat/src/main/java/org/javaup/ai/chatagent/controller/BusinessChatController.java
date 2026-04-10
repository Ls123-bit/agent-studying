package org.javaup.ai.chatagent.controller;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.javaup.ai.chatagent.dto.ConversationIdentityDto;
import org.javaup.ai.chatagent.dto.ConversationSessionListQueryDto;
import org.javaup.ai.chatagent.dto.ChatRequestDto;
import org.javaup.ai.chatagent.model.ConversationMemorySummaryView;
import org.javaup.ai.chatagent.model.ConversationSessionView;
import org.javaup.ai.chatagent.model.KnowledgeDocumentOptionView;
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

@AllArgsConstructor
@RestController
@RequestMapping("/api/chat")
public class BusinessChatController {

    private final BusinessChatService businessChatService;

    /**
     * SSE 流式对话入口。
     */
    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> stream(@Valid @RequestBody ChatRequestDto dto) {
        return businessChatService.openConversationStream(dto);
    }

    @PostMapping("/document/options")
    public ApiResponse<java.util.List<KnowledgeDocumentOptionView>> documentOptions() {
        return ApiResponse.ok(businessChatService.listKnowledgeDocumentOptions());
    }

    /**
     * 主动停止某个会话当前正在生成的回答。
     *
     */
    @PostMapping("/session/stop")
    public ApiResponse<ConversationStopVo> stop(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.stopConversation(dto.getConversationId()));
    }

    /**
     * 查看单个会话详情。
     *
     */
    @PostMapping("/session/detail")
    public ApiResponse<ConversationSessionView> session(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.getSession(dto.getConversationId()));
    }

    /**
     * 查看所有会话。
     *
     * <p>分页参数放在 body DTO 中，
     * 当前统一使用字符串传递 pageNo/pageSize，降低前端数值精度处理风险。</p>
     */
    @PostMapping("/session/list")
    public ApiResponse<ConversationSessionListVo> sessions(@RequestBody(required = false) ConversationSessionListQueryDto dto) {
        return ApiResponse.ok(businessChatService.listSessions(dto));
    }

    /**
     * 重置会话，删除业务会话记录和 Agent checkpoint。
     *
     * <p>对前端而言这是“删除会话”，
     * 对后端而言实际执行的是“停止执行中任务 + 清理业务归档 + 清理 Graph checkpoint”三段动作。</p>
     */
    @PostMapping("/session/reset")
    public ApiResponse<ConversationResetVo> reset(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.resetConversation(dto.getConversationId()));
    }

    /**
     * 手动重建某条会话的长期摘要。
     *
     * <p>这个接口不要求用户再发起一轮新对话，
     * 更适合后台观测页、教学演示和问题排查场景。</p>
     */
    @PostMapping("/session/summary/rebuild")
    public ApiResponse<ConversationMemorySummaryView> rebuildSummary(@Valid @RequestBody ConversationIdentityDto dto) {
        return ApiResponse.ok(businessChatService.rebuildConversationSummary(dto.getConversationId()));
    }
}
