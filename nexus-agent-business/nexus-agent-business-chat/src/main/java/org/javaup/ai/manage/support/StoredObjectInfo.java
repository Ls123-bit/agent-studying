package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/


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
