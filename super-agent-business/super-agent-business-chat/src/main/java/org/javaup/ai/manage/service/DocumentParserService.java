package org.javaup.ai.manage.service;

import org.javaup.enums.DocumentFileTypeEnum;
import org.javaup.ai.manage.support.DocumentAnalysisResult;

/**
 * 文档解析服务。
 */
public interface DocumentParserService {

    /**
     * 解析文档内容并返回分析结果。
     */
    DocumentAnalysisResult parse(byte[] bytes, String originalFileName, String mimeType, DocumentFileTypeEnum fileType);
}
