package org.example.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.IndexTask;
import org.example.repository.IndexTaskRepository;
import org.springframework.stereotype.Service;

/**
 * @author ckj
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IndexService {
    private final IndexTaskRepository indexTaskRepository;
    private final IndexTaskLauncher indexTaskLauncher;


    public void submitTask(Long id, String text) {
        IndexTask task = new IndexTask();
        task.setDocId(id);
        task.setTaskType(IndexTask.TASK_TYPE_INDEX);
        indexTaskRepository.save(task);

        // 提交异步任务去解析文件
        indexTaskLauncher.launchWithText(task.getId(), id, text);
    }
}
