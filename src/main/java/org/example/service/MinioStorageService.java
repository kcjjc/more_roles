package org.example.service;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
