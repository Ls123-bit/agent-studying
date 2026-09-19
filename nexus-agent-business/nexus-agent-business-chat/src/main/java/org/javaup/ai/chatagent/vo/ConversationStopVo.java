package org.javaup.ai.chatagent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationStopVo {

    private String conversationId;

    private boolean stopped;

    private String message;
}
