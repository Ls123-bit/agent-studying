package org.javaup.ai.manage.dto;

import lombok.Data;


@Data
public class DocumentPageQueryDto {

    private Integer pageNo;

    private Integer pageSize;

    private String keyword;
}
