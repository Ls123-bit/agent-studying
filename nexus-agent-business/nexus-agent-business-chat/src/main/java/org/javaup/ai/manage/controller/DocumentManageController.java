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
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 控制层
 * @author: 阿星不是程序员
 **/

@RestController
@RequestMapping("/manage/document")
public class DocumentManageController {

    private final DocumentManageService documentManageService;

    public DocumentManageController(DocumentManageService documentManageService) {
        this.documentManageService = documentManageService;
    }






    /**
     * 上传接口的入口在 DocumentManageController，
     * 它是一个标准的 Spring MVC 控制器。
     * 这个接口比较特殊——不是普通的 JSON 请求，而是 multipart/form-data，
     * 同时包含二进制文件和文档元信息两部分。
     * 上传文档并投递后续解析任务。
     * <p>
     * 请求体采用 multipart/form-data，通常包含两部分：
     * 1. {@code file}：真实上传的文档二进制内容；
     * 2. {@code meta}：可选的文档元信息，例如展示名称、知识范围、业务分类、操作人等。
     * </p>
     * <p>
     * 控制器本身不直接处理文件落盘、对象存储、文档入库或任务投递，
     * 这里只负责把 multipart 中的两部分正确绑定出来，然后交给服务层统一完成：
     * 文件校验、文件类型识别、对象存储上传、文档主表写入、任务表写入、日志记录和 Kafka 投递。
     * </p>
     *
     * @param file 上传的文档文件，必须通过 multipart 的 {@code file} 字段传入
     * @param dto 上传附带的文档元信息，来自 multipart 的 {@code meta} 字段，可为空
     * @return 上传结果，包含文档 ID、任务 ID 以及当前解析/策略/索引状态
     */
    @Operation(summary = "上传文档并投递解析任务")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentUploadVo> upload(@RequestPart("file") MultipartFile file,
                                                @Valid @RequestPart(value = "meta", required = false) DocumentUploadDto dto) {
        // meta 允许前端不传；这里统一兜底成一个空 DTO，避免服务层重复判空，让后续取字段时更稳定。
        return ApiResponse.ok(documentManageService.upload(file, dto == null ? new DocumentUploadDto() : dto));//Controller 这里做了一个兜底：如果 dto 为 null，就 new 一个空的 DocumentUploadDto，这样服务层就不用到处判空了
    }

    @Operation(summary = "分页查询文档列表")
    @PostMapping("/page/query")
    public ApiResponse<DocumentPageQueryVo> queryDocumentPage(@Valid @RequestBody DocumentPageQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryDocumentPage(dto));
    }

    @Operation(summary = "查询文档详情")
    @PostMapping("/detail/query")
    public ApiResponse<DocumentListItemVo> queryDocumentDetail(@Valid @RequestBody DocumentDetailQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryDocumentDetail(dto));
    }

    @Operation(summary = "删除文档及其关联数据")
    @PostMapping("/delete")
    public ApiResponse<DocumentDeleteVo> deleteDocument(@Valid @RequestBody DocumentDeleteDto dto) {
        return ApiResponse.ok(documentManageService.deleteDocument(dto));
    }

    @Operation(summary = "查询文档策略推荐结果")
    @PostMapping("/strategy/plan/query")
    public ApiResponse<DocumentStrategyPlanQueryVo> queryStrategyPlan(@Valid @RequestBody DocumentStrategyPlanQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryStrategyPlan(dto));
    }

    @Operation(summary = "确认文档策略方案")
    @PostMapping("/strategy/confirm")
    public ApiResponse<DocumentStrategyConfirmVo> confirmStrategy(@Valid @RequestBody DocumentStrategyConfirmDto dto) {
        return ApiResponse.ok(documentManageService.confirmStrategy(dto));
    }

    @Operation(summary = "执行文档索引构建")
    @PostMapping("/index/build")
    public ApiResponse<DocumentIndexBuildVo> buildIndex(@Valid @RequestBody DocumentIndexBuildDto dto) {
        return ApiResponse.ok(documentManageService.buildIndex(dto));
    }

    @Operation(summary = "查询文档 chunk 列表")
    @PostMapping("/chunk/query")
    public ApiResponse<DocumentChunkQueryVo> queryDocumentChunks(@Valid @RequestBody DocumentChunkQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryDocumentChunks(dto));
    }

    @Operation(summary = "查询单个文档 chunk 详情")
    @PostMapping("/chunk/detail/query")
    public ApiResponse<DocumentChunkDetailVo> queryDocumentChunkDetail(@Valid @RequestBody DocumentChunkDetailQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryDocumentChunkDetail(dto));
    }

    @Operation(summary = "查询任务执行日志")
    @PostMapping("/task/log/query")
    public ApiResponse<DocumentTaskLogQueryVo> queryTaskLogs(@Valid @RequestBody DocumentTaskLogQueryDto dto) {
        return ApiResponse.ok(documentManageService.queryTaskLogs(dto));
    }

}
