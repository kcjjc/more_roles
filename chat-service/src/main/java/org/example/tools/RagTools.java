package org.example.tools;

import org.example.common.rag.RetrievalHit;
import org.example.service.RagRetrievalClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * RAG 检索工具: 把"查知识库"暴露给模型自主调用 —— ReAct 的 Act 部分(Agentic RAG).
 * <p>
 * 有意<b>不做</b> {@code @Component} 单例: kbId 是每会话状态, 由 {@link #forConversation}
 * 按会话现造实例传入 {@code ChatClient.prompt().tools(Object...)}(接受任意对象实例, 反射扫描 @Tool 方法).
 * 安全约束: kbId 绝不作为工具参数暴露 —— 模型可控的只有 query, 否则模型幻觉出他人库 id 即可越权检索.
 * <p>
 * 降级语义沿用 {@link RagRetrievalClient#retrieve}: rag-service 不可用返回空列表,
 * 这里转成"未检索到"提示让模型基于角色设定自行兜底, 不中断对话.
 *
 * @author ckj
 */
public class RagTools {

    private final RagRetrievalClient ragRetrievalClient;
    private final Long kbId;

    private RagTools(RagRetrievalClient ragRetrievalClient, Long kbId) {
        this.ragRetrievalClient = ragRetrievalClient;
        this.kbId = kbId;
    }

    /** 按会话创建工具实例: kbId 来自会话绑定(建会话时已做归属校验), 不经过模型 */
    public static RagTools forConversation(RagRetrievalClient ragRetrievalClient, Long kbId) {
        return new RagTools(ragRetrievalClient, kbId);
    }

    @Tool(description = """
            检索当前会话绑定的角色知识库。当用户问题涉及角色设定、背景故事、世界观
            或资料库文档中的事实性内容时调用；闲聊、问候、常识问题不要调用。
            query 必须是独立完整的检索句：结合对话历史补全代词指代（如"她"要写成
            具体角色名），脱离上下文也能看懂，不超过50字。若检索结果不足以回答，
            可以换个角度重新组织 query 再次调用。
            """)
    public String searchKnowledgeBase(
            @ToolParam(description = "检索用句子，补全指代后的完整问题") String query) {
        List<RetrievalHit> hits = ragRetrievalClient.retrieve(query, kbId, null);   // topK=null → rag 侧默认配置
        if (hits == null || hits.isEmpty()) {
            return "未检索到相关内容，请基于你已有的角色设定回答";
        }
        return formatHits(hits);
    }

    /** 片段格式对齐 ChatService.buildSystem 的【参考资料】段: 编号 + 页码/章节 + 正文 */
    private String formatHits(List<RetrievalHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            RetrievalHit hit = hits.get(i);
            sb.append('[').append(i + 1).append("] ");
            if (hit.pageNum() != null || hit.sectionTitle() != null) {
                if (hit.pageNum() != null) {
                    sb.append("第").append(hit.pageNum()).append("页");
                }
                if (hit.sectionTitle() != null) {
                    if (hit.pageNum() != null) {
                        sb.append("·");
                    }
                    sb.append(hit.sectionTitle());
                }
                sb.append('\n');
            }
            sb.append(hit.content()).append('\n');
        }
        return sb.toString();
    }
}
