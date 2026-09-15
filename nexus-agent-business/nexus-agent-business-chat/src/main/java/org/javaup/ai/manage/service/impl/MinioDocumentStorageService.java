package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.service.DocumentStorageService;
import org.javaup.ai.manage.support.StoredObjectInfo;
import org.javaup.enums.DocumentManageCode;
import org.javaup.exception.SuperAgentFrameException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/

@AllArgsConstructor
@Service
public class MinioDocumentStorageService implements DocumentStorageService {

    private final MinioClient minioClient;

    private final DocumentManageProperties properties;








    /*
    这里做了两件事：
    1.拼接对象存储路径：格式是：前缀//documentId/时间戳-原始文件名，比如 doc-original/123456/1714000000000-报告.pdf。加时间戳是为了同一文档多次上传时不会互相覆盖
    2.调用upload（）执行真正的上传，然后把 bucket、objectName、objectUrl 封装成 StoredObjectInfo 返回
     */
    @Override
    public StoredObjectInfo uploadOriginalFile(Long documentId, String originalFileName, byte[] bytes, String contentType) {
        //原始文件路径按照"前缀/文档ID/时间戳-原始文件名"组织 便于同一文档的多次上传版本区分与追踪。
        String objectName = properties.getMinio().getObjectPrefix() + "/" + documentId + "/" + System.currentTimeMillis() + "-" + originalFileName;
        upload(objectName, bytes, contentType);
        // 返回对象定位信息给上层，供文档主表持久化保存。
        return new StoredObjectInfo(properties.getMinio().getBucketName(), objectName, buildObjectUrl(objectName));
    }

    @Override
    public String uploadParsedText(Long documentId, String parsedText) {

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

            return inputStream.readAllBytes();
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STORAGE_FAILED.getCode(),
                "下载 MinIO 文件失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public String downloadText(String objectName) {

        return new String(downloadObject(objectName), StandardCharsets.UTF_8);
    }

    @Override
    public void deleteObjects(List<String> objectNameList) {
        if (CollUtil.isEmpty(objectNameList)) {
            return;
        }

        List<String> validObjectNameList = objectNameList.stream()
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .distinct()
            .toList();
        if (validObjectNameList.isEmpty()) {
            return;
        }

        try {

            if (!bucketExists()) {
                return;
            }

            for (String objectName : validObjectNameList) {
                minioClient.removeObject(
                    RemoveObjectArgs.builder()
                        .bucket(properties.getMinio().getBucketName())
                        .object(objectName)
                        .build()
                );
            }
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STORAGE_FAILED.getCode(),
                "删除 MinIO 文件失败: " + exception.getMessage(), exception);
        }
    }




    /*
    执行一次真正的MinIO对象上传
    <p>
 * 这个方法是上传链路在存储层的核心入口，负责两件事：
 * 1. 确保 bucket 已存在；
 * 2. 把字节数组以指定 contentType 写入 MinIO。
 * </p>
     */
    private void upload(String objectName, byte[] bytes, String contentType) {
        try {
            //先保证桶存在，再写对象；否则首次部署或新环境下第一次上传会直接失败。
            ensureBucketExists();
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(properties.getMinio().getBucketName())
                    .object(objectName)
                        // 如果上层没能提供明确的 MIME 类型（**MIME 是一段字符串标签，用来描述「这一串二进制字节是什么类型的数据」，让接收端知道该怎么处理这堆字节**。），就回退到通用二进制类型。
                    .contentType(StrUtil.isNotBlank(contentType) ? contentType : "application/octet-stream")
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .build()
            );
        }
        catch (Exception exception) {
            // MinIO SDK 抛出的各种底层异常统一包装成业务异常，方便上层按"存储失败"统一处理。
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STORAGE_FAILED.getCode(),
                "上传 MinIO 文件失败: " + exception.getMessage(), exception);
        }
    }



    /*
    ensureBucketExists() 会先检查桶是否存在，不存在就自动创建：
     */
    private void ensureBucketExists() throws Exception {
        if (!bucketExists()) {
            //只有在桶不存在时才创建，避免每次上传都重复发起创建请求。
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.getMinio().getBucketName()).build());
        }
    }

    private boolean bucketExists() throws Exception {
        String bucketName = properties.getMinio().getBucketName();
        return minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
    }


    /*
    最后让 buildObjectUrl() 拼出文件的完整访问地址：
     */
    private String buildObjectUrl(String objectName) {
        String endpoint = properties.getMinio().getEndpoint();
        if (endpoint.endsWith("/")) {
            // 去掉结尾的"/"，避免下面手工拼接 bucket/objectName 时出现重复分隔符。
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return endpoint + "/" + properties.getMinio().getBucketName() + "/" + objectName;
    }
}
