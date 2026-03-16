package org.javaup.ai.service;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {

    private final DeepSeekChatModel chatModel;
    private final SyncMcpToolCallbackProvider toolCallbackProvider;
    
    private ChatClient chatClient;
    
    public AssistantService(DeepSeekChatModel chatModel, 
                           SyncMcpToolCallbackProvider toolCallbackProvider) {
        this.chatModel = chatModel;
        this.toolCallbackProvider = toolCallbackProvider;
    }
    
    @PostConstruct
    public void init() {
        // 获取所有MCP工具
        ToolCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();
        
        // 构建ChatClient，注入MCP工具
        this.chatClient = ChatClient.builder(chatModel)
                .defaultToolCallbacks(toolCallbacks)
                .build();
    }
    
    /**
     * 智能助手对话
     */
    public String chat(String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
    }
}