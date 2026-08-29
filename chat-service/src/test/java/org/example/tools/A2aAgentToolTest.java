package org.example.tools;

import org.example.common.a2a.A2aArtifact;
import org.example.common.a2a.A2aSendMessageResponse;
import org.example.common.a2a.A2aTask;
import org.example.common.a2a.A2aTask.A2aTaskStatus;
import org.example.common.a2a.A2aTextPart;
import org.example.service.A2aClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A2aAgentTool 的纯单测(mock A2aClient, 不依赖远程 agent 与真实模型):
 * 覆盖 产物文本提取 / agent 不可达降级 / FAILED 任务降级 / 空产物降级.
 *
 * @author ckj
 */
class A2aAgentToolTest {

    private static final String FALLBACK = "知识库专家 agent 暂不可用，请基于你已有的角色设定回答";

    private final A2aClient client = mock(A2aClient.class);

    private static A2aSendMessageResponse taskResp(String state, List<A2aArtifact> artifacts) {
        return new A2aSendMessageResponse(
                new A2aTask("t1", "c1", new A2aTaskStatus(state, null, null), artifacts), null);
    }

    @Test
    void 完成任务_提取产物文本返回给模型() {
        when(client.sendMessage("星野是谁", 7L)).thenReturn(taskResp("TASK_STATE_COMPLETED",
                List.of(new A2aArtifact("a1", "kb-answer", List.of(new A2aTextPart("她是守夜人。"))))));
        A2aAgentTool tool = A2aAgentTool.forConversation(client, 7L);
        assertEquals("她是守夜人。", tool.searchKnowledgeBaseAgent("星野是谁"));
    }

    @Test
    void agent不可达_返回降级文案() {
        when(client.sendMessage("星野是谁", 7L)).thenThrow(new RuntimeException("connect timeout"));
        A2aAgentTool tool = A2aAgentTool.forConversation(client, 7L);
        assertEquals(FALLBACK, tool.searchKnowledgeBaseAgent("星野是谁"));
    }

    @Test
    void 失败任务_返回降级文案() {
        when(client.sendMessage("q", 7L)).thenReturn(taskResp("TASK_STATE_FAILED", null));
        A2aAgentTool tool = A2aAgentTool.forConversation(client, 7L);
        assertEquals(FALLBACK, tool.searchKnowledgeBaseAgent("q"));
    }

    @Test
    void 完成但产物为空_返回降级文案() {
        when(client.sendMessage("q", 7L)).thenReturn(taskResp("TASK_STATE_COMPLETED",
                List.of(new A2aArtifact("a1", "kb-answer", List.of()))));
        A2aAgentTool tool = A2aAgentTool.forConversation(client, 7L);
        assertTrue(tool.searchKnowledgeBaseAgent("q").startsWith("知识库专家 agent 暂不可用"));
    }
}
