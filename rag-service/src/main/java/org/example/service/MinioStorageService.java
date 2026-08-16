package org.example.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * @author ckj
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MinioStorageService {

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    /** 分块大小，S3 multipart 协议的单块下限是 5MB */
    private static final long PART_SIZE = 5 * 1024 * 1024;

    public void upload(String objectPath, byte[] bytes, String contentType) {
        upload(objectPath, new ByteArrayInputStream(bytes), bytes.length, contentType);
    }

    /**
     * 分块上传：文件超过 PART_SIZE 时由 SDK 自动走 S3 multipart 分块传输，
     * 不超过则退化为单次 PUT。入参流由调用方负责关闭。
     */
    public void upload(String objectPath, InputStream stream, long size, String contentType) {
        ensureBucketExists();
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .stream(stream, size, PART_SIZE)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build());
            log.debug("MinIO 上传完成, objectPath={}, {}字节", objectPath, size);
        } catch (Exception e) {
            throw new RuntimeException("MinIO 上传文件失败, objectPath=" + objectPath, e);
        }
    }

    private synchronized void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket 不存在，已自动创建, bucket={}", bucket);
            }
        } catch (Exception e) {
            throw new RuntimeException("MinIO 检查/创建 bucket 失败, bucket=" + bucket, e);
        }
    }

    public byte[] download(String objectPath) {
        try (GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectPath)
                .build())) {
            byte[] bytes = response.readAllBytes();
            log.debug("MinIO 下载完成, objectPath={}, {}字节", objectPath, bytes.length);
            return bytes;
        } catch (Exception e) {
            throw new RuntimeException("MinIO 下载文件失败, objectPath=" + objectPath, e);
        }
    }

    public void delete(String objectPath) {
        throw new UnsupportedOperationException("MinIO 完整实现见后面");
    }
}
