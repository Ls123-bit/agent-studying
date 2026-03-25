package org.javaup.ai.chatagent.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.javaup.ai.chatagent.model.ActionResponse;
import org.javaup.ai.chatagent.model.ChatRequest;
import org.javaup.ai.chatagent.model.ConversationSessionView;
import org.javaup.ai.chatagent.model.ConversationTurnView;
import org.javaup.ai.chatagent.service.BusinessChatService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class BusinessChatController {

    private final BusinessChatService businessChatService;

    public BusinessChatController(BusinessChatService businessChatService) {
        this.businessChatService = businessChatService;
    }

    /**
     * SSE 流式对话入口。
     */
    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> stream(@Valid @RequestBody ChatRequest request) {
        return businessChatService.streamChat(request);
    }

    /**
     * 非流式对话入口。
     */
    @PostMapping
    public ConversationTurnView chat(@Valid @RequestBody ChatRequest request) {
        return businessChatService.chat(request);
    }

    /**
     * 主动停止某个会话当前正在生成的回答。
     */
    @PostMapping("/stop/{conversationId}")
    public ActionResponse stop(@PathVariable String conversationId) {
        return businessChatService.stopConversation(conversationId);
    }

    /**
     * 查看单个会话详情。
     */
    @GetMapping("/sessions/{conversationId}")
    public ConversationSessionView session(@PathVariable String conversationId) {
        return businessChatService.getSession(conversationId);
    }

    /**
     * 查看所有会话。
     */
    @GetMapping("/sessions")
    public List<ConversationSessionView> sessions() {
        return businessChatService.listSessions();
    }

    /**
     * 重置会话，删除业务会话记录和 Agent checkpoint。
     */
    @DeleteMapping("/sessions/{conversationId}")
    public ActionResponse reset(@PathVariable String conversationId) {
        return businessChatService.resetConversation(conversationId);
    }
}
