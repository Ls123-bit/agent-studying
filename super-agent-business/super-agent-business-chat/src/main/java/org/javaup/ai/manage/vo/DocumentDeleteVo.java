package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 删除文档出参。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDeleteVo {

    /**
     * 已删除的文档 id。
     */
    private Long documentId;

    /**
     * 已删除的文档名称。
     */
    private String documentName;
}
