package org.example.repository;

import org.example.entity.PersonaFragment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 人格信息分片数据访问层.
 * <p>
 * 一条人格 = 多个分片(共享 personaId). 继承 JpaRepository 即有 save/findAll 等基础方法,
 * 这里再按方法名约定补几个分片场景常用的查询.
 *
 * @author ckj
 */
public interface PersonaFragmentRepository extends JpaRepository<PersonaFragment, Long> {

    /**
     * 取出某用户某条人格的【全部有效分片】, 按 seq 升序.
     * <p>
     * 用途: 代码端拿到后按顺序拼接, 还原完整人格信息.
     */
    List<PersonaFragment> findByUserIdAndPersonaIdAndStatusOrderBySeqAsc(
            Long userId, String personaId, Integer status);

    /**
     * 列出某用户的【全部有效分片】, 先按 personaId 分组、再按 seq 升序.
     * <p>
     * 用途: 概览该用户的所有人格(调用方按 personaId 去重后展示).
     */
    List<PersonaFragment> findByUserIdAndStatusOrderByPersonaIdAscSeqAsc(
            Long userId, Integer status);

    /**
     * 软删除某用户某条人格的全部分片: 把 status 置 0, 不真正删行.
     *
     * @return 受影响行数(被置为删除的分片数)
     */
    @Modifying
    @Query("UPDATE PersonaFragment f SET f.status = :deleted " +
            "WHERE f.userId = :userId AND f.personaId = :personaId")
    int softDeleteByUserIdAndPersonaId(@Param("userId") Long userId,
                                       @Param("personaId") String personaId,
                                       @Param("deleted") Integer deleted);
}
