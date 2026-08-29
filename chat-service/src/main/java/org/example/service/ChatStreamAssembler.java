package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式对话 → SSE 事件的拼装单元(无状态)。
 * <p>
 * 把 {@code ChatClient.prompt().stream()} 产出的 {@code Flux<ChatResponse>} 逐块转成
 * SSE 帧流, 帧协议(见 README 的 stream 接口):
 * <ul>
 *   <li>{@code event: delta} —— data 为纯文本增量, 前端按序拼接;</li>
 *   <li>{@code event: done}  —— data 为 {@code {"conversationId":..,"tokens":..}}, 流正常结束;</li>
 *   <li>{@code event: error} —— data 为 {@code {"message":".."}}, 流中断/校验失败/落库失败。</li>
 * </ul>
 * 核心职责: 聚合完整回复(落库用)、捕获末块 usage(调用方开 {@code streamUsage(true)} 时
 * 仅最后一块带 usage, 之后不再有携带 usage 的块)、流正常完成时先触发落库回调再发 done 帧。
 * <p>
 * 丢弃语义: 模型流中断 / 客户端断开(cancel) / 聚合文本为空 —— 均不触发落库回调,
 * 未完成回复直接丢弃, 用户重发即可(与"摘要游标失败不推进, 下次重试"同一思路)。
 *
 * @author ckj
 */
@Component
public class ChatStreamAssembler {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamAssembler.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 流正常完成的回调: reply=聚合出的完整回复(非空), tokens=本次主调用消耗; 由调用方落库 */
    @FunctionalInterface
    public interface Completion {
        void onComplete(String reply, int tokens);
    }

    /**
     * 把模型输出流拼装成 SSE 帧流.
     *
     * @param source         模型流式响应(调用方需已带 streamUsage)
     * @param conversationId 会话 id(日志与 done 帧用)
     * @param completion     流正常结束且聚合文本非空时的落库回调; 抛异常则改发 error 帧
     */
    public Flux<ServerSentEvent<String>> assemble(Flux<ChatResponse> source,
                                                  Long conversationId,
                                                  Completion completion) {
        StringBuilder replyBuf = new StringBuilder();
        AtomicReference<Integer> tokensRef = new AtomicReference<>();
        // 错误门闩: onErrorResume 把错误转成 error 帧后主链会"正常完成",
        // concatWith 的收尾帧必须靠它短路 —— 否则中断的部分回复仍会被落库
        AtomicBoolean errored = new AtomicBoolean(false);
        return source
                .doOnNext(resp -> {
                    String text = textOf(resp);
                    if (text != null && !text.isEmpty()) {
                        replyBuf.append(text);
                    }
                    Integer tokens = totalTokensOf(resp);
                    if (tokens != null) {
                        tokensRef.set(tokens);   // streamUsage(true) 时仅末块带 usage, 覆盖也无妨
                    }
                })
                .mapNotNull(resp -> {
                    String text = textOf(resp);
                    return (text == null || text.isEmpty()) ? null : deltaFrame(text);
                })
                .onErrorResume(e -> {
                    errored.set(true);
                    log.warn("[stream] 会话 {} 模型流中断, 本轮丢弃不落库: {}", conversationId, e.getMessage());
                    return Flux.just(errorFrame("模型暂时无响应，请重试"));
                })
                .doOnCancel(() -> log.warn("[stream] 会话 {} 客户端断开, 未完成回复丢弃不落库", conversationId))
                .concatWith(Flux.defer(() -> errored.get()
                        ? Flux.empty()
                        : Flux.just(finishFrame(conversationId, replyBuf.toString(), tokensRef.get(), completion))));
    }

    /** 收尾帧: 聚合为空 → error; 否则先触发落库回调, 成功发 done, 回调失败发 error */
    private ServerSentEvent<String> finishFrame(Long conversationId, String reply,
                                                Integer tokens, Completion completion) {
        if (reply == null || reply.isBlank()) {
            log.warn("[stream] 会话 {} 流结束但聚合文本为空, 丢弃不落库", conversationId);
            return errorFrame("模型返回为空");
        }
        int resolved = tokens == null ? 0 : tokens;
        try {
            completion.onComplete(reply, resolved);
        } catch (RuntimeException e) {
            log.error("[stream] 会话 {} 流后落库失败: {}", conversationId, e.getMessage(), e);
            return errorFrame("保存失败，请重试");
        }
        return doneFrame(conversationId, resolved);
    }

    // ---------- 帧构造(静态, controller/service 直接复用) ----------

    /** delta 帧: data 为纯文本增量 */
    public static ServerSentEvent<String> deltaFrame(String text) {
        return ServerSentEvent.builder(text).event("delta").build();
    }

    /** done 帧: data 为 {"conversationId":..,"tokens":..} */
    public static ServerSentEvent<String> doneFrame(Long conversationId, int tokens) {
        return ServerSentEvent.builder(json(Map.of("conversationId", conversationId, "tokens", tokens)))
                .event("done")
                .build();
    }

    /** error 帧: data 为 {"message":".."}; message 为空时兜底通用文案 */
    public static ServerSentEvent<String> errorFrame(String message) {
        String msg = (message == null || message.isBlank()) ? "模型暂时无响应，请重试" : message;
        return ServerSentEvent.builder(json(Map.of("message", msg))).event("error").build();
    }

    // ---------- 取值(尽力而为, 任何异常兜底 null, 不让日志/usage 缺失中断流) ----------

    private static String textOf(ChatResponse resp) {
        try {
            return (resp.getResult() != null) ? resp.getResult().getOutput().getText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer totalTokensOf(ChatResponse resp) {
        try {
            if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                return resp.getMetadata().getUsage().getTotalTokens();
            }
        } catch (Exception e) {
            log.debug("取流式块 token 失败, 忽略该块", e);
        }
        return null;
    }

    private static String json(Map<String, Object> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            return values.toString();   // 兜底: 极端情况下前端拿到非严格 JSON, 好过拼装失败
        }
    }
}
