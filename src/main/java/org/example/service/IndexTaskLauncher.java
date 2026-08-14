package org.example.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * @author ckj
 */
@Component
public class IndexTaskLauncher {
    private final LoadService loadService;

    public IndexTaskLauncher (LoadService loadService) {
        this.loadService = loadService;
    }

    @Async("indexTaskExecutor")
    public void launchWithText(Long taskId,Long docId,String textContent) {
        loadService.executeWithText(taskId,docId,textContent);
    }

    /** 文件已存 MinIO 的文档: 异步从 MinIO 下载 → 解析 → 索引(REST 上传入口走这条) */
    @Async("indexTaskExecutor")
    public void launchFromMinio(Long taskId, Long docId) {
        loadService.executeFromMinio(taskId, docId);
    }


}
