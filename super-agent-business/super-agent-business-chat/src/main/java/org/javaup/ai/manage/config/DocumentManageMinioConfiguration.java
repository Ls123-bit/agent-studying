package org.javaup.ai.manage.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.javaup.enums.DocumentManageCode;
import org.javaup.exception.SuperAgentFrameException;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 配置类
 * @author: 阿星不是程序员
 **/
/**
 * 文档管理模块 MinIO 配置。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(DocumentManageProperties.class)
public class DocumentManageMinioConfiguration {

    /**
     * 构建 MinIO 客户端。
     */
    @Bean
    public MinioClient documentMinioClient(DocumentManageProperties properties) {
        return MinioClient.builder()
            .endpoint(properties.getMinio().getEndpoint())
            .credentials(properties.getMinio().getAccessKey(), properties.getMinio().getSecretKey())
            .build();
    }

    /**
     * 应用启动后主动校验 bucket 是否存在。
     *
     * <p>这样可以在系统启动阶段就把 MinIO 的基础存储环境准备好，
     * 避免用户第一次上传文件时才触发 bucket 创建，提升首次请求体验。</p>
     *
     * <p>同时，业务层上传时仍然保留 bucket 校验逻辑作为兜底，
     * 防止运行过程中 bucket 被手工删除后出现不可恢复的问题。</p>
     */
    @Bean
    public CommandLineRunner documentMinioBucketInitializer(MinioClient documentMinioClient,
                                                            DocumentManageProperties properties) {
        return args -> {
            String bucketName = properties.getMinio().getBucketName();
            try {
                boolean exists = documentMinioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
                if (!exists) {
                    documentMinioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                    log.info("文档管理模块 MinIO bucket 不存在，已自动创建，bucket={}", bucketName);
                }
                else {
                    log.info("文档管理模块 MinIO bucket 已存在，bucket={}", bucketName);
                }
            }
            catch (Exception exception) {
                throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STORAGE_FAILED.getCode(),
                    "初始化 MinIO bucket 失败: " + exception.getMessage(), exception);
            }
        };
    }
}
