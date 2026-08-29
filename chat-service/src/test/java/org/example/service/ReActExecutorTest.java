package org.example.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReActExecutor 循环语义的纯单测(mock ChatModel, 不依赖真实模型):
 * 覆盖 一轮工具调用后收敛 / 模型不请求工具直通 / 达轮数上限熔断去工具收尾.
 *
 * @author ckj
 */
class ReActExecutorTest {

    /** 探针工具: 记录模型实际传来的参数, 供断言 Action 是否正确触达 */
    static class ProbeTool {
        final List<String> received = new ArrayList<>();

        @Tool(description = "测试探针工具")
        public String probe(@ToolParam(description = "查询") String query) {
            received.add(query);
            return "探针结果:" + query;
        }
    }

    private final ChatModel chatModel = mock(ChatModel.class);
    private final ReActExecutor executor = new ReActExecutor(chatModel);

    private ReActExecutor withMaxIterations(int max) {
        ReflectionTestUtils.setField(executor, "maxIterations", max);
        return executor;
    }

    private static ChatResponse toolCallResp(String toolName, String args) {
        AssistantMessage assistant = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, args)))
                .build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    private static ChatResponse textResp(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void 一轮工具调用后收敛_轨迹含Action与Observation() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCallResp("probe", "{\"query\":\"星野是谁\"}"))
                .thenReturn(textResp("她是守夜人"));

        ProbeTool tool = new ProbeTool();
        ReActExecutor.ReActResult r = withMaxIterations(5)
                .execute("系统", List.of(), "星野是谁", List.of(tool));

        assertEquals("她是守夜人", r.reply());
        assertFalse(r.truncated());
        assertEquals(List.of("星野是谁"), tool.received);
        assertEquals(1, r.steps().size());
        assertEquals("probe", r.steps().get(0).toolName());
        assertTrue(r.steps().get(0).observation().contains("探针结果:星野是谁"));

        // 第二次模型调用必须带上工具结果(Observation 回传进消息列表)
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(captor.capture());
        boolean hasToolResponse = captor.getAllValues().get(1).getInstructions().stream()
                .anyMatch(m -> m instanceof ToolResponseMessage);
        assertTrue(hasToolResponse);
    }

    @Test
    void 模型不请求工具_直通返回() {
        when(chatModel.call(any(Prompt.class))).thenReturn(textResp("直接回答"));
        ProbeTool tool = new ProbeTool();

        ReActExecutor.ReActResult r = withMaxIterations(5)
                .execute("系统", List.of(), "你好", List.of(tool));

        assertEquals("直接回答", r.reply());
        assertTrue(r.steps().isEmpty());
        assertTrue(tool.received.isEmpty());
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void 达轮数上限_去工具强制收尾() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCallResp("probe", "{\"query\":\"一\"}"))
                .thenReturn(toolCallResp("probe", "{\"query\":\"二\"}"))
                .thenReturn(textResp("兜底回复"));

        ProbeTool tool = new ProbeTool();
        ReActExecutor.ReActResult r = withMaxIterations(1)
                .execute("系统", List.of(), "追问", List.of(tool));

        assertTrue(r.truncated());
        assertEquals("兜底回复", r.reply());
        assertEquals(1, tool.received.size());   // 第二次工具请求未被执行, 直接收尾
        assertEquals(1, r.steps().size());
        verify(chatModel, times(3)).call(any(Prompt.class));
    }
}
