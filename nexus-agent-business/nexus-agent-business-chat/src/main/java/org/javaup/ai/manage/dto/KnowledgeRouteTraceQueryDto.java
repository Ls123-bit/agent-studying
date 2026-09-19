package org.javaup.ai.manage.dto;

import lombok.Data;

@Data
public class KnowledgeRouteTraceQueryDto {

    private String conversationId;

    private String mode;

    private String routeStatus;

    private String pageNo;

    private String pageSize;
}
