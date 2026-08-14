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
        IndexTask task = createTask(id);
        // 提交异步任务去解析文件
        indexTaskLauncher.launchWithText(task.getId(), id, text);
    }

    /**
     * 文件已存 MinIO 的文档提交索引任务: 异步任务里从 MinIO 下载后走解析器
     * (区别于 {@link #submitTask}, 那条路直接给文本, 跳过解析, 只适用于纯文本).
     */
    public void submitTaskFromMinio(Long docId) {
        IndexTask task = createTask(docId);
        indexTaskLauncher.launchFromMinio(task.getId(), docId);
    }

    private IndexTask createTask(Long docId) {
        IndexTask task = new IndexTask();
        task.setDocId(docId);
        task.setTaskType(IndexTask.TASK_TYPE_INDEX);
        return indexTaskRepository.save(task);
    }
}
