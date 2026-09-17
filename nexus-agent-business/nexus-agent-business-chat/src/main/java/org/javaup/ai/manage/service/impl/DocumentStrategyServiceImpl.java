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



    /*Parent-Child 双层切块入口
    整个切块采用的是 Parent-Child 双层流水线 架构——先把文档切成大块（父块），再把每个大块切成小块（子块）。父块和子块各有一条独立的处理流水线，每条流水线可以串联多个切块策略。
    不是每种策略都会执行
    流水线里具体执行哪些策略，取决于方案里配置了哪些步骤。比如方案里只配了"结构切块 + 递归切块"，那语义切块和 LLM 切块就不会执行。

     */
    /**
     * 执行 Parent-Child 切块策略，生成父块结果集。
     * <p>
     * 这里不是简单把原文直接切成一组 chunk，而是分两层执行：
     * 1. 先根据父块流水线生成 parent seed；
     * 2. 再针对每个 parent seed 继续执行子块流水线；
     * 3. 对父子结果做去空、去重、兜底补齐，最终返回稳定的父子结构。
     * </p>
     * <p>
     * 整个过程允许混用结构切块、递归切块、语义切块和 LLM 切块，
     * 但输出口径始终统一成 ParentBlockCandidate -> List&lt;ChunkCandidate&gt;。
     * </p>
     */
    @Override
    public List<ParentBlockCandidate> buildParentBlocks(SuperAgentDocument document,
                                                        SuperAgentDocumentStrategyPlan plan,
                                                        List<SuperAgentDocumentStrategyStep> steps,
                                                        String parsedText) {
        // 先把整套步骤拆成父块流水线和子块流水线，保证两条链路各自独立排序执行。
        List<SuperAgentDocumentStrategyStep> parentSteps = sortPipelineSteps(steps, DocumentStrategyPipelineTypeEnum.PARENT);
        List<SuperAgentDocumentStrategyStep> childSteps = sortPipelineSteps(steps, DocumentStrategyPipelineTypeEnum.CHILD);
        // Parent-Child 结构要求父块和子块两层都必须存在，否则无法形成完整索引单元。
        if (parentSteps.isEmpty()) {
            throw new IllegalStateException("当前方案缺少父块流水线，无法生成 Parent-Child 结构。");
        }

        if (childSteps.isEmpty()) {
            throw new IllegalStateException("当前方案缺少子块流水线，无法生成 Parent-Child 结构。");
        }

        // 尝试拿到解析阶段沉淀的结构节点；
        // 如果方案里含有 STRUCTURE 步骤，这些节点会成为非常重要的天然切块边界。
        List<SuperAgentDocumentStructureNode> structureNodes = structureNodeService.listDocumentNodes(
            document == null ? null : document.getId(),
            document == null ? null : document.getLastParseTaskId()
        );
        // 第一层先产出父块种子，后续每个父块都会再进入子块流水线。
        List<ChunkCandidate> parentSeedList = buildParentSeedList(parsedText, parentSteps, structureNodes);
        List<ParentBlockCandidate> parentBlockList = new ArrayList<>();
        // 先对父块种子做一次清洗，避免空文本和重复块继续污染后续子块切分。
        for (ChunkCandidate parentSeed : cleanupChunkList(parentSeedList)) {
            if (parentSeed == null || StrUtil.isBlank(parentSeed.getText())) {
                continue;
            }
            // 针对每个父块独立生成 child seed，保证子块始终在父块语义范围内继续细分。
            List<ChunkCandidate> childSeedList = buildChildSeedList(parentSeed, childSteps, structureNodes);
            List<ChunkCandidate> finalChildren = cleanupChunkList(childSeedList);
            // 如果子块流水线最终没有产出任何有效 child，就退回到“父块本身就是唯一 child”的兜底策略，
            // 避免出现父块存在但 child 为空的不可用结构。
            if (finalChildren.isEmpty()) {
                finalChildren = List.of(cloneChunkCandidate(parentSeed, parentSeed.getText().trim()));
            }

            // 将父块元数据与最终 child 列表打包成 ParentBlockCandidate，供异步索引链落库。
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
        // 父块列表最后再做一次去重和规范化，保证输出结果稳定。
        return cleanupParentBlockList(parentBlockList);
    }


    /*
    生成父块种子
    这里的策略是：有结构节点就优先用结构节点，没有就退回到普通流水线。具体来说，方法内部走了三条分支：
1.结构优先路径：如果父块流水线里配了结构步骤（containsStructureStep），并且文档确实有可用的结构节点，就先调用 buildStructureParentSeeds() 从结构节点中提取父块种子。提取成功后，再用 stripStructureSteps() 把结构步骤从流水线里剔除，剩余的非结构步骤（比如递归、语义）继续通过 executePipeline() 对这些种子做进一步细分
2.结构降级路径：如果结构节点虽然存在，但 buildStructureParentSeeds() 没筛出任何可用的父块（比如所有章节节点都是纯标题壳），就放弃结构路径，把整篇文本包装成一个 ChunkCandidate，丢进完整的父块流水线从头执行
3.无结构路径：如果方案里压根没配结构步骤，或者文档没有结构节点，直接走这条路——整篇文本作为输入，交给父块流水线处理
这样设计的好处是：既能充分利用文档的天然层级结构来获得更精准的切块边界，又不会因为结构信息缺失或质量不够而卡住整个流程。

     */
    private List<ChunkCandidate> buildParentSeedList(String parsedText,
                                                     List<SuperAgentDocumentStrategyStep> parentSteps,
                                                     List<SuperAgentDocumentStructureNode> structureNodes) {
        if (containsStructureStep(parentSteps) && structureNodes != null && !structureNodes.isEmpty()) {
            // 结构化父块优先从章节节点中直接提取
            List<ChunkCandidate> structureSeeds = buildStructureParentSeeds(structureNodes);
            if (structureSeeds.isEmpty()) {
                // 结构节点虽然存在，但没筛出可用父块时，回退到整文流水线切块
                return executePipeline(
                    List.of(new ChunkCandidate("", parsedText, DocumentChunkSourceTypeEnum.ORIGINAL.getCode())),
                    parentSteps,
                    DocumentStrategyPipelineTypeEnum.PARENT
                );
            }
            // 结构步骤已经消化完成，后续只需要执行剩余非结构步骤
            List<SuperAgentDocumentStrategyStep> remainingSteps = stripStructureSteps(parentSteps);
            if (remainingSteps.isEmpty()) {
                return structureSeeds;
            }
            return executePipeline(structureSeeds, remainingSteps, DocumentStrategyPipelineTypeEnum.PARENT);
        }
        // 没有结构步骤或没有结构节点时，直接从整篇文本开始执行父块流水线
        return executePipeline(
            List.of(new ChunkCandidate("", parsedText, DocumentChunkSourceTypeEnum.ORIGINAL.getCode())),
            parentSteps,
            DocumentStrategyPipelineTypeEnum.PARENT
        );
    }

    /*
    为每个父块生成子块种子
    这个方法的思路和 buildParentSeedList 很像，也是"结构优先、流水线兜底"，但多了几个前置条件的判断：
1.结构切分路径：要走这条路，需要同时满足三个条件——子块流水线里配了结构步骤、当前父块种子绑定了 structureNodeId（说明它来自结构节点而不是普通文本切块）、文档有可用的结构节点列表。三个条件都满足时，调用 buildStructureChildSeeds() 从父节点的直接子节点中提取子块种子。提取完成后，同样剔除结构步骤，剩余步骤继续通过 executePipeline() 细分
2.普通流水线路径：如果上面三个条件任意一个不满足，就走这条路——用 cloneChunkCandidate() 把父块文本复制一份作为输入，丢进子块流水线从头执行递归/语义/LLM 等切块策略
这里有个细节值得注意：parentSeed.getStructureNodeId() != null 这个判断很关键。如果父块种子是通过普通流水线（比如递归切块）产出的，它身上不会带 structureNodeId，这时候即使子块流水线配了结构步骤也不会走结构路径，因为没有结构树上的锚点可以定位子节点。

     */
    private List<ChunkCandidate> buildChildSeedList(ChunkCandidate parentSeed,
                                                    List<SuperAgentDocumentStrategyStep> childSteps,
                                                    List<SuperAgentDocumentStructureNode> structureNodes) {
        if (containsStructureStep(childSteps)
            && parentSeed != null
            && parentSeed.getStructureNodeId() != null
            && structureNodes != null
            && !structureNodes.isEmpty()) {
            // 子块结构切分要求 parentSeed 必须能定位回结构树上的父节点
            List<ChunkCandidate> structureSeeds = buildStructureChildSeeds(parentSeed, structureNodes);
            List<SuperAgentDocumentStrategyStep> remainingSteps = stripStructureSteps(childSteps);
            if (remainingSteps.isEmpty()) {
                return structureSeeds;
            }
            return executePipeline(structureSeeds, remainingSteps, DocumentStrategyPipelineTypeEnum.CHILD);
        }
        // 结构切分不可用时，就从父块文本本身出发继续做递归/语义/LLM 等细分
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

    /*
    从结构节点提取父块
    这个方法做了两轮遍历，目的是从结构节点中筛选出真正适合作为父块的章节：
第一轮遍历：建立一个 parentHasChildSection 映射表，记录"哪些节点 ID 下面还有 SECTION 类型的子节点"。这个信息后面用来判断一个章节是"内容承载节点"还是"纯标题壳节点"
第二轮遍历：只保留 nodeType 为 SECTION 的节点，然后用 isContentBearingSection() 做进一步过滤。过滤逻辑是：如果一个章节节点下面还嵌套了子章节（在第一轮映射表里能查到），说明它自己只是个"壳"——比如"第一章"下面有"1.1 节"和"1.2 节"，那"第一章"本身并不直接承载正文内容，真正的内容在子章节里。这种壳节点会被过滤掉，只留下叶子级别的、真正包含正文的章节作为父块种子
最终通过 toChunkCandidate() 把筛选出的结构节点转换成统一的 ChunkCandidate 对象，带上 sectionPath、structureNodeId 等元数据，供后续流水线继续处理。

     */
    private List<ChunkCandidate> buildStructureParentSeeds(List<SuperAgentDocumentStructureNode> structureNodes) {
        Map<Long, Boolean> parentHasChildSection = new LinkedHashMap<>();
        for (SuperAgentDocumentStructureNode node : structureNodes) {
            if (node == null || node.getParentNodeId() == null) {
                continue;
            }
            // 先标记"哪些节点下面还有 SECTION 子节点"
            if (DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType())) {
                parentHasChildSection.put(node.getParentNodeId(), true);
            }
        }
        List<ChunkCandidate> seeds = new ArrayList<>();
        for (SuperAgentDocumentStructureNode node : structureNodes) {
            if (node == null || !DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType())) {
                continue;
            }
            // 过滤掉只有标题、没有正文承载能力的章节节点
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


    /*
    流水线执行引擎
    这是所有切块策略的调度中心。它接收一组候选块和一组有序的策略步骤，然后按顺序逐步执行，每一步的输出作为下一步的输入。

    这个方法的设计非常简洁，但有几个关键点：

1.三次清洗：进入前清洗一次、每步执行后清洗一次、最终返回前再清洗一次。cleanupChunkList() 会去掉空文本块和重复块，保证每一步拿到的都是干净数据
2.switch 分派：用 Java 17 的 switch 表达式按策略类型分派，四种策略各自独立实现，互不耦合
3.流水线语义：前一步的输出就是后一步的输入。比如方案配了"结构切块 → 递归切块"，那结构切块产出的章节块会作为递归切块的输入，递归切块再把超长的章节块切成更小的片段
4.pipelineType 透传：父块流水线和子块流水线共用同一个引擎，通过 pipelineType 参数区分，各策略内部会根据这个参数选择不同的阈值（比如父块的 maxChars 通常比子块大）
     */
    private List<ChunkCandidate> executePipeline(List<ChunkCandidate> sourceList,
                                                 List<SuperAgentDocumentStrategyStep> orderedSteps,
                                                 DocumentStrategyPipelineTypeEnum pipelineType) {
        // 进入流水线前先清洗一次输入，保证每一步面对的都是可用候选块。
        List<ChunkCandidate> currentChunks = cleanupChunkList(sourceList);
        for (SuperAgentDocumentStrategyStep step : orderedSteps) {
            DocumentStrategyTypeEnum strategyType = DocumentStrategyTypeEnum.getRc(step.getStrategyType());
            if (strategyType == null) {
                continue;
            }
            // 根据策略类型分派到对应切块器：结构、递归、语义、LLM。
            currentChunks = switch (strategyType) {
                case STRUCTURE -> applyStructureChunking(currentChunks, pipelineType);
                case RECURSIVE -> applyRecursiveChunking(currentChunks, pipelineType);
                case SEMANTIC -> applySemanticChunking(currentChunks, pipelineType);
                case LLM -> applyLlmChunking(currentChunks, pipelineType);
            };
            // 每一步执行完立刻清洗，保证下游步骤不会处理无效或重复块。
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

    /*
    入口方法：
    策略一：结构切块
    结构切块是四种策略中最"聪明"的一种——它不是机械地按长度切，而是利用文档的天然层级结构（标题、章节）来确定切块边界。
    入口方法很简单，就是遍历每个候选块，把它的文本和上下文元数据传给真正的切块逻辑。
     */
    private List<ChunkCandidate> applyStructureChunking(List<ChunkCandidate> sourceList,
                                                        DocumentStrategyPipelineTypeEnum pipelineType) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        for (ChunkCandidate candidate : sourceList) {
            if (candidate == null || StrUtil.isBlank(candidate.getText())) {
                continue;
            }
            // 每个输入块会保留自己的 sectionPath / sourceType 上下文，再拆成更细的结构块。
            resultList.addAll(applyStructureChunking(
                candidate.getText(),
                pipelineType,
                candidate.getSectionPath(),
                candidate.getSourceType()
            ));
        }
        return resultList;
    }

    /*
    核心实现：按行扫描+标题识别

    这个方法的核心思路是逐行扫描 + 标题栈维护，具体分为以下几步：
1.逐行扫描：把整段文本按换行符拆成行，逐行送入 DocumentLineClassifier 做分类。分类器会判断每一行是标题、列表项还是普通正文
2.标题触发切块：每当遇到一个标题行，先调用 flushChunk() 把之前累积的正文内容输出为一个 chunk，然后更新标题栈。这样就保证了每个 chunk 的内容都属于同一个章节，不会跨章节串块
3.标题栈维护：用一个 Deque<String> 维护当前的标题层级路径。遇到新标题时，先根据标题层级回退栈（比如当前栈是 [第一章, 1.1节, 1.1.1小节]，遇到一个 2 级标题，就把栈回退到 [第一章]，再压入新标题），然后用 composeSectionPath() 把栈里的标题用 > 连接成完整路径
4.降级兜底：如果整段文本扫描完，一个标题都没识别出来（candidateList 为空），说明这段文本没有明显的结构特征，就自动降级到递归切块处理，避免整段文本丢失
     */
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

                // 新标题出现时，先把上一段正文落成一个 chunk，避免跨章节串块。
                flushChunk(candidateList, currentSectionPath, sourceType, currentChunk);

                // 根据标题层级回退标题栈，确保像 1 -> 1.1 -> 1.1.1 这样的路径始终正确闭合。
                while (headingStack.size() >= classification.level()) {
                    headingStack.removeLast();
                }
                headingStack.addLast(classification.title());
                // 重新计算当前块所属的完整 sectionPath，供后续检索和回显使用。
                currentSectionPath = composeSectionPath(baseSectionPath, String.join(" > ", headingStack));
                currentChunk.append(trimmed).append('\n');
                continue;
            }

            // 非标题行继续并入当前章节块。
            currentChunk.append(line).append('\n');
        }
        // 循环结束后别忘了把最后一个累积块冲刷出来。
        flushChunk(candidateList, currentSectionPath, sourceType, currentChunk);

        if (candidateList.isEmpty()) {
            // 如果结构切块完全没有识别出有效边界，就退回递归切块兜底，避免整段文本丢失。
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


    /*
    策略二：递归切块
    递归切块是最"务实"的策略——它不关心文档的语义结构，只关心一件事：把超长文本切成不超过阈值的小块，同时尽量保留自然边界。
    入口方法：

     */
    private List<ChunkCandidate> applyRecursiveChunking(List<ChunkCandidate> sourceList,
                                                        DocumentStrategyPipelineTypeEnum pipelineType) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        // 父块和子块的最大长度、重叠大小允许不同，这里统一按流水线类型解析参数。
        int maxChars = resolveRecursiveMaxChars(pipelineType);
        int overlapChars = resolveRecursiveOverlap(maxChars, pipelineType);
        for (ChunkCandidate candidate : sourceList) {

            // 递归切块会尽量优先尊重段落、行、句子边界，再退化到固定窗口。
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





    /*
    策略三：语义切块
    入口方法：
    入口方法有个短路判断：如果候选块的文本长度不超过 semanticMinChars，就直接保留不切。这是因为太短的文本做语义切分没有意义，反而可能切得过碎。

     */
    private List<ChunkCandidate> applySemanticChunking(List<ChunkCandidate> sourceList,
                                                       DocumentStrategyPipelineTypeEnum pipelineType) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        int semanticMinChars = resolveSemanticMinChars(pipelineType);
        for (ChunkCandidate candidate : sourceList) {
            if (StrUtil.isBlank(candidate.getText())
                || candidate.getText().length() <= semanticMinChars) {

                // 文本太短时没有继续做语义切分的必要，直接原样保留。
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


    /*
    策略四：LLM切块
    LLM 切块是四种策略中最"重"的一种——它直接调用大模型来理解文本语义，让模型决定在哪里切块。效果通常最好，但成本也最高
    入口方法：

     */
    private List<ChunkCandidate> applyLlmChunking(List<ChunkCandidate> sourceList,
                                                  DocumentStrategyPipelineTypeEnum pipelineType) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (!Boolean.TRUE.equals(properties.getChunk().getLlmEnabled()) || chatModel == null) {

            // LLM 能力关闭或模型缺失时，直接降级到语义切块，保证链路仍然可执行。
            return applySemanticChunking(sourceList, pipelineType);
        }

        List<ChunkCandidate> resultList = new ArrayList<>();
        for (ChunkCandidate candidate : sourceList) {
            if (StrUtil.isBlank(candidate.getText())) {
                continue;
            }

            int llmMaxChars = resolveLlmMaxChars(pipelineType);
            // 为了避免单次提示过长，超长文本会先按无重叠递归切开，再分别交给 LLM 处理。
            List<String> sourceTextList = candidate.getText().length() > llmMaxChars
                ? recursiveSplit(candidate.getText(), llmMaxChars, 0)
                : List.of(candidate.getText());

            for (String sourceText : sourceTextList) {
                List<String> llmChunkList = llmSplit(chatModel, sourceText);
                if (llmChunkList.isEmpty()) {

                    // LLM 没给出可用结果时，单段文本回退到语义切块，避免整段失败。
                    resultList.addAll(semanticSplit(cloneChunkCandidate(candidate, sourceText), pipelineType));
                    continue;
                }
                // LLM 返回的每个文本片段都继承原候选块的上下文元数据。
                for (String llmChunk : llmChunkList) {
                    resultList.add(cloneChunkCandidate(candidate, llmChunk));
                }
            }
        }
        return resultList;
    }



    /*
    语义切块核心算法：基于 Jaccard 相似度的句子累积

    这个方法的核心逻辑是逐句累积 + 双条件触发切块：
1.逐句扫描：先用 splitSentences() 按句级标点（。！？!?；;.）把文本拆成句子列表
2.提取 token：对每个句子调用 extractTokens() 提取简化 token 集合——英文按单词提取并转小写，中文按单字提取。这个 token 集合用来计算主题相似度
3.Jaccard 相似度：用 jaccard() 计算当前句子的 token 集合与当前累积块的 token 集合之间的 Jaccard 相似度。Jaccard 相似度 = 交集大小 / 并集大小，值域 [0, 1]，越接近 1 说明两组 token 越相似
4.双条件触发：满足以下任一条件就切块——(a) 累积长度超过 semanticMaxChars（硬上限）；(b) 累积长度已达到 semanticMinChars 且相似度低于阈值（主题跳变）。注意条件 (b) 有个前提：当前块必须先达到最小长度，这是为了避免前几句就因为微小的主题波动被切得过碎
     */
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
            // 每个句子都会提取一组简化 token，用于估算它与当前块主题的相似度。
            Set<String> sentenceTokenSet = extractTokens(sentence);

            boolean exceedMaxChars = currentChunk.length() + sentence.length() > semanticMaxChars;
            double similarity = currentTokenSet.isEmpty() ? 1D : jaccard(currentTokenSet, sentenceTokenSet);
            // 只有在当前块已经达到最小长度后，才允许因为主题跳变而切块，
            // 避免把前几句切得过碎。
            boolean semanticBreak = currentChunk.length() >= semanticMinChars
                && similarity < properties.getChunk().getSemanticSimilarityThreshold();

            if (currentChunk.length() > 0 && (exceedMaxChars || semanticBreak)) {
                // 一旦命中"超长"或"主题跳变"，就输出当前块并重置累计状态。
                resultList.add(cloneChunkCandidate(candidate, currentChunk.toString().trim()));
                currentChunk.setLength(0);
                currentTokenSet.clear();
            }
            // 当前句子总会并入新的或已有的语义块中。
            currentChunk.append(sentence);
            currentTokenSet.addAll(sentenceTokenSet);
        }

        if (currentChunk.length() > 0) {
            resultList.add(cloneChunkCandidate(candidate, currentChunk.toString().trim()));
        }
        return resultList;
    }


    /*
    四级降级拆分
    这是递归切块的核心算法，它的设计思路是：优先在最自然的边界处切开，切不开就退到下一级边界，直到最后用固定窗口硬切。
     */
    private List<String> recursiveSplit(String text, int maxChars, int overlapChars) {
        String trimmed = text == null ? "" : text.trim();
        if (StrUtil.isBlank(trimmed)) {
            return List.of();
        }
        if (trimmed.length() <= maxChars) {
            // 本身已经不超过阈值时，不需要继续拆。
            return List.of(trimmed);
        }
        // 优先按空行分段，尽量保留自然段边界。
        List<String> paragraphList = splitByRegex(trimmed, "\\n\\s*\\n");
        if (paragraphList.size() > 1) {
            return mergeAndSplit(paragraphList, maxChars, overlapChars);
        }
        // 没有段落边界时，退到逐行拆分。
        List<String> lineList = splitByRegex(trimmed, "\\n");
        if (lineList.size() > 1) {
            return mergeAndSplit(lineList, maxChars, overlapChars);
        }
        // 再退一步，尝试按句号、问号、分号等句级边界拆分。
        List<String> sentenceList = splitSentences(trimmed);
        if (sentenceList.size() > 1) {
            return mergeAndSplit(sentenceList, maxChars, overlapChars);
        }
        // 如果连句子边界都不可用，就只能用固定窗口硬切。
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
















    /*
    四种策略的阈值参数都通过一组resolve方法统一管理，根据父块/子块流水线类型返回不同的值
    下面四个方法是参数解析方法
    设计思路是：父块流水线用更大的阈值，子块流水线用配置文件里的标准值。这是因为父块本身就是"大块"，需要更宽松的长度限制；而子块是最终用于检索的单元，需要更精细的控制。

     */



    /*
    递归切块入口方法先根据流水线类型解析关键参数： overlapChars（相邻块的重叠字符数）。
    根据父/子流水线类型解析递归切块的 overlap 参数。
    父块流水线用固定的大阈值（PARENT_BLOCK_MAX_CHARS）

子块流水线从配置文件读取。然后对每个候选块调用 recursiveSplit() 做实际拆分。
     */
    private int resolveRecursiveOverlap(int maxChars, DocumentStrategyPipelineTypeEnum pipelineType) {
        if (pipelineType == DocumentStrategyPipelineTypeEnum.PARENT) {
            // 父块使用更保守的固定 overlap，避免父块之间上下文割裂太强。
            return Math.min(PARENT_BLOCK_OVERLAP_CHARS, Math.max(0, maxChars - 1));
        }
        Integer configuredOverlap = properties.getChunk().getRecursiveOverlapChars();
        if (configuredOverlap == null || configuredOverlap <= 0) {
            return 0;
        }

        return Math.min(configuredOverlap, Math.max(0, maxChars - 1));
    }


    /*
    递归切块入口方法先根据流水线类型解析关键参数：maxChars（单块最大字符数）/解析递归切块的最大长度。
     */
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




    /*
    llmSplit：调用大模型切块
     */
    private List<String> llmSplit(ChatModel chatModel, String sourceText) {
        String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_LLM_SPLIT, Map.of(
            "sourceText", StrUtil.blankToDefault(sourceText, "")
        ));

        try {
            // 这里只接受纯内容返回值，后面还会进一步从中提取 JSON 数组。
            String content = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .user(prompt)
                .call()
                .content();

            if (StrUtil.isBlank(content)) {
                return List.of();
            }
            // 有些模型会包裹解释文字或 markdown，这里只截取最外层 JSON 数组片段。
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


    /*
    去重与清洗
    在整个切块流程中，cleanupChunkList() 被反复调用——流水线入口清洗一次、每步执行后清洗一次、最终返回前再清洗一次。它是保证输出质量的最后一道防线。
    ————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————
    这个方法做了三件事：
1.去空：跳过 null 和文本为空的候选块
2.trim 标准化：对文本做 trim()，消除首尾空白差异
3.去重：用 LinkedHashMap 按"路径 + 位置索引 + 标准化文本"构造去重键。LinkedHashMap 保证了去重后仍然保持原始插入顺序，putIfAbsent 保证同一个键只保留第一次出现的候选块
去重键的设计很讲究——不是简单地按文本去重，而是加上了路径和位置信息。这样即使两个不同章节恰好有相同的文本内容（比如都有一句"详见下文"），也不会被误去重。

     */
    private List<ChunkCandidate> cleanupChunkList(List<ChunkCandidate> sourceList) {
        Map<String, ChunkCandidate> uniqueMap = new LinkedHashMap<>();
        for (ChunkCandidate candidate : sourceList) {
            if (candidate == null || StrUtil.isBlank(candidate.getText())) {
                continue;
            }
            String normalizedText = candidate.getText().trim();
            // 用路径 + itemIndex + 标准化文本构造去重键，尽量保留同一位置的唯一语义块。
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


    /*
    结构切块过程中用到的两个工具方法之一：
    将当前累积文本刷新成一个 chunk 候选，并清空缓冲区。
    ————————————————————————————————————————————————
    flushChunk 的作用很简单：把 StringBuilder 里累积的文本输出为一个 ChunkCandidate，然后清空缓冲区。每次遇到新标题或文本扫描结束时都会调用它。
     */
    private void flushChunk(List<ChunkCandidate> candidateList,
                            String currentSectionPath,
                            Integer sourceType,
                            StringBuilder currentChunk) {
        String text = currentChunk.toString().trim();
        if (StrUtil.isNotBlank(text)) {

            // 结构切块过程中生成的是“还未绑定结构节点 ID 的文本块”，
            // 这里只保留 sectionPath 和 sourceType，后续再由上游逻辑决定如何使用。
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

    /*
    composeSectionPath 负责把基础路径和当前层级路径拼接起来。比如基础路径是 用户管理，当前标题栈拼出来的是 用户注册，最终就得到 用户管理 > 用户注册。
     */
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
