package org.javaup.ai.manage.service.impl;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentDocumentParentBlock;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyPlanMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyStepMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.javaup.ai.manage.service.DocumentAsyncProcessService;
import org.javaup.ai.manage.service.DocumentParserService;
import org.javaup.ai.manage.service.DocumentStorageService;
import org.javaup.ai.manage.service.DocumentStrategyService;
import org.javaup.ai.manage.service.DocumentTaskLogService;
import org.javaup.ai.manage.service.DocumentVectorGateway;
import org.javaup.ai.manage.service.keyword.DocumentKeywordSearchGateway;
import org.javaup.ai.manage.support.ChunkCandidate;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.ParentBlockCandidate;
import org.javaup.ai.manage.support.DocumentStrategyPlanDraft;
import org.javaup.ai.manage.support.DocumentStrategyStepDraft;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentChunkSourceTypeEnum;
import org.javaup.enums.DocumentFileTypeEnum;
import org.javaup.enums.DocumentIndexStatusEnum;
import org.javaup.enums.DocumentLogLevelEnum;
import org.javaup.enums.DocumentOperatorTypeEnum;
import org.javaup.enums.DocumentParseStatusEnum;
import org.javaup.enums.DocumentPlanSourceEnum;
import org.javaup.enums.DocumentPlanStatusEnum;
import org.javaup.enums.DocumentStrategyExecuteStatusEnum;
import org.javaup.enums.DocumentStrategyPipelineTypeEnum;
import org.javaup.enums.DocumentStrategyStatusEnum;
import org.javaup.enums.DocumentTaskEventTypeEnum;
import org.javaup.enums.DocumentTaskStageEnum;
import org.javaup.enums.DocumentTaskStatusEnum;
import org.javaup.enums.DocumentVectorStatusEnum;
import org.javaup.enums.DocumentVectorStoreTypeEnum;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档异步处理服务实现。
 *
 * <p>这个类承接消息队列投递过来的异步任务，真正执行两条后台流水线：</p>
 * <p>1. 解析流水线：下载原文件 -> 解析正文 -> 推荐策略 -> 更新文档状态。</p>
 * <p>2. 索引流水线：读取解析文本 -> 按方案切块 -> 保存 chunk -> 向量化 -> 更新索引状态。</p>
 *
 * <p>前端之所以能看到“任务时间线”和阶段状态变化，核心数据都在这里产生。</p>
 */
@Slf4j
@Service
public class DocumentAsyncProcessServiceImpl implements DocumentAsyncProcessService {

    private final SuperAgentDocumentMapper documentMapper;

    private final SuperAgentDocumentStrategyPlanMapper planMapper;

    private final SuperAgentDocumentStrategyStepMapper stepMapper;

    private final SuperAgentDocumentTaskMapper taskMapper;

    private final SuperAgentDocumentParentBlockMapper parentBlockMapper;

    private final SuperAgentDocumentChunkMapper chunkMapper;

    private final DocumentStorageService storageService;

    private final DocumentParserService parserService;

    private final DocumentStrategyService strategyService;

    private final DocumentTaskLogService taskLogService;

    private final DocumentVectorGateway vectorGateway;

    private final ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider;

    private final DocumentManageProperties properties;

    @Resource
    private UidGenerator uidGenerator;

    public DocumentAsyncProcessServiceImpl(SuperAgentDocumentMapper documentMapper,
                                           SuperAgentDocumentStrategyPlanMapper planMapper,
                                           SuperAgentDocumentStrategyStepMapper stepMapper,
                                           SuperAgentDocumentTaskMapper taskMapper,
                                           SuperAgentDocumentParentBlockMapper parentBlockMapper,
                                           SuperAgentDocumentChunkMapper chunkMapper,
                                           DocumentStorageService storageService,
                                           DocumentParserService parserService,
                                           DocumentStrategyService strategyService,
                                           DocumentTaskLogService taskLogService,
                                           DocumentVectorGateway vectorGateway,
                                           ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider,
                                           DocumentManageProperties properties) {
        this.documentMapper = documentMapper;
        this.planMapper = planMapper;
        this.stepMapper = stepMapper;
        this.taskMapper = taskMapper;
        this.parentBlockMapper = parentBlockMapper;
        this.chunkMapper = chunkMapper;
        this.storageService = storageService;
        this.parserService = parserService;
        this.strategyService = strategyService;
        this.taskLogService = taskLogService;
        this.vectorGateway = vectorGateway;
        this.keywordSearchGatewayProvider = keywordSearchGatewayProvider;
        this.properties = properties;
    }

