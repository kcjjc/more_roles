package org.example.tools;

import org.example.common.rag.RetrievalHit;
import org.example.service.RagRetrievalClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagTools 的纯单测(mock RagRetrievalClient, 不依赖 rag-service 与真实模型):
 * 覆盖 空结果降级文案 / 命中拼接格式(带页码章节与不带).
 *
 * @author ckj
 */
class RagToolsTest {

    private final RagRetrievalClient client = mock(RagRetrievalClient.class);

    private static RetrievalHit hit(String content, Integer pageNum, String sectionTitle) {
        return new RetrievalHit(1L, 1L, 7L, 0, content, pageNum, sectionTitle, 0.9);
    }

    @Test
    void 空结果_返回提示让模型自行兜底() {
        when(client.retrieve("星野是谁", 7L, null)).thenReturn(List.of());
        RagTools tools = RagTools.forConversation(client, 7L);
        assertEquals("未检索到相关内容，请基于你已有的角色设定回答",
                tools.searchKnowledgeBase("星野是谁"));
    }

    @Test
    void 命中_拼接编号页码章节与正文() {
        when(client.retrieve("星野是谁", 7L, null))
                .thenReturn(List.of(hit("守夜人", 3, "童年"), hit("灯塔", null, null)));
        RagTools tools = RagTools.forConversation(client, 7L);
        String out = tools.searchKnowledgeBase("星野是谁");
        assertTrue(out.startsWith("[1] 第3页·童年\n守夜人\n"), "带页码章节的片段应形如 [1] 第3页·童年\\n正文\\n, 实际: " + out);
        assertTrue(out.contains("[2] 灯塔\n"), "无页码章节的片段只有编号+正文, 实际: " + out);
    }
}
