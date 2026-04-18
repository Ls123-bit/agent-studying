package org.javaup.ai.manage.service.impl;

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
/**
 * 基于 Tika 的文档解析服务实现。
 *
 * <p>这个类负责把原始文件转成后续链路真正需要的“可切块纯文本 + 分析指标”。</p>
 *
 * <p>除了提取文本本身，它还会顺带计算：</p>
 * <p>1. 标题数和段落数，用于判断结构化程度。</p>
 * <p>2. 字符数和 token 估算，用于推荐合适的切块策略。</p>
 * <p>3. 内容质量等级，用于决定是否推荐 LLM 智能切块。</p>
 */
@Service
public class TikaDocumentParserService implements DocumentParserService {

    private final Tika tika = new Tika();
    private final DocumentLineClassifier documentLineClassifier;
    private final DocumentStructureNodeExtractor structureNodeExtractor;

    public TikaDocumentParserService(DocumentLineClassifier documentLineClassifier,
                                     DocumentStructureNodeExtractor structureNodeExtractor) {
        this.documentLineClassifier = documentLineClassifier;
        this.structureNodeExtractor = structureNodeExtractor;
    }

    @Override
    /**
     * 解析原始文件，并产出后续整条文档处理链路依赖的分析结果。
     *
     * <p>这个方法是“上传文件”进入后台异步处理后的第一个核心节点，
     * 它的输出会直接影响后面的三个关键阶段：</p>
     * <p>1. 策略推荐：根据结构程度、段落长度、内容质量决定推荐哪些切块策略。</p>
     * <p>2. 页面展示：前端会展示字符数、token 数、解析状态等信息。</p>
     * <p>3. 索引构建：后面真正执行切块时，读取的就是这里解析并清洗后的文本。</p>
     *
     * <p>返回的 {@link DocumentAnalysisResult} 里最关键的字段含义是：</p>
     * <p>1. parsedText：后续切块和索引构建真正使用的标准化正文。</p>
     * <p>2. charCount/tokenCount：用于页面展示，也用于判断文档是否过长。</p>
     * <p>3. structureLevel：决定是否优先推荐“基于文档结构切块”。</p>
     * <p>4. contentQualityLevel：决定是否需要更强的兜底策略，尤其是 LLM 智能切块。</p>
     * <p>5. headingCount/paragraphCount/maxParagraphLength：给策略推荐提供更细粒度的判断依据。</p>
     */
    public DocumentAnalysisResult parse(byte[] bytes, String originalFileName, String mimeType, DocumentFileTypeEnum fileType) {
        // 第一步先把原始二进制文件尽量稳定地提取成“原始正文”。
        // 这里得到的文本可能还带有换行噪声、空白噪声，甚至解析残留字符。
        String rawText = extractRawText(bytes, originalFileName, mimeType, fileType);

        // 第二步做统一清洗，把不同格式来源的正文规整成后续算法更容易处理的文本。
        // 真正进入策略推荐和索引构建的，不是 rawText，而是 cleanedText。
        String cleanedText = cleanupText(rawText);

        /*
         * 结构树现在由“代码主导 + LLM 判歧”的解析器统一产出，
         * 因此标题统计优先复用结构节点结果，避免“统计口径”和“真实结构树”不一致。
         */
        List<DocumentStructureNodeCandidate> structureNodes = structureNodeExtractor.extract(originalFileName, cleanedText);
        int headingCount = countHeadings(cleanedText, structureNodes);

        // 把正文拆成段落列表，后面会同时用到：
        // 1. paragraphCount：判断文本是否成篇、是否足够适合做语义切块
        // 2. maxParagraphLength：判断是否存在超长段落，需要递归切块兜底
        List<String> paragraphList = extractParagraphs(cleanedText);

        // 最大段落长度是推荐递归切块的重要依据之一。
        // 即使整篇文档不算很长，只要出现特别长的段落，也可能需要递归拆分。
        int maxParagraphLength = paragraphList.stream().mapToInt(String::length).max().orElse(0);

        // 字符数是最基础的体量指标，前端会直接展示，策略推荐也会参考它。
        int charCount = cleanedText.length();

        // token 数不是精确 tokenizer 结果，而是一个足够稳定的近似值。
        // 这里的目标不是精确计费，而是给“文档是否偏长”提供量级判断。
        int tokenCount = estimateTokenCount(cleanedText);

        // 结构等级综合标题数和段落数得出，
        // 主要服务于“是否优先按文档天然章节来切”这个决策。
        int structureLevel = evaluateStructureLevel(headingCount, paragraphList.size());

        // 内容质量等级主要看文本长度和乱码比例，
        // 低质量文本意味着传统切块策略效果可能不稳定，需要更强兜底。
        int contentQualityLevel = evaluateContentQuality(cleanedText, charCount);

        // 最终把“标准化正文 + 各类分析指标”一起返回给上游异步处理服务。
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

    /**
     * 按文件类型选择合适的解析方式，尽量把二进制文件转换成可读文本。
     *
     * <p>这一层的目标不是做复杂语义分析，而是尽可能稳地拿到“文本内容本身”。</p>
     *
     * <p>当前策略是：</p>
     * <p>1. PDF / DOC / DOCX / HTML 交给 Tika 解析。</p>
     * <p>2. TXT / MD 直接按 UTF-8 读取，减少不必要的解析损耗。</p>
     * <p>3. 其他类型优先尝试 Tika，失败后再根据 mimeType 判断是否能走文本兜底。</p>
     */
    private String extractRawText(byte[] bytes, String originalFileName, String mimeType, DocumentFileTypeEnum fileType) {
        try {
            if (fileType == DocumentFileTypeEnum.PDF || fileType == DocumentFileTypeEnum.DOC || fileType == DocumentFileTypeEnum.DOCX) {
                // 这类二进制办公文档优先交给 Tika 统一解析。
                return tika.parseToString(new ByteArrayInputStream(bytes));
            }
            if (fileType == DocumentFileTypeEnum.TXT || fileType == DocumentFileTypeEnum.MD) {
                // 纯文本类文件直接按 UTF-8 读取，避免走重量级解析。
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (fileType == DocumentFileTypeEnum.HTML) {
                // HTML 也交给 Tika，便于自动去掉标签保留可读正文。
                return tika.parseToString(new ByteArrayInputStream(bytes));
            }

            // 未显式列出的类型仍然尝试用 Tika 兜底解析。
            // 这样即使未来扩充文件类型，很多常见场景也能先跑起来。
            return tika.parseToString(new ByteArrayInputStream(bytes));
        }
        catch (Exception exception) {
            /*
             * 对简单文本类型保留一个直接按 UTF-8 回退的兜底路径，
             * 避免 Tika 个别格式判断异常时把本来可读的文本一起拖垮。
             */
            if (fileType == DocumentFileTypeEnum.TXT || fileType == DocumentFileTypeEnum.MD || mimeType != null && mimeType.startsWith("text/")) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            throw new IllegalStateException("Tika 解析失败: " + exception.getMessage(), exception);
        }
    }

    /**
     * 清洗文本，减少后续策略推荐和切块时的噪声。
     *
     * <p>这里不是为了把文本“美化成适合阅读”的样子，
     * 而是为了让后续的结构识别、段落统计、切块逻辑尽量稳定。</p>
     *
     * <p>主要处理几类问题：</p>
     * <p>1. 不同系统产生的换行符差异。</p>
     * <p>2. 二进制解析后残留的空字符。</p>
     * <p>3. 多余制表符和异常空白。</p>
     * <p>4. 连续过多空行导致的伪段落。</p>
     */
    private String cleanupText(String rawText) {
        if (StrUtil.isBlank(rawText)) {
            return "";
        }

        // 这里的清洗目标不是“美化文本”，而是消除切块时最容易产生噪声的格式残留。
        String cleaned = rawText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u0000', ' ')
            .replaceAll("[\\t\\x0B\\f]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .replaceAll("[ ]{2,}", " ")
            .trim();
        return cleaned;
    }

    /**
     * 统计标题数量，用来判断结构化程度。
     *
     * <p>这里不追求标题识别 100% 准确，而是追求“是否有明显结构”的大方向判断。
     * 因为后续策略推荐只需要知道这份文档更像“有章节的结构化文档”，
     * 还是“纯文本堆叠的弱结构文档”。</p>
     */
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
            // 这里和结构切块共用同一套行级分类规则，保证“结构统计”和“结构切块”的口径一致。
            if (documentLineClassifier.classify(line).isHeading()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 提取段落列表。
     *
     * <p>段落是当前解析阶段最重要的中间结构之一，
     * 后面会用它来评估：</p>
     * <p>1. 这份文档是否足够成篇，适不适合语义切块。</p>
     * <p>2. 是否存在超长段落，需要递归切块兜底。</p>
     */
    private List<String> extractParagraphs(String text) {
        List<String> paragraphList = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String trimmed = paragraph.trim();
            if (StrUtil.isNotBlank(trimmed)) {
                // 只保留真正有内容的段落，空白段不参与结构和长度统计。
                paragraphList.add(trimmed);
            }
        }
        return paragraphList;
    }

    /**
     * 粗略估算 token 数。
     *
     * <p>第一期不追求和具体模型 tokenizer 完全一致，
     * 只需要给策略推荐和切块兜底提供一个可用的数量级参考。</p>
     *
     * <p>估算规则是：</p>
     * <p>1. 英文按单词计数。</p>
     * <p>2. 中文按单字计数。</p>
     * <p>3. 其余字符再按一个宽松比例折算。</p>
     *
     * <p>这个值主要用于“长度感知”，不是用于账单级精确计算。</p>
     */
    private int estimateTokenCount(String text) {
        int englishWordCount = 0;
        int chineseCharCount = 0;

        // 英文按单词近似 token 数。
        for (String word : text.split("\\s+")) {
            if (word.matches(".*[A-Za-z].*")) {
                englishWordCount++;
            }
        }

        // 中文按单字近似 token 数。
        for (char current : text.toCharArray()) {
            if (String.valueOf(current).matches("[\\u4e00-\\u9fa5]")) {
                chineseCharCount++;
            }
        }

        // 最后一项是对标点、数字、碎片字符等非中文字符做一个近似折算，
        // 避免整篇文本几乎没有中文时，token 估算明显偏低。
        return englishWordCount + chineseCharCount + Math.max(1, (text.length() - chineseCharCount) / 4);
    }

    /**
     * 评估文档结构化程度。
     *
     * <p>这是一个偏启发式的等级判断，不是严格的 NLP 结构识别结果。</p>
     *
     * <p>当前的业务意图很明确：</p>
     * <p>1. 标题多，说明结构明显，倾向 HIGH / MEDIUM。</p>
     * <p>2. 虽然标题少，但段落已经比较成篇，至少认定为 LOW。</p>
     * <p>3. 连段落都很少，就只能认为结构未知。</p>
     */
    private int evaluateStructureLevel(int headingCount, int paragraphCount) {
        // 结构等级越高，越倾向于推荐“基于文档结构切块”。
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

    /**
     * 评估文档内容质量。
     *
     * <p>这里的“质量”不是指文章写得好不好，
     * 而是指“解析出来的文本是否适合作为检索和切块输入”。</p>
     *
     * <p>主要参考两类信号：</p>
     * <p>1. 文本是否太短，短到几乎没有分析价值。</p>
     * <p>2. 是否出现大量乱码字符，说明解析结果不稳定。</p>
     *
     * <p>这个等级会直接影响后面的策略推荐，尤其是是否需要推荐 LLM 智能切块。</p>
     */
    private int evaluateContentQuality(String text, int charCount) {
        if (StrUtil.isBlank(text) || charCount < 20) {
            // 太短的文本即使没有乱码，也很难支撑稳定的结构分析和召回。
            return DocumentContentQualityLevelEnum.LOW.getCode();
        }

        // 乱码字符比例越高，说明解析效果越差，后续更可能需要 LLM 或更强兜底策略。
        long brokenCharCount = text.chars().filter(value -> value == '�').count();
        double brokenRatio = charCount == 0 ? 1D : (double) brokenCharCount / (double) charCount;
        if (brokenRatio > 0.02D || charCount < 100) {
            // 长度太短或者乱码明显时，直接判为低质量。
            return DocumentContentQualityLevelEnum.LOW.getCode();
        }
        if (brokenRatio > 0.005D || charCount < 500) {
            // 中等长度但仍有一定噪声时，判为中等质量。
            return DocumentContentQualityLevelEnum.MEDIUM.getCode();
        }

        // 只有在文本量充足且乱码比例很低时，才判为高质量。
        return DocumentContentQualityLevelEnum.HIGH.getCode();
    }
}
