package org.javaup.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.service.DocumentStrategyService;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.javaup.ai.manage.support.ChunkCandidate;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentLineClassifier;
import org.javaup.ai.manage.support.DocumentStrategyPlanDraft;
import org.javaup.ai.manage.support.DocumentStrategyStepDraft;
import org.javaup.ai.manage.support.ParentBlockCandidate;
import org.javaup.ai.prompt.PromptTemplateNames;
import org.javaup.ai.prompt.PromptTemplateService;
import org.javaup.enums.DocumentChunkSourceTypeEnum;
import org.javaup.enums.DocumentContentQualityLevelEnum;
import org.javaup.enums.DocumentFileTypeEnum;
import org.javaup.enums.DocumentStrategyExecuteStatusEnum;
import org.javaup.enums.DocumentStrategyPipelineTypeEnum;
import org.javaup.enums.DocumentStrategyRoleEnum;
import org.javaup.enums.DocumentStrategySourceTypeEnum;
import org.javaup.enums.DocumentStrategyTypeEnum;
import org.javaup.enums.DocumentStructureLevelEnum;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/

/*
分块策略
四种分块策略：
策略                  枚举值                 说明
结构分块            STRUCTURE               按标题层级切分，适合结构清晰的文档
递归分块            RECURSIVE               按段落/行/句子/固定窗口逐级切分，通用兜底
语义分块            SEMANTIC                按 Jaccard 相似度检测话题边界，适合主题明确的文档
LLM分块            LLM                     调用大模型判断分块边界，适合质量要求高的场景
 */

@Slf4j
@AllArgsConstructor
@Service
public class DocumentStrategyServiceImpl implements DocumentStrategyService {

    private static final Pattern ENGLISH_WORD_PATTERN = Pattern.compile("[A-Za-z0-9]{2,}");


    /*
    父子双层分块
    层级                  用途                  特点
    父块（parent）       回答时提供完整的上下文     较大（-2200字符），有重叠
    子块（Child）        检索时精准匹配            较小，从父块中进一步的切分
     */
    private static final int PARENT_BLOCK_MAX_CHARS = 2200;//父块最大字符数
    private static final int PARENT_BLOCK_OVERLAP_CHARS = 180;//父块之间的重叠字符数
    private static final int PARENT_SEMANTIC_MAX_CHARS = 1600;//语义分块的父块上限
    private static final int PARENT_SEMANTIC_MIN_CHARS = 480;//语义分块的父块下限

    private final DocumentManageProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final DocumentLineClassifier documentLineClassifier;
    private final DocumentStructureNodeService structureNodeService;
    private final PromptTemplateService promptTemplateService;




