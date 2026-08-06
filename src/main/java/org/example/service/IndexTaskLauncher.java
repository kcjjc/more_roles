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


}
