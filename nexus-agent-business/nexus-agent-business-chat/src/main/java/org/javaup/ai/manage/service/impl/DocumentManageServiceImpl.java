package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentDocumentParentBlock;
import org.javaup.ai.manage.data.SuperAgentDocumentProfile;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.data.SuperAgentDocumentTaskLog;
import org.javaup.ai.manage.data.SuperAgentTopicDocumentRelation;
import org.javaup.ai.manage.dto.DocumentChunkQueryDto;
import org.javaup.ai.manage.dto.DocumentChunkDetailQueryDto;
import org.javaup.ai.manage.dto.DocumentDeleteDto;
import org.javaup.ai.manage.dto.DocumentDetailQueryDto;
import org.javaup.ai.manage.dto.DocumentIndexBuildDto;
import org.javaup.ai.manage.dto.DocumentPageQueryDto;
import org.javaup.ai.manage.dto.DocumentStrategyConfirmDto;
import org.javaup.ai.manage.dto.DocumentStrategyPlanQueryDto;
import org.javaup.ai.manage.dto.DocumentStrategyStepItemDto;
import org.javaup.ai.manage.dto.DocumentTaskLogQueryDto;
import org.javaup.ai.manage.dto.DocumentUploadDto;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentProfileMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyPlanMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyStepMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskLogMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.javaup.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.javaup.ai.manage.mq.DocumentKafkaProducer;
import org.javaup.ai.manage.mq.message.DocumentIndexBuildMessage;
import org.javaup.ai.manage.mq.message.DocumentParseRouteMessage;
import org.javaup.ai.manage.service.DocumentManageService;
import org.javaup.ai.manage.service.DocumentNavigationIndexService;
import org.javaup.ai.manage.service.DocumentStorageService;
import org.javaup.ai.manage.service.DocumentStructureGraphProjectionService;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.javaup.ai.manage.service.DocumentStrategyService;
import org.javaup.ai.manage.service.DocumentTaskLogService;
import org.javaup.ai.manage.service.DocumentVectorGateway;
import org.javaup.ai.manage.service.KnowledgeRouteIndexService;
import org.javaup.ai.manage.service.keyword.DocumentKeywordSearchGateway;
import org.javaup.ai.manage.support.StoredObjectInfo;
import org.javaup.ai.manage.vo.DocumentChunkItemVo;
import org.javaup.ai.manage.vo.DocumentChunkQueryVo;
import org.javaup.ai.manage.vo.DocumentChunkDetailVo;
import org.javaup.ai.manage.vo.DocumentDeleteVo;
import org.javaup.ai.manage.vo.DocumentIndexBuildVo;
import org.javaup.ai.manage.vo.DocumentListItemVo;
import org.javaup.ai.manage.vo.DocumentParentBlockItemVo;
import org.javaup.ai.manage.vo.DocumentPageQueryVo;
import org.javaup.ai.manage.vo.DocumentStrategyConfirmVo;
import org.javaup.ai.manage.vo.DocumentStrategyPipelineVo;
import org.javaup.ai.manage.vo.DocumentStrategyPlanQueryVo;
import org.javaup.ai.manage.vo.DocumentStrategyPlanVo;
import org.javaup.ai.manage.vo.DocumentStrategyStepVo;
import org.javaup.ai.manage.vo.DocumentTaskLogQueryVo;
import org.javaup.ai.manage.vo.DocumentTaskLogVo;
import org.javaup.ai.manage.vo.DocumentUploadVo;
import org.javaup.enums.BaseCode;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentChunkSourceTypeEnum;
import org.javaup.enums.DocumentFileTypeEnum;
import org.javaup.enums.DocumentIndexStatusEnum;
import org.javaup.enums.DocumentLogLevelEnum;
import org.javaup.enums.DocumentManageCode;
import org.javaup.enums.DocumentOperatorTypeEnum;
import org.javaup.enums.DocumentParseStatusEnum;
import org.javaup.enums.DocumentPlanSourceEnum;
import org.javaup.enums.DocumentPlanStatusEnum;
import org.javaup.enums.DocumentStorageTypeEnum;
import org.javaup.enums.DocumentStrategyExecuteStatusEnum;
import org.javaup.enums.DocumentStrategyPipelineTypeEnum;
import org.javaup.enums.DocumentStrategyRoleEnum;
import org.javaup.enums.DocumentStrategySourceTypeEnum;
import org.javaup.enums.DocumentStrategyStatusEnum;
import org.javaup.enums.DocumentStrategyTypeEnum;
import org.javaup.enums.DocumentTaskEventTypeEnum;
import org.javaup.enums.DocumentTaskStageEnum;
import org.javaup.enums.DocumentTaskStatusEnum;
import org.javaup.enums.DocumentTaskTypeEnum;
import org.javaup.enums.DocumentTriggerSourceEnum;
import org.javaup.enums.DocumentVectorStatusEnum;
import org.javaup.exception.SuperAgentFrameException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/

@Slf4j
@AllArgsConstructor
@Service
public class DocumentManageServiceImpl implements DocumentManageService {

    private final SuperAgentDocumentMapper documentMapper;

    private final SuperAgentDocumentStrategyPlanMapper planMapper;

    private final SuperAgentDocumentStrategyStepMapper stepMapper;

    private final SuperAgentDocumentTaskMapper taskMapper;

    private final SuperAgentDocumentTaskLogMapper taskLogMapper;

    private final SuperAgentDocumentChunkMapper chunkMapper;

    private final SuperAgentDocumentParentBlockMapper parentBlockMapper;

    private final SuperAgentDocumentProfileMapper documentProfileMapper;

    private final SuperAgentTopicDocumentRelationMapper topicDocumentRelationMapper;

    private final DocumentStorageService storageService;

    private final DocumentStructureNodeService structureNodeService;

    private final DocumentStrategyService strategyService;

    private final DocumentTaskLogService taskLogService;

    private final DocumentVectorGateway vectorGateway;

    private final ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider;

    private final ObjectProvider<DocumentNavigationIndexService> navigationIndexServiceProvider;

    private final ObjectProvider<DocumentStructureGraphProjectionService> graphProjectionServiceProvider;

    private final ObjectProvider<KnowledgeRouteIndexService> knowledgeRouteIndexServiceProvider;

    private final DocumentKafkaProducer kafkaProducer;

    private final TransactionTemplate transactionTemplate;
    
    private final UidGenerator uidGenerator;



