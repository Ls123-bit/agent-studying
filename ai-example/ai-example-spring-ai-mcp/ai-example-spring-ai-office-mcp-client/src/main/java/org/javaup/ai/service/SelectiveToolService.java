package org.javaup.ai.service;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SelectiveToolService {

    private final List<McpSyncClient> mcpClients;
    private final DeepSeekChatModel chatModel;
    
    public SelectiveToolService(List<McpSyncClient> mcpClients,
                                DeepSeekChatModel chatModel) {
        this.mcpClients = mcpClients;
        this.chatModel = chatModel;
    }
    
    /**
     * 创建只包含指定Server工具的ChatClient
     */
    public ChatClient createClientWithServers(String... serverNames) {
        List<McpSyncClient> selectedClients = mcpClients.stream()
                .filter(client -> {
                    String name = client.getClientInfo().name();
                    for (String serverName : serverNames) {
                        if (name.contains(serverName)) {
                            return true;
                        }
                    }
                    return false;
                })
                .toList();
        
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(selectedClients)
                .build();
        
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(provider.getToolCallbacks())
                .build();
    }
    
    /**
     * HR相关问题专用的ChatClient
     */
    public String chatWithHR(String message) {
        ChatClient hrClient = createClientWithServers("hr");
        return hrClient.prompt()
                .user(message)
                .call()
                .content();
    }
}