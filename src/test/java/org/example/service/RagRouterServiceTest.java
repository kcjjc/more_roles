package org.example.service;

import org.example.service.RagRouterService.RouteDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由输出解析的纯单测(不依赖 Spring 容器与真实模型):
 * 覆盖 合法 JSON / 带代码围栏 / 字段缺失 / 空白 query / 非法输出 的全部降级路径.
 *
 * @author ckj
 */
class RagRouterServiceTest {

    private static final String RAW = "那后来呢？";

    private RouteDecision parse(String output) {
        return RagRouterService.parseRouteOutput(output, RAW);
    }

    @Test
    void 合法JSON_要查_用改写后的查询() {
        RouteDecision d = parse("{\"need\": true, \"query\": \"星野守夜人后来的经历\"}");
        assertTrue(d.need());
        assertEquals("星野守夜人后来的经历", d.query());
        assertFalse(d.fallback());
    }

    @Test
    void 带代码围栏_能提取出JSON() {
        RouteDecision d = parse("```json\n{\"need\": false, \"query\": \"\"}\n```");
        assertFalse(d.need());
        assertEquals("", d.query());
        assertFalse(d.fallback());
    }

    @Test
    void 判了要查_但query为空_退回原话() {
        RouteDecision d = parse("{\"need\": true, \"query\": \"\"}");
        assertTrue(d.need());
        assertEquals(RAW, d.query());
        assertFalse(d.fallback());
    }

    @Test
    void 没有JSON_降级为拿原话检索() {
        RouteDecision d = parse("我觉得这条消息需要查询资料库。");
        assertTrue(d.need());
        assertEquals(RAW, d.query());
        assertTrue(d.fallback());
    }

    @Test
    void 截断的JSON_降级() {
        RouteDecision d = parse("{\"need\": tru");
        assertTrue(d.fallback());
        assertEquals(RAW, d.query());
    }

    @Test
    void 空输出_降级() {
        assertTrue(parse(null).fallback());
        assertTrue(parse("   ").fallback());
    }

    @Test
    void need写字符串true_也能解析() {
        RouteDecision d = parse("{\"need\": \"true\", \"query\": \"星野 童年经历\"}");
        assertTrue(d.need());
        assertEquals("星野 童年经历", d.query());
    }

    @Test
    void 缺query字段_视为空_退回原话() {
        RouteDecision d = parse("{\"need\": true}");
        assertTrue(d.need());
        assertEquals(RAW, d.query());
    }
}