    /*
    文件上传，
    TransactionTemplate 手动控制事务边界：
    数据库写入操作（文档主表、任务表、任务日志）放在 transactionTemplate.execute() 内部，
    事务提交成功后再发送 Kafka 消息。
    这样做的目的是避免"消息已发出但事务还没提交"的时间窗口问题。


     */
    @Override
    public DocumentUploadVo upload(MultipartFile file, DocumentUploadDto dto) {





        /*
        第一步：文件校验
         */
        //第一层防线：上传文件对象为空，或者 multipart 中虽然有字段但没有实际内容，都视为非法上传请求
        if (file == null || file.isEmpty()) {
            throw new SuperAgentFrameException(DocumentManageCode.EMPTY_FILE_CONTENT.getCode(),
                DocumentManageCode.EMPTY_FILE_CONTENT.getMsg());
        }
        //原始文件名不仅用于展示，更用于后续识别文件类型，因此缺失时不能继续往下走。
        String originalFileName = file.getOriginalFilename();
        if (StrUtil.isBlank(originalFileName)) {
            throw new SuperAgentFrameException(DocumentManageCode.UNSUPPORTED_FILE_TYPE.getCode(),
                "上传文件缺少原始文件名，无法识别文件类型。");
        }

        //根据文件名后缀识别文档类型；如果无法识别，就不允许进入后续存储和解析流程。  文件类型必须是系统支持的，匹配不上就直接拒绝
        DocumentFileTypeEnum fileType = DocumentFileTypeEnum.fromFileName(originalFileName);
        if (fileType == null) {
            throw new SuperAgentFrameException(DocumentManageCode.UNSUPPORTED_FILE_TYPE.getCode(),
                DocumentManageCode.UNSUPPORTED_FILE_TYPE.getMsg());
        }




        /*
        第二步：读取文件字节 +生成文档id
         */
        //先把 MultipartFile 一次性读取成字节数组，后续存储、文件大小计算都复用这份内存快照。
        byte[] fileBytes = getFileBytes(file);
        //文档主键在上传阶段就提前生成，后面对象存储路径、数据库主表、任务表都要围绕这个 documentId 建关联。
        Long documentId = uidGenerator.getUid();//documentId 通过百度的 UidGenerator 提前生成，这个 ID 后面会贯穿整条链路——对象存储路径里有它，数据库主表的主键是它，任务表也要关联它。





        /*
        第三步：上传文件到MinIO对象存储
         */
        // 原始文件先上传到对象存储，拿到 bucket/object/url 等定位信息后，再写入文档主表。
        StoredObjectInfo storedObjectInfo = storageService.uploadOriginalFile(
                documentId, originalFileName, fileBytes, file.getContentType());



        /*
        第四步：构建文档主记录
        文件上传到 MinIO 之后，接下来要在内存中组装文档主记录对象。
        注意这一步只是构建对象、设置字段，
        还没有真正写入数据库——入库操作统一放在后面的手动事务中执行。
         */
        //文档主表记录的是"这份文档当前是什么状态、文件存在哪里、后续应该进入什么处理阶段"。
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(documentId);
        // 如果前端传了 documentName，就优先使用业务展示名；否则回退到上传时的原始文件名。
        document.setDocumentName(StrUtil.isNotBlank(dto.getDocumentName()) ? dto.getDocumentName() : originalFileName);
        document.setOriginalFileName(originalFileName);
        document.setFileType(fileType.getCode());
        document.setMimeType(file.getContentType());
        document.setFileSize((long) fileBytes.length);
        document.setStorageType(DocumentStorageTypeEnum.MINIO.getCode());
        document.setBucketName(storedObjectInfo.getBucketName());
        document.setObjectName(storedObjectInfo.getObjectName());
        document.setObjectUrl(storedObjectInfo.getObjectUrl());

        //三个状态字段的初始值   (这三个状态会随着后续异步链路的推进逐步更新)
        //上传成功后并不代表解析完成，因此解析状态先标记为 PARSING，表示"已入队等待后续处理链继续推进"。（已经进入解析队列，等待异步处理）
        document.setParseStatus(DocumentParseStatusEnum.PARSING.getCode());
        // 策略和索引阶段都还没有真正执行，所以这里先放到"等待推荐 / 等待构建"的初始态。（策略推荐还没开始）
        document.setStrategyStatus(DocumentStrategyStatusEnum.WAIT_RECOMMEND.getCode());
        //索引构建还没开始
        document.setIndexStatus(DocumentIndexStatusEnum.WAIT_BUILD.getCode());
        // 字符数和 token 数需要等解析文本产出后（拿到纯文本之后）再统计，因此上传阶段先置 0。
        document.setCharCount(0);
        document.setTokenCount(0);

        // 这些字段属于上传时就能确定的业务标签，用于后续检索范围、文档分类、运营筛选等场景。
        document.setKnowledgeScopeCode(StrUtil.trimToNull(dto.getKnowledgeScopeCode()));
        document.setKnowledgeScopeName(StrUtil.trimToNull(dto.getKnowledgeScopeName()));
        document.setBusinessCategory(StrUtil.trimToNull(dto.getBusinessCategory()));
        document.setDocumentTags(StrUtil.trimToNull(dto.getDocumentTags()));
        document.setStatus(BusinessStatus.YES.getCode());





        /*
        第五步：构建任务记录
        文档主记录组装好之后，紧接着要构建一条"解析路由任务"记录。和文档主记录一样，这一步也只是在内存中组装对象，真正的入库操作放在后面的手动事务中统一执行。
         */
        // 上传完成后，不会在当前请求线程里直接做重解析，而是创建一条任务记录交给异步链路处理。
        Long taskId = uidGenerator.getUid();
        SuperAgentDocumentTask task = new SuperAgentDocumentTask();
        task.setId(taskId);
        task.setDocumentId(documentId);
        // 上传之后的第一类任务是"解析路由"，它负责决定后续解析和策略推荐该怎么走。  （taskType = PARSE_ROUTE：这是上传后的第一类任务，叫"解析路由"。它的职责是决定后续的解析和策略推荐该怎么走）
        task.setTaskType(DocumentTaskTypeEnum.PARSE_ROUTE.getCode());
        task.setTaskStatus(DocumentTaskStatusEnum.NEW.getCode());//（taskStatus = NEW：刚创建，还没开始执行）
        // 当前阶段先记为 FILE_UPLOAD，表示这条任务刚从文件上传阶段移交出来。
        task.setCurrentStage(DocumentTaskStageEnum.FILE_UPLOAD.getCode());
        // operatorId 允许为空；为空时表示系统触发，否则表示由具体用户触发。
        Long operatorId = parseOptionalLong(dto.getOperatorId());
        task.setTriggerSource(resolveTriggerSource(operatorId));//triggerSource：通过 resolveTriggerSource() 判断——有 operatorId 就是用户触发，没有就是系统触发
        task.setRetryCount(0);
        task.setStatus(BusinessStatus.YES.getCode());






        /*
        第六步：手动提交事务——————入库+任务日志
        前面两步只是在内存中组装好了文档主记录和任务记录，还没有真正写入数据库。接下来通过 TransactionTemplate 手动开启事务，把文档主表、任务表、任务日志的写入放在同一个事务里执行：
         */
        // 使用 TransactionTemplate 手动控制事务边界，而不是在方法上加 @Transactional。
        // 这样做的核心目的是：确保事务提交之后再发送 Kafka 消息。
        // 如果用 @Transactional，Kafka 发送和数据库操作在同一个事务上下文中，
        // 存在"消息已发出但事务还没提交"的时间窗口——消费方查数据库可能查不到数据。
        DocumentUploadVo uploadVo = transactionTemplate.execute(status -> {
            // 文档主表入库：这是整条上传链路的核心写入，后续任务和日志都要引用 documentId
            documentMapper.insert(document);
            // 任务表入库：创建一条"解析路由"任务，交给异步消费方处理
            taskMapper.insert(task);

            // 任务日志记录：把"上传已完成并已投递异步链路"这件事显式记录下来，
            // 方便后续排查问题时回看时间线
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.FILE_UPLOAD.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                resolveOperatorType(operatorId),
                operatorId,
                "文件上传完成，已进入解析与策略推荐队列。",
                Map.of("originalFileName", originalFileName, "fileSize", fileBytes.length));
//在事务内部就把返回值组装好，确保返回给前端的数据和数据库中的数据完全一致
            return new DocumentUploadVo(documentId, taskId, document.getDocumentName(),
                document.getParseStatus(), document.getStrategyStatus(), document.getIndexStatus());
        });



