package org.javaup.ai.manage.dto;

import lombok.Data;

import java.util.List;

@Data
public class DocumentProfileBatchRegenerateDto {

    private List<String> documentIds;

    private String operatorId;
}
