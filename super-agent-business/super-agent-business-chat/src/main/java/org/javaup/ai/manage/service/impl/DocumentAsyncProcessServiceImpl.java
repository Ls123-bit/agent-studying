package org.javaup.ai.manage.service.impl;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyPlanMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyStepMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.javaup.ai.manage.service.DocumentAsyncProcessService;
import org.javaup.ai.manage.service.DocumentParserService;
import org.javaup.ai.manage.service.DocumentStorageService;
import org.javaup.ai.manage.service.DocumentStrategyService;
import org.javaup.ai.manage.service.DocumentTaskLogService;
import org.javaup.ai.manage.service.DocumentVectorGateway;
import org.javaup.ai.manage.support.ChunkCandidate;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
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
import org.javaup.enums.DocumentStrategyStatusEnum;
import org.javaup.enums.DocumentTaskEventTypeEnum;
import org.javaup.enums.DocumentTaskStageEnum;
import org.javaup.enums.DocumentTaskStatusEnum;
import org.javaup.enums.DocumentVectorStatusEnum;
import org.javaup.enums.DocumentVectorStoreTypeEnum;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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

    private final SuperAgentDocumentChunkMapper chunkMapper;

    private final DocumentStorageService storageService;

    private final DocumentParserService parserService;

    private final DocumentStrategyService strategyService;

    private final DocumentTaskLogService taskLogService;

    private final DocumentVectorGateway vectorGateway;

    private final DocumentManageProperties properties;

    @Resource
    private UidGenerator uidGenerator;

    public DocumentAsyncProcessServiceImpl(SuperAgentDocumentMapper documentMapper,
                                           SuperAgentDocumentStrategyPlanMapper planMapper,
                                           SuperAgentDocumentStrategyStepMapper stepMapper,
                                           SuperAgentDocumentTaskMapper taskMapper,
                                           SuperAgentDocumentChunkMapper chunkMapper,
                                           DocumentStorageService storageService,
                                           DocumentParserService parserService,
                                           DocumentStrategyService strategyService,
                                           DocumentTaskLogService taskLogService,
                                           DocumentVectorGateway vectorGateway,
                                           DocumentManageProperties properties) {
        this.documentMapper = documentMapper;
        this.planMapper = planMapper;
        this.stepMapper = stepMapper;
        this.taskMapper = taskMapper;
        this.chunkMapper = chunkMapper;
        this.storageService = storageService;
        this.parserService = parserService;
        this.strategyService = strategyService;
        this.taskLogService = taskLogService;
        this.vectorGateway = vectorGateway;
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
            plan.setStrategyCount(planDraft.getSteps().size());
            plan.setStrategySnapshot(planDraft.getStrategySnapshot());
            plan.setRecommendReason(planDraft.getRecommendReason());
            plan.setStatus(BusinessStatus.YES.getCode());
            planMapper.insert(plan);

            // 再把方案里的每个策略步骤按顺序落库，形成可编辑的策略链。
            // 前端后面看到的“推荐步骤列表”就是从这里来的。
            int stepNo = 1;
            for (DocumentStrategyStepDraft draft : planDraft.getSteps()) {
                SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
                step.setId(uidGenerator.getUid());
                step.setPlanId(planId);
                step.setDocumentId(documentId);
                step.setStepNo(stepNo++);
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
                detail("planId", planId, "strategySnapshot", planDraft.getStrategySnapshot(), "recommendReason", planDraft.getRecommendReason()));
        }
        catch (Exception exception) {
            log.error("异步解析文档失败，documentId={}, taskId={}", documentId, taskId, exception);

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
        List<SuperAgentDocumentStrategyStep> stepList = listSteps(planId);
        try {
            // 把任务推进到“运行中 / 切块执行阶段”。
            task.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
            task.setCurrentStage(DocumentTaskStageEnum.CHUNK_EXECUTE.getCode());
            task.setStartTime(startTime);
            taskMapper.updateById(task);

            // 文档主状态同步切换成构建中，让前端立即能看到索引任务已启动。
            document.setIndexStatus(DocumentIndexStatusEnum.BUILDING.getCode());
            documentMapper.updateById(document);

            // 当前方案下的所有步骤统一标记为执行中，便于后续复盘执行轨迹。
            updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTING.getCode());

            // 先写一条“开始执行切块流水线”的日志，展示本次构建使用的是哪套策略快照。
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
                DocumentTaskEventTypeEnum.START.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "开始执行切块流水线。",
                Map.of("strategySnapshot", plan.getStrategySnapshot()));

            // 索引构建直接读取解析后的纯文本，不再回源读取原始二进制文件。
            String parsedText = storageService.downloadText(document.getParseTextPath());

            // 按已确认方案依次执行切块策略，得到候选 chunk 列表。
            List<ChunkCandidate> chunkCandidateList = strategyService.buildChunks(document, plan, stepList, parsedText);

            // 只要切块成功跑完，就把方案步骤状态整体推进到“执行成功”。
            updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTE_SUCCESS.getCode());

            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "切块执行完成。",
                Map.of("chunkCount", chunkCandidateList.size()));

            // 任务进入“切块后处理”阶段，主要做无效 chunk 过滤。
            task.setCurrentStage(DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode());
            taskMapper.updateById(task);

            // 这里只保留有文本内容的 chunk，空块不进入后续向量化。
            List<ChunkCandidate> finalChunkList = chunkCandidateList.stream()
                .filter(item -> StringUtils.hasText(item.getText()))
                .toList();
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "切块后处理完成。",
                Map.of("chunkCount", finalChunkList.size()));

            // 先把 chunk 元数据保存到业务表，再执行向量化，这样即使后面失败也能排查到原始 chunk。
            List<SuperAgentDocumentChunk> chunkEntityList = buildChunkEntities(documentId, taskId, planId, finalChunkList);
            for (SuperAgentDocumentChunk chunk : chunkEntityList) {
                chunkMapper.insert(chunk);
            }

            // chunk 持久化完成后，任务进入“向量化”阶段。
            task.setCurrentStage(DocumentTaskStageEnum.VECTORIZE.getCode());
            taskMapper.updateById(task);

            // 日志里把批次大小、批次数、向量库类型一起打出来，便于排查性能问题。
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
                    "vectorStoreType", DocumentVectorStoreTypeEnum.PG_VECTOR.getMsg()));

            // 真正调用向量网关执行 embedding 计算并写入 PGVector。
            vectorGateway.vectorize(chunkEntityList);

            // 向量化完成后，chunk 实体里会带回向量状态等信息，这里回写到业务表。
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
                    "vectorStoreType", DocumentVectorStoreTypeEnum.PG_VECTOR.getMsg()));

            // 进入最终入库完成阶段，标志本次索引链路已经走完。
            task.setCurrentStage(DocumentTaskStageEnum.STORE_COMPLETE.getCode());
            taskMapper.updateById(task);

            // 方案被真正执行过之后，方案状态可以从“已确认”推进为“已执行”。
            plan.setPlanStatus(DocumentPlanStatusEnum.EXECUTED.getCode());
            planMapper.updateById(plan);

            // 文档主记录记录本次成功索引的任务 ID，后续问答只会基于这个任务检索。
            document.setIndexStatus(DocumentIndexStatusEnum.BUILD_SUCCESS.getCode());
            document.setLastIndexTaskId(taskId);
            documentMapper.updateById(document);

            // 最后统一收尾任务状态并写入“索引构建完成”日志。
            finishTaskSuccess(task, DocumentTaskStageEnum.STORE_COMPLETE.getCode(), startTime);
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.STORE_COMPLETE.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "索引构建完成。",
                Map.of("taskId", taskId, "chunkCount", chunkEntityList.size()));
        }
        catch (Exception exception) {
            log.error("异步构建索引失败，documentId={}, taskId={}, planId={}", documentId, taskId, planId, exception);

            // 构建失败时先把文档主状态改成失败，前端列表页才能第一时间感知异常。
            document.setIndexStatus(DocumentIndexStatusEnum.BUILD_FAILED.getCode());
            documentMapper.updateById(document);

            // 已落库的 chunk 如果没有完成向量化，统一标记成向量失败，方便后续排查。
            chunkMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentChunk>()
                .eq(SuperAgentDocumentChunk::getTaskId, taskId)
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                .set(SuperAgentDocumentChunk::getVectorStatus, DocumentVectorStatusEnum.VECTOR_FAILED.getCode())
                .set(SuperAgentDocumentChunk::getVectorStoreType, DocumentVectorStoreTypeEnum.PG_VECTOR.getCode()));

            // 方案步骤和任务状态也一起回退为失败，避免不同表之间状态不一致。
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
     * 构建 chunk 实体。
     */
    private List<SuperAgentDocumentChunk> buildChunkEntities(Long documentId,
                                                             Long taskId,
                                                             Long planId,
                                                             List<ChunkCandidate> chunkCandidateList) {
        List<SuperAgentDocumentChunk> chunkEntityList = new java.util.ArrayList<>();
        for (int index = 0; index < chunkCandidateList.size(); index++) {
            ChunkCandidate candidate = chunkCandidateList.get(index);

            // 每个候选 chunk 都要固化为业务实体，后续问答引用、日志排查都依赖这里的主键。
            SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
            chunk.setId(uidGenerator.getUid());
            chunk.setDocumentId(documentId);
            chunk.setTaskId(taskId);
            chunk.setPlanId(planId);
            chunk.setChunkNo(index + 1);
            chunk.setSourceType(candidate.getSourceType() == null
                ? DocumentChunkSourceTypeEnum.ORIGINAL.getCode() : candidate.getSourceType());
            chunk.setSectionPath(candidate.getSectionPath());
            chunk.setPageNo(candidate.getPageNo());
            chunk.setChunkText(candidate.getText());
            chunk.setCharCount(candidate.getText().length());

            // token 这里用轻量估算，而不是实时调用 tokenizer，
            // 目的是给前端和日志一个近似量级，不阻塞主链路。
            chunk.setTokenCount(estimateTokenCount(candidate.getText()));
            chunk.setVectorStatus(DocumentVectorStatusEnum.WAIT_VECTOR.getCode());
            chunk.setVectorStoreType(DocumentVectorStoreTypeEnum.PG_VECTOR.getCode());
            chunk.setStatus(BusinessStatus.YES.getCode());
            chunkEntityList.add(chunk);
        }
        return chunkEntityList;
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
        // 切块流水线必须严格按 stepNo 执行，否则用户在前端调整的顺序就失效了。
        return stepMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentStrategyStep>()
            .eq(SuperAgentDocumentStrategyStep::getPlanId, planId)
            .eq(SuperAgentDocumentStrategyStep::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentStrategyStep::getStepNo));
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
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int chineseCount = 0;
        int englishCount = 0;

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
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            detailMap.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return detailMap;
    }
}
