package org.javaup.ai.manage.service.impl;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.service.DocumentStorageService;
import org.javaup.ai.manage.support.StoredObjectInfo;
import org.javaup.enums.DocumentManageCode;
import org.javaup.exception.SuperAgentFrameException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 基于 MinIO 的文档存储服务实现。
 *
 * <p>这个类负责管理文档链路里两类对象：</p>
 * <p>1. 用户上传的原始文件。</p>
 * <p>2. 解析后生成的纯文本文件。</p>
 *
 * <p>这样文档主表只保存对象定位信息，真正的大文件都落在对象存储里。</p>
 */
@Service
public class MinioDocumentStorageService implements DocumentStorageService {

    private final MinioClient minioClient;

    private final DocumentManageProperties properties;

    public MinioDocumentStorageService(MinioClient minioClient,
                                       DocumentManageProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @Override
    public StoredObjectInfo uploadOriginalFile(Long documentId, String originalFileName, byte[] bytes, String contentType) {
        // 原始文件按“前缀 / documentId / 时间戳-文件名”组织对象路径，便于排查和避免重名覆盖。
        String objectName = properties.getMinio().getObjectPrefix() + "/" + documentId + "/" + System.currentTimeMillis() + "-" + originalFileName;
        upload(objectName, bytes, contentType);
        return new StoredObjectInfo(properties.getMinio().getBucketName(), objectName, buildObjectUrl(objectName));
    }

    @Override
    public String uploadParsedText(Long documentId, String parsedText) {
        // 解析文本使用固定命名，后续索引构建按 documentId 直接回读即可。
        String objectName = properties.getMinio().getParsedTextPrefix() + "/" + documentId + ".txt";
        upload(objectName, parsedText.getBytes(StandardCharsets.UTF_8), "text/plain;charset=UTF-8");
        return objectName;
    }

    @Override
    public byte[] downloadObject(String objectName) {
        try (InputStream inputStream = minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(properties.getMinio().getBucketName())
                .object(objectName)
                .build())) {
            // 这里统一把对象读成 byte[]，上层再决定按文件还是按文本使用。
            return inputStream.readAllBytes();
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STORAGE_FAILED.getCode(),
                "下载 MinIO 文件失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public String downloadText(String objectName) {
        // 解析文本文件统一按 UTF-8 读取，和 uploadParsedText 的编码保持一致。
        return new String(downloadObject(objectName), StandardCharsets.UTF_8);
    }

    /**
     * 执行对象上传，并在必要时自动补齐 bucket。
     */
    private void upload(String objectName, byte[] bytes, String contentType) {
        try {
            // 上传前先确保 bucket 存在，避免首次部署时因为环境未初始化直接失败。
            ensureBucketExists();
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(properties.getMinio().getBucketName())
                    .object(objectName)
                    .contentType(StringUtils.hasText(contentType) ? contentType : "application/octet-stream")
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .build()
            );
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STORAGE_FAILED.getCode(),
                "上传 MinIO 文件失败: " + exception.getMessage(), exception);
        }
    }

    /**
     * 如果 bucket 不存在，则在首次上传时自动创建。
     */
    private void ensureBucketExists() throws Exception {
        String bucketName = properties.getMinio().getBucketName();
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            // 当前项目选择在首次写入时自动建桶，减少部署前置步骤。
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        }
    }

    /**
     * 生成一个简单的对象访问地址，方便后台展示和排查。
     */
    private String buildObjectUrl(String objectName) {
        String endpoint = properties.getMinio().getEndpoint();
        if (endpoint.endsWith("/")) {
            // 去掉结尾的 /，避免后面拼接地址时出现双斜杠。
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return endpoint + "/" + properties.getMinio().getBucketName() + "/" + objectName;
    }
}
