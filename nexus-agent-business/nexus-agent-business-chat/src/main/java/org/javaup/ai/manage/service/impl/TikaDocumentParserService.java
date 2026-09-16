package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.util.StrUtil;
import org.apache.tika.Tika;
import org.javaup.ai.manage.service.DocumentParserService;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentLineClassifier;
import org.javaup.ai.manage.support.DocumentStructureNodeCandidate;
import org.javaup.ai.manage.support.DocumentStructureNodeExtractor;
import org.javaup.enums.DocumentContentQualityLevelEnum;
import org.javaup.enums.DocumentFileTypeEnum;
import org.javaup.enums.DocumentStructureLevelEnum;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/
/*
文档解析器
基于Apache Tika的多格式文档解析器
 */
@AllArgsConstructor
@Service
public class TikaDocumentParserService implements DocumentParserService {

    private static final Tika TIKA = new Tika();
    private final DocumentLineClassifier documentLineClassifier;
    private final DocumentStructureNodeExtractor structureNodeExtractor;

    @Override


    /**
     * 解析文档原始字节并生成标准化分析结果。
     * <p>
     * 这是上传后异步解析链里的核心一步，整体顺序如下：
     * 1. 先根据文件类型提取原始文本；
     * 2. 对文本做换行、空白字符和乱码清洗；
     * 3. 提取结构节点候选，并估算标题数量；
     * 4. 统计段落、字符数、token 数；
     * 5. 评估文档结构等级和内容质量等级；
     * 6. 把这些结果打包成 DocumentAnalysisResult 返回给上游。
     * </p>
     * 整个parse方法可以看作一条分析流水线，每一步都在为后续的策略推荐积累判断依据，
     */
    public DocumentAnalysisResult parse(byte[] bytes, String originalFileName, String mimeType, DocumentFileTypeEnum fileType) {//解析文档，返回分析结果（支持的格式： PDF、DOC、DOCX、TXT、MD、HTML）解析结果（DocumentAnalysisResult）
        //第一步先把不同格式文件转换成统一的原始文本
        String rawText = extractRawText(bytes, originalFileName, mimeType, fileType);
        //再做文本清洗，为后续标题识别、段落切分和结构抽取提供更稳定的输入。
        String cleanedText = cleanupText(rawText);
        // 结构节点由专门的提取器负责抽取，这些节点后面会参与导航和切块策略判断。
        List<DocumentStructureNodeCandidate> structureNodes = structureNodeExtractor.extract(originalFileName, cleanedText);
        int headingCount = countHeadings(cleanedText, structureNodes);
        // 段落统计既用于结构判断，也用于后续策略推荐时判断是否适合语义切块。
        List<String> paragraphList = extractParagraphs(cleanedText);

        int maxParagraphLength = paragraphList.stream().mapToInt(String::length).max().orElse(0);

        int charCount = cleanedText.length();
        // token 数这里是估算值，不是精确 tokenizer 结果，但足够用于策略判断和粗粒度统计。
        int tokenCount = estimateTokenCount(cleanedText);

        int structureLevel = evaluateStructureLevel(headingCount, paragraphList.size());

        int contentQualityLevel = evaluateContentQuality(cleanedText, charCount);

        return new DocumentAnalysisResult(
            cleanedText,
            charCount,
            tokenCount,
            structureLevel,
            contentQualityLevel,
            headingCount,
            paragraphList.size(),
            maxParagraphLength,
            structureNodes
        );
    }