    @Override
    /**
     * 处理“解析文档 + 生成推荐策略”异步任务。
     *
     * <p>这是上传文档后的第一条核心后台流水线，职责可以拆成四步：</p>
     * <p>1. 读取原始文件，并调用解析器生成 {@link DocumentAnalysisResult}。</p>
     * <p>2. 把解析后的纯文本存起来，供后续索引构建直接使用。</p>
     * <p>3. 基于解析结果推荐一版系统策略方案，并保存方案与步骤。</p>
     * <p>4. 把解析结果中的关键指标回写到文档主表，供前端展示和后续判断使用。</p>
     *
     * <p>如果你从前端流程顺着往下看，这个方法就是“上传完成以后后台到底干了什么”的主入口。</p>
     */
    public void handleParseRoute(Long documentId, Long taskId) {
        // 消费消息后先回查文档和任务，避免消息重复消费或脏消息导致空指针。
        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        if (document == null || task == null) {
            log.warn("解析任务对应的文档或任务不存在，documentId={}, taskId={}", documentId, taskId);
            return;
        }

        Date startTime = new Date();
        try {
            /*
             * 消费者一接手解析任务，就要先推进 task/document 状态。
             * 否则前端轮询时只能一直看到 NEW，看不出后台已经真正开始处理。
             */
            // 一进入消费者就把任务推进到“运行中 / 内容解析阶段”，
            // 这样前端时间线可以实时感知后台已经开始处理。
            task.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
            task.setCurrentStage(DocumentTaskStageEnum.CONTENT_PARSE.getCode());
            task.setStartTime(startTime);
            taskMapper.updateById(task);

            // 文档主状态同步切换为解析中，列表页无需等到整条链路结束。
            document.setParseStatus(DocumentParseStatusEnum.PARSING.getCode());
            documentMapper.updateById(document);

            // 先记录“开始解析”的任务日志，作为整条解析时间线的起点。
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
                DocumentTaskEventTypeEnum.START.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "开始解析文档内容。",
                Map.of("objectName", document.getObjectName()));

            // 从对象存储下载原始文件后，交给 parserService 抽取纯文本和结构信息。
            // 这里产出的 analysisResult 是后面整条推荐链路的输入核心：
            // 1. parsedText 会进入对象存储，供索引构建阶段直接读取
            // 2. charCount / tokenCount 会回写到文档主表，供前端展示
            // 3. structureLevel / headingCount / paragraphCount / maxParagraphLength / contentQualityLevel
            //    会被 strategyService 用来判断推荐哪几种切块策略
            byte[] fileBytes = storageService.downloadObject(document.getObjectName());
            DocumentAnalysisResult analysisResult = parserService.parse(fileBytes, document.getOriginalFileName(),
                document.getMimeType(), DocumentFileTypeEnum.getRc(document.getFileType()));

            /*
             * parseTextPath 是后续索引构建阶段的直接输入，不再回源解析原始文件。
             * 这样能保证“策略推荐看到的文本”和“实际切块使用的文本”完全一致。
             */
            // 解析后的纯文本也单独保存下来，后面构建索引时直接读取它，不再重复解析源文件。
            // 这样做的好处是：
            // 1. 避免构建索引时再次对 PDF / Word 做重解析
            // 2. 保证“推荐策略时看到的文本”和“真正切块时使用的文本”完全一致
            String parseTextPath = storageService.uploadParsedText(documentId, analysisResult.getParsedText());

            // 内容解析完成后，把统计信息写进日志，方便前端教学展示和排错。
            // 这里记录的是“解析阶段的观测结果”，不是最终索引结果。
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "文档解析完成。",
                Map.of(
                    "charCount", analysisResult.getCharCount(),
                    "tokenCount", analysisResult.getTokenCount(),
                    "structureLevel", analysisResult.getStructureLevel(),
                    "contentQualityLevel", analysisResult.getContentQualityLevel()
                ));

            // 解析阶段结束后，当前任务推进到“策略推荐”阶段。
            // 这一跳很关键，因为它把任务时间线从“内容解析”明确切换到了“策略生成”。
            task.setCurrentStage(DocumentTaskStageEnum.STRATEGY_ROUTE.getCode());
            taskMapper.updateById(task);

            // 根据解析结果推导推荐策略，这是前端“系统推荐方案”的来源。
            // 这里真正消费了上面 analysisResult 里的各个指标：
            // 1. structureLevel / headingCount -> 是否推荐结构切块
            // 2. charCount / maxParagraphLength -> 是否追加递归切块
            // 3. paragraphCount / contentQualityLevel -> 是否推荐语义切块
            // 4. contentQualityLevel -> 是否需要 LLM 智能切块兜底
            DocumentStrategyPlanDraft planDraft = strategyService.recommendStrategy(document, analysisResult);
            Long planId = uidGenerator.getUid();
            int planVersion = getNextPlanVersion(documentId);

