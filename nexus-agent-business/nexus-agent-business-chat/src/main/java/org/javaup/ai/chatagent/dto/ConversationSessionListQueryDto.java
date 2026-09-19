package org.javaup.ai.chatagent.dto;

import lombok.Data;


@Data
public class ConversationSessionListQueryDto {

    private String keyword;

    private String chatMode;

    private String turnStatus;

    private String pageNo;

    private String pageSize;
}
