package org.example.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A2A Artifact(规范 4.1.7): Task 的<b>产出物</b>(与作为通信载体的 Message 相对,
 * 见规范 3.7) —— 任务的回答/文档/结构化数据放这里。本实现把知识库回答作为单个 TextPart。
 *
 * @author ckj
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aArtifact(String artifactId, String name, List<A2aTextPart> parts) {
}
