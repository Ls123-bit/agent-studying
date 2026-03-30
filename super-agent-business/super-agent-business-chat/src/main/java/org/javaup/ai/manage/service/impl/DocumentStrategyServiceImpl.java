package org.javaup.ai.manage.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.service.DocumentStrategyService;
import org.javaup.ai.manage.support.ChunkCandidate;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentStrategyPlanDraft;
import org.javaup.ai.manage.support.DocumentStrategyStepDraft;
import org.javaup.enums.DocumentChunkSourceTypeEnum;
import org.javaup.enums.DocumentContentQualityLevelEnum;
import org.javaup.enums.DocumentFileTypeEnum;
import org.javaup.enums.DocumentStrategyExecuteStatusEnum;
import org.javaup.enums.DocumentStrategyRoleEnum;
import org.javaup.enums.DocumentStrategySourceTypeEnum;
import org.javaup.enums.DocumentStrategyTypeEnum;
import org.javaup.enums.DocumentStructureLevelEnum;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档策略服务实现。
 *
 * <p>这里把“推荐策略”和“执行切块”统一收敛到一个策略引擎里，
 * 让控制器和应用服务不需要关心四种切块策略的具体实现细节。</p>
 */
@Slf4j
@Service
public class DocumentStrategyServiceImpl implements DocumentStrategyService {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6}\\s+.+|\\d+(\\.\\d+){0,3}[、.\\s].+|[一二三四五六七八九十]+[、.\\s].+|第[一二三四五六七八九十\\d]+[章节条]\\s*.+)$");

    private static final Pattern ENGLISH_WORD_PATTERN = Pattern.compile("[A-Za-z0-9]{2,}");

    private final DocumentManageProperties properties;

    private final ObjectMapper objectMapper;

    private final ObjectProvider<ChatModel> chatModelProvider;

    public DocumentStrategyServiceImpl(DocumentManageProperties properties,
                                       ObjectMapper objectMapper,
                                       ObjectProvider<ChatModel> chatModelProvider) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.chatModelProvider = chatModelProvider;
    }

    @Override
    public DocumentStrategyPlanDraft recommendStrategy(SuperAgentDocument document, DocumentAnalysisResult analysisResult) {
        // 推荐策略的核心思路是：先根据文档结构、长度、质量等特征打标签，
        // 再决定是否加入结构切块、递归切块、语义切块、LLM 切块。
        List<DocumentStrategyStepDraft> stepDraftList = new ArrayList<>();
        List<String> reasonList = new ArrayList<>();
        DocumentFileTypeEnum fileType = DocumentFileTypeEnum.getRc(document.getFileType());

        // 这四个布尔值分别代表四类策略是否值得出现在推荐链路里。
        // 它们本质上就是把 parse 阶段产出的 DocumentAnalysisResult 映射成可执行决策：
        // 1. structureLevel + headingCount + fileType -> 是否适合结构切块
        // 2. charCount + maxParagraphLength -> 是否需要递归切块兜底
        // 3. charCount + paragraphCount + contentQualityLevel -> 是否适合语义切块
        // 4. contentQualityLevel + charCount + 配置开关 -> 是否需要 LLM 智能切块
        boolean structureRecommended = shouldUseStructure(fileType, analysisResult);
        boolean recursiveRecommended = shouldUseRecursive(analysisResult);
        boolean semanticRecommended = shouldUseSemantic(analysisResult);
        boolean llmRecommended = shouldUseLlm(analysisResult);

        if (structureRecommended) {
            // 结构切块通常适合做第一步，因为它最接近文档原始章节边界。
            // 这里对应的 parse 指标主要是：
            // 1. structureLevel 足够高
            // 2. headingCount 足够多
            // 3. 文档类型本身适合保留章节结构
            stepDraftList.add(new DocumentStrategyStepDraft(
                DocumentStrategyTypeEnum.STRUCTURE.getCode(),
                DocumentStrategyRoleEnum.PRIMARY.getCode(),
                DocumentStrategySourceTypeEnum.SYSTEM_RECOMMEND.getCode(),
                "检测到文档具有较明显的标题或章节结构，优先按文档天然结构切块。"
            ));
            reasonList.add("检测到文档结构较清晰，优先采用基于文档结构切块。");
        }

        if (recursiveRecommended) {
            // 递归切块更像是兜底策略，用来处理超长段落或超长文档。
            // 这里直接对应 parse 阶段的两个长度指标：
            // 1. charCount 代表整篇文档是否偏长
            // 2. maxParagraphLength 代表是否存在局部超长段落
            stepDraftList.add(new DocumentStrategyStepDraft(
                DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                structureRecommended ? DocumentStrategyRoleEnum.FALLBACK.getCode() : DocumentStrategyRoleEnum.PRIMARY.getCode(),
                DocumentStrategySourceTypeEnum.SYSTEM_RECOMMEND.getCode(),
                "文档整体较长或存在超长段落，需要递归分块保证 chunk 长度可控。"
            ));
            reasonList.add("文档长度较大或存在超长段落，增加递归分块兜底。");
        }

        if (semanticRecommended) {
            // 语义切块用于优化主题边界，通常接在主策略之后做质量增强。
            // 它依赖 parse 阶段已经给出：
            // 1. 足够的 charCount，说明文本量能支撑主题判断
            // 2. 足够的 paragraphCount，说明文本不是零碎短句堆砌
            // 3. 至少中等的 contentQualityLevel，避免低质量文本误切
            stepDraftList.add(new DocumentStrategyStepDraft(
                DocumentStrategyTypeEnum.SEMANTIC.getCode(),
                stepDraftList.isEmpty() ? DocumentStrategyRoleEnum.PRIMARY.getCode() : DocumentStrategyRoleEnum.OPTIMIZE.getCode(),
                DocumentStrategySourceTypeEnum.SYSTEM_RECOMMEND.getCode(),
                "文本主题边界相对明确，适合使用语义分块进一步优化边界。"
            ));
            reasonList.add("文本主题边界较明显，建议追加语义分块优化召回质量。");
        }

        if (llmRecommended) {
            // 大模型切块成本更高，一般只在低质量文本等复杂场景下推荐。
            // 这个判断最关注 parse 阶段给出的 contentQualityLevel，
            // 因为它反映“传统规则切块是否可能不稳定”。
            stepDraftList.add(new DocumentStrategyStepDraft(
                DocumentStrategyTypeEnum.LLM.getCode(),
                stepDraftList.isEmpty() ? DocumentStrategyRoleEnum.PRIMARY.getCode() : DocumentStrategyRoleEnum.ENHANCE.getCode(),
                DocumentStrategySourceTypeEnum.SYSTEM_RECOMMEND.getCode(),
                "文档质量偏低或结构识别不稳定，建议使用大模型智能切块增强复杂场景。"
            ));
            reasonList.add("文档质量偏低或结构不稳定，建议增加大模型智能切块增强。");
        }

        if (stepDraftList.isEmpty()) {
            // 如果所有规则都没命中，仍然要给一个可执行的保底方案，避免前端无方案可确认。
            // 这里默认回退到递归切块，是因为它对文档结构和质量依赖最小，适合作为基础兜底策略。
            stepDraftList.add(new DocumentStrategyStepDraft(
                DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                DocumentStrategyRoleEnum.PRIMARY.getCode(),
                DocumentStrategySourceTypeEnum.SYSTEM_RECOMMEND.getCode(),
                "未识别出明显结构，先使用递归分块完成基础切块。"
            ));
            reasonList.add("未识别出明显结构，默认使用递归分块完成基础切块。");
        }

        // 推荐链路对外输出时会固定成稳定顺序，便于前端展示和日志对比。
        List<DocumentStrategyStepDraft> orderedSteps = stepDraftList.stream()
            .sorted(Comparator.comparingInt(DocumentStrategyStepDraft::getStrategyType))
            .toList();

        String strategySnapshot = orderedSteps.stream()
            .map(item -> String.valueOf(item.getStrategyType()))
            .collect(Collectors.joining(","));

        return new DocumentStrategyPlanDraft(strategySnapshot, String.join("；", reasonList), orderedSteps);
    }

    @Override
    public List<SuperAgentDocumentStrategyStep> normalizeSteps(SuperAgentDocumentStrategyPlan basePlan,
                                                               List<SuperAgentDocumentStrategyStep> baseSteps,
                                                               List<Integer> requestStrategyTypes,
                                                               Long documentId) {
        // 先过滤掉非法策略类型，再借助 LinkedHashSet 做“保序去重”。
        LinkedHashSet<Integer> requestTypeSet = new LinkedHashSet<>();
        for (Integer strategyType : requestStrategyTypes) {
            if (DocumentStrategyTypeEnum.getRc(strategyType) != null) {
                requestTypeSet.add(strategyType);
            }
        }

        /*
         * 这里要保留用户在前端拖拽后的真实顺序。
         *
         * 第一版为了保证顺序“规范化”，按枚举预设顺序重新排了一遍，
         * 但这样会把前端拖拽的结果覆盖掉，导致界面看上去能排序，
         * 实际入库后又恢复成系统默认顺序。
         *
         * 现在直接基于 LinkedHashSet 的插入顺序构建最终列表：
         * 1. 过滤掉非法策略类型
         * 2. 自动去重
         * 3. 保留用户提交的真实顺序
         */
        List<Integer> normalizedTypes = new ArrayList<>(requestTypeSet);

        // 基础方案步骤转成 Map，后面可以快速判断某个策略是“保留旧步骤”还是“用户新加步骤”。
        Map<Integer, SuperAgentDocumentStrategyStep> baseStepMap = new LinkedHashMap<>();
        for (SuperAgentDocumentStrategyStep baseStep : baseSteps) {
            baseStepMap.put(baseStep.getStrategyType(), baseStep);
        }

        List<SuperAgentDocumentStrategyStep> normalizedStepList = new ArrayList<>();
        for (int index = 0; index < normalizedTypes.size(); index++) {
            Integer strategyType = normalizedTypes.get(index);
            SuperAgentDocumentStrategyStep baseStep = baseStepMap.get(strategyType);

            // 这里不复用旧实体，而是重新组装一份标准化后的步骤，
            // 这样 stepNo、角色、来源类型都能和最终执行链保持一致。
            SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
            step.setDocumentId(documentId);
            step.setStepNo(index + 1);
            step.setStrategyType(strategyType);
            step.setStrategyRole(resolveRole(strategyType, normalizedTypes));
            step.setSourceType(baseStep == null
                ? DocumentStrategySourceTypeEnum.USER_ADD.getCode()
                : DocumentStrategySourceTypeEnum.USER_KEEP.getCode());
            step.setExecuteStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode());
            step.setRecommendReason(baseStep == null ? "用户手动追加该策略。" : baseStep.getRecommendReason());
            normalizedStepList.add(step);
        }
        return normalizedStepList;
    }

    @Override
    public List<ChunkCandidate> buildChunks(SuperAgentDocument document,
                                            SuperAgentDocumentStrategyPlan plan,
                                            List<SuperAgentDocumentStrategyStep> steps,
                                            String parsedText) {
        // 切块流水线的输入先视为一个完整文本块，
        // 然后让每个策略按顺序不断把它变成更适合检索的 chunk 列表。
        List<ChunkCandidate> currentList = List.of(
            new ChunkCandidate("", null, parsedText, DocumentChunkSourceTypeEnum.ORIGINAL.getCode())
        );

        // 这里必须严格按 stepNo 排序，确保执行顺序与前端确认顺序完全一致。
        List<SuperAgentDocumentStrategyStep> orderedSteps = steps.stream()
            .sorted(Comparator.comparingInt(SuperAgentDocumentStrategyStep::getStepNo))
            .toList();

        for (SuperAgentDocumentStrategyStep step : orderedSteps) {
            DocumentStrategyTypeEnum strategyType = DocumentStrategyTypeEnum.getRc(step.getStrategyType());
            if (strategyType == null) {
                continue;
            }

            // 每跑完一个策略都立即清洗结果，避免空块、重复块在后续步骤中被继续放大。
            currentList = switch (strategyType) {
                case STRUCTURE -> applyStructureChunking(parsedText);
                case RECURSIVE -> applyRecursiveChunking(currentList);
                case SEMANTIC -> applySemanticChunking(currentList);
                case LLM -> applyLlmChunking(currentList);
            };
            currentList = cleanupChunkList(currentList);
        }
        return cleanupChunkList(currentList);
    }

    /**
     * 判断是否优先使用结构切块。
     *
     * <p>这个判断直接消费 parse 阶段的三个关键信号：</p>
     * <p>1. fileType：文件类型本身是否适合保留天然章节边界。</p>
     * <p>2. structureLevel：整体结构程度是否达到中等及以上。</p>
     * <p>3. headingCount：即使整体结构等级一般，只要标题足够多，也说明章节边界较明显。</p>
     */
    private boolean shouldUseStructure(DocumentFileTypeEnum fileType, DocumentAnalysisResult analysisResult) {
        // 结构切块只在适合保留章节边界的文档类型上开启，
        // 同时还要求结构识别质量达到一定阈值。
        boolean suitableType = fileType == DocumentFileTypeEnum.PDF
            || fileType == DocumentFileTypeEnum.DOC
            || fileType == DocumentFileTypeEnum.DOCX
            || fileType == DocumentFileTypeEnum.MD
            || fileType == DocumentFileTypeEnum.HTML;
        return suitableType && (analysisResult.getStructureLevel() >= DocumentStructureLevelEnum.MEDIUM.getCode()
            || analysisResult.getHeadingCount() >= 2);
    }

    /**
     * 判断是否推荐递归分块。
     *
     * <p>这里完全围绕 parse 阶段的长度指标展开：</p>
     * <p>1. charCount 超阈值，说明整篇文档偏长。</p>
     * <p>2. maxParagraphLength 超阈值，说明局部可能出现巨型段落。</p>
     *
     * <p>只要命中任意一个条件，就说明仅靠结构或语义切块可能仍会留下过大的 chunk，
     * 所以需要递归切块做长度兜底。</p>
     */
    private boolean shouldUseRecursive(DocumentAnalysisResult analysisResult) {
        // 文档整体很长，或者存在超长段落时，递归切块才有必要介入。
        return analysisResult.getCharCount() >= properties.getChunk().getRecursiveMaxChars()
            || analysisResult.getMaxParagraphLength() >= properties.getChunk().getRecursiveMaxChars();
    }

    /**
     * 判断是否推荐语义分块。
     *
     * <p>语义切块比递归切块更依赖文本质量和上下文完整度，所以这里要求更严格：</p>
     * <p>1. charCount 达到最小值，保证文本体量足够。</p>
     * <p>2. paragraphCount 至少为 3，保证不是零散内容。</p>
     * <p>3. contentQualityLevel 至少为 MEDIUM，保证文本主题边界具备可判断性。</p>
     */
    private boolean shouldUseSemantic(DocumentAnalysisResult analysisResult) {
        // 语义切块至少需要有一定文本量和段落数，否则切分收益很低。
        return analysisResult.getCharCount() >= properties.getChunk().getSemanticMinChars()
            && analysisResult.getParagraphCount() >= 3
            && analysisResult.getContentQualityLevel() >= DocumentContentQualityLevelEnum.MEDIUM.getCode();
    }

    /**
     * 判断是否推荐大模型智能切块。
     *
     * <p>大模型切块主要针对 parse 阶段识别出的“低质量文本”场景：</p>
     * <p>1. contentQualityLevel 为 LOW，说明解析结果可能存在较多噪声或结构不稳定。</p>
     * <p>2. charCount 仍要达到最小门槛，避免为特别短的文本付出额外模型成本。</p>
     * <p>3. 配置开关开启时才允许推荐，避免默认把高成本策略暴露给所有场景。</p>
     */
    private boolean shouldUseLlm(DocumentAnalysisResult analysisResult) {
        // 大模型切块默认只在“低质量文本 + 开启配置”的情况下触发，控制成本。
        return Boolean.TRUE.equals(properties.getChunk().getRecommendLlmWhenLowQuality())
            && analysisResult.getContentQualityLevel().equals(DocumentContentQualityLevelEnum.LOW.getCode())
            && analysisResult.getCharCount() >= properties.getChunk().getSemanticMinChars();
    }

    /**
     * 解析标题级别和标题文本。
     */
    private HeadingInfo parseHeading(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("#")) {
            // Markdown 标题直接按 # 数量推断层级。
            int level = 0;
            while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                level++;
            }
            return new HeadingInfo(level, trimmed.substring(level).trim());
        }

        Matcher digitMatcher = Pattern.compile("^(\\d+(?:\\.\\d+)*)").matcher(trimmed);
        if (digitMatcher.find()) {
            // 类似“1.2.3”这种编号标题，用点号层级数近似标题深度。
            String prefix = digitMatcher.group(1);
            return new HeadingInfo(prefix.split("\\.").length, trimmed);
        }

        // 中文“第X章 / 第X条”按经验映射到常见层级。
        if (trimmed.startsWith("第") && trimmed.contains("章")) {
            return new HeadingInfo(1, trimmed);
        }
        if (trimmed.startsWith("第") && trimmed.contains("条")) {
            return new HeadingInfo(2, trimmed);
        }
        return new HeadingInfo(1, trimmed);
    }

    /**
     * 执行基于文档结构的切块。
     */
    private List<ChunkCandidate> applyStructureChunking(String parsedText) {
        List<ChunkCandidate> candidateList = new ArrayList<>();
        Deque<String> headingStack = new ArrayDeque<>();
        StringBuilder currentChunk = new StringBuilder();
        String currentSectionPath = "";

        for (String line : parsedText.split("\n")) {
            String trimmed = line.trim();
            if (HEADING_PATTERN.matcher(trimmed).matches()) {
                // 遇到新标题时，先把上一段正文 flush 成一个 chunk。
                flushChunk(candidateList, currentSectionPath, currentChunk);
                HeadingInfo headingInfo = parseHeading(trimmed);

                // headingStack 保存当前章节路径，用来生成“一级 > 二级 > 三级”的 sectionPath。
                while (headingStack.size() >= headingInfo.level()) {
                    headingStack.removeLast();
                }
                headingStack.addLast(headingInfo.title());
                currentSectionPath = String.join(" > ", headingStack);
                currentChunk.append(trimmed).append('\n');
                continue;
            }
            currentChunk.append(line).append('\n');
        }
        flushChunk(candidateList, currentSectionPath, currentChunk);

        if (candidateList.isEmpty()) {
            // 如果结构识别没有切出任何有效块，就自动回退到递归切块，保证链路可继续执行。
            return applyRecursiveChunking(List.of(new ChunkCandidate("", null, parsedText, DocumentChunkSourceTypeEnum.ORIGINAL.getCode())));
        }
        return candidateList;
    }

    /**
     * 执行递归分块。
     */
    private List<ChunkCandidate> applyRecursiveChunking(List<ChunkCandidate> sourceList) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        int maxChars = properties.getChunk().getRecursiveMaxChars();
        for (ChunkCandidate candidate : sourceList) {
            // 每个输入 chunk 都可能继续被拆成多个更短片段。
            List<String> splitTextList = recursiveSplit(candidate.getText(), maxChars);
            for (String splitText : splitTextList) {
                resultList.add(new ChunkCandidate(candidate.getSectionPath(), candidate.getPageNo(),
                    splitText, candidate.getSourceType()));
            }
        }
        return resultList;
    }

    /**
     * 执行语义分块。
     */
    private List<ChunkCandidate> applySemanticChunking(List<ChunkCandidate> sourceList) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        for (ChunkCandidate candidate : sourceList) {
            if (!StringUtils.hasText(candidate.getText())
                || candidate.getText().length() <= properties.getChunk().getSemanticMinChars()) {
                // 太短的文本没必要再做语义切分，直接保留原块。
                resultList.add(candidate);
                continue;
            }
            resultList.addAll(semanticSplit(candidate));
        }
        return resultList;
    }

    /**
     * 执行大模型智能切块。
     *
     * <p>如果当前环境未开启 LLM 切块，或者模型调用失败，
     * 则自动回退到语义分块，保证索引链路仍然能走通。</p>
     */
    private List<ChunkCandidate> applyLlmChunking(List<ChunkCandidate> sourceList) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (!Boolean.TRUE.equals(properties.getChunk().getLlmEnabled()) || chatModel == null) {
            // LLM 没开或当前环境没有模型时，直接退化为语义切块。
            return applySemanticChunking(sourceList);
        }

        List<ChunkCandidate> resultList = new ArrayList<>();
        for (ChunkCandidate candidate : sourceList) {
            if (!StringUtils.hasText(candidate.getText())) {
                continue;
            }

            // 为了控制单次 prompt 大小，超长文本会先递归切成多个子片段再逐段给模型。
            List<String> sourceTextList = candidate.getText().length() > properties.getChunk().getLlmMaxChars()
                ? recursiveSplit(candidate.getText(), properties.getChunk().getLlmMaxChars())
                : List.of(candidate.getText());

            for (String sourceText : sourceTextList) {
                List<String> llmChunkList = llmSplit(chatModel, sourceText);
                if (llmChunkList.isEmpty()) {
                    // 模型切块失败时继续回退到语义切块，保证不中断主流程。
                    resultList.addAll(semanticSplit(new ChunkCandidate(candidate.getSectionPath(), candidate.getPageNo(),
                        sourceText, candidate.getSourceType())));
                    continue;
                }
                for (String llmChunk : llmChunkList) {
                    resultList.add(new ChunkCandidate(candidate.getSectionPath(), candidate.getPageNo(),
                        llmChunk, candidate.getSourceType()));
                }
            }
        }
        return resultList;
    }

    /**
     * 基于句子级别相似度做语义切分。
     */
    private List<ChunkCandidate> semanticSplit(ChunkCandidate candidate) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        List<String> sentenceList = splitSentences(candidate.getText());
        if (sentenceList.size() <= 1) {
            resultList.add(candidate);
            return resultList;
        }

        StringBuilder currentChunk = new StringBuilder();
        Set<String> currentTokenSet = new LinkedHashSet<>();

        for (String sentence : sentenceList) {
            Set<String> sentenceTokenSet = extractTokens(sentence);

            // 语义切块同时考虑两个条件：
            // 1. 当前块是否超过最大长度
            // 2. 当前句与已有内容的主题相似度是否明显下降
            boolean exceedMaxChars = currentChunk.length() + sentence.length() > properties.getChunk().getSemanticMaxChars();
            double similarity = currentTokenSet.isEmpty() ? 1D : jaccard(currentTokenSet, sentenceTokenSet);
            boolean semanticBreak = currentChunk.length() >= properties.getChunk().getSemanticMinChars()
                && similarity < properties.getChunk().getSemanticSimilarityThreshold();

            if (currentChunk.length() > 0 && (exceedMaxChars || semanticBreak)) {
                // 一旦触发切分条件，就把当前累计内容收敛成一个 chunk。
                resultList.add(new ChunkCandidate(candidate.getSectionPath(), candidate.getPageNo(),
                    currentChunk.toString().trim(), candidate.getSourceType()));
                currentChunk.setLength(0);
                currentTokenSet.clear();
            }

            currentChunk.append(sentence);
            currentTokenSet.addAll(sentenceTokenSet);
        }

        if (currentChunk.length() > 0) {
            resultList.add(new ChunkCandidate(candidate.getSectionPath(), candidate.getPageNo(),
                currentChunk.toString().trim(), candidate.getSourceType()));
        }
        return resultList;
    }

    /**
     * 递归拆分超长文本。
     */
    private List<String> recursiveSplit(String text, int maxChars) {
        String trimmed = text == null ? "" : text.trim();
        if (!StringUtils.hasText(trimmed)) {
            return List.of();
        }
        if (trimmed.length() <= maxChars) {
            return List.of(trimmed);
        }

        // 递归拆分遵循“先粗后细”的原则：段落 -> 行 -> 句子 -> 固定窗口。
        List<String> paragraphList = splitByRegex(trimmed, "\\n\\s*\\n");
        if (paragraphList.size() > 1) {
            return mergeAndSplit(paragraphList, maxChars);
        }

        List<String> lineList = splitByRegex(trimmed, "\\n");
        if (lineList.size() > 1) {
            return mergeAndSplit(lineList, maxChars);
        }

        List<String> sentenceList = splitSentences(trimmed);
        if (sentenceList.size() > 1) {
            return mergeAndSplit(sentenceList, maxChars);
        }

        List<String> fixedWindowList = new ArrayList<>();
        int start = 0;
        while (start < trimmed.length()) {
            // 当前面所有语义边界都不可用时，最后才退化成固定窗口硬切。
            int end = Math.min(trimmed.length(), start + maxChars);
            fixedWindowList.add(trimmed.substring(start, end).trim());
            start = end;
        }
        return fixedWindowList;
    }

    /**
     * 把一组片段先合并到目标长度附近，再递归兜底拆分。
     */
    private List<String> mergeAndSplit(List<String> segmentList, int maxChars) {
        List<String> resultList = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String segment : segmentList) {
            String trimmed = segment.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }

            if (trimmed.length() > maxChars) {
                // 单个片段本身过长时，不能直接加入，需要再次递归细拆。
                if (current.length() > 0) {
                    resultList.add(current.toString().trim());
                    current.setLength(0);
                }
                resultList.addAll(recursiveSplit(trimmed, maxChars));
                continue;
            }

            if (current.length() + trimmed.length() + 1 > maxChars) {
                // 当前缓存即将超长时，先落一个结果块，再继续累计后面的片段。
                resultList.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(trimmed).append('\n');
        }

        if (current.length() > 0) {
            resultList.add(current.toString().trim());
        }
        return resultList;
    }

    /**
     * 根据正则拆分文本，并移除空白项。
     */
    private List<String> splitByRegex(String text, String regex) {
        return Arrays.stream(text.split(regex))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .toList();
    }

    /**
     * 按句子拆分文本。
     */
    private List<String> splitSentences(String text) {
        return Arrays.stream(text.split("(?<=[。！？!?；;\\.])"))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .toList();
    }

    /**
     * 提取简单 token，用于做句子级相似度比较。
     */
    private Set<String> extractTokens(String text) {
        LinkedHashSet<String> tokenSet = new LinkedHashSet<>();
        Matcher matcher = ENGLISH_WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            // 英文和数字按词提取，减少仅按字符比较带来的噪声。
            tokenSet.add(matcher.group());
        }
        for (char current : text.toCharArray()) {
            // 中文按单字提取，用一个轻量规则近似主题词分布。
            if (String.valueOf(current).matches("[\\u4e00-\\u9fa5]")) {
                tokenSet.add(String.valueOf(current));
            }
        }
        return tokenSet;
    }

    /**
     * 计算 Jaccard 相似度。
     */
    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0D;
        }

        // Jaccard = 交集 / 并集，用于衡量两组 token 是否还属于同一主题上下文。
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        return union.isEmpty() ? 0D : (double) intersection.size() / (double) union.size();
    }

    /**
     * 调用大模型执行智能切块。
     */
    private List<String> llmSplit(ChatModel chatModel, String sourceText) {
        String prompt = """
            你是 RAG 文档切块助手。
            请把下面文本切成适合知识检索的若干片段，并严格返回 JSON 数组字符串。
            要求：
            1. 每个片段尽量语义完整。
            2. 不要输出解释文字。
            3. 不要丢失原文关键信息。
            4. 返回格式示例：[\"片段1\",\"片段2\"]
            
            文本如下：
            """ + sourceText;

        try {
            // 模型返回的文本不一定完全干净，所以后面还要再抽取 JSON 数组主体。
            String content = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .user(prompt)
                .call()
                .content();

            if (!StringUtils.hasText(content)) {
                return List.of();
            }
            String jsonArray = extractJsonArray(content);
            if (!StringUtils.hasText(jsonArray)) {
                return List.of();
            }

            // 解析成字符串数组后，再把空白块过滤掉，避免污染下游 chunk 结果。
            List<String> resultList = objectMapper.readValue(jsonArray, new TypeReference<List<String>>() {
            });
            return resultList.stream().filter(StringUtils::hasText).map(String::trim).toList();
        }
        catch (Exception exception) {
            log.warn("大模型智能切块失败，回退到语义切块", exception);
            return List.of();
        }
    }

    /**
     * 截取模型输出中的 JSON 数组主体。
     */
    private String extractJsonArray(String content) {
        // 这里只做一个宽松提取：截取第一个 [ 到最后一个 ]，
        // 兼容模型偶尔包裹解释文本或 Markdown 代码块的情况。
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return content.substring(start, end + 1);
    }

    /**
     * 清洗 chunk 结果。
     */
    private List<ChunkCandidate> cleanupChunkList(List<ChunkCandidate> sourceList) {
        Map<String, ChunkCandidate> uniqueMap = new LinkedHashMap<>();
        for (ChunkCandidate candidate : sourceList) {
            if (candidate == null || !StringUtils.hasText(candidate.getText())) {
                continue;
            }
            String normalizedText = candidate.getText().trim();

            // 同一 sectionPath 下文本完全相同的 chunk 只保留一份，避免重复入库和重复召回。
            String uniqueKey = candidate.getSectionPath() + "||" + normalizedText;
            uniqueMap.putIfAbsent(uniqueKey,
                new ChunkCandidate(candidate.getSectionPath(), candidate.getPageNo(), normalizedText, candidate.getSourceType()));
        }
        return new ArrayList<>(uniqueMap.values());
    }

    /**
     * 把当前缓存中的 chunk 刷入列表。
     */
    private void flushChunk(List<ChunkCandidate> candidateList, String currentSectionPath, StringBuilder currentChunk) {
        String text = currentChunk.toString().trim();
        if (StringUtils.hasText(text)) {
            // flush 的职责只有一个：把当前缓存正文收束成正式 chunk 并清空缓存。
            candidateList.add(new ChunkCandidate(currentSectionPath, null, text, DocumentChunkSourceTypeEnum.ORIGINAL.getCode()));
        }
        currentChunk.setLength(0);
    }

    /**
     * 解析规范化后的角色。
     */
    private Integer resolveRole(Integer strategyType, List<Integer> normalizedTypes) {
        // 角色不是前端传什么就存什么，而是由最终链路顺序和策略类型自动推导。
        if (DocumentStrategyTypeEnum.STRUCTURE.getCode().equals(strategyType)) {
            return DocumentStrategyRoleEnum.PRIMARY.getCode();
        }
        if (DocumentStrategyTypeEnum.RECURSIVE.getCode().equals(strategyType)) {
            return normalizedTypes.size() == 1 ? DocumentStrategyRoleEnum.PRIMARY.getCode() : DocumentStrategyRoleEnum.FALLBACK.getCode();
        }
        if (DocumentStrategyTypeEnum.SEMANTIC.getCode().equals(strategyType)) {
            return normalizedTypes.size() == 1 ? DocumentStrategyRoleEnum.PRIMARY.getCode() : DocumentStrategyRoleEnum.OPTIMIZE.getCode();
        }
        return normalizedTypes.size() == 1 ? DocumentStrategyRoleEnum.PRIMARY.getCode() : DocumentStrategyRoleEnum.ENHANCE.getCode();
    }

    /**
     * 标题信息。
     */
    private record HeadingInfo(int level, String title) {
    }
}
