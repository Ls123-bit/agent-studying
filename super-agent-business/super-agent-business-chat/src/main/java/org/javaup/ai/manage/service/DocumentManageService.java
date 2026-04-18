package org.javaup.ai.manage.service;

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
import org.javaup.ai.manage.vo.DocumentIndexBuildVo;
import org.javaup.ai.manage.vo.DocumentChunkQueryVo;
import org.javaup.ai.manage.vo.DocumentChunkDetailVo;
import org.javaup.ai.manage.vo.DocumentDeleteVo;
import org.javaup.ai.manage.vo.DocumentListItemVo;
import org.javaup.ai.manage.vo.DocumentPageQueryVo;
import org.javaup.ai.manage.vo.DocumentStrategyConfirmVo;
import org.javaup.ai.manage.vo.DocumentStrategyPlanQueryVo;
import org.javaup.ai.manage.vo.DocumentTaskLogQueryVo;
import org.javaup.ai.manage.vo.DocumentUploadVo;
import org.springframework.web.multipart.MultipartFile;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 文档管理应用服务。
 */
public interface DocumentManageService {

    /**
     * 上传文档并投递解析任务。
     */
    DocumentUploadVo upload(MultipartFile file, DocumentUploadDto dto);

    /**
     * 分页查询文档列表。
     */
    DocumentPageQueryVo queryDocumentPage(DocumentPageQueryDto dto);

    /**
     * 查询文档详情。
     */
    DocumentListItemVo queryDocumentDetail(DocumentDetailQueryDto dto);

    /**
     * 删除文档及其关联数据。
     */
    DocumentDeleteVo deleteDocument(DocumentDeleteDto dto);

    /**
     * 查询当前文档的策略推荐结果。
     */
    DocumentStrategyPlanQueryVo queryStrategyPlan(DocumentStrategyPlanQueryDto dto);

    /**
     * 确认最终策略方案。
     */
    DocumentStrategyConfirmVo confirmStrategy(DocumentStrategyConfirmDto dto);

    /**
     * 构建索引。
     */
    DocumentIndexBuildVo buildIndex(DocumentIndexBuildDto dto);

    /**
     * 查询文档 chunk 列表。
     */
    DocumentChunkQueryVo queryDocumentChunks(DocumentChunkQueryDto dto);

    /**
     * 查询单个 chunk 详情。
     */
    DocumentChunkDetailVo queryDocumentChunkDetail(DocumentChunkDetailQueryDto dto);

    /**
     * 查询任务日志。
     */
    DocumentTaskLogQueryVo queryTaskLogs(DocumentTaskLogQueryDto dto);
}
