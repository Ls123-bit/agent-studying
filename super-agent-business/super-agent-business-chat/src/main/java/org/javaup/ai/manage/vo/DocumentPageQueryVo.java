package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页查询文档列表出参。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentPageQueryVo {

    private Integer pageNo;

    private Integer pageSize;

    private Long total;

    private List<DocumentListItemVo> records;
}
