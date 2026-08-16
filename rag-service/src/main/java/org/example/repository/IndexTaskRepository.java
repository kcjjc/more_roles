package org.example.repository;

import org.example.entity.IndexTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 任务索引数据访问层.
 *
 * @author ckj
 */
public interface IndexTaskRepository extends JpaRepository<IndexTask, Long> {

    /** 扫描处于指定状态的任务, 按创建时间正序(先进先出, 索引轮询捞活用). */
    List<IndexTask> findByStatusOrderByCreatedAtAsc(String status);

    /** 取某文档的全部任务, 按创建时间倒序(看一个文档的索引/重建历史). */
    List<IndexTask> findByDocIdOrderByCreatedAtDesc(Long docId);
}
