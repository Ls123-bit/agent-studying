package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MinIO 对象存储结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoredObjectInfo {

    /**
     * bucket 名称。
     */
    private String bucketName;

    /**
     * 对象名称。
     */
    private String objectName;

    /**
     * 对象地址。
     */
    private String objectUrl;
}
