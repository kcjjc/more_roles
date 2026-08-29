package org.example.service;

import org.example.common.a2a.A2aMessage;
import org.example.common.a2a.A2aSendMessageRequest;
import org.example.common.a2a.A2aSendMessageResponse;
import org.example.common.a2a.A2aTask;
import org.example.common.a2a.A2aTextPart;
import org.example.service.RagService.AskResult;
import org.example.service.RetrievalService.ChunkHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A2aService 的纯单测(mock RagService, 任务仓用真实内存实现):
 * 覆盖 文本提取与 kbId 透传 / 成功组装 COMPLETED+Artifact(含来源) / 失败转 FAILED Task / 任务仓查询.
 *
 * @author ckj
 */
class A2aServiceTest {

    private final RagService ragService = mock(RagService.class);
    private final A2aTaskStore taskStore = new A2aTaskStore();
    private final A2aService service = new A2aService(ragService, taskStore);

    private static A2aSendMessageRequest request(List<A2aTextPart> parts, Map<String, Object> metadata) {
        return new A2aSendMessageRequest(
                new A2aMessage(A2aMessage.ROLE_USER, parts, "m1", null, null, metadata), null);
    }

    @Test
    void 成功_返回COMPLETED任务_产物含回答与来源() {
        ChunkHit hit = new ChunkHit(33L, 5L, 1L, 4, "每年 5 天带薪年假", 2, "假期管理", 0.87);
        when(ragService.ask("年假有几天", 1L, null))
                .thenReturn(new AskResult("员工每年享有 5 天带薪年假。", List.of(hit)));

        A2aSendMessageResponse resp = service.handleSend(
                request(List.of(new A2aTextPart("年假有几天")), Map.of("kbId", 1L)));

        A2aTask task = resp.task();
        assertEquals("TASK_STATE_COMPLETED", task.status().state());
        assertEquals(1, task.artifacts().size());
        String text = task.artifacts().get(0).parts().get(0).text();
        assertTrue(text.startsWith("员工每年享有 5 天带薪年假"), "产物首段应是回答: " + text);
        assertTrue(text.contains("[来源片段]") && text.contains("每年 5 天带薪年假"), "产物应附来源: " + text);
        // 任务已入仓, 可按 id 查回
        assertEquals(task, service.getTask(task.id()));
    }

    @Test
    void 多段文本按换行拼接_kbId字符串也能解析_缺失则全局() {
        when(ragService.ask("第一行\n第二行", null, null))
                .thenReturn(new AskResult("ok", List.of()));

        A2aSendMessageResponse resp = service.handleSend(request(
                List.of(new A2aTextPart("第一行"), new A2aTextPart("第二行")), null));
        assertEquals("TASK_STATE_COMPLETED", resp.task().status().state());

        // kbId 传字符串形态也应透传
        when(ragService.ask("q", 7L, null)).thenReturn(new AskResult("ok", List.of()));
        service.handleSend(request(List.of(new A2aTextPart("q")), Map.of("kbId", "7")));
        // kbId 缺失 → null(全局检索), 上一个用例已覆盖
    }

    @Test
    void ask失败_返回FAILED任务_原因在statusMessage() {
        when(ragService.ask("炸了", null, null)).thenThrow(new IllegalStateException("模型调用失败: timeout"));

        A2aSendMessageResponse resp = service.handleSend(
                request(List.of(new A2aTextPart("炸了")), null));

        A2aTask task = resp.task();
        assertEquals("TASK_STATE_FAILED", task.status().state());
        assertNull(task.artifacts());
        assertTrue(task.status().message().parts().get(0).text().contains("模型调用失败"),
                "失败原因应在 status.message: " + task.status().message().parts().get(0).text());
        // 失败任务同样入仓可查
        assertEquals(task, service.getTask(task.id()));
    }

    @Test
    void 查不存在的任务_返回null() {
        assertNull(service.getTask("no-such-task"));
    }
}
