package org.javaup.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentDocumentParentBlock;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyPlanMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyStepMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.javaup.ai.manage.service.DocumentAsyncProcessService;
import org.javaup.ai.manage.service.DocumentNavigationIndexService;
import org.javaup.ai.manage.service.DocumentParserService;
import org.javaup.ai.manage.service.DocumentProfileService;
import org.javaup.ai.manage.service.DocumentStorageService;
import org.javaup.ai.manage.service.DocumentStrategyService;
import org.javaup.ai.manage.service.DocumentStructureGraphProjectionService;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.javaup.ai.manage.service.DocumentTaskLogService;
import org.javaup.ai.manage.service.DocumentVectorGateway;
import org.javaup.ai.manage.service.keyword.DocumentKeywordSearchGateway;
import org.javaup.ai.manage.support.ChunkCandidate;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentStrategyPlanDraft;
import org.javaup.ai.manage.support.DocumentStrategyStepDraft;
import org.javaup.ai.manage.support.ParentBlockCandidate;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/

@Slf4j
@AllArgsConstructor
@Service
/*
异步处理管线
 */
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

    private final DocumentStructureNodeService structureNodeService;

    private final DocumentTaskLogService taskLogService;

    private final DocumentVectorGateway vectorGateway;

    private final ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider;

    private final ObjectProvider<DocumentNavigationIndexService> navigationIndexServiceProvider;

    private final ObjectProvider<DocumentStructureGraphProjectionService> graphProjectionServiceProvider;

    private final DocumentProfileService documentProfileService;

    @Resource
    private UidGenerator uidGenerator;

    @Override





    /*
    handleParseRoute流程   异步解析主方法
1.从 MinIO 下载文件
2.调用 Tika 解析
3.上传解析文本到 MinIO
4.替换文档结构节点
5.同步导航索引（ES）和图投影（Neo4j）
6.生成文档 Profile
7.推荐分块策略并持久化
     */
    public void handleParseRoute(Long documentId, Long taskId) {

/**
 * 处理"解析路由"任务。
 * <p>
 * 这是文档上传成功后的第一条异步业务主链，整体顺序如下：
 * 1. 读取文档记录和任务记录，确认异步处理上下文存在；
 * 2. 把任务状态切到 RUNNING，把当前阶段推进到 CONTENT_PARSE；
 * 3. 从对象存储下载原始文件，并调用解析器提取纯文本和结构信息；
 * 4. 把解析后的纯文本重新上传为 txt，便于后续索引构建直接复用；
 * 5. 用结构节点服务替换文档结构节点，并同步导航索引、图投影和画像；
 * 6. 基于解析结果调用策略服务生成推荐切块方案；
 * 7. 把推荐方案和步骤写入数据库，同时更新文档的解析状态、策略状态和统计信息；
 * 8. 以成功或失败状态收尾任务，并记录任务日志。
 * </p>
 * <p>
 * 这个方法本身不直接执行向量化和 chunk 落库，那是后续"索引构建任务"的职责；
 * 当前阶段的目标是把文档从"原始文件"推进到"解析完成并拿到推荐策略"。
 * </p>
 */
      //第一件事就是从数据库把文档记录和任务记录捞出来。如果查不到（比如上传事务回滚了、或者数据被删了），直接 return，不做任何处理。
        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        if (document == null || task == null) {
            log.warn("解析任务对应的文档或任务不存在，documentId={}, taskId={}", documentId, taskId);
            return;
        }
//然后把任务状态从 NEW 切到 RUNNING，当前阶段设为 CONTENT_PARSE。文档主表的 parseStatus 也同步更新为 PARSING。这样前端轮询文档状态时就能看到"正在解析中"。
        Date startTime = new Date();
        try {
            //进入异步解析后，先把任务状态切成 RUNNING，当前的阶段设为"内容解析" （任务正式进入运行态，并把当前阶段推进到“切块执行”）
            task.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
            task.setCurrentStage(DocumentTaskStageEnum.CONTENT_PARSE.getCode());
            task.setStartTime(startTime);
            taskMapper.updateById(task);

            //文档主表的解析状态也同步切到PARSING，方便前端查询时看到实时状态（文档维度同步标记为"构建中"，方便列表页/详情页立刻展示当前状态。）
            document.setParseStatus(DocumentParseStatusEnum.PARSING.getCode());
            documentMapper.updateById(document);























//记录开始日志+下载原始文件   先记一条开始日志，把原始对象名落下来，便于后续排查下载与解析问题
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
                DocumentTaskEventTypeEnum.START.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "开始解析文档内容。",
                Map.of("objectName", document.getObjectName()));
//重新从对象存储下载原始文件，确保异步链处理的是"上传后实际持久成功"的那份文件
            /*
            为什么要重新下载文件：
            上传阶段的 byte[] 是在 HTTP 请求线程里的内存数据，请求结束后就释放了。
            异步消费方运行在另一个线程（甚至另一个进程），所以必须从 MinIO 重新下载。
            这也顺便验证了文件确实已经成功持久化到对象存储。
             */
            byte[] fileBytes = storageService.downloadObject(document.getObjectName());









            /*
            调用Tika解析文档
            这一步是整条链路的核心————把原始二进制文件变成结构化的分析结果，深入看看（TikaDocumentParserService.parse() 的实现。）
             */
            // parserService 会负责提取纯文本、结构节点候选、字符数、token 数、质量等级等分析结果。
            DocumentAnalysisResult analysisResult = parserService.parse(fileBytes, document.getOriginalFileName(),
                document.getMimeType(), DocumentFileTypeEnum.getRc(document.getFileType()));






            /*
            上传解析文本+结构节点落库：这里做了四件事：
1.上传解析文本：把清洗后的纯文本以 .txt 格式存到 MinIO，路径格式是 parsed-text/documentId.txt。后续索引构建阶段直接读这个 txt 就行，不用再重新解析原始 PDF/DOCX
2.替换结构节点：先删除该文档的旧节点，再插入新节点。"替换"而不是"追加"，是为了保证每次解析后只保留最新的结构树
3.同步导航产物：包括导航 ES 索引和结构图投影，这些是可选组件，有就同步，没有就跳过
4.生成文档画像：基于解析结果和结构节点生成文档的摘要画像

             */
            // 解析后的纯文本单独落成 txt，是为了让后续切块与索引构建阶段不必重复解析原始二进制文件。
            String parseTextPath = storageService.uploadParsedText(documentId, analysisResult.getParsedText());
            // 结构节点采用“替换”方式写入，保证同一文档同一解析任务只保留当前最新结构树。
            List<SuperAgentDocumentStructureNode> structureNodes = structureNodeService.replaceDocumentNodes(
                documentId,
                taskId,
                analysisResult.getStructureNodes()
            );
            int structureNodeCount = structureNodes.size();
            // 导航索引、结构图投影和画像都属于解析后的派生产物，这里顺手同步。
            syncNavigationArtifacts(documentId, taskId, structureNodes);
            documentProfileService.generateProfile(documentId, analysisResult, structureNodes);







            //记录解析完成日志
            // 内容解析完成后，先把解析阶段的统计结果记录到任务日志里。
            /*
日志里把解析阶段的关键统计数据都记下来了：字符数、token 数、结构等级、内容质量等级、结构节点数。这些数据后续排查问题时非常有用。
然后把任务的当前阶段从 CONTENT_PARSE 推进到 STRATEGY_ROUTE，表示内容解析已完成，接下来要进入策略推荐阶段了。
             */
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
                    "contentQualityLevel", analysisResult.getContentQualityLevel(),
                    "structureNodeCount", structureNodeCount
                ));
            // 下一阶段进入策略推荐路由，意味着后面开始决定 Parent/Child 流水线该如何配置。
            task.setCurrentStage(DocumentTaskStageEnum.STRATEGY_ROUTE.getCode());
            taskMapper.updateById(task);








            /*
            方案持久化 拿到策略草案后，handleParseRoute要把它写进数据库
            创建策略方案主记录
             */

            // 根据文档类型、结构质量、正文长度、段落特征等信息生成推荐策略草案。
            DocumentStrategyPlanDraft planDraft = strategyService.recommendStrategy(document, analysisResult);
            Long planId = uidGenerator.getUid();
            int planVersion = getNextPlanVersion(documentId);

            // 推荐策略先落成一条 plan 主记录，表示"系统给当前文档建议采用这套切块方案"。
            SuperAgentDocumentStrategyPlan plan = new SuperAgentDocumentStrategyPlan();
            plan.setId(planId);
            plan.setDocumentId(documentId);
            plan.setPlanVersion(planVersion);
            plan.setPlanSource(DocumentPlanSourceEnum.SYSTEM_RECOMMEND.getCode());//方案主记录的关键字段：planSource = SYSTEM_RECOMMEND：标记这是系统自动推荐的，不是用户手动调整的
            plan.setPlanStatus(DocumentPlanStatusEnum.WAIT_CONFIRM.getCode());//方案主记录的关键字段：planStatus = WAIT_CONFIRM：方案还需要用户确认才能执行，不会自动进入索引构建
            plan.setStrategyCount(planDraft.getParentSteps().size() + planDraft.getChildSteps().size());
            plan.setStrategySnapshot(planDraft.getStrategySnapshot());//方案主记录的关键字段：strategySnapshot ：快照字符串，方便后续快速展示和比对
            plan.setRecommendReason(planDraft.getRecommendReason());
            plan.setStatus(BusinessStatus.YES.getCode());
            planMapper.insert(plan);





            /*
            写入策略步骤
            父块步骤和子块步骤分别循环写入，每个步骤的 executeStatus 都初始化为 WAIT_EXECUTE，等后续索引构建时才会真正执行。

             */
            // 父块步骤与子块步骤分别写入策略步骤表，
            // 后续确认方案和执行索引构建时会基于这些步骤推进。
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









            /*更新文档主表 + 任务成功收尾

             */
            // 到这里，文档已经完成内容解析并拿到了推荐策略，
            // 因此可以把主表状态切成"解析成功 + 已推荐策略"。
            //更新了文档主表的一大堆字段
            document.setParseStatus(DocumentParseStatusEnum.PARSE_SUCCESS.getCode());//parseStatus → PARSE_SUCCESS：解析成功
            document.setStrategyStatus(DocumentStrategyStatusEnum.RECOMMENDED.getCode());//strategyStatus → RECOMMENDED：策略已推荐，等待用户确认
            document.setCharCount(analysisResult.getCharCount());//从解析结果回填
            document.setTokenCount(analysisResult.getTokenCount());//从解析结果回填
            document.setStructureLevel(analysisResult.getStructureLevel());
            document.setContentQualityLevel(analysisResult.getContentQualityLevel());
            document.setParseTextPath(parseTextPath);//解析后纯文本在 MinIO 的路径
            document.setParseErrorMsg(null);//清空错误信息（如果之前有失败记录的话）
            document.setCurrentPlanId(planId);//当前生效的推荐方案 ID
            document.setLastParseTaskId(taskId);
            document.setStructureNodeCount(structureNodeCount);
            documentMapper.updateById(document);

            // 最后把任务按成功态收尾，并记录"系统已生成推荐策略"的阶段日志。
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
                    "structureNodeCount", structureNodeCount,
                    "recommendReason", planDraft.getRecommendReason()));
        }
        /*
        失败时做三件事：
1.文档主表标记为 PARSE_FAILED，并把错误信息写进 parseErrorMsg
2.任务标记为 FAILED，记录耗时和错误信息
3.写一条 ERROR 级别的任务日志
         */
        catch (Exception exception) {//异常处理：解析失败的收尾 ，如果上面任何异步抛了异常，就会进入catch块
            log.error("异步解析文档失败，documentId={}, taskId={}", documentId, taskId, exception);

            // 一旦异步解析链失败，文档主表要明确标记为解析失败，并保留错误信息供前端和日志查看。
            document.setParseStatus(DocumentParseStatusEnum.PARSE_FAILED.getCode());
            document.setParseErrorMsg(exception.getMessage());
            documentMapper.updateById(document);

            // 任务本身也同步转为失败态，并追加一条错误日志，把失败停留在哪个阶段明确记录下来。
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


    /*
    handleIndexBuild流程
1.从 MinIO 下载解析文本
2.执行分块管线（strategyService.buildParentBlocks()）
3.持久化父块和子块到数据库
4.向量化子块（DocumentVectorGateway）
5.关键词索引子块（DocumentKeywordSearchGateway）
6.更新文档索引状态
     */
    /*
    阶段一：初始化
    方法一进来，先把三个核心数据拿到手：文档，任务，策略方案，任何一个缺失都直接退出，同时还会预先读取并排序策略步骤
     */
    @Override
    public void handleIndexBuild(Long documentId, Long taskId, Long planId) {
// 先取出索引构建链路必需的三类核心数据：文档、任务、策略方案。
// 任意一个缺失都说明这条异步消息已经失去执行基础，直接记录告警并退出。
        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        SuperAgentDocumentStrategyPlan plan = planMapper.selectById(planId);
        if (document == null || task == null || plan == null) {
            log.warn("索引任务对应的数据不存在，documentId={}, taskId={}, planId={}", documentId, taskId, planId);
            return;
        }

        Date startTime = new Date();

        List<SuperAgentDocumentStrategyStep> stepList = listSteps(planId);
        try {

            /*
            排序规则是：先父流水线、再子流水线；同一流水线内按 stepNo 升序，最后再按主键兜底。这样保证后续切块执行时步骤顺序是稳定的。
             */
            // 任务正式进入运行态，并把当前阶段推进到"切块执行"。
            task.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
            task.setCurrentStage(DocumentTaskStageEnum.CHUNK_EXECUTE.getCode());
            task.setStartTime(startTime);
            taskMapper.updateById(task);
            // 文档维度同步标记为"构建中"，方便列表页/详情页立刻展示当前状态。
            document.setIndexStatus(DocumentIndexStatusEnum.BUILDING.getCode());
            documentMapper.updateById(document);
            // 将当前方案下所有策略步骤统一置为"执行中"
            updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTING.getCode());

            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
                DocumentTaskEventTypeEnum.START.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "开始执行切块流水线。",
                Map.of("strategySnapshot", plan.getStrategySnapshot()));





            /*
            这是整个流程最核心的一步————读取解析好的文本，然后按策略方案切成父块和子块
             */
            // 读取解析阶段已经落盘的纯文本内容；
            // 索引构建不再重复解析原始文件，而是基于解析后的标准文本继续切块。
            String parsedText = storageService.downloadText(document.getParseTextPath());

            // 根据文档、方案和步骤列表，生成父块候选集。
            // 这个阶段只得到"候选结果"，还没落库，也还没做空内容过滤。 关键方法是：buildParentBlocks()  切块策略的核心入口。我们接着往里看它到底做了什么。
            List<ParentBlockCandidate> parentBlockCandidateList = strategyService.buildParentBlocks(document, plan, stepList, parsedText);
            // 切块成功后，将策略步骤整体标记为"执行成功"
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

            task.setCurrentStage(DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode());
            taskMapper.updateById(task);

            List<ParentBlockCandidate> finalParentBlockList = parentBlockCandidateList.stream()
                .filter(item -> item != null
                    && StrUtil.isNotBlank(item.getText())
                    && item.getChildChunks() != null
                    && item.getChildChunks().stream().anyMatch(child -> StrUtil.isNotBlank(child.getText())))
                .toList();

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

            ParentChildEntityBundle entityBundle = buildParentChildEntities(documentId, taskId, planId, finalParentBlockList);
            List<SuperAgentDocumentParentBlock> parentBlockEntityList = entityBundle.parentBlocks();
            List<SuperAgentDocumentChunk> chunkEntityList = entityBundle.childChunks();

            for (SuperAgentDocumentParentBlock parentBlock : parentBlockEntityList) {
                parentBlockMapper.insert(parentBlock);
            }
            for (SuperAgentDocumentChunk chunk : chunkEntityList) {
                chunkMapper.insert(chunk);
            }

            task.setCurrentStage(DocumentTaskStageEnum.VECTORIZE.getCode());
            taskMapper.updateById(task);

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

            vectorGateway.vectorize(chunkEntityList);

            DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
            if (keywordSearchGateway != null) {
                keywordSearchGateway.indexChunks(chunkEntityList);
            }

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

            task.setCurrentStage(DocumentTaskStageEnum.STORE_COMPLETE.getCode());
            taskMapper.updateById(task);

            plan.setPlanStatus(DocumentPlanStatusEnum.EXECUTED.getCode());
            planMapper.updateById(plan);

            document.setIndexStatus(DocumentIndexStatusEnum.BUILD_SUCCESS.getCode());
            document.setLastIndexTaskId(taskId);
            documentMapper.updateById(document);

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

            document.setIndexStatus(DocumentIndexStatusEnum.BUILD_FAILED.getCode());
            documentMapper.updateById(document);

            chunkMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentChunk>()
                .eq(SuperAgentDocumentChunk::getTaskId, taskId)
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                .set(SuperAgentDocumentChunk::getVectorStatus, DocumentVectorStatusEnum.VECTOR_FAILED.getCode())
                .set(SuperAgentDocumentChunk::getVectorStoreType, DocumentVectorStoreTypeEnum.PG_VECTOR.getCode()));

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

    private ParentChildEntityBundle buildParentChildEntities(Long documentId,
                                                             Long taskId,
                                                             Long planId,
                                                             List<ParentBlockCandidate> parentBlockCandidateList) {
        List<SuperAgentDocumentParentBlock> parentBlockEntityList = new ArrayList<>();
        List<SuperAgentDocumentChunk> chunkEntityList = new ArrayList<>();
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
            parentBlock.setStructureNodeId(parentCandidate.getStructureNodeId());
            parentBlock.setStructureNodeType(parentCandidate.getStructureNodeType());
            parentBlock.setCanonicalPath(parentCandidate.getCanonicalPath());
            parentBlock.setItemIndex(parentCandidate.getItemIndex());
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
                chunk.setStructureNodeId(childCandidate.getStructureNodeId());
                chunk.setStructureNodeType(childCandidate.getStructureNodeType());
                chunk.setCanonicalPath(childCandidate.getCanonicalPath());
                chunk.setItemIndex(childCandidate.getItemIndex());
                chunk.setChunkText(childCandidate.getText().trim());
                chunk.setCharCount(childCandidate.getText().length());

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



    /*updateStepExecuteStatus 是个工具方法，按 planId 全量更新所有步骤的执行状态：
     */
    private void updateStepExecuteStatus(Long planId, Integer executeStatus) {
        // 这里按 planId 全量更新，是因为索引执行对整套策略同时生效，
        // 不区分单个 step 分别推进状态。
        stepMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentStrategyStep>()
            .eq(SuperAgentDocumentStrategyStep::getPlanId, planId)
            .eq(SuperAgentDocumentStrategyStep::getStatus, BusinessStatus.YES.getCode())
            .set(SuperAgentDocumentStrategyStep::getExecuteStatus, executeStatus));
    }




    /*
    然后把任务推进到运行态，同时更新文档和策略步骤的状态

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

    private int getNextPlanVersion(Long documentId) {

        List<SuperAgentDocumentStrategyPlan> planList = planMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentStrategyPlan>()
            .eq(SuperAgentDocumentStrategyPlan::getDocumentId, documentId)
            .eq(SuperAgentDocumentStrategyPlan::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentStrategyPlan::getPlanVersion)
            .last("limit 1"));
        return planList.isEmpty() ? 1 : planList.get(0).getPlanVersion() + 1;
    }


    /*
    是一个通用的任务成功收尾方法
    把任务按成功态收尾，统一写入完成时间、耗时，并清空错误字段。
     */
    private void finishTaskSuccess(SuperAgentDocumentTask task, Integer stage, Date startTime) {

        Date finishTime = new Date();
        task.setTaskStatus(DocumentTaskStatusEnum.SUCCESS.getCode());
        task.setCurrentStage(stage);
        task.setFinishTime(finishTime);
        task.setCostMillis(finishTime.getTime() - startTime.getTime());
        task.setErrorCode(null);
        task.setErrorMsg(null);
        taskMapper.updateById(task);
    }


    /*
    导航产物同步
    同步解析后的导航相关产物。
 * <p>
 * 结构节点落库之后，还可以进一步同步到导航索引和结构图投影。
 * 这些能力是可选组件，因此这里会先判空或判 enabled，再决定是否执行。
 * </p>
 这里用了 Spring 的 ObjectProvider 模式——导航索引服务和图投影服务都是可选依赖，通过 getIfAvailable() 判断是否存在。如果项目没有配置这些组件，就自动跳过，不会报错。
     */
    private void syncNavigationArtifacts(Long documentId,
                                         Long parseTaskId,
                                         List<SuperAgentDocumentStructureNode> structureNodes) {
        log.info("开始同步导航产物: documentId={}, parseTaskId={}, structureNodeCount={}",
            documentId,
            parseTaskId,
            structureNodes == null ? 0 : structureNodes.size());
        DocumentNavigationIndexService navigationIndexService = navigationIndexServiceProvider.getIfAvailable();
        if (navigationIndexService != null) {
            log.info("同步导航 ES 索引: documentId={}, parseTaskId={}", documentId, parseTaskId);
            navigationIndexService.reindexDocumentNodes(documentId, parseTaskId, structureNodes);
        }
        else {
            log.info("跳过导航 ES 索引同步，因为服务未启用: documentId={}, parseTaskId={}", documentId, parseTaskId);
        }
        DocumentStructureGraphProjectionService graphProjectionService = graphProjectionServiceProvider.getIfAvailable();
        if (graphProjectionService != null && graphProjectionService.enabled()) {
            log.info("同步结构图投影: documentId={}, parseTaskId={}", documentId, parseTaskId);
            graphProjectionService.projectToGraph(documentId, parseTaskId);
        }
        else {
            log.info("跳过结构图投影，因为图服务未启用: documentId={}, parseTaskId={}", documentId, parseTaskId);
        }
    }



    /**
     * 把任务按失败态收尾，统一写入完成时间、耗时和错误信息。
     * 关于重试
     * 注意这里没有自动重试机制。如果解析失败了，任务就直接标记为 FAILED，不会自动重新消费。重试依赖外部的 Kafka 重试机制或者人工触发重新上传。
     */
    private void failTask(SuperAgentDocumentTask task, Date startTime, Exception exception, Integer currentStage) {

        Date finishTime = new Date();
        task.setTaskStatus(DocumentTaskStatusEnum.FAILED.getCode());
        task.setCurrentStage(currentStage);
        task.setFinishTime(finishTime);
        task.setCostMillis(finishTime.getTime() - startTime.getTime());
        task.setErrorCode("TASK_FAILED");
        task.setErrorMsg(exception.getMessage());
        taskMapper.updateById(task);
    }

    private int estimateTokenCount(String text) {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        int chineseCount = 0;
        int englishCount = 0;

        for (char current : text.toCharArray()) {
            if (String.valueOf(current).matches("[\\u4e00-\\u9fa5]")) {
                chineseCount++;
            }
        }

        for (String word : text.split("\\s+")) {
            if (word.matches(".*[A-Za-z].*")) {
                englishCount++;
            }
        }

        return chineseCount + englishCount + Math.max(1, (text.length() - chineseCount) / 4);
    }

    private Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> detailMap = new LinkedHashMap<>();

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
