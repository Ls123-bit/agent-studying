package org.javaup.ai.manage.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.javaup.ai.manage.dto.DocumentIndexBuildDto;
import org.javaup.ai.manage.dto.DocumentChunkQueryDto;
import org.javaup.ai.manage.dto.DocumentChunkDetailQueryDto;
import org.javaup.ai.manage.dto.DocumentDetailQueryDto;
import org.javaup.ai.manage.dto.DocumentDeleteDto;
import org.javaup.ai.manage.dto.DocumentPageQueryDto;
import org.javaup.ai.manage.dto.DocumentStrategyConfirmDto;
import org.javaup.ai.manage.dto.DocumentStrategyPlanQueryDto;
import org.javaup.ai.manage.dto.DocumentTaskLogQueryDto;
import org.javaup.ai.manage.dto.DocumentUploadDto;
import org.javaup.ai.manage.service.DocumentManageService;
import org.javaup.ai.manage.vo.DocumentIndexBuildVo;
import org.javaup.ai.manage.vo.DocumentChunkQueryVo;
import org.javaup.ai.manage.vo.DocumentChunkDetailVo;
import org.javaup.ai.manage.vo.DocumentListItemVo;
import org.javaup.ai.manage.vo.DocumentDeleteVo;
import org.javaup.ai.manage.vo.DocumentPageQueryVo;
import org.javaup.ai.manage.vo.DocumentStrategyConfirmVo;
import org.javaup.ai.manage.vo.DocumentStrategyPlanQueryVo;
import org.javaup.ai.manage.vo.DocumentTaskLogQueryVo;
import org.javaup.ai.manage.vo.DocumentUploadVo;
import org.javaup.common.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档管理控制器。
 *
 * <p>这一层只做两件事：</p>
 * <p>1. 把前端请求参数转换成应用服务能够接收的 DTO。</p>
 * <p>2. 按照“上传 -> 推荐策略 -> 确认策略 -> 构建索引 -> 查看日志”的主流程，
 * 把请求分发到对应的应用服务。</p>
 *
 * <p>真正的业务状态流转、任务创建、日志记录、异步投递等逻辑，
 * 都下沉在 service 实现中，控制器这里尽量保持薄。</p>
 */
@RestController
@RequestMapping("/manage/document")
public class DocumentManageController {

    private final DocumentManageService documentManageService;

    public DocumentManageController(DocumentManageService documentManageService) {
        this.documentManageService = documentManageService;
    }

    /**
     * 上传文档并投递解析任务。
     *
     * <p>这是前端“上传并解析”按钮对应的入口。
     * 调用后会立即落库文档记录、创建解析任务，并把异步解析消息投递出去。</p>
     */
    @Operation(summary = "上传文档并投递解析任务")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentUploadVo> upload(@RequestPart("file") MultipartFile file,
                                                @Valid @RequestPart(value = "meta", required = false) DocumentUploadDto dto) {
        // meta 在前端是可选项，所以这里兜底成空 DTO，
        // 避免 service 层到处判空，统一走一套参数读取逻辑。
        return ApiResponse.ok(documentManageService.upload(file, dto == null ? new DocumentUploadDto() : dto));
    }

    /**
     * 分页查询文档列表。
     *
     * <p>前端会在以下场景频繁调用这个接口：</p>
     * <p>1. 页面初始化加载列表。</p>
     * <p>2. 上传成功后刷新文档状态。</p>
     * <p>3. 构建索引轮询期间刷新最新任务与索引状态。</p>
     */
    @Operation(summary = "分页查询文档列表")
    @PostMapping("/page/query")
    public ApiResponse<DocumentPageQueryVo> queryDocumentPage(@Valid @RequestBody DocumentPageQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryDocumentPage(dto));
    }

    /**
     * 查询单个文档详情。
     */
    @Operation(summary = "查询文档详情")
    @PostMapping("/detail/query")
    public ApiResponse<DocumentListItemVo> queryDocumentDetail(@Valid @RequestBody DocumentDetailQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryDocumentDetail(dto));
    }

    /**
     * 删除文档及其关联数据。
     */
    @Operation(summary = "删除文档及其关联数据")
    @PostMapping("/delete")
    public ApiResponse<DocumentDeleteVo> deleteDocument(@Valid @RequestBody DocumentDeleteDto dto) {
        return ApiResponse.ok(documentManageService.deleteDocument(dto));
    }

    /**
     * 查询策略推荐结果。
     *
     * <p>前端会在选中文档时读取一次，
     * 在文档上传后还会周期性轮询，直到推荐方案准备完成。</p>
     */
    @Operation(summary = "查询文档策略推荐结果")
    @PostMapping("/strategy/plan/query")
    public ApiResponse<DocumentStrategyPlanQueryVo> queryStrategyPlan(@Valid @RequestBody DocumentStrategyPlanQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryStrategyPlan(dto));
    }

    /**
     * 确认策略方案。
     *
     * <p>前端允许用户在推荐方案基础上增删策略并调整顺序，
     * 这个接口负责把最终确认的策略链路固化为当前文档的生效方案。</p>
     */
    @Operation(summary = "确认文档策略方案")
    @PostMapping("/strategy/confirm")
    public ApiResponse<DocumentStrategyConfirmVo> confirmStrategy(@Valid @RequestBody DocumentStrategyConfirmDto dto) {
        return ApiResponse.ok(documentManageService.confirmStrategy(dto));
    }

    /**
     * 构建索引。
     *
     * <p>只有文档完成解析并且策略已经确认后，才能发起索引构建。
     * 这里会创建索引任务，并把真正的切块与向量化动作交给异步消费者处理。</p>
     */
    @Operation(summary = "执行文档索引构建")
    @PostMapping("/index/build")
    public ApiResponse<DocumentIndexBuildVo> buildIndex(@Valid @RequestBody DocumentIndexBuildDto dto) {
        return ApiResponse.ok(documentManageService.buildIndex(dto));
    }

    /**
     * 查询文档 chunk 列表。
     *
     * <p>用于查看某个文档当前可展示的 chunk 结果。
     * 优先返回最近一次成功索引任务的 chunk，如果尚未成功构建，则退回最近一次构建任务。</p>
     */
    @Operation(summary = "查询文档 chunk 列表")
    @PostMapping("/chunk/query")
    public ApiResponse<DocumentChunkQueryVo> queryDocumentChunks(@Valid @RequestBody DocumentChunkQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryDocumentChunks(dto));
    }

    /**
     * 查询单个 chunk 详情。
     */
    @Operation(summary = "查询单个文档 chunk 详情")
    @PostMapping("/chunk/detail/query")
    public ApiResponse<DocumentChunkDetailVo> queryDocumentChunkDetail(@Valid @RequestBody DocumentChunkDetailQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryDocumentChunkDetail(dto));
    }

    /**
     * 查询任务日志。
     *
     * <p>文档中心的“任务时间线”和“最近任务摘要”都依赖这个接口。
     * 它会返回任务当前状态，以及按时间顺序排好的日志明细。</p>
     */
    @Operation(summary = "查询任务执行日志")
    @PostMapping("/task/log/query")
    public ApiResponse<DocumentTaskLogQueryVo> queryTaskLogs(@Valid @RequestBody DocumentTaskLogQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryTaskLogs(dto));
    }

}
