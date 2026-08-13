package org.example.repository;

import org.example.entity.KnowledgeBase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 知识库数据访问层.
 * <p>
 * 继承 JpaRepository 即有 save/findAll/deleteById 等基础方法,
 * 这里按方法名约定补几个知识库场景常用的查询.
 *
 * @author ckj
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    /** 列出某用户创建的【未删除】知识库(知识库列表页用). */
    Page<KnowledgeBase> findByCreatedByAndDeletedFalse(Long createdBy, Pageable pageable);

    /** 列出某用户创建的【未删除】知识库, 且 id 精确命中(按单个 id 筛选用). */
    Page<KnowledgeBase> findByCreatedByAndIdAndDeletedFalse(Long createdBy, Long id, Pageable pageable);
}