    /*
    策略推荐逻辑（策略推荐核心方法）：这个方法在 DocumentStrategyServiceImpl 里，它不执行切块，而是回答一个更上游的问题："对于这份文档，后续索引构建时应该采用怎样的 Parent/Child 切块流水线？"-----四个基础判断
    recommendStrategy（）会根据文档特征自动推荐
    有标题结构 → 父块用 STRUCTURE
    无标题但质量高 → 子块用 SEMANTIC
    质量低 → 子块用 LLM
    兜底 → RECURSIVE
     */
    @Override
    public DocumentStrategyPlanDraft recommendStrategy(SuperAgentDocument document, DocumentAnalysisResult analysisResult) {

        List<String> reasonList = new ArrayList<>();
        // fileType 会参与结构切块判断，因为并不是所有文件格式都天然适合结构识别。
        DocumentFileTypeEnum fileType = DocumentFileTypeEnum.getRc(document.getFileType());

        // 这四个布尔判断是后续整套推荐的"基础事实层"。
        // 它们并不直接生成步骤，而是回答"这份文档在切块上有哪些客观特征和风险"。四个基础判断
        boolean structureRecommended = shouldUseStructure(fileType, analysisResult);
        boolean recursiveRecommended = shouldUseRecursive(analysisResult);
        boolean semanticRecommended = shouldUseSemantic(analysisResult);
        boolean llmRecommended = shouldUseLlm(analysisResult);



        /*
        父块流水线决策

         */
        List<Integer> parentStrategyTypes = new ArrayList<>();
        Map<Integer, String> parentReasonMap = new LinkedHashMap<>();
        if (structureRecommended) {
            // 父块更偏向保留"章节级别"的大语义单元，所以只要结构信号明显，就优先保留天然章节边界。
            parentStrategyTypes.add(DocumentStrategyTypeEnum.STRUCTURE.getCode());
            parentReasonMap.put(DocumentStrategyTypeEnum.STRUCTURE.getCode(),
                "检测到文档具有较明显的标题或章节结构，父块优先保留天然章节边界。");
            reasonList.add("父块流水线优先采用基于文档结构切块，保留回答阶段需要的大语义单元。");
        }
        else {
            // 如果结构信号不足，继续强行做结构切块往往只会切出不稳定的边界，
            // 因此父块退回到大粒度递归分块，用长度控制来保证"至少有稳定的大块可用"。
            parentStrategyTypes.add(DocumentStrategyTypeEnum.RECURSIVE.getCode());
            parentReasonMap.put(DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                "未识别出稳定结构时，父块先使用较大粒度的递归分块作为稳定回答单元。");
            reasonList.add("父块流水线未命中明显结构信号，默认使用较大粒度递归分块作为回答单元。");
        }



        /*
        子块流水线决策
        子块的决策稍微复杂一些，是一个优先级链：

1.质量差 → 先上 LLM 智能切块
2.质量还行、段落丰富 → 用语义切块
3.不管前面选了什么，只要文本够长、或者用了 LLM、或者前面啥都没选 → 追加递归分块做长度兜底
所以子块流水线可能是单步的（只有 RECURSIVE），也可能是两步的（LLM + RECURSIVE 或 SEMANTIC + RECURSIVE）。
         */
        List<Integer> childStrategyTypes = new ArrayList<>();
        Map<Integer, String> childReasonMap = new LinkedHashMap<>();
        if (llmRecommended) {
            //子块更偏向召回单元，文本质量差时最怕把噪声、断句错误、结构混乱直接带进向量库；
            // 因此如果满足 LLM 条件，优先用 LLM 先做一次更智能的边界增强。
            childStrategyTypes.add(DocumentStrategyTypeEnum.LLM.getCode());
            childReasonMap.put(DocumentStrategyTypeEnum.LLM.getCode(),
                "文档质量偏低或结构识别不稳定，子块先使用大模型智能切块增强复杂场景。");
            reasonList.add("子块流水线追加大模型智能切块，处理低质量或结构不稳定文本。");
        }
        else if (semanticRecommended) {
            // 如果文本质量尚可、段落足够丰富，就让子块优先按主题边界拆分，
            // 这样做出的检索块通常比纯长度切分更适合召回。
            childStrategyTypes.add(DocumentStrategyTypeEnum.SEMANTIC.getCode());
            childReasonMap.put(DocumentStrategyTypeEnum.SEMANTIC.getCode(),
                "文本主题边界相对明确，子块先使用语义分块优化召回边界。");
            reasonList.add("子块流水线优先采用语义分块，优化召回边界和主题完整性。");
        }

        if (recursiveRecommended || llmRecommended || childStrategyTypes.isEmpty()) {
            // 递归分块在子块流水线里承担"长度约束兜底"的角色：
            // 1. 文本本来就很长，需要控制 chunk 大小；
            // 2. 前面加了 LLM 后，仍然需要一个长度兜底步骤；
            // 3. 如果语义/LLM 都没命中，递归分块至少能保证产出稳定的检索块。
            childStrategyTypes.add(DocumentStrategyTypeEnum.RECURSIVE.getCode());
            childReasonMap.put(DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                "文档整体较长、存在超长段落，或需要在增强切块后追加长度兜底。");
            reasonList.add("子块流水线追加递归分块，控制召回单元长度并作为兜底。");
        }


        /*
        打包成策略草案
// 这里把"推荐出的策略类型"转成真正可落库展示的步骤草案，
// 包含 pipelineType、role、reason 等信息。
         */
        List<DocumentStrategyStepDraft> parentSteps = buildDraftSteps(
            DocumentStrategyPipelineTypeEnum.PARENT, parentStrategyTypes, parentReasonMap
        );
        List<DocumentStrategyStepDraft> childSteps = buildDraftSteps(
            DocumentStrategyPipelineTypeEnum.CHILD, childStrategyTypes, childReasonMap
        );

        // strategySnapshot 是后续任务日志、方案展示和异步索引构建都会复用的一份紧凑快照表示。
        String strategySnapshot = buildCombinedStrategySnapshot(parentSteps, childSteps);
        return new DocumentStrategyPlanDraft(strategySnapshot, String.join("；", reasonList), parentSteps, childSteps);
    }

