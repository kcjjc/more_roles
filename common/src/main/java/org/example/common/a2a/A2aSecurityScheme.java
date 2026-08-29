package org.example.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A2A Card 的认证方案声明(规范 4.5, OpenAPI 风格): oneof 包装, 本实现只用 API key.
 * 对应 JSON: {"apiKeySecurityScheme": {"in": "HEADER", "name": "X-Api-Key"}}
 *
 * @author ckj
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aSecurityScheme(ApiKeySecurityScheme apiKeySecurityScheme) {

    /** API key 传递位置与字段名(规范 4.5.2) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiKeySecurityScheme(String in, String name) {
    }
}
