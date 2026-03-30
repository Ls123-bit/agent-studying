package org.javaup.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.enums.ChatTurnStatus;

import java.util.Date;
import java.util.List;

/**
 * 单轮对话视图对象。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationTurnView {

    private long turnId;
    private String question;
    private String answer;
    private List<String> thinkingSteps;
    private List<SearchReference> references;
    private List<String> recommendations;
    private List<String> usedTools;
    private ChatTurnStatus status;
    private String errorMessage;
    private Long firstResponseTimeMs;
    private Long totalResponseTimeMs;
    private Date createTime;
    private Date editTime;
}
