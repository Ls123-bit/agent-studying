package org.javaup.ai.manage.service;

import org.javaup.ai.manage.support.StoredObjectInfo;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 文档存储服务。
 */
public interface DocumentStorageService {

    /**
     * 上传原始文件。
     */
    StoredObjectInfo uploadOriginalFile(Long documentId, String originalFileName, byte[] bytes, String contentType);

    /**
     * 上传解析后的文本。
     */
    String uploadParsedText(Long documentId, String parsedText);

    /**
     * 下载原始文件字节。
     */
    byte[] downloadObject(String objectName);

    /**
     * 下载文本内容。
     */
    String downloadText(String objectName);

    /**
     * 删除对象存储中的文档相关文件。
     */
    void deleteObjects(List<String> objectNameList);
}