    @Override
    public List<SuperAgentDocumentStrategyStep> normalizeSteps(SuperAgentDocumentStrategyPlan basePlan,
                                                               List<SuperAgentDocumentStrategyStep> baseSteps,
                                                               List<Integer> requestParentStrategyTypes,
                                                               List<Integer> requestChildStrategyTypes,
                                                               Long documentId) {

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

        List<SuperAgentDocumentStructureNode> structureNodes = structureNodeService.listDocumentNodes(
            document == null ? null : document.getId(),
            document == null ? null : document.getLastParseTaskId()
        );
        List<ChunkCandidate> parentSeedList = buildParentSeedList(parsedText, parentSteps, structureNodes);
        List<ParentBlockCandidate> parentBlockList = new ArrayList<>();
        for (ChunkCandidate parentSeed : cleanupChunkList(parentSeedList)) {
            if (parentSeed == null || StrUtil.isBlank(parentSeed.getText())) {
                continue;
            }
            List<ChunkCandidate> childSeedList = buildChildSeedList(parentSeed, childSteps, structureNodes);
            List<ChunkCandidate> finalChildren = cleanupChunkList(childSeedList);
            if (finalChildren.isEmpty()) {
                finalChildren = List.of(cloneChunkCandidate(parentSeed, parentSeed.getText().trim()));
            }

            parentBlockList.add(new ParentBlockCandidate(
                parentSeed.getSectionPath(),
                parentSeed.getStructureNodeId(),
                parentSeed.getStructureNodeType(),
                parentSeed.getCanonicalPath(),
                parentSeed.getItemIndex(),
                parentSeed.getText().trim(),
                parentSeed.getSourceType(),
                finalChildren
            ));
        }
        return cleanupParentBlockList(parentBlockList);
    }

    private List<ChunkCandidate> buildParentSeedList(String parsedText,
                                                     List<SuperAgentDocumentStrategyStep> parentSteps,
                                                     List<SuperAgentDocumentStructureNode> structureNodes) {
        if (containsStructureStep(parentSteps) && structureNodes != null && !structureNodes.isEmpty()) {
            List<ChunkCandidate> structureSeeds = buildStructureParentSeeds(structureNodes);
            if (structureSeeds.isEmpty()) {
                return executePipeline(
                    List.of(new ChunkCandidate("", parsedText, DocumentChunkSourceTypeEnum.ORIGINAL.getCode())),
                    parentSteps,
                    DocumentStrategyPipelineTypeEnum.PARENT
                );
            }
            List<SuperAgentDocumentStrategyStep> remainingSteps = stripStructureSteps(parentSteps);
            if (remainingSteps.isEmpty()) {
                return structureSeeds;
            }
            return executePipeline(structureSeeds, remainingSteps, DocumentStrategyPipelineTypeEnum.PARENT);
        }
        return executePipeline(
            List.of(new ChunkCandidate("", parsedText, DocumentChunkSourceTypeEnum.ORIGINAL.getCode())),
            parentSteps,
            DocumentStrategyPipelineTypeEnum.PARENT
        );
    }

