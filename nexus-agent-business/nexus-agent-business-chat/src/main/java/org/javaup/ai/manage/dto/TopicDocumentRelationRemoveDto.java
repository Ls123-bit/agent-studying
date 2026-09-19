package org.javaup.ai.manage.dto;

import lombok.Data;

@Data
public class TopicDocumentRelationRemoveDto {

    private String topicCode;

    private String documentId;

    private String operatorId;
}
