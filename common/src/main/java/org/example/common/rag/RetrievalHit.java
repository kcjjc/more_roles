package org.example.common.rag;

/**
 * 检索命中分块 + 相似度分数(chat 侧拼 prompt 引用时会用到页码/章节标题).
 *
 * @param id            分块 id(doc_chunk.id)
 * @param docId         所属文档 id
 * @param kbId          所属知识库 id
 * @param chunkIndex    分块在文档内的序号(从 0 起)
 * @param content       分块文本
 * @param pageNum       来源页码(PDF 类文档才有, 可空)
 * @param sectionTitle  来源章节标题(结构化文档才有, 可空)
 * @param score         相似度(1 - cosine 距离, 越大越相关; rag 侧已按阈值过滤)
 * @author ckj
 */
public record RetrievalHit(Long id, Long docId, Long kbId, Integer chunkIndex,
                           String content, Integer pageNum, String sectionTitle,
                           double score) {
}