    private List<ChunkCandidate> buildChildSeedList(ChunkCandidate parentSeed,
                                                    List<SuperAgentDocumentStrategyStep> childSteps,
                                                    List<SuperAgentDocumentStructureNode> structureNodes) {
        if (containsStructureStep(childSteps)
            && parentSeed != null
            && parentSeed.getStructureNodeId() != null
            && structureNodes != null
            && !structureNodes.isEmpty()) {
            List<ChunkCandidate> structureSeeds = buildStructureChildSeeds(parentSeed, structureNodes);
            List<SuperAgentDocumentStrategyStep> remainingSteps = stripStructureSteps(childSteps);
            if (remainingSteps.isEmpty()) {
                return structureSeeds;
            }
            return executePipeline(structureSeeds, remainingSteps, DocumentStrategyPipelineTypeEnum.CHILD);
        }
        return executePipeline(
            List.of(cloneChunkCandidate(parentSeed, parentSeed.getText())),
            childSteps,
            DocumentStrategyPipelineTypeEnum.CHILD
        );
    }

    private boolean containsStructureStep(List<SuperAgentDocumentStrategyStep> steps) {
        return steps != null && steps.stream().anyMatch(step -> DocumentStrategyTypeEnum.STRUCTURE.getCode().equals(step.getStrategyType()));
    }

    private List<SuperAgentDocumentStrategyStep> stripStructureSteps(List<SuperAgentDocumentStrategyStep> steps) {
        return steps == null ? List.of() : steps.stream()
            .filter(step -> !DocumentStrategyTypeEnum.STRUCTURE.getCode().equals(step.getStrategyType()))
            .toList();
    }

