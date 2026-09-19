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


public interface DocumentManageService {

    DocumentUploadVo upload(MultipartFile file, DocumentUploadDto dto);//上传文件；存 MinIO、创建解析任务、发 Kafka 消息

    DocumentPageQueryVo queryDocumentPage(DocumentPageQueryDto dto);//分页查询文档列表

    DocumentListItemVo queryDocumentDetail(DocumentDetailQueryDto dto);//查询文档详情

    DocumentDeleteVo deleteDocument(DocumentDeleteDto dto);//删除文档：清向量库、关键词索引、导航索引、图投影、Profile、分块、任务记录

    DocumentStrategyPlanQueryVo queryStrategyPlan(DocumentStrategyPlanQueryDto dto);//查询分块策略方案

    DocumentStrategyConfirmVo confirmStrategy(DocumentStrategyConfirmDto dto);//确认/调整分块策略

    DocumentIndexBuildVo buildIndex(DocumentIndexBuildDto dto);//触发索引构建：创建构建任务、发 Kafka 消息

    DocumentChunkQueryVo queryDocumentChunks(DocumentChunkQueryDto dto);//查询文档的分块列表

    DocumentChunkDetailVo queryDocumentChunkDetail(DocumentChunkDetailQueryDto dto);//查询单个分块详情

    DocumentTaskLogQueryVo queryTaskLogs(DocumentTaskLogQueryDto dto);//查询任务日志
}