            /*
             * 推荐方案分两层落库：
             * 1. 先落方案头
             * 2. 再落步骤明细
             *
             * 这样前端即使只查到 plan 主记录，也能知道当前文档已经有了一版待确认方案。
             */
            // 先保存策略方案主表，记录这是一版系统自动推荐的待确认方案。
            // 这里保存的是“方案头信息”，包括：
            // 1. 方案版本
            // 2. 推荐来源
            // 3. 当前状态（待确认）
            // 4. 策略快照和推荐原因
            SuperAgentDocumentStrategyPlan plan = new SuperAgentDocumentStrategyPlan();
            plan.setId(planId);
            plan.setDocumentId(documentId);
            plan.setPlanVersion(planVersion);
            plan.setPlanSource(DocumentPlanSourceEnum.SYSTEM_RECOMMEND.getCode());
            plan.setPlanStatus(DocumentPlanStatusEnum.WAIT_CONFIRM.getCode());
            plan.setStrategyCount(planDraft.getParentSteps().size() + planDraft.getChildSteps().size());
            plan.setStrategySnapshot(planDraft.getStrategySnapshot());
            plan.setRecommendReason(planDraft.getRecommendReason());
            plan.setStatus(BusinessStatus.YES.getCode());
            planMapper.insert(plan);

