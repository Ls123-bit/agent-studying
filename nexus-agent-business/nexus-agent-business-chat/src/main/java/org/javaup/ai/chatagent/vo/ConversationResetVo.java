package org.javaup.ai.chatagent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationResetVo {

    private String conversationId;

    private boolean stoppedRunningTask;

    private int removedDialogueCount;

    private int removedExchangeCount;

    private int removedCheckpointCount;

    private String message;
}
