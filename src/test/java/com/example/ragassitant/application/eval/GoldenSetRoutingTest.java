package com.example.ragassitant.application.eval;

import com.example.ragassitant.domain.model.ToolCall;
import com.example.ragassitant.domain.service.HeuristicRouter;
import com.example.ragassitant.domain.service.PromptInjectionDetector;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 确定性评测：评测集里每条用例的 {@code route} 断言，逐条验证路由层（不调 LLM）。
 *
 * <p>路由层的判定顺序和 AgentAppService 一致：闲聊短路 → 注入拦截 → 启发式工具路由。
 * 改闲聊词表、改注入规则、改正则路由之后，这里会直接指出哪条用例被带坏了。
 */
class GoldenSetRoutingTest {

    private static final GoldenSet SET = GoldenSet.load("golden-v1");
    private final HeuristicRouter router = new HeuristicRouter();
    private final PromptInjectionDetector injection = new PromptInjectionDetector();

    @Test
    void goldenSetIsWellFormed() {
        assertTrue(SET.cases().size() >= 50, "评测集至少 50 条");
        Set<String> ids = new HashSet<>();
        for (GoldenSet.Case c : SET.cases()) {
            assertTrue(ids.add(c.id()), "用例 id 重复: " + c.id());
            assertNotNull(c.question(), c.id());
            assertNotNull(c.route(), c.id() + " 缺少 route 断言");
            assertTrue(List.of("rag", "agent").contains(c.mode()), c.id() + " mode 非法: " + c.mode());
            assertNotNull(c.answer(), c.id() + " 缺少 answer 断言");
        }
    }

    @TestFactory
    Stream<DynamicTest> routeMatchesGoldenSet() {
        return SET.cases().stream().map(c -> DynamicTest.dynamicTest(c.id() + " " + c.question(), () -> {
            String actual = routeOf(c.question());
            assertEquals(c.route(), actual, "路由不符: " + c.question());
            if (c.routeArgs() != null) {
                ToolCall call = router.route(c.question());
                for (Map.Entry<String, Object> e : c.routeArgs().entrySet()) {
                    Object got = call.arguments.get(e.getKey());
                    assertEquals(String.valueOf(e.getValue()), String.valueOf(got),
                            c.id() + " 参数 " + e.getKey() + " 不符");
                }
            }
        }));
    }

    private String routeOf(String question) {
        if (HeuristicRouter.isSmallTalk(question)) return "small_talk";
        if (injection.isPromptInjection(question)) return "injection";
        ToolCall call = router.route(question);
        return call == null ? "none" : call.name;
    }
}
