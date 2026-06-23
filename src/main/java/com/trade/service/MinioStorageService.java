package com.trade.service;

import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 对象存储服务 —— 用于存储和检索原始文件
 */
@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucket-name:documents}")
    private String bucketName;

    /**
     * 存储原始文件到 MinIO
     *
     * @param documentId 文档 ID
     * @param fileName  原始文件名
     * @param content   文件内容
     * @param contentType MIME 类型
     * @return 存储成功返回 true
     */
    public boolean storeFile(String documentId, String fileName, byte[] content, String contentType) {
        try {
            // 确保 bucket 存在
            ensureBucketExists();

            // 构建对象路径：documents/{documentId}/{fileName}
            String objectName = documentId + "/" + fileName;

            // 上传文件
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(new ByteArrayInputStream(content), content.length, -1)
                            .contentType(contentType)
                            .build()
            );

            log.info("✅ 文件存储成功: bucket={}, object={}, size={}", bucketName, objectName, content.length);
            return true;
        } catch (Exception e) {
            log.error("❌ 文件存储失败: documentId={}, fileName={}", documentId, fileName, e);
            return false;
        }
    }

    /**
     * 获取文件内容
     *
     * @param documentId 文档 ID
     * @param fileName  文件名
     * @return 文件内容字节数组，如果 MinIO 不可用或文件不存在则返回 null
     */
    public byte[] getFile(String documentId, String fileName) {
        try {
            // 检查 MinIO 是否可用
            if (!isMinioAvailable()) {
                log.warn("⚠️ MinIO 不可用，无法获取文件: documentId={}, fileName={}", documentId, fileName);
                return null;
            }

            String objectName = documentId + "/" + fileName;

            // 获取文件
            GetObjectResponse response = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );

            // 读取内容
            byte[] content = response.readAllBytes();
            response.close();

            log.debug("✅ 文件获取成功: bucket={}, object={}, size={}", bucketName, objectName, content.length);
            return content;
        } catch (Exception e) {
            log.error("❌ 文件获取失败: documentId={}, fileName={}", documentId, fileName, e);
            return null;
        }
    }

    /**
     * 检查 MinIO 是否可用
     */
    public boolean isMinioAvailable() {
        try {
            minioClient.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(bucketName)
                            .build()
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取文件下载 URL（预签名 URL）
     *
     * @param documentId   文档 ID
     * @param fileName     文件名
     * @param expirySeconds URL 有效期（秒）
     * @return 预签名 URL
     */
    public String getPresignedUrl(String documentId, String fileName, long expirySeconds) {
        try {
            String objectName = documentId + "/" + fileName;

            String url = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(objectName)
                            .expiry((int) expirySeconds, TimeUnit.SECONDS)
                            .build()
            );

            log.debug("✅ 生成预签名 URL: {}", url);
            return url;
        } catch (Exception e) {
            log.error("❌ 生成预签名 URL 失败: documentId={}, fileName={}", documentId, fileName, e);
            return null;
        }
    }

    /**
     * 检查文件是否存在
     *
     * @param documentId 文档 ID
     * @param fileName  文件名
     * @return 是否存在
     */
    public boolean fileExists(String documentId, String fileName) {
        try {
            String objectName = documentId + "/" + fileName;

            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 删除文件
     *
     * @param documentId 文档 ID
     * @param fileName  文件名
     * @return 删除成功返回 true
     */
    public boolean deleteFile(String documentId, String fileName) {
        try {
            String objectName = documentId + "/" + fileName;

            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );

            log.info("✅ 文件删除成功: bucket={}, object={}", bucketName, objectName);
            return true;
        } catch (Exception e) {
            log.error("❌ 文件删除失败: documentId={}, fileName={}", documentId, fileName, e);
            return false;
        }
    }

    /**
     * 确保 bucket 存在，不存在则创建
     */
    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder()
                        .bucket(bucketName)
                        .build()
        );

        if (!exists) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(bucketName)
                            .build()
            );
            log.info("✅ 创建 bucket: {}", bucketName);
        }
    }
}
