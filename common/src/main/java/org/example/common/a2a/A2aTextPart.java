package org.example.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A2A 文本 Part(规范 4.1.6). v1.0 起 Part <b>无 kind 判别符</b> ——
 * JSON 成员名本身就是类型标识: {"text": "..."} 即 TextPart.
 *
 * @author ckj
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aTextPart(String text) {
}
