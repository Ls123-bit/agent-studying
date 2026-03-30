package org.javaup.ai.manage.service.impl;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.data.SuperAgentDocumentTaskLog;
import org.javaup.ai.manage.dto.DocumentIndexBuildDto;
import org.javaup.ai.manage.dto.DocumentPageQueryDto;
import org.javaup.ai.manage.dto.DocumentStrategyConfirmDto;
import org.javaup.ai.manage.dto.DocumentStrategyPlanQueryDto;
import org.javaup.ai.manage.dto.DocumentTaskLogQueryDto;
import org.javaup.ai.manage.dto.DocumentUploadDto;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyPlanMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyStepMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskLogMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.javaup.ai.manage.mq.DocumentKafkaProducer;
import org.javaup.ai.manage.mq.message.DocumentIndexBuildMessage;
import org.javaup.ai.manage.mq.message.DocumentParseRouteMessage;
import org.javaup.ai.manage.service.DocumentManageService;
import org.javaup.ai.manage.service.DocumentStorageService;
import org.javaup.ai.manage.service.DocumentStrategyService;
import org.javaup.ai.manage.service.DocumentTaskLogService;
import org.javaup.ai.manage.support.StoredObjectInfo;
import org.javaup.ai.manage.vo.DocumentIndexBuildVo;
import org.javaup.ai.manage.vo.DocumentListItemVo;
import org.javaup.ai.manage.vo.DocumentPageQueryVo;
import org.javaup.ai.manage.vo.DocumentStrategyConfirmVo;
import org.javaup.ai.manage.vo.DocumentStrategyPlanQueryVo;
import org.javaup.ai.manage.vo.DocumentStrategyPlanVo;
import org.javaup.ai.manage.vo.DocumentStrategyStepVo;
import org.javaup.ai.manage.vo.DocumentTaskLogQueryVo;
import org.javaup.ai.manage.vo.DocumentTaskLogVo;
import org.javaup.ai.manage.vo.DocumentUploadVo;
import org.javaup.enums.BusinessStatus;
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
import org.javaup.enums.DocumentStrategyRoleEnum;
import org.javaup.enums.DocumentStrategySourceTypeEnum;
import org.javaup.enums.DocumentStrategyStatusEnum;
import org.javaup.enums.DocumentStrategyTypeEnum;
import org.javaup.enums.DocumentTaskEventTypeEnum;
import org.javaup.enums.DocumentTaskStageEnum;
import org.javaup.enums.DocumentTaskStatusEnum;
import org.javaup.enums.DocumentTaskTypeEnum;
import org.javaup.enums.DocumentTriggerSourceEnum;
import org.javaup.exception.SuperAgentFrameException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
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
 * 文档管理应用服务实现。
 *
 * <p>这一层是文档管理模块的“编排层”，负责把多个领域动作串成前端可感知的业务流程：</p>
 * <p>1. 上传文档时，落库文档记录并创建解析任务。</p>
 * <p>2. 查询列表时，补齐每份文档的最近任务状态。</p>
 * <p>3. 确认策略时，校验前端提交顺序并决定复用旧方案还是生成新方案。</p>
 * <p>4. 构建索引时，创建异步任务并把执行权交给消息消费者。</p>
 *
 * <p>可以把它理解成“面向页面流程”的应用服务，而不是“只做单表 CRUD”的 service。</p>
 */
@Slf4j
@Service
public class DocumentManageServiceImpl implements DocumentManageService {

    private final SuperAgentDocumentMapper documentMapper;

    private final SuperAgentDocumentStrategyPlanMapper planMapper;

    private final SuperAgentDocumentStrategyStepMapper stepMapper;

    private final SuperAgentDocumentTaskMapper taskMapper;

    private final SuperAgentDocumentTaskLogMapper taskLogMapper;

    private final DocumentStorageService storageService;

    private final DocumentStrategyService strategyService;

    private final DocumentTaskLogService taskLogService;

