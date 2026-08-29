package org.example.service;

/**
 * ReAct 循环的一步轨迹(Action → Observation): 模型发起的一次工具调用及其结果摘要.
 * <p>
 * durationMs 为该<b>轮</b>工具执行耗时 —— 一轮的 assistant 消息可含多个并行 toolCall,
 * 此时各 step 记同一段轮耗时.
 *
 * @param toolName    工具名(如 searchKnowledgeBase)
 * @param arguments   模型给出的参数原文(JSON, 超长截断)
 * @param observation 工具返回的结果摘要(超长截断)
 * @param durationMs  该轮工具执行耗时(毫秒)
 * @author ckj
 */
public record ReActStep(String toolName, String arguments, String observation, long durationMs) {
}
