package org.example.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @author ckj
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MinioStorageService {
    public byte[] download(String objectPath) {
        throw new UnsupportedOperationException("MinIO 完整实现见后面");
    }

    public void delete(String objectPath) {
        throw new UnsupportedOperationException("MinIO 完整实现见后面");
    }
}