    private final DocumentKafkaProducer kafkaProducer;

    @Resource
    private UidGenerator uidGenerator;

    public DocumentManageServiceImpl(SuperAgentDocumentMapper documentMapper,
                                     SuperAgentDocumentStrategyPlanMapper planMapper,
                                     SuperAgentDocumentStrategyStepMapper stepMapper,
                                     SuperAgentDocumentTaskMapper taskMapper,
                                     SuperAgentDocumentTaskLogMapper taskLogMapper,
                                     DocumentStorageService storageService,
                                     DocumentStrategyService strategyService,
                                     DocumentTaskLogService taskLogService,
                                     DocumentKafkaProducer kafkaProducer) {
        this.documentMapper = documentMapper;
        this.planMapper = planMapper;
        this.stepMapper = stepMapper;
        this.taskMapper = taskMapper;
        this.taskLogMapper = taskLogMapper;
        this.storageService = storageService;
        this.strategyService = strategyService;
        this.taskLogService = taskLogService;
        this.kafkaProducer = kafkaProducer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentUploadVo upload(MultipartFile file, DocumentUploadDto dto) {
        // 第一步先拦截空文件，避免后续还没进入真正业务就产生无意义任务。
        if (file == null || file.isEmpty()) {
            throw new SuperAgentFrameException(DocumentManageCode.EMPTY_FILE_CONTENT.getCode(),
                DocumentManageCode.EMPTY_FILE_CONTENT.getMsg());
        }

        // 原始文件名既决定展示名称，也决定文件类型识别，所以这里必须校验。
        String originalFileName = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFileName)) {
            throw new SuperAgentFrameException(DocumentManageCode.UNSUPPORTED_FILE_TYPE.getCode(),
                "上传文件缺少原始文件名，无法识别文件类型。");
        }

        // 通过文件名映射业务支持的文件类型枚举，未识别直接拒绝进入链路。
        DocumentFileTypeEnum fileType = DocumentFileTypeEnum.fromFileName(originalFileName);
        if (fileType == null) {
            throw new SuperAgentFrameException(DocumentManageCode.UNSUPPORTED_FILE_TYPE.getCode(),
                DocumentManageCode.UNSUPPORTED_FILE_TYPE.getMsg());
        }

        // 先把文件读成字节数组，后续会同时用于对象存储上传和文件大小统计。
        byte[] fileBytes = getFileBytes(file);
        Long documentId = uidGenerator.getUid();

        // 原文件先进入对象存储，数据库只保存定位信息，不保存大文件本体。
        StoredObjectInfo storedObjectInfo = storageService.uploadOriginalFile(
                documentId, originalFileName, fileBytes, file.getContentType());

        // 创建文档主记录，并把状态初始化成“解析中 / 等推荐 / 待构建”。
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(documentId);
        document.setDocumentName(StringUtils.hasText(dto.getDocumentName()) ? dto.getDocumentName() : originalFileName);
        document.setOriginalFileName(originalFileName);
        document.setFileType(fileType.getCode());
        document.setMimeType(file.getContentType());
        document.setFileSize((long) fileBytes.length);
        document.setStorageType(DocumentStorageTypeEnum.MINIO.getCode());
        document.setBucketName(storedObjectInfo.getBucketName());
        document.setObjectName(storedObjectInfo.getObjectName());
        document.setObjectUrl(storedObjectInfo.getObjectUrl());
        document.setParseStatus(DocumentParseStatusEnum.PARSING.getCode());
        document.setStrategyStatus(DocumentStrategyStatusEnum.WAIT_RECOMMEND.getCode());
        document.setIndexStatus(DocumentIndexStatusEnum.WAIT_BUILD.getCode());
        document.setCharCount(0);
        document.setTokenCount(0);
        document.setStatus(BusinessStatus.YES.getCode());
        documentMapper.insert(document);