    private List<ChunkCandidate> buildStructureParentSeeds(List<SuperAgentDocumentStructureNode> structureNodes) {
        Map<Long, Boolean> parentHasChildSection = new LinkedHashMap<>();
        for (SuperAgentDocumentStructureNode node : structureNodes) {
            if (node == null || node.getParentNodeId() == null) {
                continue;
            }
            if (DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType())) {
                parentHasChildSection.put(node.getParentNodeId(), true);
            }
        }
        List<ChunkCandidate> seeds = new ArrayList<>();
        for (SuperAgentDocumentStructureNode node : structureNodes) {
            if (node == null || !DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType())) {
                continue;
            }
            if (!isContentBearingSection(node, parentHasChildSection.getOrDefault(node.getId(), false))) {
                continue;
            }
            seeds.add(toChunkCandidate(node));
        }
        return seeds;
    }

    private List<ChunkCandidate> buildStructureChildSeeds(ChunkCandidate parentSeed,
                                                          List<SuperAgentDocumentStructureNode> structureNodes) {
        Map<Long, List<SuperAgentDocumentStructureNode>> childrenByParent = new LinkedHashMap<>();
        for (SuperAgentDocumentStructureNode node : structureNodes) {
            if (node == null || node.getParentNodeId() == null) {
                continue;
            }
            childrenByParent.computeIfAbsent(node.getParentNodeId(), ignored -> new ArrayList<>()).add(node);
        }
        List<ChunkCandidate> seeds = new ArrayList<>();
        for (SuperAgentDocumentStructureNode child : childrenByParent.getOrDefault(parentSeed.getStructureNodeId(), List.of())) {
            if (child == null || StrUtil.isBlank(child.getContentText())) {
                continue;
            }
            DocumentStructureNodeTypeEnum nodeType = DocumentStructureNodeTypeEnum.getRc(child.getNodeType());
            if (nodeType == DocumentStructureNodeTypeEnum.SECTION
                || nodeType == DocumentStructureNodeTypeEnum.STEP
                || nodeType == DocumentStructureNodeTypeEnum.LIST_ITEM) {
                seeds.add(toChunkCandidate(child));
            }
        }
        if (!seeds.isEmpty()) {
            return seeds;
        }
        return List.of(cloneChunkCandidate(parentSeed, parentSeed.getText()));
    }

    private boolean isContentBearingSection(SuperAgentDocumentStructureNode node, boolean hasChildSection) {
        if (node == null || StrUtil.isBlank(node.getContentText())) {
            return false;
        }
        String content = node.getContentText().trim();
        if (!hasChildSection) {
            return true;
        }
        String headingText = StrUtil.blankToDefault(node.getAnchorText(), node.getTitle()).trim();
        if (content.equals(headingText)) {
            return false;
        }
        return content.length() > headingText.length() + 16 || content.contains("\n");
    }

    private ChunkCandidate toChunkCandidate(SuperAgentDocumentStructureNode node) {
        return new ChunkCandidate(
            node.getSectionPath(),
            node.getId(),
            node.getNodeType(),
            StrUtil.blankToDefault(node.getCanonicalPath(), ""),
            node.getItemIndex(),
            node.getContentText(),
            DocumentChunkSourceTypeEnum.ORIGINAL.getCode()
        );
    }

    /*
    buildDraftSteps() 把策略类型列表转成步骤草案：
    * 按顺序构造某条流水线的步骤草案。
 * <p>
 * 这个方法不是简单地把“策略类型整数列表”复制出来，而是把推荐结果提升成
 * 一组后续可展示、可落库、可执行的 {@link DocumentStrategyStepDraft}。
 * </p>
 * <p>
 * 每个 step draft 至少要补齐四类信息：
 * 1. pipelineType：说明该步骤属于父块流水线还是子块流水线；
 * 2. strategyType：说明执行的是结构切块、递归分块、语义分块还是 LLM 分块；
 * 3. strategyRole：说明该步骤在整条流水线中的职责，例如 PRIMARY、FALLBACK、OPTIMIZE、ENHANCE；
 * 4. recommendReason：说明系统为什么把这个步骤放进当前流水线，供前端展示和日志追踪使用。
 * </p>
 * <p>
 * 之所以单独抽成一个 helper，是因为 {@link #recommendStrategy(SuperAgentDocument, DocumentAnalysisResult)}
 * 在确定“父块应该有哪些策略类型、子块应该有哪些策略类型”之后，
 * 还需要把这种“类型级结论”转换成真正能落成方案步骤的结构化对象。
 * 这一步正是由这里完成的。
 * </p>
     */
    private List<DocumentStrategyStepDraft> buildDraftSteps(DocumentStrategyPipelineTypeEnum pipelineType,
                                                            List<Integer> strategyTypes,
                                                            Map<Integer, String> reasonMap) {
        List<DocumentStrategyStepDraft> draftList = new ArrayList<>();
        for (int index = 0; index < strategyTypes.size(); index++) {
            Integer strategyType = strategyTypes.get(index);
            // index 不只是循环下标，它还决定了这个步骤在流水线中的顺序，
            // 同时也会影响 resolveRole 的结果，例如第一个步骤通常是 PRIMARY，
            // 而后续的递归步骤更可能被标为 FALLBACK。
            draftList.add(new DocumentStrategyStepDraft(
                pipelineType.getCode(),
                strategyType,
                    // role 不是固定写死的，而是由“步骤所在顺序 + 策略类型”共同决定。
                resolveRole(index, strategyType),
                    // 这里生成的是系统自动推荐出来的方案草稿，因此 sourceType 固定为 SYSTEM_RECOMMEND。
                DocumentStrategySourceTypeEnum.SYSTEM_RECOMMEND.getCode(),
                    // 推荐原因优先使用调用方已经为当前策略准备好的解释文本；
                    // 如果没有命中，则退回到一个通用说明，避免前端展示空理由。
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

    /*
    判断是否适合使用结构切块。
 * <p>
 * 只有在文件类型本身适合结构识别，且结构等级或标题数量达到一定阈值时才推荐。
 * </p>
 结构切块需要同时满足两个条件：文件类型适合（PDF/DOC/DOCX/MD/HTML，TXT 不行）+ 结构信号足够（结构等级 >= MEDIUM 或标题 >= 2 个）。
     */
    private boolean shouldUseStructure(DocumentFileTypeEnum fileType, DocumentAnalysisResult analysisResult) {

        boolean suitableType = fileType == DocumentFileTypeEnum.PDF
            || fileType == DocumentFileTypeEnum.DOC
            || fileType == DocumentFileTypeEnum.DOCX
            || fileType == DocumentFileTypeEnum.MD
            || fileType == DocumentFileTypeEnum.HTML;
        return suitableType && (analysisResult.getStructureLevel() >= DocumentStructureLevelEnum.MEDIUM.getCode()
            || analysisResult.getHeadingCount() >= 2);
    }

    /*
    判断是否需要递归分块。
 * <p>
 * 当全文或单段过长时，需要用递归分块控制 chunk 长度。
 * </p>
     */
    private boolean shouldUseRecursive(DocumentAnalysisResult analysisResult) {

        return analysisResult.getCharCount() >= properties.getChunk().getRecursiveMaxChars()
            || analysisResult.getMaxParagraphLength() >= properties.getChunk().getRecursiveMaxChars();
    }

    /**
     * 判断是否适合使用语义分块。
     */
    private boolean shouldUseSemantic(DocumentAnalysisResult analysisResult) {

        return analysisResult.getCharCount() >= properties.getChunk().getSemanticMinChars()
            && analysisResult.getParagraphCount() >= 3
            && analysisResult.getContentQualityLevel() >= DocumentContentQualityLevelEnum.MEDIUM.getCode();
    }

    /**
     * 判断是否需要启用 LLM 智能切块。
     * <p>
     * 这里只在配置允许、文本质量偏低且长度达到一定规模时才推荐，
     * 避免把 LLM 用在收益不明显的小文本上。
     * </p>
     */
    private boolean shouldUseLlm(DocumentAnalysisResult analysisResult) {

        return Boolean.TRUE.equals(properties.getChunk().getRecommendLlmWhenLowQuality())
            && analysisResult.getContentQualityLevel().equals(DocumentContentQualityLevelEnum.LOW.getCode())
            && analysisResult.getCharCount() >= properties.getChunk().getSemanticMinChars();
    }

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
            DocumentLineClassifier.LineClassification classification = documentLineClassifier.classify(trimmed);
            if (classification.isHeading()) {

                flushChunk(candidateList, currentSectionPath, sourceType, currentChunk);

                while (headingStack.size() >= classification.level()) {
                    headingStack.removeLast();
                }
                headingStack.addLast(classification.title());
                currentSectionPath = composeSectionPath(baseSectionPath, String.join(" > ", headingStack));
                currentChunk.append(trimmed).append('\n');
                continue;
            }

            currentChunk.append(line).append('\n');
        }
        flushChunk(candidateList, currentSectionPath, sourceType, currentChunk);

        if (candidateList.isEmpty()) {

            return applyRecursiveChunking(
                List.of(new ChunkCandidate(baseSectionPath, parsedText, sourceType)),
                pipelineType
            );
        }
        return candidateList;
    }

    private List<ChunkCandidate> applyRecursiveChunking(List<ChunkCandidate> sourceList) {
        return applyRecursiveChunking(sourceList, DocumentStrategyPipelineTypeEnum.CHILD);
    }

    private List<ChunkCandidate> applyRecursiveChunking(List<ChunkCandidate> sourceList,
                                                        DocumentStrategyPipelineTypeEnum pipelineType) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        int maxChars = resolveRecursiveMaxChars(pipelineType);
        int overlapChars = resolveRecursiveOverlap(maxChars, pipelineType);
        for (ChunkCandidate candidate : sourceList) {

            List<String> splitTextList = recursiveSplit(candidate.getText(), maxChars, overlapChars);
            for (String splitText : splitTextList) {
                resultList.add(cloneChunkCandidate(candidate, splitText));
            }
        }
        return resultList;
    }

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

                resultList.add(candidate);
                continue;
            }

            resultList.addAll(semanticSplit(candidate, pipelineType));
        }
        return resultList;
    }

    private List<ChunkCandidate> applyLlmChunking(List<ChunkCandidate> sourceList) {
        return applyLlmChunking(sourceList, DocumentStrategyPipelineTypeEnum.CHILD);
    }

    private List<ChunkCandidate> applyLlmChunking(List<ChunkCandidate> sourceList,
                                                  DocumentStrategyPipelineTypeEnum pipelineType) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (!Boolean.TRUE.equals(properties.getChunk().getLlmEnabled()) || chatModel == null) {

            return applySemanticChunking(sourceList, pipelineType);
        }

        List<ChunkCandidate> resultList = new ArrayList<>();
        for (ChunkCandidate candidate : sourceList) {
            if (StrUtil.isBlank(candidate.getText())) {
                continue;
            }

            int llmMaxChars = resolveLlmMaxChars(pipelineType);
            List<String> sourceTextList = candidate.getText().length() > llmMaxChars
                ? recursiveSplit(candidate.getText(), llmMaxChars, 0)
                : List.of(candidate.getText());

            for (String sourceText : sourceTextList) {
                List<String> llmChunkList = llmSplit(chatModel, sourceText);
                if (llmChunkList.isEmpty()) {

                    resultList.addAll(semanticSplit(cloneChunkCandidate(candidate, sourceText), pipelineType));
                    continue;
                }
                for (String llmChunk : llmChunkList) {
                    resultList.add(cloneChunkCandidate(candidate, llmChunk));
                }
            }
        }
        return resultList;
    }

    private List<ChunkCandidate> semanticSplit(ChunkCandidate candidate,
                                               DocumentStrategyPipelineTypeEnum pipelineType) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        List<String> sentenceList = splitSentences(candidate.getText());
        if (sentenceList.size() <= 1) {

            resultList.add(candidate);
            return resultList;
        }

        StringBuilder currentChunk = new StringBuilder();
        Set<String> currentTokenSet = new LinkedHashSet<>();
        int semanticMinChars = resolveSemanticMinChars(pipelineType);
        int semanticMaxChars = resolveSemanticMaxChars(pipelineType);

        for (String sentence : sentenceList) {

            Set<String> sentenceTokenSet = extractTokens(sentence);

            boolean exceedMaxChars = currentChunk.length() + sentence.length() > semanticMaxChars;
            double similarity = currentTokenSet.isEmpty() ? 1D : jaccard(currentTokenSet, sentenceTokenSet);
            boolean semanticBreak = currentChunk.length() >= semanticMinChars
                && similarity < properties.getChunk().getSemanticSimilarityThreshold();

            if (currentChunk.length() > 0 && (exceedMaxChars || semanticBreak)) {

                resultList.add(cloneChunkCandidate(candidate, currentChunk.toString().trim()));
                currentChunk.setLength(0);
                currentTokenSet.clear();
            }

            currentChunk.append(sentence);
            currentTokenSet.addAll(sentenceTokenSet);
        }

        if (currentChunk.length() > 0) {
            resultList.add(cloneChunkCandidate(candidate, currentChunk.toString().trim()));
        }
        return resultList;
    }

    private List<String> recursiveSplit(String text, int maxChars, int overlapChars) {
        String trimmed = text == null ? "" : text.trim();
        if (StrUtil.isBlank(trimmed)) {
            return List.of();
        }
        if (trimmed.length() <= maxChars) {

            return List.of(trimmed);
        }

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

            int end = Math.min(trimmed.length(), start + maxChars);
            fixedWindowList.add(trimmed.substring(start, end).trim());
            if (end >= trimmed.length()) {
                break;
            }

            start += step;
        }
        return fixedWindowList;
    }

    private List<String> mergeAndSplit(List<String> segmentList, int maxChars, int overlapChars) {
        List<String> rawResultList = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String segment : segmentList) {
            String trimmed = segment.trim();
            if (StrUtil.isBlank(trimmed)) {
                continue;
            }

            if (trimmed.length() > maxChars) {

                if (current.length() > 0) {
                    rawResultList.add(current.toString().trim());
                    current.setLength(0);
                }
                rawResultList.addAll(recursiveSplit(trimmed, maxChars, overlapChars));
                continue;
            }

            if (current.length() + trimmed.length() + 1 > maxChars) {

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

    private List<String> splitByRegex(String text, String regex) {
        return Arrays.stream(text.split(regex))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    private List<String> splitSentences(String text) {
        return Arrays.stream(text.split("(?<=[。！？!?；;\\.])"))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    private Set<String> extractTokens(String text) {
        LinkedHashSet<String> tokenSet = new LinkedHashSet<>();
        Matcher matcher = ENGLISH_WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {

            tokenSet.add(matcher.group());
        }
        for (char current : text.toCharArray()) {

            if (String.valueOf(current).matches("[\\u4e00-\\u9fa5]")) {
                tokenSet.add(String.valueOf(current));
            }
        }

        return tokenSet;
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0D;
        }

        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        return union.isEmpty() ? 0D : (double) intersection.size() / (double) union.size();
    }

    private List<String> llmSplit(ChatModel chatModel, String sourceText) {
        String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_LLM_SPLIT, Map.of(
            "sourceText", StrUtil.blankToDefault(sourceText, "")
        ));

        try {

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

            List<String> resultList = objectMapper.readValue(jsonArray, new TypeReference<List<String>>() {
            });
            return resultList.stream().filter(StrUtil::isNotBlank).map(String::trim).toList();
        }
        catch (Exception exception) {
            log.warn("大模型智能切块失败，回退到语义切块", exception);
            return List.of();
        }
    }

    private String extractJsonArray(String content) {

        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return content.substring(start, end + 1);
    }

    private List<ChunkCandidate> cleanupChunkList(List<ChunkCandidate> sourceList) {
        Map<String, ChunkCandidate> uniqueMap = new LinkedHashMap<>();
        for (ChunkCandidate candidate : sourceList) {
            if (candidate == null || StrUtil.isBlank(candidate.getText())) {
                continue;
            }
            String normalizedText = candidate.getText().trim();

            String uniqueKey = StrUtil.blankToDefault(candidate.getCanonicalPath(), candidate.getSectionPath())
                + "||" + candidate.getItemIndex()
                + "||" + normalizedText;
            uniqueMap.putIfAbsent(uniqueKey, cloneChunkCandidate(candidate, normalizedText));
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
            String uniqueKey = StrUtil.blankToDefault(candidate.getCanonicalPath(), candidate.getSectionPath())
                + "||" + candidate.getItemIndex()
                + "||" + normalizedText;
            uniqueMap.putIfAbsent(uniqueKey, cloneParentBlockCandidate(candidate, normalizedText,
                candidate.getChildChunks() == null ? List.of() : new ArrayList<>(candidate.getChildChunks())));
        }
        return new ArrayList<>(uniqueMap.values());
    }

    private void flushChunk(List<ChunkCandidate> candidateList,
                            String currentSectionPath,
                            Integer sourceType,
                            StringBuilder currentChunk) {
        String text = currentChunk.toString().trim();
        if (StrUtil.isNotBlank(text)) {

            candidateList.add(new ChunkCandidate(
                currentSectionPath,
                null,
                null,
                "",
                null,
                text,
                sourceType == null ? DocumentChunkSourceTypeEnum.ORIGINAL.getCode() : sourceType
            ));
        }
        currentChunk.setLength(0);
    }

    private ChunkCandidate cloneChunkCandidate(ChunkCandidate source, String text) {
        if (source == null) {
            return new ChunkCandidate("", text, DocumentChunkSourceTypeEnum.ORIGINAL.getCode());
        }
        return new ChunkCandidate(
            source.getSectionPath(),
            source.getStructureNodeId(),
            source.getStructureNodeType(),
            StrUtil.blankToDefault(source.getCanonicalPath(), ""),
            source.getItemIndex(),
            text,
            source.getSourceType()
        );
    }

    private ParentBlockCandidate cloneParentBlockCandidate(ParentBlockCandidate source,
                                                           String text,
                                                           List<ChunkCandidate> childChunks) {
        if (source == null) {
            return new ParentBlockCandidate("", text, DocumentChunkSourceTypeEnum.ORIGINAL.getCode(), childChunks);
        }
        return new ParentBlockCandidate(
            source.getSectionPath(),
            source.getStructureNodeId(),
            source.getStructureNodeType(),
            StrUtil.blankToDefault(source.getCanonicalPath(), ""),
            source.getItemIndex(),
            text,
            source.getSourceType(),
            childChunks
        );
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

}
