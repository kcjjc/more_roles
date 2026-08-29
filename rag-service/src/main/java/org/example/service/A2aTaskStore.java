package org.example.service;

import org.example.common.a2a.A2aTask;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A2A Task 的内存任务仓(最小实现).
 * <p>
 * {@code GET /tasks/{id}} 的数据源. 学习导向的最简选择:
 * <ul>
 *   <li>{@link LinkedHashMap} + synchronized —— 按 accessOrder 淘汰最老条目, 容量有界防内存膨胀;</li>
 *   <li>重启即空 —— 之后 getTask 返回 TaskNotFoundError, 符合规范语义
 *       ("task 可能已过期被清理"), 客户端本就不应依赖长存;</li>
 *   <li>后续演进: 落库映射一张 a2a_task 表(结构与 {@code index_task} 同构, 见 CLAUDE.md)。</li>
 * </ul>
 *
 * @author ckj
 */
@Component
public class A2aTaskStore {

    private static final int MAX_TASKS = 1_000;

    /** accessOrder=true: get 命中也算访问, 淘汰的是"最久未被查询"的任务 */
    private final Map<String, A2aTask> tasks =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, A2aTask> eldest) {
                    return size() > MAX_TASKS;
                }
            };

    public synchronized void put(A2aTask task) {
        tasks.put(task.id(), task);
    }

    public synchronized Optional<A2aTask> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public synchronized int size() {
        return tasks.size();
    }
}