        // 上传接口本身不直接执行解析，而是先创建解析路由任务，真正执行放到异步链路里。
        Long taskId = uidGenerator.getUid();
        SuperAgentDocumentTask task = new SuperAgentDocumentTask();
        task.setId(taskId);
        task.setDocumentId(documentId);
        task.setTaskType(DocumentTaskTypeEnum.PARSE_ROUTE.getCode());
        task.setTaskStatus(DocumentTaskStatusEnum.NEW.getCode());
        task.setCurrentStage(DocumentTaskStageEnum.FILE_UPLOAD.getCode());
        task.setTriggerSource(resolveTriggerSource(dto.getOperatorId()));
        task.setRetryCount(0);
        task.setStatus(BusinessStatus.YES.getCode());
        taskMapper.insert(task);

        // 在任务刚创建时写一条“文件上传完成”的日志，方便前端时间线从起点就能看到记录。
        taskLogService.saveLog(taskId, documentId,
            DocumentTaskStageEnum.FILE_UPLOAD.getCode(),
            DocumentTaskEventTypeEnum.COMPLETE.getCode(),
            DocumentLogLevelEnum.INFO.getCode(),
            resolveOperatorType(dto.getOperatorId()),
            dto.getOperatorId(),
            "文件上传完成，已进入解析与策略推荐队列。",
            Map.of("originalFileName", originalFileName, "fileSize", fileBytes.length));

        // 最后投递异步解析消息，让上传接口可以快速返回，避免前端长时间等待。
        kafkaProducer.sendParseRoute(new DocumentParseRouteMessage(documentId, taskId));

