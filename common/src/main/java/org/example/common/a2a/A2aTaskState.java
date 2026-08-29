package org.example.common.a2a;

/**
 * A2A TaskState 枚举值(规范 4.1.3): JSON 里按 ProtoJSON 约定用
 * SCREAMING_SNAKE_CASE 字符串(枚举在 proto 中的原始名)。
 *
 * @author ckj
 */
public final class A2aTaskState {

    public static final String SUBMITTED = "TASK_STATE_SUBMITTED";
    public static final String WORKING = "TASK_STATE_WORKING";
    public static final String COMPLETED = "TASK_STATE_COMPLETED";
    public static final String FAILED = "TASK_STATE_FAILED";
    public static final String CANCELED = "TASK_STATE_CANCELED";
    public static final String REJECTED = "TASK_STATE_REJECTED";
    /** 中断态: 需要用户补充输入(多轮), 本实现未用 */
    public static final String INPUT_REQUIRED = "TASK_STATE_INPUT_REQUIRED";
    /** 中断态: 需要授权凭证, 本实现未用 */
    public static final String AUTH_REQUIRED = "TASK_STATE_AUTH_REQUIRED";

    private A2aTaskState() {
    }
}
