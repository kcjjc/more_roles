package org.example.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A2A Task(规范 4.1.1): agent 侧的工作单元, 有状态机生命周期
 * (SUBMITTED → WORKING → COMPLETED/FAILED/CANCELED/REJECTED, 或中断态 INPUT_REQUIRED/AUTH_REQUIRED).
 * 服务端生成 id 与 contextId; 产出物放 artifacts 而非 Message(规范 3.7 的消息/产物分离原则)。
 *
 * @author ckj
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aTask(
        String id,
        String contextId,
        A2aTaskStatus status,
        List<A2aArtifact> artifacts) {

    /** Task 当前状态; message 可携带状态说明(如失败原因或 input-required 的追问) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record A2aTaskStatus(String state, A2aMessage message, String timestamp) {
    }
}
