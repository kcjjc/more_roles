package org.example.service;

import org.example.entity.PersonaFragment;
import org.example.repository.PersonaFragmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 人格信息服务: 负责长文本的【分片写入】和【合并读取】.
 * <p>
 * 把"人格信息可能很长"这件事对上层屏蔽掉 —— 调用方只管传整段文本、拿整段文本,
 * 切片/拼接的细节都在这里.
 *
 * @author ckj
 */
@Service
public class PersonaService {

    private final PersonaFragmentRepository fragmentRepository;

    public PersonaService(PersonaFragmentRepository fragmentRepository) {
        this.fragmentRepository = fragmentRepository;
    }

    /**
     * 保存一条人格信息: 自动按 4000 字/片切分, 多片共享同一个 personaId.
     *
     * @param userId  所属用户 id
     * @param name    人格名称(可为 null)
     * @param content 完整人格信息(任意长度)
     * @return 该人格的 personaId, 后续用它取出完整内容
     */
    @Transactional
    public String save(Long userId, String name, String content) {
        String personaId = UUID.randomUUID().toString().replace("-", "");

        String text = content == null ? "" : content;
        int total = text.length();
        int seq = 0;
        int pos = 0;

        // 用 do-while: 即便内容为空也至少写一片, 保证 personaId 落库存在
        do {
            int end = Math.min(pos + PersonaFragment.CONTENT_MAX_LENGTH, total);
            String chunk = text.substring(pos, end);

            PersonaFragment fragment = new PersonaFragment();
            fragment.setUserId(userId);
            fragment.setPersonaId(personaId);
            fragment.setSeq(seq);
            fragment.setName(name);
            fragment.setContent(chunk);
            fragment.setStatus(PersonaFragment.STATUS_ACTIVE);
            fragmentRepository.save(fragment);

            pos = end;
            seq++;
        } while (pos < total);

        return personaId;
    }

    /**
     * 取出某用户某条人格的【完整内容】: 查全部有效分片, 按 seq 升序拼接还原.
     *
     * @return 完整人格信息; 若该 personaId 不存在或已全部软删, 返回 empty
     */
    @Transactional(readOnly = true)
    public Optional<String> getContent(Long userId, String personaId) {
        List<PersonaFragment> fragments = fragmentRepository
                .findByUserIdAndPersonaIdAndStatusOrderBySeqAsc(
                        userId, personaId, PersonaFragment.STATUS_ACTIVE);
        if (fragments.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder(fragments.size() * PersonaFragment.CONTENT_MAX_LENGTH);
        for (PersonaFragment f : fragments) {
            sb.append(f.getContent());
        }
        return Optional.of(sb.toString());
    }

    /**
     * 列出某用户的【全部有效人格】概览(每个 personaId 一条).
     *
     * @return 概览列表
     */
    @Transactional(readOnly = true)
    public List<PersonaOverview> listByUser(Long userId) {
        List<PersonaFragment> fragments = fragmentRepository
                .findByUserIdAndStatusOrderByPersonaIdAscSeqAsc(
                        userId, PersonaFragment.STATUS_ACTIVE);

        // 按 personaId 分组: 取第一片的 name, 统计分片数; LinkedHashMap 保持顺序
        Map<String, PersonaOverview> grouped = new LinkedHashMap<>();
        for (PersonaFragment f : fragments) {
            PersonaOverview overview = grouped.computeIfAbsent(
                    f.getPersonaId(),
                    pid -> new PersonaOverview(pid, f.getName()));
            overview.fragmentCount++;
        }
        return new ArrayList<>(grouped.values());
    }

    /**
     * 软删除某用户某条人格(把该 personaId 的全部分片 status 置 0).
     *
     * @return 被置为删除的分片数
     */
    @Transactional
    public int softDelete(Long userId, String personaId) {
        return fragmentRepository.softDeleteByUserIdAndPersonaId(
                userId, personaId, PersonaFragment.STATUS_DELETED);
    }

    /** 人格概览: personaId + 名称 + 分片数(供列表展示) */
    public static class PersonaOverview {
        private final String personaId;
        private final String name;
        private int fragmentCount;

        public PersonaOverview(String personaId, String name) {
            this.personaId = personaId;
            this.name = name;
        }

        public String getPersonaId() {
            return personaId;
        }

        public String getName() {
            return name;
        }

        public int getFragmentCount() {
            return fragmentCount;
        }
    }
}
