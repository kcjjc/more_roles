package org.example.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A2A message:send 的响应体(规范 3.1.1): oneof —— 返回 Task(需要任务跟踪时)
 * 或直接返回 Message(简单交互). 本实现恒返回 Task(COMPLETED/FAILED);
 * 序列化时 message 为 null 即被省略, oneof 语义由 NON_NULL 实现。
 *
 * @author ckj
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aSendMessageResponse(A2aTask task, A2aMessage message) {

    public static A2aSendMessageResponse ofTask(A2aTask task) {
        return new A2aSendMessageResponse(task, null);
    }
}
