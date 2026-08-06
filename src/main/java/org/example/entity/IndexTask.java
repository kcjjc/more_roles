package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 任务索引实体, 对应 PG 里的 index_task 表.
 * <p>
 * 文档入库/重建走异步任务队列: 一条任务对应一个文档的一次索引动作,
 * {@link #retryCount} / {@link #maxRetry} 控制失败重试, {@link #startedAt} / {@link #finishedAt}
 * 记录任务起止便于排查耗时.
 *
 * @author ckj
 */
@Entity
@Table(name = "index_task")
public class IndexTask {

    /** 任务类型: 建索引 */
    public static final String TASK_TYPE_INDEX = "INDEX";
    /** 任务类型: 重建索引 */
    public static final String TASK_TYPE_REINDEX = "REINDEX";

    /** 任务状态: 待处理 */
    public static final String STATUS_PENDING = "PENDING";
    /** 任务状态: 处理中 */
    public static final String STATUS_PROCESSING = "PROCESSING";
    /** 任务状态: 完成 */
    public static final String STATUS_DONE = "DONE";
    /** 任务状态: 失败 */
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // 对应 PG 的 BIGSERIAL 自增
    private Long id;

    /** 目标文档(关联 document.id) */
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 任务类型: {@link #TASK_TYPE_INDEX} / {@link #TASK_TYPE_REINDEX} */
    @Column(name = "task_type", nullable = false, length = 20)
    private String taskType = TASK_TYPE_INDEX;

    /** 任务状态: {@link #STATUS_PENDING} / {@link #STATUS_PROCESSING} / {@link #STATUS_DONE} / {@link #STATUS_FAILED} */
    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    /** 已重试次数 */
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    /** 最大重试次数 */
    @Column(name = "max_retry", nullable = false)
    private Integer maxRetry = 3;

    /** 失败原因(仅 FAILED 时填) */
    @Column(name = "error_msg", columnDefinition = "text")
    private String errorMsg;

    /** 任务创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 开始处理时间 */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** 完成时间(成功或失败都会填) */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (taskType == null) {
            taskType = TASK_TYPE_INDEX;
        }
        if (status == null) {
            status = STATUS_PENDING;
        }
        if (retryCount == null) {
            retryCount = 0;
        }
        if (maxRetry == null) {
            maxRetry = 3;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Integer getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(Integer maxRetry) {
        this.maxRetry = maxRetry;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    @Override
    public String toString() {
        return "IndexTask{" +
                "id=" + id +
                ", docId=" + docId +
                ", taskType='" + taskType + '\'' +
                ", status='" + status + '\'' +
                ", retryCount=" + retryCount +
                ", maxRetry=" + maxRetry +
                '}';
    }
}