    /*
    按文件类型提取原始文本。
 * <p>
 * Office（DOC、DOCX）/PDF/HTML 等复杂格式优先走 Apache Tika；
 * 纯文本格式（TXT、MD）则直接按 UTF-8 解码。
 * 兜底：如果 Tika 失败但 MIME/文件类型仍明显是文本，则退回到直接字符串解码作为兜底。
     */
    //调用 Tika 提取原始文本（按文件类型提取原始文本）
    private String extractRawText(byte[] bytes, String originalFileName, String mimeType, DocumentFileTypeEnum fileType) {
        try {
            if (fileType == DocumentFileTypeEnum.PDF || fileType == DocumentFileTypeEnum.DOC || fileType == DocumentFileTypeEnum.DOCX) {

                return TIKA.parseToString(new ByteArrayInputStream(bytes));
            }
            if (fileType == DocumentFileTypeEnum.TXT || fileType == DocumentFileTypeEnum.MD) {

                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (fileType == DocumentFileTypeEnum.HTML) {

                return TIKA.parseToString(new ByteArrayInputStream(bytes));
            }

            return TIKA.parseToString(new ByteArrayInputStream(bytes));
        }
        catch (Exception exception) {
            // 某些文本文件虽然 Tika 解析失败，但原始字节本身仍然可以直接按文本解码读取。
            if (fileType == DocumentFileTypeEnum.TXT || fileType == DocumentFileTypeEnum.MD || mimeType != null && mimeType.startsWith("text/")) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            throw new IllegalStateException("Tika 解析失败: " + exception.getMessage(), exception);
        }
    }






    /*
    cleanup Text：文本清洗
    这步看着简单，但很重要。Tika 提取出来的文本经常会有各种奇怪的控制字符、多余的空行，如果不清洗，后面的标题识别和段落切分都会受影响。
     */
    //清理文本（去除多余空白等）
    private String cleanupText(String rawText) {
        if (StrUtil.isBlank(rawText)) {
            return "";
        }

        String cleaned = rawText
            .replace("\r\n", "\n")// Windows 换行统一成 Unix 换行
            .replace('\r', '\n')// 老 Mac 换行也统一
            .replace('\u0000', ' ')// 空字符替换成空格
            .replaceAll("[\\t\\x0B\\f]+", " ")// 制表符等控制字符替换成空格
            .replaceAll("\\n{3,}", "\n\n")// 连续 3 个以上换行压缩成 2 个
            .replaceAll("[ ]{2,}", " ")// 连续多个空格压缩成 1 个
            .trim();
        return cleaned;
    }


    //统计标题数量
    private int countHeadings(String text,
                              List<DocumentStructureNodeCandidate> structureNodes) {
        if (structureNodes != null && !structureNodes.isEmpty()) {
            long structuredHeadingCount = structureNodes.stream()
                .filter(node -> node != null
                    && DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType())
                    && node.getDepth() != null
                    && node.getDepth() > 0)
                .count();
            if (structuredHeadingCount > 0) {
                return (int) structuredHeadingCount;
            }
        }
        int count = 0;
        for (String line : text.split("\n")) {

            if (documentLineClassifier.classify(line).isHeading()) {
                count++;
            }
        }
        return count;
    }


    //提取段落列表
    private List<String> extractParagraphs(String text) {
        List<String> paragraphList = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String trimmed = paragraph.trim();
            if (StrUtil.isNotBlank(trimmed)) {

                paragraphList.add(trimmed);
            }
        }
        return paragraphList;
    }

    private int estimateTokenCount(String text) {
        int englishWordCount = 0;
        int chineseCharCount = 0;

        for (String word : text.split("\\s+")) {
            if (word.matches(".*[A-Za-z].*")) {
                englishWordCount++;
            }
        }

        for (char current : text.toCharArray()) {
            if (String.valueOf(current).matches("[\\u4e00-\\u9fa5]")) {
                chineseCharCount++;
            }
        }

        return englishWordCount + chineseCharCount + Math.max(1, (text.length() - chineseCharCount) / 4);
    }


    //评估结构化程度
    private int evaluateStructureLevel(int headingCount, int paragraphCount) {

        if (headingCount >= 5) {
            return DocumentStructureLevelEnum.HIGH.getCode();
        }
        if (headingCount >= 2) {
            return DocumentStructureLevelEnum.MEDIUM.getCode();
        }
        if (paragraphCount >= 3) {
            return DocumentStructureLevelEnum.LOW.getCode();
        }
        return DocumentStructureLevelEnum.UNKNOWN.getCode();
    }


    //评估内容质量
    private int evaluateContentQuality(String text, int charCount) {
        if (StrUtil.isBlank(text) || charCount < 20) {

            return DocumentContentQualityLevelEnum.LOW.getCode();
        }

        long brokenCharCount = text.chars().filter(value -> value == '�').count();
        double brokenRatio = charCount == 0 ? 1D : (double) brokenCharCount / (double) charCount;
        if (brokenRatio > 0.02D || charCount < 100) {

            return DocumentContentQualityLevelEnum.LOW.getCode();
        }
        if (brokenRatio > 0.005D || charCount < 500) {

            return DocumentContentQualityLevelEnum.MEDIUM.getCode();
        }

        return DocumentContentQualityLevelEnum.HIGH.getCode();
    }
}
