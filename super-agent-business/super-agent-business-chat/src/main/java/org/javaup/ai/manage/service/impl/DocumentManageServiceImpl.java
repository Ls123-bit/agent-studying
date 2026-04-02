package org.javaup.ai.manage.service.impl;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.data.SuperAgentDocumentTaskLog;
import org.javaup.ai.manage.dto.DocumentChunkQueryDto;
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
import org.javaup.ai.manage.service.DocumentVectorGateway;
import org.javaup.ai.manage.support.StoredObjectInfo;
import org.javaup.ai.manage.vo.DocumentChunkItemVo;
import org.javaup.ai.manage.vo.DocumentChunkQueryVo;
import org.javaup.ai.manage.vo.DocumentDeleteVo;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private final SuperAgentDocumentChunkMapper chunkMapper;

    private final DocumentStorageService storageService;

    private final DocumentStrategyService strategyService;

    private final DocumentTaskLogService taskLogService;

    private final DocumentVectorGateway vectorGateway;

    private final DocumentKafkaProducer kafkaProducer;

    @Resource
    private UidGenerator uidGenerator;

    public DocumentManageServiceImpl(SuperAgentDocumentMapper documentMapper,
                                     SuperAgentDocumentStrategyPlanMapper planMapper,
                                     SuperAgentDocumentStrategyStepMapper stepMapper,
                                     SuperAgentDocumentTaskMapper taskMapper,
                                     SuperAgentDocumentTaskLogMapper taskLogMapper,
                                     SuperAgentDocumentChunkMapper chunkMapper,
                                     DocumentStorageService storageService,
                                     DocumentStrategyService strategyService,
                                     DocumentTaskLogService taskLogService,
                                     DocumentVectorGateway vectorGateway,
                                     DocumentKafkaProducer kafkaProducer) {
        this.documentMapper = documentMapper;
        this.planMapper = planMapper;
        this.stepMapper = stepMapper;
        this.taskMapper = taskMapper;
        this.taskLogMapper = taskLogMapper;
        this.chunkMapper = chunkMapper;
        this.storageService = storageService;
        this.strategyService = strategyService;
        this.taskLogService = taskLogService;
        this.vectorGateway = vectorGateway;
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
        if (StrUtil.isBlank(originalFileName)) {
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
        document.setDocumentName(StrUtil.isNotBlank(dto.getDocumentName()) ? dto.getDocumentName() : originalFileName);
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

        // 仍有新建/运行中的任务时不允许删除，
        // 否则后台消费者可能在删除完成后又把关联数据重新写回来。
        long activeTaskCount = taskMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, documentId)
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .in(SuperAgentDocumentTask::getTaskStatus, DocumentTaskStatusEnum.NEW.getCode(), DocumentTaskStatusEnum.RUNNING.getCode()));
        if (activeTaskCount > 0) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STATUS_INVALID.getCode(),
                "当前文档存在进行中的任务，请等待任务结束后再删除。");
        }

        // 先清理外部系统，再删 MySQL 主数据。
        // 这样如果删除中途失败，数据库里仍保留定位信息，便于重试补偿。
        storageService.deleteObjects(List.of(document.getObjectName(), document.getParseTextPath()));
        vectorGateway.deleteByDocumentId(documentId);

        chunkMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .eq(SuperAgentDocumentChunk::getDocumentId, documentId));
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

    
    /**
     * 确认文档最终生效的策略方案。
     *
     * <p>这个方法不是“生成推荐策略”，而是把推荐结果正式定稿。</p>
     *
     * <p>在整条业务链路里，它承担的是“推荐阶段”和“索引构建阶段”之间的闸门角色：</p>
     * <p>1. 解析阶段会自动生成一版系统推荐方案。</p>
     * <p>2. 但这版方案在被确认之前，只能算候选方案，还不能作为真正索引输入。</p>
     * <p>3. 只有这个方法执行成功，文档才拥有一版正式生效的确认方案，后续 `buildIndex` 才能继续。</p>
     *
     * <p>这个方法要回答的核心问题是：</p>
     * <p>“当前文档最后到底应该按哪一版策略链执行？”</p>
     *
     * <p>因此它会依次完成下面几件事：</p>
     * <p>1. 校验文档是否已经解析成功。</p>
     * <p>2. 校验用户基于的基础方案是否仍然是当前生效方案。</p>
     * <p>3. 把用户提交的步骤按顺序规范化，得到最终有序策略链。</p>
     * <p>4. 判断最终链路与原方案相比是否真的发生变化。</p>
     * <p>5. 复用原方案，或者创建一版新的确认方案。</p>
     * <p>6. 回写 document.currentPlanId 和 strategyStatus。</p>
     * <p>7. 追加策略确认相关任务日志。</p>
     *
     * <p>方法里两个关键布尔值的业务语义是：</p>
     * <p>1. normalized：服务端是否对用户提交的策略链做了纠正或规范化。</p>
     * <p>2. changed：最终生效链路相对于原始基础方案是否真的发生变化。</p>
     */
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
         *
         * 这里先把请求压平成“按最终执行顺序排列的 strategyType 列表”，
         * 后面所有比较都围绕这个有序列表展开。
         */
        List<Integer> requestTypeList = dto.getSteps().stream()
            .sorted(Comparator.comparing(item -> item.getStepNo() == null ? Integer.MAX_VALUE : item.getStepNo()))
            .map(DocumentStrategyStepItemDto::getStrategyType)
            .filter(Objects::nonNull)
            .toList();

        // normalizeSteps 会统一做合法性过滤、去重和顺序标准化。
        // 它返回的 normalizedStepList 才是后端最终准备落库和执行的策略链。
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

        // normalized 比较的是：
        // “用户请求的去重后策略链” 和 “服务端最终规范化后的策略链” 是否一致。
        //
        // 如果这里为 true，说明服务端确实替用户做了某种纠正，
        // 比如清理非法策略、移除重复策略、修正不规范输入。
        boolean normalized = !requestDistinctTypeList.equals(normalizedTypeList);

        // changed 比较的是：
        // “基础方案原本的策略链” 和 “最终确认后的策略链” 是否一致。
        //
        // 这里必须比较有序 List，而不是 Set，
        // 因为策略顺序本身就会影响切块流水线的执行结果。
        // 例如 [1,2,3] 和 [2,1,3] 虽然元素集合相同，但在业务上已经是两版不同方案。
        boolean changed = !baseTypeList.equals(normalizedTypeList);

        // 下面三个变量统一表示“这次确认最后会落到哪一版方案上”。
        // 无论用户是否真的修改了方案，最终都要落到一个 targetPlan 上，
        // 这样后续文档主表、日志和返回值都能按同一口径组装。
        Long targetPlanId;
        Integer targetPlanVersion;
        List<SuperAgentDocumentStrategyStep> targetStepList;

        if (!changed) {
            // 没有发生真实变更时，直接把原方案标记为已确认即可，
            // 这样能避免无意义地产生一版内容完全相同的新方案。
            //
            // 这种情况对应的是：
            // “系统推荐方案已经满足需求，用户只是点击确认，没有改变实际执行链。”
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
            // 一旦用户顺序或策略集合发生变化，就废弃旧方案并创建一版新的已确认方案，
            // 这样后续回看日志时能区分“系统推荐”和“用户最终生效”的差异。
            //
            // 这种情况对应的是：
            // “系统推荐只作为基础参考，真正执行的是一版用户调整后的新方案。”
            basePlan.setPlanStatus(DocumentPlanStatusEnum.DISCARDED.getCode());
            planMapper.updateById(basePlan);

            Long newPlanId = uidGenerator.getUid();
            Integer newPlanVersion = getNextPlanVersion(document.getId());
            SuperAgentDocumentStrategyPlan newPlan = new SuperAgentDocumentStrategyPlan();
            newPlan.setId(newPlanId);
            newPlan.setDocumentId(document.getId());
            newPlan.setPlanVersion(newPlanVersion);

            // 这里显式把来源标记成 USER_ADJUST，
            // 便于后续排查“这版方案到底是系统原样确认，还是用户改动后生成的”。
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

            // 新方案的步骤列表完全来自 normalizedStepList，
            // 也就是这次确认后真正要用于索引构建的最终执行链。
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
        //
        // 从这里开始，后续 buildIndex 不再关心“系统最初推荐的是哪一版”，
        // 它只会认 document.currentPlanId 指向的这版最终确认方案。
        document.setCurrentPlanId(targetPlanId);
        document.setStrategyStatus(DocumentStrategyStatusEnum.CONFIRMED.getCode());
        documentMapper.updateById(document);

        // 把策略确认动作回写到最近一次解析任务里，
        // 这样任务时间线就能完整体现“系统推荐 -> 用户调整/确认”的全过程。
        SuperAgentDocumentTask latestParseTask = getLatestTask(document.getId(), DocumentTaskTypeEnum.PARSE_ROUTE.getCode());
        if (latestParseTask != null) {
            // 虽然解析任务主体已经结束，但这里补写当前阶段，
            // 是为了让任务时间线能表达“解析链路的最后一步是策略被确认”。
            latestParseTask.setCurrentStage(DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode());
            taskMapper.updateById(latestParseTask);

            if (changed) {
                // 只有发生真实变更时，才额外记录一条“用户调整策略”的日志。
                taskLogService.saveLog(latestParseTask.getId(), document.getId(),
                    DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode(),
                    DocumentTaskEventTypeEnum.USER_ADJUST.getCode(),
                    DocumentLogLevelEnum.INFO.getCode(),
                    resolveOperatorType(dto.getOperatorId()),
                    dto.getOperatorId(),
                    "用户调整了系统推荐策略。",
                    detail("strategyTypes", normalizedTypeList, "adjustNote", dto.getAdjustNote()));
            }

            // 无论是否真的改动策略，最终都会有一条“用户确认最终方案”的日志，
            // 因为这一步才意味着文档正式具备进入索引构建的资格。
            taskLogService.saveLog(latestParseTask.getId(), document.getId(),
                DocumentTaskStageEnum.STRATEGY_CONFIRM.getCode(),
                DocumentTaskEventTypeEnum.USER_CONFIRM.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                resolveOperatorType(dto.getOperatorId()),
                dto.getOperatorId(),
                "用户已确认最终策略方案。",
                Map.of("planId", targetPlanId, "strategyTypes", normalizedTypeList));
        }

        // 返回值里给出最终生效方案的身份信息和步骤链，
        // 让调用方明确知道“最后到底确认的是哪一版方案”。
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

    
    /**
     * 发起文档索引构建。
     *
     * <p>这个方法的职责不是“当场把索引构建完”，而是正式启动一条索引构建任务。</p>
     *
     * <p>它在整条业务链路中的位置是：</p>
     * <p>1. 文档已上传。</p>
     * <p>2. 文档已解析完成，系统已给出推荐策略。</p>
     * <p>3. 用户已经通过 `confirmStrategy` 把最终方案拍板。</p>
     * <p>4. 到这里，后端才允许创建索引任务并进入异步构建链路。</p>
     *
     * <p>因此这个方法本质上做的是“建任务 + 改状态 + 写日志 + 发消息”，
     * 真正耗时的切块、保存 chunk、向量化写 PGVector 都不会在这里同步执行。</p>
     *
     * <p>它要解决的核心问题是：</p>
     * <p>“当前文档是否已经具备合法的索引构建前置条件，并且应该按哪一版方案去构建？”</p>
     *
     * <p>所以它会依次完成：</p>
     * <p>1. 校验文档是否已经满足“解析成功 + 策略已确认”。</p>
     * <p>2. 校验请求里的 `planId` 是否仍然是当前生效方案。</p>
     * <p>3. 校验当前文档是否已有运行中的索引任务，防止并发重复构建。</p>
     * <p>4. 创建一条 BUILD_INDEX 类型的新任务记录。</p>
     * <p>5. 把文档主状态推进到 BUILDING。</p>
     * <p>6. 写一条“索引任务已创建”的任务日志。</p>
     * <p>7. 发送 Kafka 构建消息，交给异步消费者真正执行。</p>
     *
     * <p>换句话说，`buildIndex` 是索引构建链路的同步入口，
     * 而 `DocumentAsyncProcessServiceImpl#handleIndexBuild` 才是真正干重活的地方。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentIndexBuildVo buildIndex(DocumentIndexBuildDto dto) {
        // 索引构建依赖两个前置条件：
        // 1. 文档解析成功
        // 2. 当前策略已经确认完成
        //
        // 这里的含义是：
        // - 没有解析成功，就没有稳定的 parsedText 可供切块
        // - 没有确认策略，就没有正式生效的切块链路可以执行
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        if (!Objects.equals(document.getParseStatus(), DocumentParseStatusEnum.PARSE_SUCCESS.getCode())
            || !Objects.equals(document.getStrategyStatus(), DocumentStrategyStatusEnum.CONFIRMED.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STATUS_INVALID.getCode(), "当前文档尚未完成“解析成功 + 策略确认”，不能构建索引。");
        }

        // 防止前端拿着旧 planId 来发起构建，确保构建的一定是当前生效方案。
        //
        // 这里和 confirmStrategy 里的 basePlanId 校验思路一致：
        // 只要当前文档已经切换到了别的方案，就不允许用旧方案 id 来启动构建。
        if (!Objects.equals(document.getCurrentPlanId(), dto.getPlanId())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(), "当前文档的生效方案与请求方案不一致。");
        }

        // 一个文档同一时刻只允许存在一个待执行/执行中的索引任务，
        // 否则 chunk 和向量数据会互相覆盖。
        //
        // 如果这里不拦，可能出现：
        // 1. 同一文档被重复切块
        // 2. 同一批 chunk 被重复写入或互相覆盖
        // 3. 文档 lastIndexTaskId 最终指向不明确
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
        //
        // 这里除了确认 planId 存在，也是在确保：
        // 当前要执行的不是一条被删掉、失效或非法的方案记录。
        SuperAgentDocumentStrategyPlan plan = planMapper.selectById(dto.getPlanId());
        if (plan == null || !Objects.equals(plan.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getCode(),
                DocumentManageCode.STRATEGY_PLAN_NOT_FOUND.getMsg());
        }

        // 创建索引任务记录，真正的切块和向量化还是异步完成。
        //
        // 这里落库的 task 会成为后续整条索引构建链路的主索引：
        // 1. 任务日志挂在它下面
        // 2. chunk 记录会带 taskId
        // 3. 成功后 document.lastIndexTaskId 也会指向它
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
        //
        // 注意这里虽然还没有真正执行切块，
        // 但从业务视角看，这份文档已经处在“索引构建进行中”的状态了。
        document.setIndexStatus(DocumentIndexStatusEnum.BUILDING.getCode());
        documentMapper.updateById(document);

        // 在任务开始前先落一条日志，方便时间线从“任务创建”开始展示。
        //
        // 这条日志的意义是把“用户点击构建索引”和“异步消费者真正开始处理”区分开，
        // 让时间线能够完整展示“任务创建 -> 真正执行”的两个阶段。
        taskLogService.saveLog(taskId, document.getId(),
            DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
            DocumentTaskEventTypeEnum.START.getCode(),
            DocumentLogLevelEnum.INFO.getCode(),
            resolveOperatorType(dto.getOperatorId()),
            dto.getOperatorId(),
            "索引构建任务已创建，等待异步执行。",
            Map.of("planId", dto.getPlanId(), "strategySnapshot", plan.getStrategySnapshot()));

        // 投递索引构建消息后，本接口就结束了，后续执行由消费者接力。
        //
        // 这里是整个方法最容易误解的地方：
        // - 发送消息成功 != 索引已经构建成功
        // - 这里只代表“构建任务已成功进入后台处理队列”
        kafkaProducer.sendIndexBuild(new DocumentIndexBuildMessage(document.getId(), taskId, dto.getPlanId()));

        // 返回值告诉调用方三类信息：
        // 1. 这次构建对应的 taskId 是多少
        // 2. 当前任务处于什么状态（通常是 NEW）
        // 3. 文档索引状态已经切到了 BUILDING
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

        List<DocumentChunkItemVo> records = resultPage.getRecords().stream()
            .map(this::toDocumentChunkItemVo)
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
     * 查询文档最近一条任务，不限定任务类型。
     */
    private SuperAgentDocumentTask getLatestTask(Long documentId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, documentId)
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
     * 解析本次 chunk 查询应当读取的任务 id。
     */
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
     * 转换文档 chunk 出参。
     */
    private DocumentChunkItemVo toDocumentChunkItemVo(SuperAgentDocumentChunk chunk) {
        return new DocumentChunkItemVo(
            chunk.getId(),
            chunk.getChunkNo(),
            chunk.getSectionPath(),
            chunk.getPageNo(),
            chunk.getSourceType(),
            enumMsg(DocumentChunkSourceTypeEnum.getRc(chunk.getSourceType())),
            chunk.getCharCount(),
            chunk.getTokenCount(),
            chunk.getVectorStatus(),
            enumMsg(DocumentVectorStatusEnum.getRc(chunk.getVectorStatus())),
            chunk.getChunkText()
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
     *
     * <p>这一层的职责是把数据库中的策略步骤实体，转换成调用方更容易直接理解的 VO。</p>
     *
     * <p>除了原始码值，这里还会把每个枚举对应的文案一起带上，
     * 这样调用方不需要再自己做二次枚举映射。</p>
     */
    private List<DocumentStrategyStepVo> toStepVoList(List<SuperAgentDocumentStrategyStep> stepList) {
        // 每个步骤都会同时返回：
        // 1. 顺序和策略类型
        // 2. 角色与来源
        // 3. 当前执行状态
        // 4. 推荐原因
        //
        // 这样无论是“查询当前方案”还是“确认方案返回”，都能直接复用同一套展示结构。
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
     * 把前端字符串主键转换成 Long。
     */
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
        if (enumObject instanceof DocumentChunkSourceTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentVectorStatusEnum value) {
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
