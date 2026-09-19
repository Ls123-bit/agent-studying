package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;



/*
简单的数据载体，包含三个字段
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoredObjectInfo {

    private String bucketName;//MinIO桶名

    private String objectName;//对象在桶内的完整路径

    private String objectUrl;//拼接好的完整访问URL
}
