package org.javaup.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
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
import org.javaup.ai.manage.support.ParentBlockCandidate;
import org.javaup.enums.DocumentChunkSourceTypeEnum;
import org.javaup.enums.DocumentContentQualityLevelEnum;
import org.javaup.enums.DocumentFileTypeEnum;
import org.javaup.enums.DocumentStrategyPipelineTypeEnum;
import org.javaup.enums.DocumentStrategyExecuteStatusEnum;
import org.javaup.enums.DocumentStrategyRoleEnum;
import org.javaup.enums.DocumentStrategySourceTypeEnum;
import org.javaup.enums.DocumentStrategyTypeEnum;
import org.javaup.enums.DocumentStructureLevelEnum;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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
import java.util.Objects;
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

    /**
     * 当用户没有启用结构切块时，父块仍然需要保持一个稳定、可复用的大块边界。
     *
     * <p>这里故意给父块一个比 child chunk 大得多的长度上限，
     * 让它更像“回答单元”而不是“召回单元”。</p>
     */
    private static final int PARENT_BLOCK_MAX_CHARS = 2200;
    private static final int PARENT_BLOCK_OVERLAP_CHARS = 180;
    private static final int PARENT_SEMANTIC_MAX_CHARS = 1600;
    private static final int PARENT_SEMANTIC_MIN_CHARS = 480;

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
        List<String> reasonList = new ArrayList<>();
        DocumentFileTypeEnum fileType = DocumentFileTypeEnum.getRc(document.getFileType());

        /*
         * 这四个布尔值可以看成“推荐层的决策开关”。
         * 它们把 parse 阶段的结构、长度、质量指标翻译成“哪些策略值得出现在线路里”。
         */
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

        /*
         * Parent 和 Child 是两条目标完全不同的流水线：
         * - Parent 负责生成回答单元
         * - Child 负责生成召回单元
         *
         * 因此推荐阶段也不再产出一条扁平策略链，
         * 而是显式生成两条可独立确认的流水线。
         */
        List<Integer> parentStrategyTypes = new ArrayList<>();
        Map<Integer, String> parentReasonMap = new LinkedHashMap<>();
        if (structureRecommended) {
            parentStrategyTypes.add(DocumentStrategyTypeEnum.STRUCTURE.getCode());
            parentReasonMap.put(DocumentStrategyTypeEnum.STRUCTURE.getCode(),
                "检测到文档具有较明显的标题或章节结构，父块优先保留天然章节边界。");
            reasonList.add("父块流水线优先采用基于文档结构切块，保留回答阶段需要的大语义单元。");
        }
        else {
            parentStrategyTypes.add(DocumentStrategyTypeEnum.RECURSIVE.getCode());
            parentReasonMap.put(DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                "未识别出稳定结构时，父块先使用较大粒度的递归分块作为稳定回答单元。");
            reasonList.add("父块流水线未命中明显结构信号，默认使用较大粒度递归分块作为回答单元。");
        }

        List<Integer> childStrategyTypes = new ArrayList<>();
        Map<Integer, String> childReasonMap = new LinkedHashMap<>();
        if (llmRecommended) {
            childStrategyTypes.add(DocumentStrategyTypeEnum.LLM.getCode());
            childReasonMap.put(DocumentStrategyTypeEnum.LLM.getCode(),
                "文档质量偏低或结构识别不稳定，子块先使用大模型智能切块增强复杂场景。");
            reasonList.add("子块流水线追加大模型智能切块，处理低质量或结构不稳定文本。");
        }
        else if (semanticRecommended) {
            childStrategyTypes.add(DocumentStrategyTypeEnum.SEMANTIC.getCode());
            childReasonMap.put(DocumentStrategyTypeEnum.SEMANTIC.getCode(),
                "文本主题边界相对明确，子块先使用语义分块优化召回边界。");
            reasonList.add("子块流水线优先采用语义分块，优化召回边界和主题完整性。");
        }

        if (recursiveRecommended || llmRecommended || childStrategyTypes.isEmpty()) {
            childStrategyTypes.add(DocumentStrategyTypeEnum.RECURSIVE.getCode());
            childReasonMap.put(DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                "文档整体较长、存在超长段落，或需要在增强切块后追加长度兜底。");
            reasonList.add("子块流水线追加递归分块，控制召回单元长度并作为兜底。");
        }

        List<DocumentStrategyStepDraft> parentSteps = buildDraftSteps(
            DocumentStrategyPipelineTypeEnum.PARENT, parentStrategyTypes, parentReasonMap
        );
        List<DocumentStrategyStepDraft> childSteps = buildDraftSteps(
            DocumentStrategyPipelineTypeEnum.CHILD, childStrategyTypes, childReasonMap
        );

        String strategySnapshot = buildCombinedStrategySnapshot(parentSteps, childSteps);
        return new DocumentStrategyPlanDraft(strategySnapshot, String.join("；", reasonList), parentSteps, childSteps);
    }

    @Override
    public List<SuperAgentDocumentStrategyStep> normalizeSteps(SuperAgentDocumentStrategyPlan basePlan,
                                                               List<SuperAgentDocumentStrategyStep> baseSteps,
                                                               List<Integer> requestParentStrategyTypes,
                                                               List<Integer> requestChildStrategyTypes,
                                                               Long documentId) {
        /*
         * 前端提交的步骤列表不一定可靠：
         * - 可能有重复
         * - 可能有非法 strategyType
         * - 可能顺序被用户拖拽修改过
         *
         * normalizeSteps 的目标就是把这份“用户输入”变成一条最终可执行的标准化链路。
         */
        List<Integer> normalizedParentTypes = normalizePipelineTypes(requestParentStrategyTypes);
        List<Integer> normalizedChildTypes = normalizePipelineTypes(requestChildStrategyTypes);

        Map<String, Map<Integer, SuperAgentDocumentStrategyStep>> baseStepMap = new LinkedHashMap<>();
        for (SuperAgentDocumentStrategyStep baseStep : baseSteps) {
            String pipelineType = baseStep.getPipelineType();
            if (StrUtil.isBlank(pipelineType)) {
                pipelineType = DocumentStrategyPipelineTypeEnum.CHILD.getCode();
            }
            baseStepMap.computeIfAbsent(pipelineType, ignored -> new LinkedHashMap<>())
                .put(baseStep.getStrategyType(), baseStep);
        }

        List<SuperAgentDocumentStrategyStep> normalizedStepList = new ArrayList<>();
        normalizedStepList.addAll(buildNormalizedSteps(
            DocumentStrategyPipelineTypeEnum.PARENT,
            normalizedParentTypes,
            baseStepMap.getOrDefault(DocumentStrategyPipelineTypeEnum.PARENT.getCode(), Map.of()),
            documentId
        ));
        normalizedStepList.addAll(buildNormalizedSteps(
            DocumentStrategyPipelineTypeEnum.CHILD,
            normalizedChildTypes,
            baseStepMap.getOrDefault(DocumentStrategyPipelineTypeEnum.CHILD.getCode(), Map.of()),
            documentId
        ));
        return normalizedStepList;
    }

    
    /**
     * 按最终确认的策略步骤执行切块流水线。
     *
     * <p>这是文档切块的总入口，也是“策略方案”真正被执行的地方。</p>
     *
     * <p>可以把这个方法理解成一个按顺序串联起来的可组合流水线：</p>
     * <p>1. 输入是一份解析完成后的纯文本 `parsedText`。</p>
     * <p>2. 首先把整份文本视作一个原始 `ChunkCandidate`。</p>
     * <p>3. 然后按照方案中的 `stepNo` 顺序，把每个策略依次作用在当前 chunk 列表上。</p>
     * <p>4. 每执行完一步都做一次统一清洗，避免空块、重复块在后续步骤中被继续放大。</p>
     * <p>5. 最终输出一组适合后续持久化和向量化的 chunk 候选结果。</p>
     *
     * <p>这里有一个非常重要的语义：</p>
     * <p>不同策略的输入并不完全相同。</p>
     * <p>1. `RECURSIVE` / `SEMANTIC` / `LLM` 都是对“当前 chunk 列表”继续加工。</p>
     * <p>2. `STRUCTURE` 则是一个更像“重新按原文天然结构建模”的步骤，
     * 它直接基于整份 `parsedText` 重建结构块，而不是在已有块上二次切。</p>
     *
     * <p>所以从行为上讲：</p>
     * <p>1. 递归切块负责兜底控制长度。</p>
     * <p>2. 语义切块负责优化主题边界。</p>
     * <p>3. LLM 切块负责处理低质量或复杂文本。</p>
     * <p>4. 结构切块负责优先保留原文章节边界。</p>
     *
     * <p>最终返回的 `ChunkCandidate` 还不是数据库实体，
     * 只是“已经带有 sectionPath / sourceType / text 的中间结果”，
     * 后面会在索引构建服务里进一步转成 `SuperAgentDocumentChunk` 落库。</p>
     */
    @Override
    public List<ParentBlockCandidate> buildParentBlocks(SuperAgentDocument document,
                                                        SuperAgentDocumentStrategyPlan plan,
                                                        List<SuperAgentDocumentStrategyStep> steps,
                                                        String parsedText) {
        List<SuperAgentDocumentStrategyStep> parentSteps = sortPipelineSteps(steps, DocumentStrategyPipelineTypeEnum.PARENT);
        List<SuperAgentDocumentStrategyStep> childSteps = sortPipelineSteps(steps, DocumentStrategyPipelineTypeEnum.CHILD);
        if (parentSteps.isEmpty()) {
            throw new IllegalStateException("当前方案缺少父块流水线，无法生成 Parent-Child 结构。");
        }
        if (childSteps.isEmpty()) {
            throw new IllegalStateException("当前方案缺少子块流水线，无法生成 Parent-Child 结构。");
        }

        List<ChunkCandidate> parentSeedList = executePipeline(
            List.of(new ChunkCandidate("", parsedText, DocumentChunkSourceTypeEnum.ORIGINAL.getCode())),
            parentSteps,
            DocumentStrategyPipelineTypeEnum.PARENT
        );
        List<ParentBlockCandidate> parentBlockList = new ArrayList<>();
        for (ChunkCandidate parentSeed : cleanupChunkList(parentSeedList)) {
            if (parentSeed == null || StrUtil.isBlank(parentSeed.getText())) {
                continue;
            }
            List<ChunkCandidate> childSeedList = executePipeline(
                List.of(new ChunkCandidate(parentSeed.getSectionPath(), parentSeed.getText(), parentSeed.getSourceType())),
                childSteps,
                DocumentStrategyPipelineTypeEnum.CHILD
            );
            List<ChunkCandidate> finalChildren = cleanupChunkList(childSeedList);
            if (finalChildren.isEmpty()) {
                finalChildren = List.of(new ChunkCandidate(
                    parentSeed.getSectionPath(),
                    parentSeed.getText().trim(),
                    parentSeed.getSourceType()
                ));
            }

            parentBlockList.add(new ParentBlockCandidate(
                parentSeed.getSectionPath(),
                parentSeed.getText().trim(),
                parentSeed.getSourceType(),
                finalChildren
            ));
        }
        return cleanupParentBlockList(parentBlockList);
    }

    private List<DocumentStrategyStepDraft> buildDraftSteps(DocumentStrategyPipelineTypeEnum pipelineType,
                                                            List<Integer> strategyTypes,
                                                            Map<Integer, String> reasonMap) {
        List<DocumentStrategyStepDraft> draftList = new ArrayList<>();
        for (int index = 0; index < strategyTypes.size(); index++) {
            Integer strategyType = strategyTypes.get(index);
            draftList.add(new DocumentStrategyStepDraft(
                pipelineType.getCode(),
                strategyType,
                resolveRole(index, strategyType),
                DocumentStrategySourceTypeEnum.SYSTEM_RECOMMEND.getCode(),
                reasonMap.getOrDefault(strategyType, "系统为当前流水线生成的推荐步骤。")
            ));
        }
        return draftList;
    }

    private List<Integer> normalizePipelineTypes(List<Integer> requestStrategyTypes) {
        LinkedHashSet<Integer> requestTypeSet = new LinkedHashSet<>();
        for (Integer strategyType : requestStrategyTypes == null ? List.<Integer>of() : requestStrategyTypes) {
            if (DocumentStrategyTypeEnum.getRc(strategyType) != null) {
                requestTypeSet.add(strategyType);
            }
        }
        return new ArrayList<>(requestTypeSet);
    }

    private List<SuperAgentDocumentStrategyStep> buildNormalizedSteps(DocumentStrategyPipelineTypeEnum pipelineType,
                                                                      List<Integer> normalizedTypes,
                                                                      Map<Integer, SuperAgentDocumentStrategyStep> baseStepMap,
                                                                      Long documentId) {
        List<SuperAgentDocumentStrategyStep> normalizedStepList = new ArrayList<>();
        for (int index = 0; index < normalizedTypes.size(); index++) {
            Integer strategyType = normalizedTypes.get(index);
            SuperAgentDocumentStrategyStep baseStep = baseStepMap.get(strategyType);
            SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
            step.setDocumentId(documentId);
            step.setPipelineType(pipelineType.getCode());
            step.setStepNo(index + 1);
            step.setStrategyType(strategyType);
            step.setStrategyRole(resolveRole(index, strategyType));
            step.setSourceType(baseStep == null
                ? DocumentStrategySourceTypeEnum.USER_ADD.getCode()
                : DocumentStrategySourceTypeEnum.USER_KEEP.getCode());
            step.setExecuteStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode());
            step.setRecommendReason(baseStep == null ? "用户手动追加该策略。" : baseStep.getRecommendReason());
            normalizedStepList.add(step);
        }
        return normalizedStepList;
    }

    private List<SuperAgentDocumentStrategyStep> sortPipelineSteps(List<SuperAgentDocumentStrategyStep> steps,
                                                                   DocumentStrategyPipelineTypeEnum pipelineType) {
        return steps.stream()
            .filter(step -> pipelineType.getCode().equalsIgnoreCase(
                StrUtil.blankToDefault(step.getPipelineType(), DocumentStrategyPipelineTypeEnum.CHILD.getCode())
            ))
            .sorted(Comparator.comparingInt(SuperAgentDocumentStrategyStep::getStepNo))
            .toList();
    }

    private List<ChunkCandidate> executePipeline(List<ChunkCandidate> sourceList,
                                                 List<SuperAgentDocumentStrategyStep> orderedSteps,
                                                 DocumentStrategyPipelineTypeEnum pipelineType) {
        List<ChunkCandidate> currentChunks = cleanupChunkList(sourceList);
        for (SuperAgentDocumentStrategyStep step : orderedSteps) {
            DocumentStrategyTypeEnum strategyType = DocumentStrategyTypeEnum.getRc(step.getStrategyType());
            if (strategyType == null) {
                continue;
            }
            currentChunks = switch (strategyType) {
                case STRUCTURE -> applyStructureChunking(currentChunks, pipelineType);
                case RECURSIVE -> applyRecursiveChunking(currentChunks, pipelineType);
                case SEMANTIC -> applySemanticChunking(currentChunks, pipelineType);
                case LLM -> applyLlmChunking(currentChunks, pipelineType);
            };
            currentChunks = cleanupChunkList(currentChunks);
        }
        return cleanupChunkList(currentChunks);
    }

    private String buildCombinedStrategySnapshot(List<DocumentStrategyStepDraft> parentSteps,
                                                 List<DocumentStrategyStepDraft> childSteps) {
        String parentSnapshot = buildPipelineSnapshot(parentSteps.stream()
            .map(DocumentStrategyStepDraft::getStrategyType)
            .toList());
        String childSnapshot = buildPipelineSnapshot(childSteps.stream()
            .map(DocumentStrategyStepDraft::getStrategyType)
            .toList());
        return "PARENT:" + parentSnapshot + ";CHILD:" + childSnapshot;
    }

    private String buildPipelineSnapshot(List<Integer> strategyTypes) {
        return strategyTypes.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
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
        /*
         * suitableType 和结构识别阈值必须同时成立：
         * - suitableType 解决“文件类型适不适合做结构切块”
         * - structureLevel / headingCount 解决“解析结果里到底识别出了多少结构线索”
         */
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
        /*
         * 这里有意使用“或”而不是“且”：
         * 只要整篇文档很长，或者局部段落极端超长，递归切块就已经有存在价值。
         */
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
        /*
         * 这里的三个条件缺一不可：
         * - 有足够文本量，说明主题判断有基础
         * - 有足够段落数，说明不是零散短句
         * - 至少中等质量，说明语义边界判断不至于太飘
         */
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
        /*
         * LLM 切块不是“只要低质量就上”，还要求：
         * - 配置明确允许
         * - 文本长度达到最小门槛
         * 否则很容易为了很短的文本平白付出一次模型调用成本。
         */
        return Boolean.TRUE.equals(properties.getChunk().getRecommendLlmWhenLowQuality())
            && analysisResult.getContentQualityLevel().equals(DocumentContentQualityLevelEnum.LOW.getCode())
            && analysisResult.getCharCount() >= properties.getChunk().getSemanticMinChars();
    }

    /**
     * 解析标题级别和标题文本。
     *
     * <p>这个方法服务于结构切块，用来把一行疑似标题文本转换成：
     * “它是第几级标题，以及标题正文是什么”。</p>
     *
     * <p>之所以要有层级，是因为结构切块不仅要识别“这是一行标题”，
     * 还要维护类似“一级标题 > 二级标题 > 三级标题”的章节路径。</p>
     *
     * <p>当前支持的标题风格包括：</p>
     * <p>1. Markdown 风格：`#`、`##`、`###`</p>
     * <p>2. 数字编号风格：`1.`、`1.2`、`1.2.3`</p>
     * <p>3. 中文章节风格：`第一章`、`第二条`</p>
     *
     * <p>这不是一个完美的标题解析器，而是一个偏工程化的启发式实现：
     * 目标是尽可能稳定地服务于结构切块，而不是追求所有文档格式都 100% 识别正确。</p>
     */
    private HeadingInfo parseHeading(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("#")) {
            // Markdown 标题直接按 # 数量推断层级。
            /*
             * 这里不是正则一次性算出层级，而是顺着字符串往前扫连续的 #，
             * 这样面对类似“### 标题”这种最常见写法会更直接。
             */
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
            /*
             * 这里不是在解析“章节真实语义”，而是在解析“它看起来像几级标题”。
             * 对结构切块来说，层级相对稳定比绝对学术准确更重要。
             */
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
     *
     * <p>这个方法的目标不是简单按长度切，而是尽量保留文档天然的章节边界。</p>
     *
     * <p>它的工作方式是：</p>
     * <p>1. 按行扫描整个 `parsedText`。</p>
     * <p>2. 一旦识别到标题，就把前面累计的正文刷成一个 chunk。</p>
     * <p>3. 根据标题层级维护一个 `headingStack`，实时构建章节路径。</p>
     * <p>4. 后续正文都归到当前章节路径下，直到遇到下一个标题。</p>
     *
     * <p>因此它特别适合：</p>
     * <p>1. Markdown 手册</p>
     * <p>2. 规范文档</p>
     * <p>3. 有较清晰章节标题的 PDF / Word / HTML</p>
     *
     * <p>它不依赖块长阈值本身做切分，优先保留的是“结构完整性”，
     * 所以如果切出来的单块仍然过长，通常需要后续再串一个递归切块做长度兜底。</p>
     *
     * <p>如果整篇文本里一个可识别标题都没有，它会自动回退到递归切块，
     * 这样即使结构识别失败，整条索引链路也不会断掉。</p>
     */
    private List<ChunkCandidate> applyStructureChunking(String parsedText) {
        return applyStructureChunking(
            parsedText,
            DocumentStrategyPipelineTypeEnum.PARENT,
            "",
            DocumentChunkSourceTypeEnum.ORIGINAL.getCode()
        );
    }

    private List<ChunkCandidate> applyStructureChunking(List<ChunkCandidate> sourceList,
                                                        DocumentStrategyPipelineTypeEnum pipelineType) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        for (ChunkCandidate candidate : sourceList) {
            if (candidate == null || StrUtil.isBlank(candidate.getText())) {
                continue;
            }
            resultList.addAll(applyStructureChunking(
                candidate.getText(),
                pipelineType,
                candidate.getSectionPath(),
                candidate.getSourceType()
            ));
        }
        return resultList;
    }

    private List<ChunkCandidate> applyStructureChunking(String parsedText,
                                                        DocumentStrategyPipelineTypeEnum pipelineType,
                                                        String baseSectionPath,
                                                        Integer sourceType) {
        List<ChunkCandidate> candidateList = new ArrayList<>();
        Deque<String> headingStack = new ArrayDeque<>();
        StringBuilder currentChunk = new StringBuilder();
        String currentSectionPath = StrUtil.blankToDefault(baseSectionPath, "");

        for (String line : parsedText.split("\n")) {
            String trimmed = line.trim();
            if (HEADING_PATTERN.matcher(trimmed).matches()) {
                // 遇到新标题时，先把上一段正文 flush 成一个 chunk。
                // 这样能保证每个 chunk 尽量在标题边界处自然收束。
                flushChunk(candidateList, currentSectionPath, sourceType, currentChunk);
                HeadingInfo headingInfo = parseHeading(trimmed);

                // headingStack 保存当前章节路径，用来生成“一级 > 二级 > 三级”的 sectionPath。
                while (headingStack.size() >= headingInfo.level()) {
                    headingStack.removeLast();
                }
                headingStack.addLast(headingInfo.title());
                currentSectionPath = composeSectionPath(baseSectionPath, String.join(" > ", headingStack));
                currentChunk.append(trimmed).append('\n');
                continue;
            }
            /*
             * 非标题行统一累计进当前章节块。
             * 也就是说，结构切块的切分边界完全由“识别到新标题”来驱动。
            */
            currentChunk.append(line).append('\n');
        }
        flushChunk(candidateList, currentSectionPath, sourceType, currentChunk);

        if (candidateList.isEmpty()) {
            // 如果结构识别没有切出任何有效块，就自动回退到递归切块，保证链路可继续执行。
            return applyRecursiveChunking(
                List.of(new ChunkCandidate(baseSectionPath, parsedText, sourceType)),
                pipelineType
            );
        }
        return candidateList;
    }

    /**
     * 执行递归分块。
     *
     * <p>递归分块是当前策略引擎里的“长度兜底器”。</p>
     *
     * <p>它不关心你当前输入块是来自结构切块、语义切块还是原始文本，
     * 它只关心一件事：当前块是不是太长了。</p>
     *
     * <p>执行方式是：</p>
     * <p>1. 遍历当前 chunk 列表。</p>
     * <p>2. 对每个 chunk 的正文调用 `recursiveSplit`。</p>
     * <p>3. 保留原块的 `sectionPath / sourceType`，只替换正文内容。</p>
     *
     * <p>因此它更像是一个“通用后处理器”，
     * 可以接在结构切块之后，也可以单独作为主策略使用。</p>
     *
     * <p>当前版本还支持 overlap：
     * 当自然边界切分完成后，会在相邻块之间补一段前文尾部，
     * 以减轻关键信息刚好落在边界附近导致的上下文断裂。</p>
     */
    private List<ChunkCandidate> applyRecursiveChunking(List<ChunkCandidate> sourceList) {
        return applyRecursiveChunking(sourceList, DocumentStrategyPipelineTypeEnum.CHILD);
    }

    private List<ChunkCandidate> applyRecursiveChunking(List<ChunkCandidate> sourceList,
                                                        DocumentStrategyPipelineTypeEnum pipelineType) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        int maxChars = resolveRecursiveMaxChars(pipelineType);
        int overlapChars = resolveRecursiveOverlap(maxChars, pipelineType);
        for (ChunkCandidate candidate : sourceList) {
            /*
             * 递归切块不会改 sectionPath / sourceType，
             * 只会把原有正文拆成多个更短片段。
             * 这样前面策略已经识别出来的结构信息，能继续被后面的块继承。
             */
            // 每个输入 chunk 都可能继续被拆成多个更短片段。
            List<String> splitTextList = recursiveSplit(candidate.getText(), maxChars, overlapChars);
            for (String splitText : splitTextList) {
                resultList.add(new ChunkCandidate(candidate.getSectionPath(), splitText, candidate.getSourceType()));
            }
        }
        return resultList;
    }

    /**
     * 执行语义分块。
     *
     * <p>语义分块的目标不是“尽量切短”，而是“尽量在主题边界处切”。</p>
     *
     * <p>它会把当前 chunk 当成一段连续文本，先拆成句子，
     * 再根据句子与当前块上下文的相似度，以及长度阈值，决定是否在这里断开。</p>
     *
     * <p>和递归切块相比，语义切块更强调内容完整性和主题连贯性，
     * 所以它通常适合作为结构/递归之后的优化步骤，而不是所有场景下的唯一主策略。</p>
     *
     * <p>这里还带了一个保护逻辑：
     * 如果某个 chunk 本身已经很短，或者没有有效文本，就不再做语义切分，
     * 直接原样保留，避免切得过碎。</p>
     */
    private List<ChunkCandidate> applySemanticChunking(List<ChunkCandidate> sourceList) {
        return applySemanticChunking(sourceList, DocumentStrategyPipelineTypeEnum.CHILD);
    }

    private List<ChunkCandidate> applySemanticChunking(List<ChunkCandidate> sourceList,
                                                       DocumentStrategyPipelineTypeEnum pipelineType) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        int semanticMinChars = resolveSemanticMinChars(pipelineType);
        for (ChunkCandidate candidate : sourceList) {
            if (StrUtil.isBlank(candidate.getText())
                || candidate.getText().length() <= semanticMinChars) {
                // 太短的文本没必要再做语义切分，直接保留原块。
                resultList.add(candidate);
                continue;
            }
            /*
             * 只有足够长的块才值得继续做语义切分。
             * 太短的块再切只会让检索粒度过碎，通常得不偿失。
             */
            resultList.addAll(semanticSplit(candidate, pipelineType));
        }
        return resultList;
    }

    /**
     * 执行大模型智能切块。
     *
     * <p>如果当前环境未开启 LLM 切块，或者模型调用失败，
     * 则自动回退到语义分块，保证索引链路仍然能走通。</p>
     *
     * <p>它适合解决的是“传统规则切分不太稳定”的场景，例如：</p>
     * <p>1. 文本结构很差</p>
     * <p>2. 段落边界混乱</p>
     * <p>3. OCR 后文本噪声较多</p>
     *
     * <p>执行方式是：</p>
     * <p>1. 先检查当前是否真的启用了 LLM 切块以及是否存在模型。</p>
     * <p>2. 对每个输入 chunk，如果过长，则先递归预切成多个子片段。</p>
     * <p>3. 逐片调用 `llmSplit` 获取模型建议的切块结果。</p>
     * <p>4. 如果模型返回异常、空结果或不可解析结果，则对该片段回退到语义切块。</p>
     *
     * <p>因此它是“增强型策略”，而不是一条必须成功的硬链路。</p>
     */
    private List<ChunkCandidate> applyLlmChunking(List<ChunkCandidate> sourceList) {
        return applyLlmChunking(sourceList, DocumentStrategyPipelineTypeEnum.CHILD);
    }

    private List<ChunkCandidate> applyLlmChunking(List<ChunkCandidate> sourceList,
                                                  DocumentStrategyPipelineTypeEnum pipelineType) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (!Boolean.TRUE.equals(properties.getChunk().getLlmEnabled()) || chatModel == null) {
            // LLM 没开或当前环境没有模型时，直接退化为语义切块。
            return applySemanticChunking(sourceList, pipelineType);
        }

        List<ChunkCandidate> resultList = new ArrayList<>();
        for (ChunkCandidate candidate : sourceList) {
            if (StrUtil.isBlank(candidate.getText())) {
                continue;
            }

            /*
             * 超长文本不会直接整段扔给模型，而是先递归预切。
             * 这是为了控制单次 prompt 大小，避免一段超长文本把 LLM 切块本身变成新的不稳定因素。
             */
            // 为了控制单次 prompt 大小，超长文本会先递归切成多个子片段再逐段给模型。
            int llmMaxChars = resolveLlmMaxChars(pipelineType);
            List<String> sourceTextList = candidate.getText().length() > llmMaxChars
                ? recursiveSplit(candidate.getText(), llmMaxChars, 0)
                : List.of(candidate.getText());

            for (String sourceText : sourceTextList) {
                List<String> llmChunkList = llmSplit(chatModel, sourceText);
                if (llmChunkList.isEmpty()) {
                    // 模型切块失败时继续回退到语义切块，保证不中断主流程。
                    /*
                     * 这里不是简单丢弃失败片段，而是立即回退到 semanticSplit。
                     * 这样 LLM 只承担增强职责，不会因为失败把整条索引链路卡死。
                     */
                    resultList.addAll(semanticSplit(new ChunkCandidate(candidate.getSectionPath(), sourceText, candidate.getSourceType()), pipelineType));
                    continue;
                }
                for (String llmChunk : llmChunkList) {
                    resultList.add(new ChunkCandidate(candidate.getSectionPath(), llmChunk, candidate.getSourceType()));
                }
            }
        }
        return resultList;
    }

    /**
     * 基于句子级别相似度做语义切分。
     *
     * <p>这是 `applySemanticChunking` 的核心实现。</p>
     *
     * <p>它的思路是：</p>
     * <p>1. 先把一段文本切成句子。</p>
     * <p>2. 逐句往当前块里累加。</p>
     * <p>3. 每加入一句，都同时检查两件事：</p>
     * <p>   a. 当前块长度是否快超上限。</p>
     * <p>   b. 当前句与前文主题是否已经明显不相似。</p>
     * <p>4. 只要命中其中任一条件，就把当前累计内容收束成一个 chunk。</p>
     *
     * <p>这里用的是轻量版语义近似：
     * 不是调 embedding 模型算相似度，而是从句子里抽 token，算 Jaccard 相似度。
     * 这样计算成本低，适合在索引构建阶段大批量执行。</p>
     */
    private List<ChunkCandidate> semanticSplit(ChunkCandidate candidate,
                                               DocumentStrategyPipelineTypeEnum pipelineType) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        List<String> sentenceList = splitSentences(candidate.getText());
        if (sentenceList.size() <= 1) {
            // 如果连句子都拆不出来，说明文本太短或结构太平，直接原样返回更稳妥。
            resultList.add(candidate);
            return resultList;
        }

        StringBuilder currentChunk = new StringBuilder();
        Set<String> currentTokenSet = new LinkedHashSet<>();
        int semanticMinChars = resolveSemanticMinChars(pipelineType);
        int semanticMaxChars = resolveSemanticMaxChars(pipelineType);

        for (String sentence : sentenceList) {
            /*
             * 当前实现不是用 embedding 相似度，而是用轻量 token 集合近似。
             * 原因是索引构建阶段可能要处理大量文本，Jaccard 的成本更低，更适合批量跑。
            */
            Set<String> sentenceTokenSet = extractTokens(sentence);

            // 语义切块同时考虑两个条件：
            // 1. 当前块是否超过最大长度
            // 2. 当前句与已有内容的主题相似度是否明显下降
            boolean exceedMaxChars = currentChunk.length() + sentence.length() > semanticMaxChars;
            double similarity = currentTokenSet.isEmpty() ? 1D : jaccard(currentTokenSet, sentenceTokenSet);
            boolean semanticBreak = currentChunk.length() >= semanticMinChars
                && similarity < properties.getChunk().getSemanticSimilarityThreshold();

            if (currentChunk.length() > 0 && (exceedMaxChars || semanticBreak)) {
                /*
                 * 一旦长度超阈值，或当前句和已有内容的主题相似度明显下降，
                 * 就说明应该在这里收束当前 chunk，开始新的主题块。
                 */
                // 一旦触发切分条件，就把当前累计内容收敛成一个 chunk。
                resultList.add(new ChunkCandidate(candidate.getSectionPath(), currentChunk.toString().trim(), candidate.getSourceType()));
                currentChunk.setLength(0);
                currentTokenSet.clear();
            }

            currentChunk.append(sentence);
            currentTokenSet.addAll(sentenceTokenSet);
        }

        if (currentChunk.length() > 0) {
            resultList.add(new ChunkCandidate(candidate.getSectionPath(), currentChunk.toString().trim(), candidate.getSourceType()));
        }
        return resultList;
    }

    /**
     * 递归拆分超长文本。
     *
     * <p>这是递归切块真正决定“怎么切”的核心方法。</p>
     *
     * <p>它遵循的是“先尊重自然边界，再退化到硬切”的原则：</p>
     * <p>1. 优先按段落切</p>
     * <p>2. 不行再按行切</p>
     * <p>3. 再不行按句子切</p>
     * <p>4. 最后才退化成固定长度滑动窗口</p>
     *
     * <p>这样做的目的是：
     * 在尽量不破坏语义边界的前提下，把每个 chunk 控制在目标长度以内。</p>
     *
     * <p>这里的 overlap 参数只服务于递归切块这一路，
     * 它不会影响结构切块和语义切块本身的判断逻辑。</p>
     */
    private List<String> recursiveSplit(String text, int maxChars, int overlapChars) {
        String trimmed = text == null ? "" : text.trim();
        if (StrUtil.isBlank(trimmed)) {
            return List.of();
        }
        if (trimmed.length() <= maxChars) {
            /*
             * 只要当前文本本身已经在目标长度之内，就不再继续往下递归。
             * 否则会出现“本来已经合适的块也被多余地拆细”的问题。
             */
            return List.of(trimmed);
        }

        // 递归拆分遵循“先粗后细”的原则：段落 -> 行 -> 句子 -> 固定窗口。
        List<String> paragraphList = splitByRegex(trimmed, "\\n\\s*\\n");
        if (paragraphList.size() > 1) {
            return mergeAndSplit(paragraphList, maxChars, overlapChars);
        }

        List<String> lineList = splitByRegex(trimmed, "\\n");
        if (lineList.size() > 1) {
            return mergeAndSplit(lineList, maxChars, overlapChars);
        }

        List<String> sentenceList = splitSentences(trimmed);
        if (sentenceList.size() > 1) {
            return mergeAndSplit(sentenceList, maxChars, overlapChars);
        }

        List<String> fixedWindowList = new ArrayList<>();
        int start = 0;
        int step = Math.max(1, maxChars - overlapChars);
        while (start < trimmed.length()) {
            // 当前面所有语义边界都不可用时，最后才退化成固定窗口硬切。
            // 这里直接使用滑动窗口，把 overlap 真实体现在相邻块之间。
            int end = Math.min(trimmed.length(), start + maxChars);
            fixedWindowList.add(trimmed.substring(start, end).trim());
            if (end >= trimmed.length()) {
                break;
            }
            /*
             * 这里的步长不是 maxChars，而是 maxChars - overlapChars。
             * 这样下一块会自然和前一块保留一段重叠上下文。
             */
            start += step;
        }
        return fixedWindowList;
    }

    /**
     * 把一组片段先合并到目标长度附近，再递归兜底拆分。
     *
     * <p>这个方法解决的问题是：
     * 某一层自然边界切出来以后，可能会出现“很多小片段”和“少数超大片段”混在一起。</p>
     *
     * <p>它的处理策略是：</p>
     * <p>1. 对于长度合适的片段，尽量往当前块里合并，避免切得过碎。</p>
     * <p>2. 对于单个自身就超长的片段，再次递归调用 `recursiveSplit` 往下细拆。</p>
     * <p>3. 全部合并完成后，再统一给结果补 overlap。</p>
     *
     * <p>所以它可以看作“分层切分时的桥接器”：
     * 上游负责按自然边界拆，下游负责把这些边界结果整形成适合索引的最终块。</p>
     */
    private List<String> mergeAndSplit(List<String> segmentList, int maxChars, int overlapChars) {
        List<String> rawResultList = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String segment : segmentList) {
            String trimmed = segment.trim();
            if (StrUtil.isBlank(trimmed)) {
                continue;
            }

            if (trimmed.length() > maxChars) {
                // 单个片段本身过长时，不能直接加入，需要再次递归细拆。
                /*
                 * 这里先把 current flush 掉再递归，是为了避免：
                 * 一个超长片段出现时，把前面已经累计好的合理片段也一起打散。
                 */
                if (current.length() > 0) {
                    rawResultList.add(current.toString().trim());
                    current.setLength(0);
                }
                rawResultList.addAll(recursiveSplit(trimmed, maxChars, overlapChars));
                continue;
            }

            if (current.length() + trimmed.length() + 1 > maxChars) {
                // 当前缓存即将超长时，先落一个结果块，再继续累计后面的片段。
                /*
                 * 这里的 +1 不是随意写的，它代表片段之间插入的换行符成本。
                 * 否则合并后真实长度会比判断时多出一个字符。
                 */
                rawResultList.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(trimmed).append('\n');
        }

        if (current.length() > 0) {
            rawResultList.add(current.toString().trim());
        }
        return applyOverlap(rawResultList, maxChars, overlapChars);
    }

    /**
     * 给自然边界切出的 chunk 补上上下文 overlap。
     *
     * <p>这里不会强行打破原有段落/句子边界重新切块，
     * 而是在后一块前面尽量拼接上一块的尾部片段，兼顾自然边界和上下文连续性。</p>
     *
     * <p>也就是说，它追求的是一种折中：</p>
     * <p>1. 仍然保留前面递归切块已经得到的自然边界。</p>
     * <p>2. 但不让相邻块之间完全断开。</p>
     *
     * <p>这样通常比“纯自然边界、完全无 overlap”更稳，
     * 又比“完全固定窗口滑动切”更保留结构完整性。</p>
     */
    private List<String> applyOverlap(List<String> rawChunkList, int maxChars, int overlapChars) {
        if (rawChunkList.isEmpty() || overlapChars <= 0) {
            return rawChunkList;
        }

        List<String> overlappedChunkList = new ArrayList<>(rawChunkList.size());
        for (int index = 0; index < rawChunkList.size(); index++) {
            String current = rawChunkList.get(index);
            if (StrUtil.isBlank(current)) {
                continue;
            }
            if (index == 0) {
                /*
                 * 第一块没有前文，自然也就没有 overlap 来源，直接原样保留。
                 */
                overlappedChunkList.add(current);
                continue;
            }

            String previous = rawChunkList.get(index - 1);
            String overlapPrefix = buildOverlapPrefix(previous, current, maxChars, overlapChars);
            if (StrUtil.isNotBlank(overlapPrefix)) {
                overlappedChunkList.add(overlapPrefix + "\n" + current);
            }
            else {
                overlappedChunkList.add(current);
            }
        }
        return overlappedChunkList;
    }

    /**
     * 从上一块尾部截取一段文本，拼到当前块前面作为 overlap。
     *
     * <p>这里有两个约束：</p>
     * <p>1. overlap 不能超过配置上限。</p>
     * <p>2. overlap 也不能大到把当前块撑爆 `maxChars`。</p>
     *
     * <p>因此它返回的是一个“尽可能保留前文上下文、但又不破坏当前块长度约束”的前缀。</p>
     */
    private String buildOverlapPrefix(String previous, String current, int maxChars, int overlapChars) {
        if (StrUtil.isBlank(previous) || StrUtil.isBlank(current)) {
            return "";
        }

        int allowedChars = Math.min(overlapChars, Math.max(0, maxChars - current.length() - 1));
        if (allowedChars <= 0) {
            return "";
        }

        String suffix = previous.length() <= allowedChars
            ? previous
            : previous.substring(previous.length() - allowedChars);
        return suffix.trim();
    }

    /**
     * 解析递归分块 overlap 配置，并保证不会大于 chunkSize。
     *
     * <p>这个方法的职责是把配置收敛成一个安全值，避免出现：</p>
     * <p>1. overlap 为负数或 null</p>
     * <p>2. overlap 大于等于 chunkSize，导致滑动窗口无法前进</p>
     */
    private int resolveRecursiveOverlap(int maxChars) {
        return resolveRecursiveOverlap(maxChars, DocumentStrategyPipelineTypeEnum.CHILD);
    }

    private int resolveRecursiveOverlap(int maxChars, DocumentStrategyPipelineTypeEnum pipelineType) {
        if (pipelineType == DocumentStrategyPipelineTypeEnum.PARENT) {
            return Math.min(PARENT_BLOCK_OVERLAP_CHARS, Math.max(0, maxChars - 1));
        }
        Integer configuredOverlap = properties.getChunk().getRecursiveOverlapChars();
        if (configuredOverlap == null || configuredOverlap <= 0) {
            return 0;
        }
        /*
         * overlap 绝不能大于等于 maxChars，否则滑动窗口就无法前进。
         * 所以这里最后还会和 maxChars - 1 再比较一次，收敛成安全值。
         */
        return Math.min(configuredOverlap, Math.max(0, maxChars - 1));
    }

    private int resolveRecursiveMaxChars(DocumentStrategyPipelineTypeEnum pipelineType) {
        return pipelineType == DocumentStrategyPipelineTypeEnum.PARENT
            ? PARENT_BLOCK_MAX_CHARS
            : properties.getChunk().getRecursiveMaxChars();
    }

    private int resolveSemanticMaxChars(DocumentStrategyPipelineTypeEnum pipelineType) {
        return pipelineType == DocumentStrategyPipelineTypeEnum.PARENT
            ? Math.max(PARENT_SEMANTIC_MAX_CHARS, properties.getChunk().getSemanticMaxChars())
            : properties.getChunk().getSemanticMaxChars();
    }

    private int resolveSemanticMinChars(DocumentStrategyPipelineTypeEnum pipelineType) {
        return pipelineType == DocumentStrategyPipelineTypeEnum.PARENT
            ? Math.max(PARENT_SEMANTIC_MIN_CHARS, properties.getChunk().getSemanticMinChars())
            : properties.getChunk().getSemanticMinChars();
    }

    private int resolveLlmMaxChars(DocumentStrategyPipelineTypeEnum pipelineType) {
        return pipelineType == DocumentStrategyPipelineTypeEnum.PARENT
            ? Math.max(properties.getChunk().getLlmMaxChars(), PARENT_BLOCK_MAX_CHARS)
            : properties.getChunk().getLlmMaxChars();
    }

    /**
     * 根据正则拆分文本，并移除空白项。
     *
     * <p>这是递归切块内部最基础的拆分辅助方法。
     * 它只负责按给定边界拆开文本，并过滤掉空结果，
     * 不负责长度控制，也不负责 overlap。</p>
     */
    private List<String> splitByRegex(String text, String regex) {
        return Arrays.stream(text.split(regex))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    /**
     * 按句子拆分文本。
     *
     * <p>这是递归切块和语义切块都会依赖的公共能力。</p>
     *
     * <p>当前规则偏工程化，主要针对中文标点和常见英文句号做简单断句。
     * 它不是专业句法分析器，但已经足够支撑当前的 chunking 场景。</p>
     */
    private List<String> splitSentences(String text) {
        return Arrays.stream(text.split("(?<=[。！？!?；;\\.])"))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    /**
     * 提取简单 token，用于做句子级相似度比较。
     *
     * <p>这里不追求语言学上的高精度分词，
     * 而是为了给 `semanticSplit` 提供一个轻量、可批量执行的相似度基础。</p>
     *
     * <p>策略是：</p>
     * <p>1. 英文和数字按“词”抽取。</p>
     * <p>2. 中文按“单字”抽取。</p>
     *
     * <p>这种做法虽然粗糙，但成本低、可解释，而且在主题边界粗判断里通常够用。</p>
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
        /*
         * 这里用 LinkedHashSet，一方面自动去重，另一方面还能保留原始出现顺序，
         * 方便调试时观察 token 抽取结果更接近原文阅读顺序。
         */
        return tokenSet;
    }

    /**
     * 计算 Jaccard 相似度。
     *
     * <p>这里的输入不是原始句子，而是已经抽取好的 token 集合。
     * 通过比较“交集 / 并集”的比例，估计两句文本是否仍然处在相近主题下。</p>
     *
     * <p>它不如 embedding 相似度精细，但足够快，适合在索引构建链路里大批量运行。</p>
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
     *
     * <p>这是 LLM 切块链路里真正和模型交互的地方。</p>
     *
     * <p>它的约束非常明确：</p>
     * <p>1. 必须只返回 JSON 数组。</p>
     * <p>2. 每个片段尽量语义完整。</p>
     * <p>3. 不能丢掉原文关键信息。</p>
     *
     * <p>调用成功后，它只返回“文本片段列表”，
     * 不负责包装成 `ChunkCandidate`，也不负责去重和清洗。</p>
     *
     * <p>调用失败时，它不会抛异常中断整条索引链路，
     * 而是返回空列表，让上层统一回退到语义切块。</p>
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

            if (StrUtil.isBlank(content)) {
                return List.of();
            }
            String jsonArray = extractJsonArray(content);
            if (StrUtil.isBlank(jsonArray)) {
                return List.of();
            }

            /*
             * 这里只接受“能被解析成 JSON 数组的字符串片段列表”。
             * 只要模型开始输出解释文字、Markdown 代码块或非法结构，就统一回退到空列表。
             */
            // 解析成字符串数组后，再把空白块过滤掉，避免污染下游 chunk 结果。
            List<String> resultList = objectMapper.readValue(jsonArray, new TypeReference<List<String>>() {
            });
            return resultList.stream().filter(StrUtil::isNotBlank).map(String::trim).toList();
        }
        catch (Exception exception) {
            log.warn("大模型智能切块失败，回退到语义切块", exception);
            return List.of();
        }
    }

    /**
     * 截取模型输出中的 JSON 数组主体。
     *
     * <p>现实里模型经常不会老老实实只返回纯 JSON，
     * 可能会包裹解释文字、Markdown 代码块，或者前后加一些说明。</p>
     *
     * <p>这里做的是一个宽松容错：
     * 只要输出里存在一个看起来像数组的主体，就尽量截出来交给 JSON 解析器继续处理。</p>
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
     *
     * <p>这是所有切块策略执行完之后的统一收口步骤。</p>
     *
     * <p>它主要做三件事：</p>
     * <p>1. 过滤空块</p>
     * <p>2. 去掉文本两端空白</p>
     * <p>3. 按 `sectionPath + text` 去重</p>
     *
     * <p>这样做的原因是：
     * 多策略串联后，尤其是“结构 -> 递归 -> 语义”这种链路，很容易产生重复块或空块，
     * 如果不统一收口，后面 chunk 落库和检索都会被噪声放大。</p>
     */
    private List<ChunkCandidate> cleanupChunkList(List<ChunkCandidate> sourceList) {
        Map<String, ChunkCandidate> uniqueMap = new LinkedHashMap<>();
        for (ChunkCandidate candidate : sourceList) {
            if (candidate == null || StrUtil.isBlank(candidate.getText())) {
                continue;
            }
            String normalizedText = candidate.getText().trim();

            /*
             * 当前去重键采用“sectionPath + 正文”。
             * 这意味着：
             * - 同章节下完全相同的正文会被合并
             * - 不同章节下即使正文一样，也仍然视作两个不同块保留
             */
            // 同一 sectionPath 下文本完全相同的 chunk 只保留一份，避免重复入库和重复召回。
            String uniqueKey = candidate.getSectionPath() + "||" + normalizedText;
            uniqueMap.putIfAbsent(uniqueKey,
                new ChunkCandidate(candidate.getSectionPath(), normalizedText, candidate.getSourceType()));
        }
        return new ArrayList<>(uniqueMap.values());
    }

    private List<ParentBlockCandidate> cleanupParentBlockList(List<ParentBlockCandidate> sourceList) {
        Map<String, ParentBlockCandidate> uniqueMap = new LinkedHashMap<>();
        for (ParentBlockCandidate candidate : sourceList) {
            if (candidate == null || StrUtil.isBlank(candidate.getText())) {
                continue;
            }
            String normalizedText = candidate.getText().trim();
            String uniqueKey = candidate.getSectionPath() + "||" + normalizedText;
            uniqueMap.putIfAbsent(uniqueKey, new ParentBlockCandidate(
                candidate.getSectionPath(),
                normalizedText,
                candidate.getSourceType(),
                candidate.getChildChunks() == null ? List.of() : new ArrayList<>(candidate.getChildChunks())
            ));
        }
        return new ArrayList<>(uniqueMap.values());
    }

    /**
     * 把当前缓存中的 chunk 刷入列表。
     *
     * <p>这是结构切块里的小工具方法。
     * 当扫描到新标题，或者扫描结束时，就会调用它把当前累计正文正式收束成一个块。</p>
     *
     * <p>它不会做复杂判断，只负责：</p>
     * <p>1. 如果当前缓存有正文，就生成一个 `ChunkCandidate`。</p>
     * <p>2. 继承当前章节路径。</p>
     * <p>3. 清空缓存，准备继续累计下一段正文。</p>
     */
    private void flushChunk(List<ChunkCandidate> candidateList,
                            String currentSectionPath,
                            Integer sourceType,
                            StringBuilder currentChunk) {
        String text = currentChunk.toString().trim();
        if (StrUtil.isNotBlank(text)) {
            // flush 的职责只有一个：把当前缓存正文收束成正式 chunk 并清空缓存。
            candidateList.add(new ChunkCandidate(
                currentSectionPath,
                text,
                sourceType == null ? DocumentChunkSourceTypeEnum.ORIGINAL.getCode() : sourceType
            ));
        }
        currentChunk.setLength(0);
    }

    private String composeSectionPath(String baseSectionPath, String currentSectionPath) {
        String normalizedBase = StrUtil.blankToDefault(baseSectionPath, "").trim();
        String normalizedCurrent = StrUtil.blankToDefault(currentSectionPath, "").trim();
        if (StrUtil.isBlank(normalizedBase)) {
            return normalizedCurrent;
        }
        if (StrUtil.isBlank(normalizedCurrent)) {
            return normalizedBase;
        }
        return normalizedBase + " > " + normalizedCurrent;
    }

    /**
     * 解析规范化后的角色。
     */
    private Integer resolveRole(int index, Integer strategyType) {
        if (index == 0) {
            return DocumentStrategyRoleEnum.PRIMARY.getCode();
        }
        if (DocumentStrategyTypeEnum.RECURSIVE.getCode().equals(strategyType)) {
            return DocumentStrategyRoleEnum.FALLBACK.getCode();
        }
        if (DocumentStrategyTypeEnum.SEMANTIC.getCode().equals(strategyType)) {
            return DocumentStrategyRoleEnum.OPTIMIZE.getCode();
        }
        if (DocumentStrategyTypeEnum.LLM.getCode().equals(strategyType)) {
            return DocumentStrategyRoleEnum.ENHANCE.getCode();
        }
        return DocumentStrategyRoleEnum.OPTIMIZE.getCode();
    }

    /**
     * 标题信息。
     */
    private record HeadingInfo(int level, String title) {
    }
}
