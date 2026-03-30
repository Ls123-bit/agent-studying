package org.javaup.ai.manage.service;

import org.javaup.ai.manage.support.StoredObjectInfo;

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
}