        return new DocumentUploadVo(documentId, taskId, document.getDocumentName(),
            document.getParseStatus(), document.getStrategyStatus(), document.getIndexStatus());
    }

    @Override
    public DocumentPageQueryVo queryDocumentPage(DocumentPageQueryDto dto) {
        // 分页参数做兜底，避免前端不传或传非法值时直接查出异常。
        int pageNo = dto.getPageNo() == null || dto.getPageNo() <= 0 ? 1 : dto.getPageNo();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() <= 0 ? 10 : dto.getPageSize();
        String keyword = StringUtils.hasText(dto.getKeyword()) ? dto.getKeyword().trim() : null;

        Page<SuperAgentDocument> page = new Page<>(pageNo, pageSize);
        LambdaQueryWrapper<SuperAgentDocument> wrapper = new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocument::getEditTime, SuperAgentDocument::getId);

        if (keyword != null) {
            wrapper.and(query -> query.like(SuperAgentDocument::getDocumentName, keyword)
                .or()
                .like(SuperAgentDocument::getOriginalFileName, keyword));
        }

        // 列表页先查文档主数据，再批量补齐每个文档最近任务，避免出现 N+1 查询。
        IPage<SuperAgentDocument> resultPage = documentMapper.selectPage(page, wrapper);
        List<SuperAgentDocument> documentList = resultPage.getRecords();
        Map<Long, SuperAgentDocumentTask> latestTaskMap = getLatestTaskMap(documentList);

        // 这里把枚举状态和最近任务一起扁平化成前端列表直接可用的结构。
        List<DocumentListItemVo> records = documentList.stream()
            .map(document -> toDocumentListItemVo(document, latestTaskMap.get(document.getId())))
            .toList();

        return new DocumentPageQueryVo(pageNo, pageSize, resultPage.getTotal(), records);
    }

    @Override
    public DocumentStrategyPlanQueryVo queryStrategyPlan(DocumentStrategyPlanQueryDto dto) {
        // 先保证文档本身存在且有效，再继续查当前生效方案。
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        DocumentStrategyPlanVo planVo = null;
        boolean planReady = false;

        // currentPlanId 指向当前文档对前端可见的生效方案，
        // 只要这个方案存在且状态有效，就说明前端可以展示策略详情。
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
        // 策略确认前，文档必须已经解析成功，否则前端看到的推荐方案就不可靠。
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        if (!Objects.equals(document.getParseStatus(), DocumentParseStatusEnum.PARSE_SUCCESS.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STATUS_INVALID.getCode(), "当前文档还未完成解析，不能确认策略。");
        }

        // basePlanId 是“用户看到并基于其操作的那一版方案”，
        // 这里必须和当前生效方案一致，避免用户基于旧页面覆盖新方案。
        if (!Objects.equals(document.getCurrentPlanId(), dto.getBasePlanId())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(), "当前文档的基础方案不存在或已切换。");
        }

        // 再校验基础方案本身确实存在且可用。
        SuperAgentDocumentStrategyPlan basePlan = planMapper.selectById(dto.getBasePlanId());
        if (basePlan == null || !Objects.equals(basePlan.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(),
                DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getMsg());
        }

        // 读取系统推荐出来的原始步骤列表，后续要和前端提交的步骤做对比。
        List<SuperAgentDocumentStrategyStep> baseStepList = listStepByPlanId(basePlan.getId());
        /*
         * 前端支持真实拖拽排序以后，这里需要优先尊重 stepNo，
         * 避免 JSON 数组顺序在某些中间层处理后发生变化时影响最终策略顺序。
         */
        List<Integer> requestTypeList = dto.getSteps().stream()
            .sorted(Comparator.comparing(item -> item.getStepNo() == null ? Integer.MAX_VALUE : item.getStepNo()))
            .map(item -> item.getStrategyType())
            .filter(Objects::nonNull)
            .toList();

        List<SuperAgentDocumentStrategyStep> normalizedStepList = strategyService.normalizeSteps(
            basePlan, baseStepList, requestTypeList, dto.getDocumentId());

        // 规范化后如果一个策略都不剩，说明前端把有效策略全部删空了，这是不允许的。
        if (normalizedStepList.isEmpty()) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_STEP_EMPTY.getCode(),
                DocumentManageCode.STRATEGY_STEP_EMPTY.getMsg());
        }

        // 分别构建“原始方案顺序”“规范化后顺序”“请求去重后顺序”，
        // 后面要判断是否发生了真实改动，以及服务端是否替用户做了纠正。
        List<Integer> baseTypeList = baseStepList.stream()
            .sorted((left, right) -> left.getStepNo().compareTo(right.getStepNo()))
            .map(SuperAgentDocumentStrategyStep::getStrategyType)
            .toList();
        List<Integer> normalizedTypeList = normalizedStepList.stream()
            .map(SuperAgentDocumentStrategyStep::getStrategyType)
            .toList();
        List<Integer> requestDistinctTypeList = new LinkedHashSet<>(requestTypeList).stream().toList();
        boolean normalized = !requestDistinctTypeList.equals(normalizedTypeList);
        boolean changed = !baseTypeList.equals(normalizedTypeList);

        Long targetPlanId;
        Integer targetPlanVersion;
        List<SuperAgentDocumentStrategyStep> targetStepList;

        if (!changed) {
            // 没有发生真实变更时，直接把原方案标记为已确认即可，
            // 这样能避免无意义地产生一版内容完全相同的新方案。
            basePlan.setPlanStatus(DocumentPlanStatusEnum.CONFIRMED.getCode());
            basePlan.setPlanSource(basePlan.getPlanSource() == null ? DocumentPlanSourceEnum.SYSTEM_RECOMMEND.getCode() : basePlan.getPlanSource());
            basePlan.setAdjustNote(dto.getAdjustNote());
            basePlan.setConfirmUserId(dto.getOperatorId());
            basePlan.setConfirmTime(new Date());
            planMapper.updateById(basePlan);
            targetPlanId = basePlan.getId();
            targetPlanVersion = basePlan.getPlanVersion();
            targetStepList = baseStepList;
        }
        else {
            // 一旦用户顺序或策略集合发生变化，就废弃旧方案并创建一版新的已确认方案，
            // 这样后续回看日志时能区分“系统推荐”和“用户最终生效”的差异。
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
            newPlan.setStrategySnapshot(normalizedTypeList.stream().map(String::valueOf).collect(Collectors.joining(",")));
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

        // 文档始终只指向当前生效方案，前端读取详情时只看 currentPlanId 即可。
        document.setCurrentPlanId(targetPlanId);
        document.setStrategyStatus(DocumentStrategyStatusEnum.CONFIRMED.getCode());
        documentMapper.updateById(document);

        // 把策略确认动作回写到最近一次解析任务里，
        // 这样任务时间线就能完整体现“系统推荐 -> 用户调整/确认”的全过程。
        SuperAgentDocumentTask latestParseTask = getLatestTask(document.getId(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode());
        if (latestParseTask != null) {
            latestParseTask.setCurrentStage(DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode());
            taskMapper.updateById(latestParseTask);

            if (changed) {
                taskLogService.saveLog(latestParseTask.getId(), document.getId(),
                    DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode(),
                    DocumentTaskEventTypeEnum.USER_ADJUST.getCode(),
                    DocumentLogLevelEnum.INFO.getCode(),
                    resolveOperatorType(dto.getOperatorId()),
                    dto.getOperatorId(),
                    "用户调整了系统推荐策略。",
                    detail("strategyTypes", normalizedTypeList, "adjustNote", dto.getAdjustNote()));
            }
            taskLogService.saveLog(latestParseTask.getId(), document.getId(),
                DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode(),
                DocumentTaskEventTypeEnum.USER_CONFIRM.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                resolveOperatorType(dto.getOperatorId()),
                dto.getOperatorId(),
                "用户已确认最终策略方案。",
                Map.of("planId", targetPlanId, "strategyTypes", normalizedTypeList));
        }

        return new DocumentStrategyConfirmVo(
            document.getId(),
            targetPlanId,
            targetPlanVersion,
            document.getStrategyStatus(),
            enumMsg(DocumentStrategyStatusEnum.getRc(document.getStrategyStatus())),
            normalized,
            toStepVoList(targetStepList)
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentIndexBuildVo buildIndex(DocumentIndexBuildDto dto) {
        // 索引构建依赖两个前置条件：
        // 1. 文档解析成功
        // 2. 当前策略已经确认完成
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        if (!Objects.equals(document.getParseStatus(), DocumentParseStatusEnum.PARSE_SUCCESS.getCode())
            || !Objects.equals(document.getStrategyStatus(), DocumentStrategyStatusEnum.CONFIRMED.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STATUS_INVALID.getCode(), "当前文档尚未完成“解析成功 + 策略确认”，不能构建索引。");
        }

        // 防止前端拿着旧 planId 来发起构建，确保构建的一定是当前生效方案。
        if (!Objects.equals(document.getCurrentPlanId(), dto.getPlanId())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(), "当前文档的生效方案与请求方案不一致。");
        }

        // 一个文档同一时刻只允许存在一个待执行/执行中的索引任务，
        // 否则 chunk 和向量数据会互相覆盖。
        long runningTaskCount = taskMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, dto.getDocumentId())
            .eq(SuperAgentDocumentTask::getTaskType, DocumentTaskTypeEnum.BUILD_INDEX.getCode())
            .in(SuperAgentDocumentTask::getTaskStatus, DocumentTaskStatusEnum.NEW.getCode(), DocumentTaskStatusEnum.RUNNING.getCode())
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode()));
        if (runningTaskCount > 0) {
            throw new SuperAgentFrameException(DocumentManageCode.INDEX_TASK_RUNNING.getCode(),
                DocumentManageCode.INDEX_TASK_RUNNING.getMsg());
        }

        // 方案本身也要存在且有效，避免索引任务引用无效快照。
        SuperAgentDocumentStrategyPlan plan = planMapper.selectById(dto.getPlanId());
        if (plan == null || !Objects.equals(plan.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(),
                DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getMsg());
        }

        // 创建索引任务记录，真正的切块和向量化还是异步完成。
        Long taskId = uidGenerator.getUid();
        SuperAgentDocumentTask task = new SuperAgentDocumentTask();
        task.setId(taskId);
        task.setDocumentId(document.getId());
        task.setPlanId(dto.getPlanId());
        task.setTaskType(DocumentTaskTypeEnum.BUILD_INDEX.getCode());
        task.setTaskStatus(DocumentTaskStatusEnum.NEW.getCode());
        task.setCurrentStage(DocumentTaskStageEnum.CHUNK_EXECUTE.getCode());
        task.setTriggerSource(resolveTriggerSource(dto.getOperatorId()));
        task.setStrategySnapshot(plan.getStrategySnapshot());
        task.setRetryCount(0);
        task.setStatus(BusinessStatus.YES.getCode());
        taskMapper.insert(task);

        // 文档主状态先切到“构建中”，让前端列表和详情立即能看到反馈。
        document.setIndexStatus(DocumentIndexStatusEnum.BUILDING.getCode());
        documentMapper.updateById(document);

        // 在任务开始前先落一条日志，方便时间线从“任务创建”开始展示。
        taskLogService.saveLog(taskId, document.getId(),
            DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
            DocumentTaskEventTypeEnum.START.getCode(),
            DocumentLogLevelEnum.INFO.getCode(),
            resolveOperatorType(dto.getOperatorId()),
            dto.getOperatorId(),
            "索引构建任务已创建，等待异步执行。",
            Map.of("planId", dto.getPlanId(), "strategySnapshot", plan.getStrategySnapshot()));

        // 投递索引构建消息后，本接口就结束了，后续执行由消费者接力。
        kafkaProducer.sendIndexBuild(new DocumentIndexBuildMessage(document.getId(), taskId, dto.getPlanId()));

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
        // 时间线查询以 taskId 为主键入口，如果任务本身不存在，直接返回业务异常。
        SuperAgentDocumentTask task = taskMapper.selectById(dto.getTaskId());
        if (task == null || !Objects.equals(task.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(), "任务不存在。");
        }

        // 日志页也做分页保护，避免单次把整条时间线全量拉出来。
        int pageNo = dto.getPageNo() == null || dto.getPageNo() <= 0 ? 1 : dto.getPageNo();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() <= 0 ? 20 : dto.getPageSize();
        Page<SuperAgentDocumentTaskLog> page = new Page<>(pageNo, pageSize);

        // 日志必须按时间正序输出，前端时间线才能保持“从开始到结束”的阅读体验。
        IPage<SuperAgentDocumentTaskLog> resultPage = taskLogMapper.selectPage(page,
            new LambdaQueryWrapper<SuperAgentDocumentTaskLog>()
                .eq(SuperAgentDocumentTaskLog::getTaskId, dto.getTaskId())
                .eq(SuperAgentDocumentTaskLog::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentTaskLog::getCreateTime, SuperAgentDocumentTaskLog::getId));

        // 这里除了明细日志外，还会把任务头信息一起带回去，
        // 前端不需要额外再查一次任务表就能展示当前阶段、耗时、错误信息。
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

    /**
     * 获取文档，不存在时抛业务异常。
     */
    private SuperAgentDocument getDocumentOrThrow(Long documentId) {
        // 统一封装“存在性 + 业务状态有效”的校验逻辑，
        // 这样各个主流程方法都不用重复写相同判断。
        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null || !Objects.equals(document.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(),
                DocumentManageCode.DOCUMENT_NOT_FOUND.getMsg());
        }
        return document;
    }

    /**
     * 查询指定方案下的步骤列表。
     */
    private List<SuperAgentDocumentStrategyStep> listStepByPlanId(Long planId) {
        // 步骤顺序是策略执行链路的核心，所以必须按 stepNo 正序返回。
        return stepMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentStrategyStep>()
            .eq(SuperAgentDocumentStrategyStep::getPlanId, planId)
            .eq(SuperAgentDocumentStrategyStep::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentStrategyStep::getStepNo, SuperAgentDocumentStrategyStep::getId));
    }

    /**
     * 获取下一个方案版本号。
     */
    private Integer getNextPlanVersion(Long documentId) {
        // 方案版本按文档维度单调递增，方便后续排查“第几版方案被执行了”。
        SuperAgentDocumentStrategyPlan latestPlan = planMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentStrategyPlan>()
            .eq(SuperAgentDocumentStrategyPlan::getDocumentId, documentId)
            .eq(SuperAgentDocumentStrategyPlan::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentStrategyPlan::getPlanVersion)
            .last("limit 1"));
        return latestPlan == null ? 1 : latestPlan.getPlanVersion() + 1;
    }

    /**
     * 查询指定类型的最近一条任务。
     */
    private SuperAgentDocumentTask getLatestTask(Long documentId, Integer taskType) {
        // 任务 ID 本身就是按时间递增生成的，所以按 ID 倒序取 1 条即可代表最近任务。
        return taskMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, documentId)
            .eq(SuperAgentDocumentTask::getTaskType, taskType)
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentTask::getId)
            .last("limit 1"));
    }

    /**
     * 读取当前分页文档对应的最近任务。
     *
     * <p>列表页最常用的是“查看最近任务状态 / 打开任务日志”，
     * 这里直接按文档维度补齐，避免前端必须先额外调一次任务接口。</p>
     */
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

        // putIfAbsent 配合倒序结果，可以天然保留每个文档的第一条，也就是最近任务。
        Map<Long, SuperAgentDocumentTask> latestTaskMap = new LinkedHashMap<>();
        for (SuperAgentDocumentTask task : taskList) {
            latestTaskMap.putIfAbsent(task.getDocumentId(), task);
        }
        return latestTaskMap;
    }

    /**
     * 转换文档列表单条记录。
     */
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

    /**
     * 转换策略方案出参。
     */
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
            toStepVoList(stepList)
        );
    }

    /**
     * 转换策略步骤出参。
     */
    private List<DocumentStrategyStepVo> toStepVoList(List<SuperAgentDocumentStrategyStep> stepList) {
        return stepList.stream().map(step -> new DocumentStrategyStepVo(
            step.getStepNo(),
            step.getStrategyType(),
            enumMsg(DocumentStrategyTypeEnum.getRc(step.getStrategyType())),
            step.getStrategyRole(),
            enumMsg(DocumentStrategyRoleEnum.getRc(step.getStrategyRole())),
            step.getSourceType(),
            enumMsg(DocumentStrategySourceTypeEnum.getRc(step.getSourceType())),
            step.getExecuteStatus(),
            enumMsg(DocumentStrategyExecuteStatusEnum.getRc(step.getExecuteStatus())),
            step.getRecommendReason()
        )).toList();
    }

    /**
     * 转换任务日志出参。
     */
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

    /**
     * 根据是否传了 operatorId 推断操作人类型。
     */
    private Integer resolveOperatorType(Long operatorId) {
        return operatorId == null ? DocumentOperatorTypeEnum.SYSTEM.getCode() : DocumentOperatorTypeEnum.USER.getCode();
    }

    /**
     * 根据是否传了 operatorId 推断触发来源。
     */
    private Integer resolveTriggerSource(Long operatorId) {
        return operatorId == null ? DocumentTriggerSourceEnum.SYSTEM.getCode() : DocumentTriggerSourceEnum.USER.getCode();
    }

    /**
     * 统一读取枚举文案。
     */
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
        return "";
    }

    /**
     * 读取上传文件字节内容。
     */
    private byte[] getFileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        }
        catch (IOException exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STORAGE_FAILED.getCode(),
                "读取上传文件内容失败: " + exception.getMessage(), exception);
        }
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
