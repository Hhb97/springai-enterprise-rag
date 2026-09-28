package com.example.ragassitant.infrastructure.governance;

import com.example.ragassitant.common.filter.BasicAuthFilter;
import com.example.ragassitant.common.trace.TraceIdFilter;
import com.example.ragassitant.infrastructure.observability.AgentMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ToolGovernanceTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicInteger executions = new AtomicInteger();
    private GovernanceProperties props;
    private ToolGovernance governance;

    @BeforeEach
    void setUp() {
        props = new GovernanceProperties();
        governance = new ToolGovernance(props, null, null, AgentMetrics.noop(), now::get);
        MDC.put(BasicAuthFilter.MDC_USER_KEY, "alice");
        MDC.put(TraceIdFilter.SESSION_ID_KEY, "s1");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private String run(String tool, String args) {
        return governance.execute(tool, args, "native", () -> "result-" + executions.incrementAndGet());
    }

    private GovernanceProperties.ToolPolicy policy(String tool) {
        return props.getTools().computeIfAbsent(tool, k -> new GovernanceProperties.ToolPolicy());
    }

    @Test
    void readOnlyToolExecutesDirectly() {
        assertEquals("result-1", run("list_devices", "{}"));
        assertEquals("result-2", run("list_devices", "{}"), "只读工具不做幂等去重");
    }

    @Test
    void disabledToolIsNotExecuted() {
        policy("get_weather").setEnabled(false);
        String out = run("get_weather", "{}");
        assertTrue(out.contains("禁用"));
        assertEquals(0, executions.get());
    }

    @Test
    void userNotInAllowListIsDenied() {
        policy("restart").setAllowedUsers(List.of("admin"));
        assertTrue(run("restart", "{}").contains("无权"));
        assertEquals(0, executions.get());

        MDC.put(BasicAuthFilter.MDC_USER_KEY, "admin");
        assertEquals("result-1", run("restart", "{}"));
    }

    @Test
    void missingUserContextIsTreatedAsAnonymousAndDenied() {
        policy("restart").setAllowedUsers(List.of("admin"));
        MDC.remove(BasicAuthFilter.MDC_USER_KEY);
        assertTrue(run("restart", "{}").contains("无权"), "拿不到身份时应拒绝，而不是放行");
    }

    @Test
    void dangerousToolIsHeldForApproval() {
        policy("restart_device").setRisk(ToolRisk.DANGEROUS);
        String out = run("restart_device", "{\"sn\":\"mv-001\"}");
        assertTrue(out.contains("尚未执行"));
        assertTrue(out.contains("确认单号"));
        assertEquals(0, executions.get());
    }

    @Test
    void writeToolIsIdempotentWithinWindow() {
        policy("set_threshold").setRisk(ToolRisk.WRITE);
        assertEquals("result-1", run("set_threshold", "{\"v\":1}"));
        assertEquals("result-1", run("set_threshold", "{\"v\":1}"), "窗口内重复调用返回上次结果");
        assertEquals("result-2", run("set_threshold", "{\"v\":2}"), "参数不同不算重复");

        now.addAndGet(61_000);
        assertEquals("result-3", run("set_threshold", "{\"v\":1}"), "窗口过后重新执行");
    }

    @Test
    void idempotencyIsScopedPerSession() {
        policy("set_threshold").setRisk(ToolRisk.WRITE);
        run("set_threshold", "{}");
        MDC.put(TraceIdFilter.SESSION_ID_KEY, "s2");
        assertEquals("result-2", run("set_threshold", "{}"));
    }

    @Test
    void returnDirectOnlyForConfiguredReadOnlyTools() {
        policy("list_devices").setReturnDirect(true);
        policy("dangerous").setRisk(ToolRisk.DANGEROUS);
        policy("dangerous").setReturnDirect(true);

        assertTrue(governance.returnDirect("list_devices"));
        assertFalse(governance.returnDirect("query_energy_by_days"), "未配置默认不直出");
        assertFalse(governance.returnDirect("dangerous"), "非只读工具不能直出");

        props.getDirectOutput().setEnabled(false);
        assertFalse(governance.returnDirect("list_devices"), "总开关关闭时一律不直出");
    }

    @Test
    void executionFailurePropagates() {
        assertThrows(IllegalStateException.class, () -> governance.execute("x", "{}", "native", () -> {
            throw new IllegalStateException("boom");
        }));
    }
}
