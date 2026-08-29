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

    // ---------- truncate 的代理对安全(防 DeepSeek 400 "unexpected end of hex escape") ----------

    /** 断言字符串不含孤立代理(每个代理 char 都有配对) */
    private static void assertNoLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < s.length(), "高代理 " + (int) c + " 在末尾无人配对");
                assertTrue(Character.isLowSurrogate(s.charAt(i + 1)), "高代理 " + (int) c + " 后面不是低代理");
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(c), "位置 " + i + " 出现无配对的低代理");
            }
        }
    }

    @Test
    void 截断不会切破emoji代理对() {
        // 200 个代码点后紧跟一个 emoji: 旧实现(substring 按 char 切)会把它切成半个
        String text = "a".repeat(200) + "😀" + "b".repeat(10);
        String out = RagRouterService.truncate(text);
        assertEquals(200 + 1, out.codePointCount(0, out.length()));   // 200 个 a + 省略号
        assertTrue(out.endsWith("…"));
        assertNoLoneSurrogate(out);
    }

    @Test
    void 既有孤立代理被清洗() {
        // 模拟历史脏数据: 高代理 \uD83D 孤立存在(无低代理配对)
        String dirty = "你好\uD83D世界";
        String out = RagRouterService.truncate(dirty);
        assertEquals("你好世界", out);
        assertNoLoneSurrogate(out);
    }

    @Test
    void 短文本原样保留_不加省略号() {
        assertEquals("你好", RagRouterService.truncate("你好"));
        assertEquals("", RagRouterService.truncate(null));
    }
}