        /*
        第七步：事务提交后发送kafka消息
        事务提交成功后，再把任务投递到 Kafka，交给异步消费方去做后续的解析和策略推荐：
         */
        // 走到这里说明 transactionTemplate.execute() 已经正常返回，
        // 即文档主表、任务表、任务日志都已经事务提交成功。
        // 此时再发 Kafka 消息，消费方查数据库一定能查到完整的上下文数据。
        kafkaProducer.sendParseRoute(new DocumentParseRouteMessage(documentId, taskId));


        /*
        第八步：返回上传结果
        uploadVo 在事务内部就已经组装好了，这里直接返回给 Controller 层
         */
        return uploadVo;
    }

    @Override
    public DocumentPageQueryVo queryDocumentPage(DocumentPageQueryDto dto) {

        int pageNo = dto.getPageNo() == null || dto.getPageNo() <= 0 ? 1 : dto.getPageNo();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() <= 0 ? 10 : dto.getPageSize();
        String keyword = StrUtil.isNotBlank(dto.getKeyword()) ? dto.getKeyword().trim() : null;

        Page<SuperAgentDocument> page = new Page<>(pageNo, pageSize);
        LambdaQueryWrapper<SuperAgentDocument> wrapper = new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocument::getEditTime, SuperAgentDocument::getId);

        if (keyword != null) {
            wrapper.and(query -> query.like(SuperAgentDocument::getDocumentName, keyword)
                .or()
                .like(SuperAgentDocument::getOriginalFileName, keyword));
        }

        IPage<SuperAgentDocument> resultPage = documentMapper.selectPage(page, wrapper);
        List<SuperAgentDocument> documentList = resultPage.getRecords();
        Map<Long, SuperAgentDocumentTask> latestTaskMap = getLatestTaskMap(documentList);

        List<DocumentListItemVo> records = documentList.stream()
            .map(document -> toDocumentListItemVo(document, latestTaskMap.get(document.getId())))
            .toList();

        return new DocumentPageQueryVo(pageNo, pageSize, resultPage.getTotal(), records);
    }

    @Override
    public DocumentListItemVo queryDocumentDetail(DocumentDetailQueryDto dto) {
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        SuperAgentDocumentTask latestTask = getLatestTask(document.getId());
        return toDocumentListItemVo(document, latestTask);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentDeleteVo deleteDocument(DocumentDeleteDto dto) {
        Long documentId = parseRequiredLong(dto.getDocumentId(), "文档id");
        SuperAgentDocument document = getDocumentOrThrow(documentId);

        long activeTaskCount = taskMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, documentId)
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .in(SuperAgentDocumentTask::getTaskStatus, DocumentTaskStatusEnum.NEW.getCode(), DocumentTaskStatusEnum.RUNNING.getCode()));
        if (activeTaskCount > 0) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STATUS_INVALID.getCode(),
                "当前文档存在进行中的任务，请等待任务结束后再删除。");
        }

        storageService.deleteObjects(List.of(document.getObjectName(), document.getParseTextPath()));
        vectorGateway.deleteByDocumentId(documentId);

        DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
        if (keywordSearchGateway != null) {
            log.info("删除文档关键词索引: documentId={}", documentId);
            keywordSearchGateway.deleteByDocumentId(documentId);
        }
        DocumentNavigationIndexService navigationIndexService = navigationIndexServiceProvider.getIfAvailable();
        if (navigationIndexService != null) {
            log.info("删除文档导航索引: documentId={}", documentId);
            navigationIndexService.deleteByDocumentId(documentId);
        }
        KnowledgeRouteIndexService knowledgeRouteIndexService = knowledgeRouteIndexServiceProvider.getIfAvailable();
        if (knowledgeRouteIndexService != null) {
            log.info("删除知识路由索引中的文档快照: documentId={}", documentId);
            knowledgeRouteIndexService.deleteDocumentRoute(documentId);
        }
        DocumentStructureGraphProjectionService graphProjectionService = graphProjectionServiceProvider.getIfAvailable();
        if (graphProjectionService != null && graphProjectionService.enabled()) {
            log.info("删除文档结构图投影: documentId={}", documentId);
            graphProjectionService.deleteByDocumentId(documentId);
        }

        documentProfileMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
            .eq(SuperAgentDocumentProfile::getDocumentId, documentId));
        topicDocumentRelationMapper.delete(new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
            .eq(SuperAgentTopicDocumentRelation::getDocumentId, documentId));
        parentBlockMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
            .eq(SuperAgentDocumentParentBlock::getDocumentId, documentId));
        chunkMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .eq(SuperAgentDocumentChunk::getDocumentId, documentId));
        structureNodeService.deleteByDocumentId(documentId);
        taskLogMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTaskLog>()
            .eq(SuperAgentDocumentTaskLog::getDocumentId, documentId));
        stepMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentStrategyStep>()
            .eq(SuperAgentDocumentStrategyStep::getDocumentId, documentId));
        taskMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, documentId));
        planMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentStrategyPlan>()
            .eq(SuperAgentDocumentStrategyPlan::getDocumentId, documentId));
        documentMapper.deleteById(documentId);

        return new DocumentDeleteVo(documentId, document.getDocumentName());
    }

    @Override
    public DocumentStrategyPlanQueryVo queryStrategyPlan(DocumentStrategyPlanQueryDto dto) {

        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        DocumentStrategyPlanVo planVo = null;
        boolean planReady = false;

        if (document.getCurrentPlanId() != null) {
            SuperAgentDocumentStrategyPlan plan = planMapper.selectById(document.getCurrentPlanId());
            if (plan != null && Objects.equals(plan.getStatus(), BusinessStatus.YES.getCode())) {
                List<SuperAgentDocumentStrategyStep> stepList = listStepByPlanId(plan.getId());
                planVo = toPlanVo(plan, stepList);
                planReady = true;
            }
        }

        return new DocumentStrategyPlanQueryVo(
            document.getId(),
            document.getDocumentName(),
            document.getParseStatus(),
            enumMsg(DocumentParseStatusEnum.getRc(document.getParseStatus())),
            document.getStrategyStatus(),
            enumMsg(DocumentStrategyStatusEnum.getRc(document.getStrategyStatus())),
            document.getIndexStatus(),
            enumMsg(DocumentIndexStatusEnum.getRc(document.getIndexStatus())),
            document.getParseErrorMsg(),
            planReady,
            planVo
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentStrategyConfirmVo confirmStrategy(DocumentStrategyConfirmDto dto) {

        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        if (!Objects.equals(document.getParseStatus(), DocumentParseStatusEnum.PARSE_SUCCESS.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STATUS_INVALID.getCode(), "当前文档还未完成解析，不能确认策略。");
        }

        if (!Objects.equals(document.getCurrentPlanId(), dto.getBasePlanId())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(), "当前文档的基础方案不存在或已切换。");
        }

        SuperAgentDocumentStrategyPlan basePlan = planMapper.selectById(dto.getBasePlanId());
        if (basePlan == null || !Objects.equals(basePlan.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(),
                DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getMsg());
        }

        List<SuperAgentDocumentStrategyStep> baseStepList = listStepByPlanId(basePlan.getId());
        List<Integer> requestParentTypeList = dto.getParentSteps().stream()
            .sorted(Comparator.comparing(item -> item.getStepNo() == null ? Integer.MAX_VALUE : item.getStepNo()))
            .map(DocumentStrategyStepItemDto::getStrategyType)
            .filter(Objects::nonNull)
            .toList();
        List<Integer> requestChildTypeList = dto.getChildSteps().stream()
            .sorted(Comparator.comparing(item -> item.getStepNo() == null ? Integer.MAX_VALUE : item.getStepNo()))
            .map(DocumentStrategyStepItemDto::getStrategyType)
            .filter(Objects::nonNull)
            .toList();

        List<SuperAgentDocumentStrategyStep> normalizedStepList = strategyService.normalizeSteps(
            basePlan, baseStepList, requestParentTypeList, requestChildTypeList, dto.getDocumentId());

        List<Integer> normalizedParentTypeList = extractPipelineTypes(normalizedStepList, DocumentStrategyPipelineTypeEnum.PARENT);
        List<Integer> normalizedChildTypeList = extractPipelineTypes(normalizedStepList, DocumentStrategyPipelineTypeEnum.CHILD);

        if (normalizedParentTypeList.isEmpty()) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_STEP_EMPTY.getCode(), "父块流水线不能为空。");
        }
        if (normalizedChildTypeList.isEmpty()) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_STEP_EMPTY.getCode(), "子块流水线不能为空。");
        }

        if (normalizedStepList.isEmpty()) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_STEP_EMPTY.getCode(),
                DocumentManageCode.STRATEGY_STEP_EMPTY.getMsg());
        }

        List<Integer> baseParentTypeList = extractPipelineTypes(baseStepList, DocumentStrategyPipelineTypeEnum.PARENT);
        List<Integer> baseChildTypeList = extractPipelineTypes(baseStepList, DocumentStrategyPipelineTypeEnum.CHILD);
        List<Integer> requestDistinctParentTypeList = new LinkedHashSet<>(requestParentTypeList).stream().toList();
        List<Integer> requestDistinctChildTypeList = new LinkedHashSet<>(requestChildTypeList).stream().toList();

        boolean normalized = !requestDistinctParentTypeList.equals(normalizedParentTypeList)
            || !requestDistinctChildTypeList.equals(normalizedChildTypeList);

        boolean changed = !baseParentTypeList.equals(normalizedParentTypeList)
            || !baseChildTypeList.equals(normalizedChildTypeList);

        Long targetPlanId;
        Integer targetPlanVersion;
        List<SuperAgentDocumentStrategyStep> targetStepList;

        if (!changed) {

            basePlan.setPlanStatus(DocumentPlanStatusEnum.CONFIRMED.getCode());
            basePlan.setPlanSource(basePlan.getPlanSource() == null ? DocumentPlanSourceEnum.SYSTEM_RECOMMEND.getCode() : basePlan.getPlanSource());
            basePlan.setAdjustNote(dto.getAdjustNote());
            basePlan.setConfirmUserId(dto.getOperatorId());
            basePlan.setConfirmTime(new Date());
            planMapper.updateById(basePlan);
            targetPlanId = basePlan.getId();
            targetPlanVersion = basePlan.getPlanVersion();
            targetStepList = baseStepList;
        } else {

            basePlan.setPlanStatus(DocumentPlanStatusEnum.DISCARDED.getCode());
            planMapper.updateById(basePlan);

            Long newPlanId = uidGenerator.getUid();
            Integer newPlanVersion = getNextPlanVersion(document.getId());
            SuperAgentDocumentStrategyPlan newPlan = new SuperAgentDocumentStrategyPlan();
            newPlan.setId(newPlanId);
            newPlan.setDocumentId(document.getId());
            newPlan.setPlanVersion(newPlanVersion);

            newPlan.setPlanSource(DocumentPlanSourceEnum.USER_ADJUST.getCode());
            newPlan.setPlanStatus(DocumentPlanStatusEnum.CONFIRMED.getCode());
            newPlan.setStrategyCount(normalizedStepList.size());
            newPlan.setStrategySnapshot(buildStrategySnapshot(normalizedStepList));
            newPlan.setRecommendReason(basePlan.getRecommendReason());
            newPlan.setAdjustNote(dto.getAdjustNote());
            newPlan.setConfirmUserId(dto.getOperatorId());
            newPlan.setConfirmTime(new Date());
            newPlan.setStatus(BusinessStatus.YES.getCode());
            planMapper.insert(newPlan);

            for (SuperAgentDocumentStrategyStep step : normalizedStepList) {
                step.setId(uidGenerator.getUid());
                step.setPlanId(newPlanId);
                step.setStatus(BusinessStatus.YES.getCode());
                stepMapper.insert(step);
            }

            targetPlanId = newPlanId;
            targetPlanVersion = newPlanVersion;
            targetStepList = normalizedStepList;
        }

        document.setCurrentPlanId(targetPlanId);
        document.setStrategyStatus(DocumentStrategyStatusEnum.CONFIRMED.getCode());
        documentMapper.updateById(document);

        SuperAgentDocumentTask latestParseTask = getLatestTask(document.getId(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode());
        if (latestParseTask != null) {

            latestParseTask.setCurrentStage(DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode());
            taskMapper.updateById(latestParseTask);

            if (changed) {

                taskLogService.saveLog(latestParseTask.getId(), document.getId(),
                    DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode(),
                    DocumentTaskEventTypeEnum.USER_ADJUST.getCode(),
                    DocumentLogLevelEnum.INFO.getCode(),
                    resolveOperatorType(parseOptionalLong(dto.getOperatorId())),
                    parseOptionalLong(dto.getOperatorId()),
                    "用户调整了系统推荐策略。",
                    detail("parentStrategyTypes", normalizedParentTypeList,
                        "childStrategyTypes", normalizedChildTypeList,
                        "adjustNote", dto.getAdjustNote()));
            }

            taskLogService.saveLog(latestParseTask.getId(), document.getId(),
                DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode(),
                DocumentTaskEventTypeEnum.USER_CONFIRM.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                    resolveOperatorType(parseOptionalLong(dto.getOperatorId())),
                    parseOptionalLong(dto.getOperatorId()),
                    "用户已确认最终策略方案。",
                Map.of("planId", targetPlanId,
                    "parentStrategyTypes", normalizedParentTypeList,
                    "childStrategyTypes", normalizedChildTypeList));
        }

        return new DocumentStrategyConfirmVo(
            document.getId(),
            targetPlanId,
            targetPlanVersion,
            document.getStrategyStatus(),
            enumMsg(DocumentStrategyStatusEnum.getRc(document.getStrategyStatus())),
            normalized,
            toPipelineVo(DocumentStrategyPipelineTypeEnum.PARENT, targetStepList),
            toPipelineVo(DocumentStrategyPipelineTypeEnum.CHILD, targetStepList)
        );
    }




    /*
      文档状态校验
      这段代码做了两件事：
1.先确认文档存在
2.再确认文档已经走完了"解析成功"和"策略确认"两个前置步骤
为什么要同时校验这两个状态？
索引构建依赖解析阶段产出的纯文本，也依赖策略方案里定义的切块规则。如果解析没完成，就没有文本可切；如果策略没确认，就不知道该怎么切。两个条件缺一不可。

     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentIndexBuildVo buildIndex(DocumentIndexBuildDto dto) {

        // 先拿到文档主记录；如果文档不存在或已被逻辑删除，会在这里直接抛错拦截。
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        // 索引构建必须建立在"文档解析完成 + 策略已经人工确认"这两个前提之上，
        // 否则异步链路无法拿到稳定的结构化结果和最终生效的处理策略。
        if (!Objects.equals(document.getParseStatus(), DocumentParseStatusEnum.PARSE_SUCCESS.getCode())
            || !Objects.equals(document.getStrategyStatus(), DocumentStrategyStatusEnum.CONFIRMED.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STATUS_INVALID.getCode(), "当前文档尚未完成“解析成功 + 策略确认”，不能构建索引。");
        }

        /*
        方案一致性校验
        // 请求里传入的 planId 必须和文档当前生效方案一致，
        // 避免前端缓存旧方案、重复点击，导致索引基于过期策略生成。
        这个校验防的是一种很常见的场景：用户打开了页面，但方案后来被别人改过了，这时候如果还用旧的 planId 去构建索引，产出的结果就和预期不一致了。
         */
        if (!Objects.equals(document.getCurrentPlanId(), dto.getPlanId())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(), "当前文档的生效方案与请求方案不一致。");
        }

        /*
        防重复提交
        直接查数据库看有没有正在跑的索引任务，有的话就拒绝。简单粗暴但很有效。
        // 查询当前文档是否已经存在"新建中/执行中"的索引任务，
        // 用于防止重复点击构建按钮后生成多个并发任务，造成重复切块和重复写索引。
         */
        long runningTaskCount = taskMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, dto.getDocumentId())
            .eq(SuperAgentDocumentTask::getTaskType, DocumentTaskTypeEnum.BUILD_INDEX.getCode())
            .in(SuperAgentDocumentTask::getTaskStatus, DocumentTaskStatusEnum.NEW.getCode(), DocumentTaskStatusEnum.RUNNING.getCode())
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode()));
        if (runningTaskCount > 0) {
            throw new SuperAgentFrameException(DocumentManageCode.INDEX_TASK_RUNNING.getCode(),
                DocumentManageCode.INDEX_TASK_RUNNING.getMsg());
        }


        /*
        创建索引任务
        这里有个很关键的设计：策略快照会被固化到任务记录上。
        为什么要这么做？因为从任务创建到 Kafka 消费端真正执行，中间可能隔了几秒甚至几分钟，这段时间里方案有可能被修改。
        把快照存下来，就能保证异步执行时用的一定是当时确认的那个版本。
        // 重新从数据库读取方案快照，而不是完全信任请求参数，
        // 确保后面写入任务的 strategySnapshot 来自一条真实存在且有效的方案记录。
         */
        SuperAgentDocumentStrategyPlan plan = planMapper.selectById(dto.getPlanId());
        if (plan == null || !Objects.equals(plan.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(),
                DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getMsg());
        }

        // 为本次索引构建生成独立任务记录；后续异步链路会围绕这个 taskId 更新阶段、日志和结果。
        Long taskId = uidGenerator.getUid();
        SuperAgentDocumentTask task = new SuperAgentDocumentTask();
        task.setId(taskId);
        task.setDocumentId(document.getId());
        task.setPlanId(dto.getPlanId());
        task.setTaskType(DocumentTaskTypeEnum.BUILD_INDEX.getCode());
        // 索引链路的首个执行阶段从 chunk 开始，后续消费者会继续推进阶段流转。
        task.setTaskStatus(DocumentTaskStatusEnum.NEW.getCode());
        task.setCurrentStage(DocumentTaskStageEnum.CHUNK_EXECUTE.getCode());
        // 操作人 ID 允许为空：为空时表示系统触发，否则表示用户主动触发。
        Long operatorId = parseOptionalLong(dto.getOperatorId());
        task.setTriggerSource(resolveTriggerSource(operatorId));
        // 将策略快照固化到任务上，保证异步执行时即使方案后续被修改，也仍然以当时确认的版本执行。
        task.setStrategySnapshot(plan.getStrategySnapshot());
        task.setRetryCount(0);
        task.setStatus(BusinessStatus.YES.getCode());
        taskMapper.insert(task);

        /*
        更新状态+投递Kafka消息

         */
        // 任务创建成功后，立即把文档索引状态切换成"构建中"，
        // 这样列表页和详情页能第一时间反映当前文档正处于索引处理中。
        document.setIndexStatus(DocumentIndexStatusEnum.BUILDING.getCode());
        documentMapper.updateById(document);

        // 记录一条启动日志
        taskLogService.saveLog(taskId, document.getId(),
            DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
            DocumentTaskEventTypeEnum.START.getCode(),
            DocumentLogLevelEnum.INFO.getCode(),
            resolveOperatorType(operatorId),
            operatorId,
            "索引构建任务已创建，等待异步执行。",
            Map.of("planId", dto.getPlanId(), "strategySnapshot", plan.getStrategySnapshot()));

        // 这里只负责投递消息，不同步执行业务重逻辑，避免接口阻塞太久；
        // 真正的索引构建会由 MQ 消费端接手。
        kafkaProducer.sendIndexBuild(new DocumentIndexBuildMessage(document.getId(), taskId, dto.getPlanId()));

        // 最后返回给前端一个即时结果，告诉它"任务已受理"：
        return new DocumentIndexBuildVo(
            document.getId(),
            taskId,
            task.getTaskType(),
            enumMsg(DocumentTaskTypeEnum.getRc(task.getTaskType())),
            task.getTaskStatus(),
            enumMsg(DocumentTaskStatusEnum.getRc(task.getTaskStatus())),
            document.getIndexStatus(),
            enumMsg(DocumentIndexStatusEnum.getRc(document.getIndexStatus()))
        );
    }

    @Override
    public DocumentTaskLogQueryVo queryTaskLogs(DocumentTaskLogQueryDto dto) {

        SuperAgentDocumentTask task = taskMapper.selectById(dto.getTaskId());
        if (task == null || !Objects.equals(task.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "任务不存在。");
        }

        int pageNo = dto.getPageNo() == null || dto.getPageNo() <= 0 ? 1 : dto.getPageNo();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() <= 0 ? 20 : dto.getPageSize();
        Page<SuperAgentDocumentTaskLog> page = new Page<>(pageNo, pageSize);

        IPage<SuperAgentDocumentTaskLog> resultPage = taskLogMapper.selectPage(page,
            new LambdaQueryWrapper<SuperAgentDocumentTaskLog>()
                .eq(SuperAgentDocumentTaskLog::getTaskId, dto.getTaskId())
                .eq(SuperAgentDocumentTaskLog::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentTaskLog::getCreateTime, SuperAgentDocumentTaskLog::getId));

        List<DocumentTaskLogVo> logVoList = resultPage.getRecords().stream()
            .map(this::toTaskLogVo)
            .toList();

        return new DocumentTaskLogQueryVo(
            task.getId(),
            task.getDocumentId(),
            task.getTaskType(),
            enumMsg(DocumentTaskTypeEnum.getRc(task.getTaskType())),
            task.getTaskStatus(),
            enumMsg(DocumentTaskStatusEnum.getRc(task.getTaskStatus())),
            task.getCurrentStage(),
            enumMsg(DocumentTaskStageEnum.getRc(task.getCurrentStage())),
            task.getStartTime(),
            task.getFinishTime(),
            task.getCostMillis(),
            task.getErrorCode(),
            task.getErrorMsg(),
            resultPage.getTotal(),
            logVoList
        );
    }

    @Override
    public DocumentChunkQueryVo queryDocumentChunks(DocumentChunkQueryDto dto) {
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        int pageNo = dto.getPageNo() == null || dto.getPageNo() <= 0 ? 1 : dto.getPageNo();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() <= 0 ? 20 : dto.getPageSize();

        Long effectiveTaskId = resolveChunkTaskId(document, dto.getTaskId());
        if (effectiveTaskId == null) {
            return new DocumentChunkQueryVo(document.getId(), null, document.getCurrentPlanId(), pageNo, pageSize, 0L, List.of());
        }

        SuperAgentDocumentTask task = taskMapper.selectById(effectiveTaskId);
        if (task == null
            || !Objects.equals(task.getStatus(), BusinessStatus.YES.getCode())
            || !Objects.equals(task.getDocumentId(), document.getId())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "切块任务不存在。");
        }

        Page<SuperAgentDocumentChunk> page = new Page<>(pageNo, pageSize);
        IPage<SuperAgentDocumentChunk> resultPage = chunkMapper.selectPage(page,
            new LambdaQueryWrapper<SuperAgentDocumentChunk>()
                .eq(SuperAgentDocumentChunk::getDocumentId, document.getId())
                .eq(SuperAgentDocumentChunk::getTaskId, effectiveTaskId)
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentChunk::getChunkNo, SuperAgentDocumentChunk::getId));

        Map<Long, SuperAgentDocumentParentBlock> parentBlockMap = listParentBlockMap(
            resultPage.getRecords().stream()
                .map(SuperAgentDocumentChunk::getParentBlockId)
                .filter(Objects::nonNull)
                .toList()
        );

        List<DocumentChunkItemVo> records = resultPage.getRecords().stream()
            .map(chunk -> toDocumentChunkItemVo(chunk, parentBlockMap.get(chunk.getParentBlockId())))
            .toList();

        return new DocumentChunkQueryVo(
            document.getId(),
            effectiveTaskId,
            task.getPlanId(),
            pageNo,
            pageSize,
            resultPage.getTotal(),
            records
        );
    }

    @Override
    public DocumentChunkDetailVo queryDocumentChunkDetail(DocumentChunkDetailQueryDto dto) {
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        Long effectiveTaskId = resolveChunkTaskId(document, dto.getTaskId());
        if (effectiveTaskId == null) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "当前文档还没有可查看的 chunk 详情。");
        }

        SuperAgentDocumentTask task = taskMapper.selectById(effectiveTaskId);
        if (task == null
            || !Objects.equals(task.getStatus(), BusinessStatus.YES.getCode())
            || !Objects.equals(task.getDocumentId(), document.getId())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "切块任务不存在。");
        }

        SuperAgentDocumentChunk chunk = chunkMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .eq(SuperAgentDocumentChunk::getId, dto.getChunkId())
            .eq(SuperAgentDocumentChunk::getDocumentId, document.getId())
            .eq(SuperAgentDocumentChunk::getTaskId, effectiveTaskId)
            .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
            .last("limit 1"));
        if (chunk == null) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "chunk 详情不存在。");
        }

        SuperAgentDocumentParentBlock parentBlock = chunk.getParentBlockId() == null
            ? null
            : parentBlockMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
                .eq(SuperAgentDocumentParentBlock::getId, chunk.getParentBlockId())
                .eq(SuperAgentDocumentParentBlock::getDocumentId, document.getId())
                .eq(SuperAgentDocumentParentBlock::getTaskId, effectiveTaskId)
                .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode())
                .last("limit 1"));

        List<SuperAgentDocumentChunk> siblingChunkList = chunk.getParentBlockId() == null
            ? List.of(chunk)
            : chunkMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
                .eq(SuperAgentDocumentChunk::getDocumentId, document.getId())
                .eq(SuperAgentDocumentChunk::getTaskId, effectiveTaskId)
                .eq(SuperAgentDocumentChunk::getParentBlockId, chunk.getParentBlockId())
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentChunk::getChunkNo, SuperAgentDocumentChunk::getId));

        return new DocumentChunkDetailVo(
            document.getId(),
            effectiveTaskId,
            task.getPlanId(),
            toDocumentChunkItemVo(chunk, parentBlock),
            toDocumentParentBlockItemVo(parentBlock),
            siblingChunkList.stream()
                .map(item -> toDocumentChunkItemVo(item, parentBlock))
                .toList()
        );
    }

    private SuperAgentDocument getDocumentOrThrow(Long documentId) {

        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null || !Objects.equals(document.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(),
                DocumentManageCode.DOCUMENT_NOT_FOUND.getMsg());
        }
        return document;
    }

    private List<SuperAgentDocumentStrategyStep> listStepByPlanId(Long planId) {
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

    private Integer getNextPlanVersion(Long documentId) {

        SuperAgentDocumentStrategyPlan latestPlan = planMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentStrategyPlan>()
            .eq(SuperAgentDocumentStrategyPlan::getDocumentId, documentId)
            .eq(SuperAgentDocumentStrategyPlan::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentStrategyPlan::getPlanVersion)
            .last("limit 1"));
        return latestPlan == null ? 1 : latestPlan.getPlanVersion() + 1;
    }

    private SuperAgentDocumentTask getLatestTask(Long documentId, Integer taskType) {

        return taskMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, documentId)
            .eq(SuperAgentDocumentTask::getTaskType, taskType)
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentTask::getId)
            .last("limit 1"));
    }

    private SuperAgentDocumentTask getLatestTask(Long documentId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, documentId)
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentTask::getId)
            .last("limit 1"));
    }

    private Map<Long, SuperAgentDocumentTask> getLatestTaskMap(List<SuperAgentDocument> documentList) {
        if (documentList == null || documentList.isEmpty()) {
            return Map.of();
        }

        Set<Long> documentIdSet = documentList.stream()
            .map(SuperAgentDocument::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (documentIdSet.isEmpty()) {
            return Map.of();
        }

        List<SuperAgentDocumentTask> taskList = taskMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .in(SuperAgentDocumentTask::getDocumentId, documentIdSet)
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentTask::getId));

        Map<Long, SuperAgentDocumentTask> latestTaskMap = new LinkedHashMap<>();
        for (SuperAgentDocumentTask task : taskList) {
            latestTaskMap.putIfAbsent(task.getDocumentId(), task);
        }
        return latestTaskMap;
    }

    private Long resolveChunkTaskId(SuperAgentDocument document, Long requestedTaskId) {
        if (requestedTaskId != null) {
            return requestedTaskId;
        }
        if (document.getLastIndexTaskId() != null) {
            return document.getLastIndexTaskId();
        }
        SuperAgentDocumentTask latestBuildTask = getLatestTask(document.getId(), DocumentTaskTypeEnum.BUILD_INDEX.getCode());
        return latestBuildTask == null ? null : latestBuildTask.getId();
    }

    private DocumentListItemVo toDocumentListItemVo(SuperAgentDocument document, SuperAgentDocumentTask latestTask) {
        return new DocumentListItemVo(
            document.getId(),
            document.getDocumentName(),
            document.getOriginalFileName(),
            document.getFileType(),
            enumMsg(DocumentFileTypeEnum.getRc(document.getFileType())),
            document.getFileSize(),
            document.getCharCount(),
            document.getTokenCount(),
            document.getParseStatus(),
            enumMsg(DocumentParseStatusEnum.getRc(document.getParseStatus())),
            document.getStrategyStatus(),
            enumMsg(DocumentStrategyStatusEnum.getRc(document.getStrategyStatus())),
            document.getIndexStatus(),
            enumMsg(DocumentIndexStatusEnum.getRc(document.getIndexStatus())),
            document.getParseErrorMsg(),
            document.getKnowledgeScopeCode(),
            document.getKnowledgeScopeName(),
            document.getBusinessCategory(),
            document.getDocumentTags(),
            document.getCurrentPlanId(),
            document.getLastIndexTaskId(),
            latestTask == null ? null : latestTask.getId(),
            latestTask == null ? null : latestTask.getTaskType(),
            latestTask == null ? "" : enumMsg(DocumentTaskTypeEnum.getRc(latestTask.getTaskType())),
            latestTask == null ? null : latestTask.getTaskStatus(),
            latestTask == null ? "" : enumMsg(DocumentTaskStatusEnum.getRc(latestTask.getTaskStatus())),
            document.getCreateTime(),
            document.getEditTime()
        );
    }

    private DocumentChunkItemVo toDocumentChunkItemVo(SuperAgentDocumentChunk chunk,
                                                     SuperAgentDocumentParentBlock parentBlock) {
        return new DocumentChunkItemVo(
            chunk.getId(),
            chunk.getParentBlockId(),
            parentBlock == null ? null : parentBlock.getParentNo(),
            parentBlock == null ? null : parentBlock.getChildCount(),
            parentBlock == null ? null : parentBlock.getStartChunkNo(),
            parentBlock == null ? null : parentBlock.getEndChunkNo(),
            chunk.getChunkNo(),
            chunk.getSectionPath(),
            chunk.getSourceType(),
            enumMsg(DocumentChunkSourceTypeEnum.getRc(chunk.getSourceType())),
            chunk.getCharCount(),
            chunk.getTokenCount(),
            chunk.getVectorStatus(),
            enumMsg(DocumentVectorStatusEnum.getRc(chunk.getVectorStatus())),
            chunk.getChunkText()
        );
    }

    private DocumentParentBlockItemVo toDocumentParentBlockItemVo(SuperAgentDocumentParentBlock parentBlock) {
        if (parentBlock == null) {
            return null;
        }
        return new DocumentParentBlockItemVo(
            parentBlock.getId(),
            parentBlock.getParentNo(),
            parentBlock.getSectionPath(),
            parentBlock.getSourceType(),
            enumMsg(DocumentChunkSourceTypeEnum.getRc(parentBlock.getSourceType())),
            parentBlock.getCharCount(),
            parentBlock.getTokenCount(),
            parentBlock.getChildCount(),
            parentBlock.getStartChunkNo(),
            parentBlock.getEndChunkNo(),
            parentBlock.getParentText()
        );
    }

    private Map<Long, SuperAgentDocumentParentBlock> listParentBlockMap(List<Long> parentBlockIds) {
        if (parentBlockIds == null || parentBlockIds.isEmpty()) {
            return Map.of();
        }
        return parentBlockMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
                .in(SuperAgentDocumentParentBlock::getId, parentBlockIds)
                .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .collect(Collectors.toMap(
                SuperAgentDocumentParentBlock::getId,
                item -> item,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private DocumentStrategyPlanVo toPlanVo(SuperAgentDocumentStrategyPlan plan, List<SuperAgentDocumentStrategyStep> stepList) {
        return new DocumentStrategyPlanVo(
            plan.getId(),
            plan.getPlanVersion(),
            plan.getPlanSource(),
            enumMsg(DocumentPlanSourceEnum.getRc(plan.getPlanSource())),
            plan.getPlanStatus(),
            enumMsg(DocumentPlanStatusEnum.getRc(plan.getPlanStatus())),
            plan.getStrategySnapshot(),
            plan.getRecommendReason(),
            toPipelineVo(DocumentStrategyPipelineTypeEnum.PARENT, stepList),
            toPipelineVo(DocumentStrategyPipelineTypeEnum.CHILD, stepList)
        );
    }

    private List<DocumentStrategyStepVo> toStepVoList(List<SuperAgentDocumentStrategyStep> stepList) {

        return stepList.stream()
            .sorted(Comparator
                .comparingInt((SuperAgentDocumentStrategyStep step) -> pipelineOrder(step.getPipelineType()))
                .thenComparing(SuperAgentDocumentStrategyStep::getStepNo)
                .thenComparing(SuperAgentDocumentStrategyStep::getId))
            .map(step -> new DocumentStrategyStepVo(
                step.getStepNo(),
                step.getPipelineType(),
                enumMsg(DocumentStrategyPipelineTypeEnum.getRc(step.getPipelineType())),
                step.getStrategyType(),
                enumMsg(DocumentStrategyTypeEnum.getRc(step.getStrategyType())),
                step.getStrategyRole(),
                enumMsg(DocumentStrategyRoleEnum.getRc(step.getStrategyRole())),
                step.getSourceType(),
                enumMsg(DocumentStrategySourceTypeEnum.getRc(step.getSourceType())),
                step.getExecuteStatus(),
                enumMsg(DocumentStrategyExecuteStatusEnum.getRc(step.getExecuteStatus())),
                step.getRecommendReason()
            ))
            .toList();
    }

    private DocumentStrategyPipelineVo toPipelineVo(DocumentStrategyPipelineTypeEnum pipelineType,
                                                    List<SuperAgentDocumentStrategyStep> stepList) {
        List<SuperAgentDocumentStrategyStep> pipelineSteps = stepList.stream()
            .filter(step -> pipelineType.getCode().equalsIgnoreCase(
                StrUtil.blankToDefault(step.getPipelineType(), DocumentStrategyPipelineTypeEnum.CHILD.getCode())
            ))
            .sorted(Comparator.comparingInt(SuperAgentDocumentStrategyStep::getStepNo))
            .toList();
        return new DocumentStrategyPipelineVo(
            pipelineType.getCode(),
            pipelineType.getMsg(),
            pipelineSteps.stream().map(step -> String.valueOf(step.getStrategyType())).collect(Collectors.joining(",")),
            toStepVoList(pipelineSteps)
        );
    }

    private List<Integer> extractPipelineTypes(List<SuperAgentDocumentStrategyStep> stepList,
                                               DocumentStrategyPipelineTypeEnum pipelineType) {
        return stepList.stream()
            .filter(step -> pipelineType.getCode().equalsIgnoreCase(
                StrUtil.blankToDefault(step.getPipelineType(), DocumentStrategyPipelineTypeEnum.CHILD.getCode())
            ))
            .sorted(Comparator.comparingInt(SuperAgentDocumentStrategyStep::getStepNo))
            .map(SuperAgentDocumentStrategyStep::getStrategyType)
            .toList();
    }

    private String buildStrategySnapshot(List<SuperAgentDocumentStrategyStep> stepList) {
        return "PARENT:" + toPipelineVo(DocumentStrategyPipelineTypeEnum.PARENT, stepList).getStrategySnapshot()
            + ";CHILD:" + toPipelineVo(DocumentStrategyPipelineTypeEnum.CHILD, stepList).getStrategySnapshot();
    }

    private int pipelineOrder(String pipelineType) {
        return DocumentStrategyPipelineTypeEnum.PARENT.getCode().equalsIgnoreCase(
            StrUtil.blankToDefault(pipelineType, "")
        ) ? 0 : 1;
    }

    private DocumentTaskLogVo toTaskLogVo(SuperAgentDocumentTaskLog logRecord) {
        return new DocumentTaskLogVo(
            logRecord.getId(),
            logRecord.getStageType(),
            enumMsg(DocumentTaskStageEnum.getRc(logRecord.getStageType())),
            logRecord.getEventType(),
            enumMsg(DocumentTaskEventTypeEnum.getRc(logRecord.getEventType())),
            logRecord.getLogLevel(),
            enumMsg(DocumentLogLevelEnum.getRc(logRecord.getLogLevel())),
            logRecord.getContent(),
            logRecord.getDetailJson(),
            logRecord.getCreateTime()
        );
    }

    private Integer resolveOperatorType(Long operatorId) {

        return operatorId == null ? DocumentOperatorTypeEnum.SYSTEM.getCode() : DocumentOperatorTypeEnum.USER.getCode();
    }


    /*
    辅助方法，根据操作人ID判断任务触发来源
     */
    private Integer resolveTriggerSource(Long operatorId) {

        return operatorId == null ? DocumentTriggerSourceEnum.SYSTEM.getCode() : DocumentTriggerSourceEnum.USER.getCode();
    }




    /*
    辅助方法
    解析可选的字符串ID
    上传接口中的一些字段允许不传，因此这里采取宽松解析策略
    空白串，非数字，非正数都统一视为null，而不是直接抛异常  （不会因为前端传了个奇怪的值就把整个上传请求打挂）
     */
    private Long parseOptionalLong(String rawValue) {
        if (StrUtil.isBlank(rawValue)) {
            return null;
        }
        try {
            //只有正整数才被视为有效 ID；0 或负数在业务上不具备合法主键语义。
            Long value = Long.valueOf(rawValue.trim());
            return value > 0 ? value : null;
        }
        catch (NumberFormatException exception) {
            // 可选字段解析失败时不打断主流程，直接按未传处理。
            return null;
        }
    }

    private Long parseOptionalLong(Long rawValue) {
        return rawValue == null || rawValue <= 0 ? null : rawValue;
    }

    private Long parseRequiredLong(String rawValue, String fieldName) {
        if (StrUtil.isBlank(rawValue)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "不能为空。");
        }

        try {

            Long value = Long.valueOf(rawValue.trim());
            if (value <= 0) {
                throw new NumberFormatException("id must be positive");
            }
            return value;
        }
        catch (NumberFormatException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "格式不正确。");
        }
    }

    private String enumMsg(Object enumObject) {
        if (enumObject == null) {
            return "";
        }
        if (enumObject instanceof DocumentParseStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentFileTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentStrategyStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentIndexStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentPlanSourceEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentPlanStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentStrategyTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentStrategyRoleEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentStrategySourceTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentStrategyExecuteStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentTaskTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentTaskStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentTaskStageEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentTaskEventTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentLogLevelEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentChunkSourceTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentVectorStatusEnum value) {
            return value.getMsg();
        }
        return "";
    }



    /*
    是一个私有方法，把 MultipartFile 的输入流一次性读成 byte[]：
    从 Spring 的 {@link MultipartFile} 中提取原始字节数组。
 <p>
上传链路后续要做对象存储上传、文件大小统计、任务日志记录等操作，
 因此这里先把输入流稳定地读取成字节数组，避免在多个地方重复读取 multipart 流。
 </p>
 为什么要先读成：byte[]？因为后续对象存储上传要用，文件大小计算也要用一次性读出来复用比多次读流更稳定

     */
    private byte[] getFileBytes(MultipartFile file) {
        try {

            return file.getBytes();
        }
        catch (IOException exception) {
            //读取文件体失败属于上传链路的底层IO异常，统一包装成文档存储失败，便于前端和日志统一识别
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STORAGE_FAILED.getCode(),
                "读取上传文件内容失败: " + exception.getMessage(), exception);
        }
    }

    private Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> detailMap = new LinkedHashMap<>();

        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            detailMap.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return detailMap;
    }
}
