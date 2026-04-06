package org.javaup.ai.chatagent.model;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话视图对象。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSessionView {

    private String conversationId;
    private boolean running;
    private int checkpointCount;
    private int messageCount;
    private String latestUserMessage;
    private String latestAssistantMessage;
    private String selectedDocumentId;
    private String selectedDocumentName;
    private Instant createdAt;
    private Instant updatedAt;
    private List<ConversationExchangeView> exchanges;
    private ConversationMemorySummaryView memorySummary;
}
