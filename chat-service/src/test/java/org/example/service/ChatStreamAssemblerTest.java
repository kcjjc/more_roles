package org.example.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatStreamAssembler 的纯单测(mock ChatResponse, 不依赖真实模型与 SSE 传输):
 * 覆盖 增量按序输出 / 空块跳过 / usage 捕获与缺失兜底 / 错误流与空回复丢弃 / 落库回调异常 / 客户端断开.
 *
 * @author ckj
 */
class ChatStreamAssemblerTest {

    private final ChatStreamAssembler assembler = new ChatStreamAssembler();

    // ---------- 测试桩 ----------

    /** 文本块(getMetadata 未打桩 → mock 返回 null, 模拟无 usage 的普通增量块) */
    private static ChatResponse chunk(String text) {
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResult()).thenReturn(text == null ? null : new Generation(new AssistantMessage(text)));
        return resp;
    }

    /** usage-only 末块(streamUsage 开启时供应商发的最后一块: 无文本, 只带 usage) */
    private static ChatResponse usageChunk(int tokens) {
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResult()).thenReturn(null);
        Usage usage = mock(Usage.class);
        when(usage.getTotalTokens()).thenReturn(tokens);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);
        when(resp.getMetadata()).thenReturn(metadata);
        return resp;
    }

    /** 回调记录桩: 落库回调收到的 (reply, tokens) */
    private static final class Recorder implements ChatStreamAssembler.Completion {
        final AtomicReference<String> reply = new AtomicReference<>();
        final AtomicReference<Integer> tokens = new AtomicReference<>();

        @Override
        public void onComplete(String reply, int tokens) {
            this.reply.set(reply);
            this.tokens.set(tokens);
        }
    }

    // ---------- 用例 ----------

    @Test
    void 正常完成_增量按序输出_回调收到聚合文本与usage() {
        Recorder cb = new Recorder();
        List<ServerSentEvent<String>> frames = assembler
                .assemble(Flux.just(chunk("你"), chunk("好"), usageChunk(66)), 1L, cb)
                .collectList().block();

        assertEquals(3, frames.size());
        assertEquals("delta", frames.get(0).event());
        assertEquals("你", frames.get(0).data());
        assertEquals("delta", frames.get(1).event());
        assertEquals("好", frames.get(1).data());
        assertEquals("done", frames.get(2).event());
        assertTrue(frames.get(2).data().contains("\"conversationId\":1"), "done 帧应含 conversationId: " + frames.get(2).data());
        assertTrue(frames.get(2).data().contains("\"tokens\":66"), "done 帧应含 usage tokens: " + frames.get(2).data());
        // 拿到 done 帧 ⇒ 落库回调已先于它执行(finishFrame 先回调再产 done)
        assertEquals("你好", cb.reply.get());
        assertEquals(66, cb.tokens.get());
    }

    @Test
    void 空文本块跳过_不产delta也不进聚合() {
        Recorder cb = new Recorder();
        List<ServerSentEvent<String>> frames = assembler
                .assemble(Flux.just(chunk(null), chunk("灯塔"), usageChunk(9)), 2L, cb)
                .collectList().block();

        assertEquals(2, frames.size());   // 只有 灯塔 的 delta + done
        assertEquals("delta", frames.get(0).event());
        assertEquals("灯塔", frames.get(0).data());
        assertEquals("灯塔", cb.reply.get());
        assertEquals(9, cb.tokens.get());
    }

    @Test
    void usage缺失_tokens兜底0() {
        Recorder cb = new Recorder();
        List<ServerSentEvent<String>> frames = assembler
                .assemble(Flux.just(chunk("hi")), 3L, cb)
                .collectList().block();

        assertEquals("done", frames.get(1).event());
        assertTrue(frames.get(1).data().contains("\"tokens\":0"), "无 usage 时 tokens 应兜底 0: " + frames.get(1).data());
        assertEquals(0, cb.tokens.get());
    }

    @Test
    void 模型流中断_单error帧_不触发落库回调() {
        Recorder cb = new Recorder();
        List<ServerSentEvent<String>> frames = assembler
                .assemble(Flux.just(chunk("部分"))
                        .concatWith(Flux.error(new RuntimeException("boom"))), 4L, cb)
                .collectList().block();

        assertEquals(2, frames.size());   // 部分 delta + error
        assertEquals("delta", frames.get(0).event());
        assertEquals("error", frames.get(1).event());
        assertTrue(frames.get(1).data().contains("模型暂时无响应"), "error 帧应为通用文案: " + frames.get(1).data());
        assertNull(cb.reply.get(), "中断的回复不落库");
    }

    @Test
    void 聚合文本为空_error帧_不触发落库回调() {
        Recorder cb = new Recorder();
        List<ServerSentEvent<String>> frames = assembler
                .assemble(Flux.just(usageChunk(5)), 5L, cb)
                .collectList().block();

        assertEquals(1, frames.size());
        assertEquals("error", frames.get(0).event());
        assertTrue(frames.get(0).data().contains("模型返回为空"), "空回复应为明确提示: " + frames.get(0).data());
        assertNull(cb.reply.get());
    }

    @Test
    void 落库回调抛异常_改发error帧() {
        List<String> called = new ArrayList<>();
        List<ServerSentEvent<String>> frames = assembler
                .assemble(Flux.just(chunk("回复")),
                        6L,
                        (reply, tokens) -> {
                            called.add(reply);
                            throw new OptimisticLockingFailureException("conflict");
                        })
                .collectList().block();

        assertEquals(List.of("回复"), called, "回调应已被调用");
        assertEquals(2, frames.size());   // delta 已流出 + error
        assertEquals("error", frames.get(1).event());
        assertTrue(frames.get(1).data().contains("保存失败"), "落库失败应明确告知: " + frames.get(1).data());
    }

    @Test
    void 客户端断开_收尾帧不产生_不落库() {
        Recorder cb = new Recorder();
        // take(1) 模拟前端拿到第一帧即断开: 上游被 cancel, concatWith 的收尾帧不再订阅
        List<ServerSentEvent<String>> frames = assembler
                .assemble(Flux.just(chunk("你"), usageChunk(7)), 7L, cb)
                .take(1)
                .collectList().block();

        assertEquals(1, frames.size());
        assertEquals("delta", frames.get(0).event());
        assertNull(cb.reply.get(), "断开后未完成回复不落库");
    }
}