            /*
             * 每个 step 都单独固化，是为了让前端后续真的能编辑这条策略链：
             * 调整顺序、移除某一步、确认最终方案都依赖这里的明细记录。
             */
            // 再把方案里的每个策略步骤按顺序落库，形成可编辑的策略链。
            // 前端后面看到的“推荐步骤列表”就是从这里来的。
            for (int index = 0; index < planDraft.getParentSteps().size(); index++) {
                DocumentStrategyStepDraft draft = planDraft.getParentSteps().get(index);
                SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
                step.setId(uidGenerator.getUid());
                step.setPlanId(planId);
                step.setDocumentId(documentId);
                step.setPipelineType(draft.getPipelineType());
                step.setStepNo(index + 1);
                step.setStrategyType(draft.getStrategyType());
                step.setStrategyRole(draft.getStrategyRole());
                step.setSourceType(draft.getSourceType());
                step.setExecuteStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode());
                step.setRecommendReason(draft.getRecommendReason());
                step.setStatus(BusinessStatus.YES.getCode());
                stepMapper.insert(step);
            }
            for (int index = 0; index < planDraft.getChildSteps().size(); index++) {
                DocumentStrategyStepDraft draft = planDraft.getChildSteps().get(index);
                SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
                step.setId(uidGenerator.getUid());
                step.setPlanId(planId);
                step.setDocumentId(documentId);
                step.setPipelineType(draft.getPipelineType());
                step.setStepNo(index + 1);
                step.setStrategyType(draft.getStrategyType());
                step.setStrategyRole(draft.getStrategyRole());
                step.setSourceType(draft.getSourceType());
                step.setExecuteStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode());
                step.setRecommendReason(draft.getRecommendReason());
                step.setStatus(BusinessStatus.YES.getCode());
                stepMapper.insert(step);
            }

            // 文档主状态在这里一次性更新为“解析成功 + 已有推荐方案”，
            // 并把解析过程中得出的统计信息回填到文档主记录中。
            //
            // 这里是 analysisResult 第一次真正落到文档主表：
            // 1. charCount / tokenCount 让前端能直接展示文档规模
            // 2. structureLevel / contentQualityLevel 让后续流程能继续复用解析结论
            // 3. parseTextPath 指向清洗后的纯文本对象
            // 4. currentPlanId 指向刚生成的推荐方案，前端读取策略详情时就能直接查到
            document.setParseStatus(DocumentParseStatusEnum.PARSE_SUCCESS.getCode());
            document.setStrategyStatus(DocumentStrategyStatusEnum.RECOMMENDED.getCode());
            document.setCharCount(analysisResult.getCharCount());
            document.setTokenCount(analysisResult.getTokenCount());
            document.setStructureLevel(analysisResult.getStructureLevel());
            document.setContentQualityLevel(analysisResult.getContentQualityLevel());
            document.setParseTextPath(parseTextPath);
            document.setParseErrorMsg(null);
            document.setCurrentPlanId(planId);
            documentMapper.updateById(document);

            /*
             * 到这里 parseRoute 任务就可以收尾成 SUCCESS 了。
             * 这里表达的是“解析 + 推荐策略”链路完成，而不是“索引构建已经完成”。
             */
            // 把当前任务收尾成成功状态，并记录系统推荐策略日志。
            // 到这里，前端就能在同一条时间线上同时看到：
            // 1. 内容解析完成
            // 2. 推荐策略生成完成
            finishTaskSuccess(task, DocumentTaskStageEnum.STRATEGY_ROUTE.getCode(), startTime);
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.STRATEGY_ROUTE.getCode(),
                DocumentTaskEventTypeEnum.RECOMMEND_STRATEGY.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "系统已生成推荐策略。",
                detail("planId", planId,
                    "strategySnapshot", planDraft.getStrategySnapshot(),
                    "parentStepCount", planDraft.getParentSteps().size(),
                    "childStepCount", planDraft.getChildSteps().size(),
                    "recommendReason", planDraft.getRecommendReason()));
        }
        catch (Exception exception) {
            log.error("异步解析文档失败，documentId={}, taskId={}", documentId, taskId, exception);

            /*
             * parseErrorMsg 必须回写到 document 主表。
             * 这样前端列表和详情页不需要翻任务日志，也能直接看到解析失败原因摘要。
             */
            // 解析失败时要把错误信息挂回文档主记录，否则前端只知道失败，不知道原因。
            document.setParseStatus(DocumentParseStatusEnum.PARSE_FAILED.getCode());
            document.setParseErrorMsg(exception.getMessage());
            documentMapper.updateById(document);

            // 任务和日志都统一标记失败，方便时间线和排障信息保持一致。
            failTask(task, startTime, exception, DocumentTaskStageEnum.CONTENT_PARSE.getCode());
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
                DocumentTaskEventTypeEnum.FAILED.getCode(),
                DocumentLogLevelEnum.ERROR.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "文档解析失败。",
                detail("error", exception.getMessage()));
        }
    }

    
    /**
     * 处理“真正执行索引构建”的异步任务。
     *
     * <p>如果说 `DocumentManageServiceImpl#buildIndex` 只是“启动构建任务”，
     * 那这个方法才是索引构建链路里真正干重活的地方。</p>
     *
     * <p>它负责把一份已经解析完成、并且已经确认策略的文档，
     * 变成真正可检索的向量索引数据。</p>
     *
     * <p>整个方法可以按阶段拆成下面几步：</p>
     * <p>1. 读取文档、任务、方案和方案步骤。</p>
     * <p>2. 把任务推进到 RUNNING / CHUNK_EXECUTE，文档推进到 BUILDING。</p>
     * <p>3. 读取解析后的纯文本，并按已确认策略执行切块。</p>
     * <p>4. 对 chunk 做后处理，过滤无效文本并落库 chunk 业务表。</p>
     * <p>5. 调用向量网关生成 embedding 并写入 PGVector。</p>
     * <p>6. 回写 chunk、plan、document、task 的最终状态。</p>
     * <p>7. 记录完整时间线日志，便于前端查看“当前阶段”和执行轨迹。</p>
     *
     * <p>它的最终目标不是只把数据写进向量库，
     * 而是同时保证下面几张核心表保持一致：</p>
     * <p>1. `super_agent_document`：当前文档索引状态。</p>
     * <p>2. `super_agent_document_task`：本次构建任务状态和当前阶段。</p>
     * <p>3. `super_agent_document_strategy_plan`：这版方案是否已真正执行。</p>
     * <p>4. `super_agent_document_chunk`：切块结果和向量状态。</p>
     *
     * <p>所以你可以把这个方法理解成“索引构建的总编排器”。</p>
     */
    @Override
    public void handleIndexBuild(Long documentId, Long taskId, Long planId) {
        // 构建索引时依赖三类数据：文档、任务、被执行的方案。
        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        SuperAgentDocumentStrategyPlan plan = planMapper.selectById(planId);
        if (document == null || task == null || plan == null) {
            log.warn("索引任务对应的数据不存在，documentId={}, taskId={}, planId={}", documentId, taskId, planId);
            return;
        }

        Date startTime = new Date();

        // 构建索引必须严格按照方案步骤顺序执行，所以先把步骤列表查出来。
        // 后面真正切块时，会完全按这份 stepList 的 stepNo 顺序来跑流水线。
        List<SuperAgentDocumentStrategyStep> stepList = listSteps(planId);
        try {
            /*
             * 索引任务进入消费者后，第一步仍然是推进 task / document / step 状态。
             * 这样前端不会只看到“任务已创建”，而是能及时感知后台已经真正开始切块和构建索引。
             */
            // 把任务推进到“运行中 / 切块执行阶段”。
            // 这里是 task 表第一次正式进入执行态，后续“当前阶段”文案也从这里开始变化。
            task.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
            task.setCurrentStage(DocumentTaskStageEnum.CHUNK_EXECUTE.getCode());
            task.setStartTime(startTime);
            taskMapper.updateById(task);

            // 文档主状态同步切换成构建中，让前端立即能看到索引任务已启动。
            // 也就是说，document 表表达的是“业务总体状态”，
            // task 表表达的是“本次构建任务执行到哪一步了”。
            document.setIndexStatus(DocumentIndexStatusEnum.BUILDING.getCode());
            documentMapper.updateById(document);

            // 当前方案下的所有步骤统一标记为执行中，便于后续复盘执行轨迹。
            // 这里不是按步骤一个个改，而是先整体推进成 EXECUTING，
            // 表示“这版方案已经进入实际执行期”。
            updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTING.getCode());

            // 先写一条“开始执行切块流水线”的日志，展示本次构建使用的是哪套策略快照。
            // 这样时间线里能明确看到：真正开始处理的是哪一版方案。
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
                DocumentTaskEventTypeEnum.START.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "开始执行切块流水线。",
                Map.of("strategySnapshot", plan.getStrategySnapshot()));

            // 索引构建直接读取解析后的纯文本，不再回源读取原始二进制文件。
            // 这一点很重要，因为构建阶段必须和推荐阶段基于同一份 cleanedText 工作，
            // 否则会出现“推荐策略时看到的文本”和“真正切块时使用的文本”不一致的问题。
            String parsedText = storageService.downloadText(document.getParseTextPath());

            /*
             * 方案 B 下，策略服务不再直接返回一串平铺 child chunk，
             * 而是先返回一组 parent block，每个 parent 内部再带一组 child chunk。
             *
             * 这样索引构建从第一步起就已经是 Parent-Child 结构，
             * 后面检索命中 child 后，才能稳定回到 parent，而不再依赖查询阶段临时扩窗。
             */
            List<ParentBlockCandidate> parentBlockCandidateList = strategyService.buildParentBlocks(document, plan, stepList, parsedText);

            // 只要切块成功跑完，就把方案步骤状态整体推进到“执行成功”。
            // 这个成功指的是“切块链路成功产出了候选块”，不等于整个索引构建已经最终成功。
            updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTE_SUCCESS.getCode());

            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "切块执行完成。",
                Map.of(
                    "parentCount", parentBlockCandidateList.size(),
                    "childCount", countChildCandidates(parentBlockCandidateList)
                ));

            // 任务进入“切块后处理”阶段，主要做无效 chunk 过滤。
            // 从这一刻开始，task.currentStage 就已经不是 STRATEGY_ROUTE 或 CHUNK_EXECUTE，
            // 而是明确进入后处理阶段。
            task.setCurrentStage(DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode());
            taskMapper.updateById(task);

            // Parent-Child 结构下，这里过滤的是“空父块”和“没有有效 child 的父块”。
            // 也就是说，parentBlockCandidateList 是“策略产物”，
            // finalParentBlockList 才是“准备真正入库和检索生效的父子结构”。
            List<ParentBlockCandidate> finalParentBlockList = parentBlockCandidateList.stream()
                .filter(item -> item != null
                    && StrUtil.isNotBlank(item.getText())
                    && item.getChildChunks() != null
                    && item.getChildChunks().stream().anyMatch(child -> StrUtil.isNotBlank(child.getText())))
                .toList();
            /*
             * 这里先把空父块 / 空 child 过滤掉，是为了避免后面父块落库和 child 向量化阶段写入无效数据。
             */
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "切块后处理完成。",
                Map.of(
                    "parentCount", finalParentBlockList.size(),
                    "childCount", countChildCandidates(finalParentBlockList)
                ));

            /*
             * 这里一次性把 parent 和 child 实体都构建出来：
             * - parent 先分配主键和 parentNo
             * - child 再分配主键、chunkNo 和 parentBlockId
             *
             * 这样 child 从一开始就带着稳定父块关系，后续向量化和检索索引也能把这层关系保存进去。
             */
            ParentChildEntityBundle entityBundle = buildParentChildEntities(documentId, taskId, planId, finalParentBlockList);
            List<SuperAgentDocumentParentBlock> parentBlockEntityList = entityBundle.parentBlocks();
            List<SuperAgentDocumentChunk> chunkEntityList = entityBundle.childChunks();

            for (SuperAgentDocumentParentBlock parentBlock : parentBlockEntityList) {
                parentBlockMapper.insert(parentBlock);
            }
            for (SuperAgentDocumentChunk chunk : chunkEntityList) {
                chunkMapper.insert(chunk);
            }

            /*
             * 先落 chunk 业务表，再做向量化，这样即使 embedding 失败，
             * 也至少能从 chunk 表里明确知道“这次切块到底产出了什么”。
             */
            // chunk 持久化完成后，任务进入“向量化”阶段。
            // 前端如果此时查任务阶段，会看到已经从“切块”推进到“向量化”。
            task.setCurrentStage(DocumentTaskStageEnum.VECTORIZE.getCode());
            taskMapper.updateById(task);

            // 日志里把批次大小、批次数、向量库类型一起打出来，便于排查性能问题。
            // 这些信息不只是展示用，也方便后面定位 embedding 批量调用的性能瓶颈。
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.VECTORIZE.getCode(),
                DocumentTaskEventTypeEnum.START.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "开始执行向量化。",
                detail("chunkCount", chunkEntityList.size(),
                    "embeddingBatchSize", DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT,
                    "embeddingBatchCount",
                    (chunkEntityList.size() + DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT - 1)
                        / DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT,
                    "vectorStoreType", DocumentVectorStoreTypeEnum.PG_VECTOR.getMsg(),
                    "parentCount", parentBlockEntityList.size()));

            // 真正调用向量网关执行 embedding 计算并写入 PGVector。
            // 到这里，chunk 才会从“仅存在于业务表”变成“在向量库中可被检索”。
            vectorGateway.vectorize(chunkEntityList);

            /*
             * 关键词索引写入和向量写入处于同一条“正式生效索引构建”流水线上。
             * 只有两者都完成，这次索引任务才算真正可用于混合检索。
             */
            DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
            if (keywordSearchGateway != null) {
                keywordSearchGateway.indexChunks(chunkEntityList);
            }

            /*
             * vectorGateway 修改的是内存中的 chunkEntityList。
             * 所以这里还必须逐条 updateById，把新的向量状态和 vectorId 持久化回 chunk 表。
             */
            // 向量化完成后，chunk 实体里会带回向量状态等信息，这里回写到业务表。
            // 也就是说，vectorGateway 负责修改内存中的 chunkEntityList，
            // 而这里负责把这些修改持久化回 chunk 表。
            for (SuperAgentDocumentChunk chunk : chunkEntityList) {
                chunkMapper.updateById(chunk);
            }

            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.VECTORIZE.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "向量化完成。",
                detail("chunkCount", chunkEntityList.size(),
                    "embeddingBatchSize", DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT,
                    "embeddingBatchCount",
                    (chunkEntityList.size() + DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT - 1)
                        / DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT,
                    "vectorStoreType", DocumentVectorStoreTypeEnum.PG_VECTOR.getMsg(),
                    "parentCount", parentBlockEntityList.size()));

            // 进入最终入库完成阶段，标志本次索引链路已经走完。
            // task 表的 currentStage 会停在 STORE_COMPLETE，表示最后完成阶段。
            task.setCurrentStage(DocumentTaskStageEnum.STORE_COMPLETE.getCode());
            taskMapper.updateById(task);

            // 方案被真正执行过之后，方案状态可以从“已确认”推进为“已执行”。
            // 这一步的意义是区分：
            // - 只是被用户确认过的方案
            // - 已经真实参与过一次索引构建的方案
            plan.setPlanStatus(DocumentPlanStatusEnum.EXECUTED.getCode());
            planMapper.updateById(plan);

            // 文档主记录记录本次成功索引的任务 ID，后续问答只会基于这个任务检索。
            // lastIndexTaskId 很关键，因为问答接口会依赖它来限定“当前可用索引版本”。
            document.setIndexStatus(DocumentIndexStatusEnum.BUILD_SUCCESS.getCode());
            document.setLastIndexTaskId(taskId);
            documentMapper.updateById(document);

            /*
             * lastIndexTaskId 是后续问答链路默认使用的索引版本锚点。
             * 一旦这里回写成功，聊天问答默认就会基于这次成功构建的索引版本做检索。
             */
            // 最后统一收尾任务状态并写入“索引构建完成”日志。
            // 到这里 task / document / plan / chunk / 向量库 才算全部收敛到成功状态。
            finishTaskSuccess(task, DocumentTaskStageEnum.STORE_COMPLETE.getCode(), startTime);
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.STORE_COMPLETE.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "索引构建完成。",
                Map.of("taskId", taskId, "chunkCount", chunkEntityList.size(), "parentCount", parentBlockEntityList.size()));
        }
        catch (Exception exception) {
            log.error("异步构建索引失败，documentId={}, taskId={}, planId={}", documentId, taskId, planId, exception);

            /*
             * 失败时不能只改 task 状态，还要把 document / chunk / step 一起收敛到失败语义。
             * 否则前端和排障时会看到彼此矛盾的状态快照。
             */
            // 构建失败时先把文档主状态改成失败，前端列表页才能第一时间感知异常。
            // document 表代表的是“这份文档当前索引是否可用”，所以失败时必须立即回写。
            document.setIndexStatus(DocumentIndexStatusEnum.BUILD_FAILED.getCode());
            documentMapper.updateById(document);

            // 已落库的 chunk 如果没有完成向量化，统一标记成向量失败，方便后续排查。
            // 这样即使中途失败，也能明确知道“chunk 已生成，但向量没有完成”。
            chunkMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentChunk>()
                .eq(SuperAgentDocumentChunk::getTaskId, taskId)
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                .set(SuperAgentDocumentChunk::getVectorStatus, DocumentVectorStatusEnum.VECTOR_FAILED.getCode())
                .set(SuperAgentDocumentChunk::getVectorStoreType, DocumentVectorStoreTypeEnum.PG_VECTOR.getCode()));

            // 方案步骤和任务状态也一起回退为失败，避免不同表之间状态不一致。
            // 这里是“失败一致性收敛”逻辑：
            // 让 plan step、task、document 至少在宏观状态上保持一致。
            updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTE_FAILED.getCode());
            failTask(task, startTime, exception, task.getCurrentStage());
            taskLogService.saveLog(taskId, documentId,
                task.getCurrentStage(),
                DocumentTaskEventTypeEnum.FAILED.getCode(),
                DocumentLogLevelEnum.ERROR.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "索引构建失败。",
                detail("error", exception.getMessage()));
        }
    }

    /**
     * 一次性构建 Parent-Child 两层实体。
     *
     * <p>这里不再像旧版那样只关心 child chunk，
     * 而是先为每个 parent 分配稳定主键和 parentNo，
     * 再在 parent 内部顺序生成 child，并把 parentBlockId 写回 child。</p>
     */
    private ParentChildEntityBundle buildParentChildEntities(Long documentId,
                                                             Long taskId,
                                                             Long planId,
                                                             List<ParentBlockCandidate> parentBlockCandidateList) {
        List<SuperAgentDocumentParentBlock> parentBlockEntityList = new java.util.ArrayList<>();
        List<SuperAgentDocumentChunk> chunkEntityList = new java.util.ArrayList<>();
        int globalChunkNo = 1;

        for (int parentIndex = 0; parentIndex < parentBlockCandidateList.size(); parentIndex++) {
            ParentBlockCandidate parentCandidate = parentBlockCandidateList.get(parentIndex);
            if (parentCandidate == null || StrUtil.isBlank(parentCandidate.getText())) {
                continue;
            }

            SuperAgentDocumentParentBlock parentBlock = new SuperAgentDocumentParentBlock();
            parentBlock.setId(uidGenerator.getUid());
            parentBlock.setDocumentId(documentId);
            parentBlock.setTaskId(taskId);
            parentBlock.setPlanId(planId);
            parentBlock.setParentNo(parentIndex + 1);
            parentBlock.setSourceType(parentCandidate.getSourceType() == null
                ? DocumentChunkSourceTypeEnum.ORIGINAL.getCode() : parentCandidate.getSourceType());
            parentBlock.setSectionPath(parentCandidate.getSectionPath());
            parentBlock.setParentText(parentCandidate.getText().trim());
            parentBlock.setCharCount(parentCandidate.getText().length());
            parentBlock.setTokenCount(estimateTokenCount(parentCandidate.getText()));
            parentBlock.setStatus(BusinessStatus.YES.getCode());

            int startChunkNo = globalChunkNo;
            int childCount = 0;
            for (ChunkCandidate childCandidate : parentCandidate.getChildChunks()) {
                if (childCandidate == null || StrUtil.isBlank(childCandidate.getText())) {
                    continue;
                }
                SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
                chunk.setId(uidGenerator.getUid());
                chunk.setDocumentId(documentId);
                chunk.setTaskId(taskId);
                chunk.setPlanId(planId);
                chunk.setParentBlockId(parentBlock.getId());
                chunk.setChunkNo(globalChunkNo++);
                chunk.setSourceType(childCandidate.getSourceType() == null
                    ? DocumentChunkSourceTypeEnum.ORIGINAL.getCode() : childCandidate.getSourceType());
                chunk.setSectionPath(StrUtil.blankToDefault(childCandidate.getSectionPath(), parentCandidate.getSectionPath()));
                chunk.setChunkText(childCandidate.getText().trim());
                chunk.setCharCount(childCandidate.getText().length());

                /*
                 * child 的 token 估算仍然保持轻量实现，
                 * 让“父块文本长度”和“子块文本长度”都能被前端和日志直观看到。
                 */
                chunk.setTokenCount(estimateTokenCount(childCandidate.getText()));
                chunk.setVectorStatus(DocumentVectorStatusEnum.WAIT_VECTOR.getCode());
                chunk.setVectorStoreType(DocumentVectorStoreTypeEnum.PG_VECTOR.getCode());
                chunk.setStatus(BusinessStatus.YES.getCode());
                chunkEntityList.add(chunk);
                childCount++;
            }

            parentBlock.setChildCount(childCount);
            parentBlock.setStartChunkNo(childCount == 0 ? null : startChunkNo);
            parentBlock.setEndChunkNo(childCount == 0 ? null : globalChunkNo - 1);
            parentBlockEntityList.add(parentBlock);
        }

        return new ParentChildEntityBundle(parentBlockEntityList, chunkEntityList);
    }

    private int countChildCandidates(List<ParentBlockCandidate> parentBlockCandidateList) {
        if (parentBlockCandidateList == null || parentBlockCandidateList.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ParentBlockCandidate candidate : parentBlockCandidateList) {
            if (candidate == null || candidate.getChildChunks() == null) {
                continue;
            }
            count += (int) candidate.getChildChunks().stream()
                .filter(child -> child != null && StrUtil.isNotBlank(child.getText()))
                .count();
        }
        return count;
    }

    /**
     * 更新方案下所有步骤的执行状态。
     */
    private void updateStepExecuteStatus(Long planId, Integer executeStatus) {
        // 当前设计里一个方案下的步骤会被整条流水线一起推进状态，
        // 所以这里直接做批量更新，而不是逐步单独更新。
        stepMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentStrategyStep>()
            .eq(SuperAgentDocumentStrategyStep::getPlanId, planId)
            .eq(SuperAgentDocumentStrategyStep::getStatus, BusinessStatus.YES.getCode())
            .set(SuperAgentDocumentStrategyStep::getExecuteStatus, executeStatus));
    }

    /**
     * 查询方案步骤。
     */
    private List<SuperAgentDocumentStrategyStep> listSteps(Long planId) {
        List<SuperAgentDocumentStrategyStep> stepList = stepMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentStrategyStep>()
            .eq(SuperAgentDocumentStrategyStep::getPlanId, planId)
            .eq(SuperAgentDocumentStrategyStep::getStatus, BusinessStatus.YES.getCode()));
        return stepList.stream()
            .sorted(Comparator
                .comparingInt((SuperAgentDocumentStrategyStep step) -> pipelineOrder(step.getPipelineType()))
                .thenComparing(SuperAgentDocumentStrategyStep::getStepNo)
                .thenComparing(SuperAgentDocumentStrategyStep::getId))
            .toList();
    }

    private int pipelineOrder(String pipelineType) {
        return DocumentStrategyPipelineTypeEnum.PARENT.getCode().equalsIgnoreCase(
            StrUtil.blankToDefault(pipelineType, "")
        ) ? 0 : 1;
    }

    /**
     * 获取当前文档下一版方案版本号。
     */
    private int getNextPlanVersion(Long documentId) {
        // 这里和同步服务里的版本计算逻辑一致，保证异步推荐产生的方案版本连续递增。
        List<SuperAgentDocumentStrategyPlan> planList = planMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentStrategyPlan>()
            .eq(SuperAgentDocumentStrategyPlan::getDocumentId, documentId)
            .eq(SuperAgentDocumentStrategyPlan::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentStrategyPlan::getPlanVersion)
            .last("limit 1"));
        return planList.isEmpty() ? 1 : planList.get(0).getPlanVersion() + 1;
    }

    /**
     * 把任务标记为成功完成。
     */
    private void finishTaskSuccess(SuperAgentDocumentTask task, Integer stage, Date startTime) {
        /*
         * 成功收尾统一放在这里，是为了保证任务状态、结束时间、耗时和错误字段清理口径始终一致。
         * 这样解析链和索引链不会各自维护一套略有差异的收尾逻辑。
         */
        // 统一在这里计算耗时和清空错误信息，避免各条链路各自维护一套收尾逻辑。
        Date finishTime = new Date();
        task.setTaskStatus(DocumentTaskStatusEnum.SUCCESS.getCode());
        task.setCurrentStage(stage);
        task.setFinishTime(finishTime);
        task.setCostMillis(finishTime.getTime() - startTime.getTime());
        task.setErrorCode(null);
        task.setErrorMsg(null);
        taskMapper.updateById(task);
    }

    /**
     * 把任务标记为失败。
     */
    private void failTask(SuperAgentDocumentTask task, Date startTime, Exception exception, Integer currentStage) {
        /*
         * 失败收尾也集中在这里，目的是让“任务失败时该改哪些字段”固定下来。
         * 这样不同 catch 分支不会因为只更新了一半字段而出现状态不一致。
         */
        // 失败收尾和成功收尾保持同一套口径，方便前端统一读取任务状态和耗时。
        Date finishTime = new Date();
        task.setTaskStatus(DocumentTaskStatusEnum.FAILED.getCode());
        task.setCurrentStage(currentStage);
        task.setFinishTime(finishTime);
        task.setCostMillis(finishTime.getTime() - startTime.getTime());
        task.setErrorCode("TASK_FAILED");
        task.setErrorMsg(exception.getMessage());
        taskMapper.updateById(task);
    }

    /**
     * 估算 chunk token 数。
     */
    private int estimateTokenCount(String text) {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        int chineseCount = 0;
        int englishCount = 0;

        /*
         * 这里是一个轻量估算器，不是严格 tokenizer。
         * 目标是给 chunk 列表和日志一个量级参考，而不是精确复刻模型真实 token 数。
         */
        // 中文按单字计数，主要是为了快速给出一个接近 token 的数量级。
        for (char current : text.toCharArray()) {
            if (String.valueOf(current).matches("[\\u4e00-\\u9fa5]")) {
                chineseCount++;
            }
        }

        // 英文按单词估算，避免把连续英文长串误算得过大。
        for (String word : text.split("\\s+")) {
            if (word.matches(".*[A-Za-z].*")) {
                englishCount++;
            }
        }

        // 对非中文且非明显英文单词的其余字符，再按 4 个字符近似折算一个 token。
        return chineseCount + englishCount + Math.max(1, (text.length() - chineseCount) / 4);
    }

    /**
     * 构造允许包含 null 值的日志详情对象。
     */
    private Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> detailMap = new LinkedHashMap<>();
        /*
         * detail(...) 允许 value 为 null，是因为日志快照比业务表更强调“还原现场”。
         * 对日志来说，记录“某个字段当时为空”本身也是有价值的信息。
         */
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            detailMap.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return detailMap;
    }

    private record ParentChildEntityBundle(
        List<SuperAgentDocumentParentBlock> parentBlocks,
        List<SuperAgentDocumentChunk> childChunks
    ) {
    }
}
