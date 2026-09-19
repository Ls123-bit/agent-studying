package org.javaup.ai.manage.dto;

import lombok.Data;

@Data
public class TopicDocumentRelationSaveDto {

    private String topicCode;

    private String documentId;

    private String relationScore;

    private String relationSource;

    private String reason;

    private String operatorId;
}
