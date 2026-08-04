package com.example.ragassitant.infrastructure.agent;

import com.example.ragassitant.domain.model.Device;
import com.example.ragassitant.domain.model.EnergyRecord;
import com.example.ragassitant.domain.model.ToolCall;
import com.example.ragassitant.domain.service.EnergyAggregator;
import com.example.ragassitant.domain.service.ToolCallValidator;
import com.example.ragassitant.domain.spi.TimeSeriesDbPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FORCE_EXEC 路径的工具执行与参数校验。
 *
 * <p>背景：这条路径绕开了 LLM 的工具决策，Spring AI 原生 Tool Calling 的参数处理在这里不生效，
 * 所以校验必须由本层自己做。改造前 {@code validateArguments} 恒返回 null，注释指向
 * {@code ToolCallValidator}，而那个类当时已经没有任何调用方 —— 校验层事实上是空的：
 * 参数缺失或解析失败会被 {@code convertType} 静默兜成 0/""，用户看到的是「暂无数据」而不是「参数不对」。
 */
class SpringAiToolRegistryTest {

    /** 只提供一台设备和固定能耗数据的桩 */
    static class StubTsdb implements TimeSeriesDbPort {
        int queryByDaysCalls = 0;
        int lastQueriedDays = -1;

        @Override
        public List<Device> listBuiltinDevices(String keyword) {
            return List.of(new Device(1L, "一号设备", "mv-003", "A栋", 1, "2025-01-15"));
        }

        @Override
        public Device findDeviceBySn(String sn) {
            return "mv-003".equalsIgnoreCase(sn) ? new Device(1L, "一号设备", "mv-003", "A栋", 1, "2025-01-15") : null;
        }

        @Override
        public List<EnergyRecord> queryEnergyByDays(String sn, int days) {
            queryByDaysCalls++;
            lastQueriedDays = days;
            List<EnergyRecord> records = new ArrayList<>();
            records.add(new EnergyRecord("2026-08-01T00:00:00Z", 12.5));
            return records;
        }

        @Override
        public List<EnergyRecord> queryEnergyByDateRange(String sn, LocalDate startDate, LocalDate endDate) {
            return List.of(new EnergyRecord("2026-08-01T00:00:00Z", 12.5));
        }

        @Override
        public Map<String, Double> aggregate(List<EnergyRecord> records, String granularity) {
            return Map.of("2026-08", 12.5);
        }
    }

    private final StubTsdb tsdb = new StubTsdb();
    private final SpringAiToolRegistry registry =
            new SpringAiToolRegistry(new DeviceTools(tsdb, new EnergyAggregator()), new ToolCallValidator());

    private static ToolCall call(String name, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            args.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return new ToolCall(name, args);
    }

    // ==================== 参数校验真的接上了 ====================

    @Test
    @DisplayName("days 非法时报参数错误，而不是静默查 0 天再回「暂无数据」")
    void rejectsInvalidDays() {
        String result = registry.invoke(call("query_energy_by_days", "sn", "mv-003", "days", "abc"));

        assertTrue(result.startsWith("❌ 参数有误"), "实际返回: " + result);
        assertEquals(0, tsdb.queryByDaysCalls, "参数非法却仍然打到了数据源");
    }

    @Test
    @DisplayName("缺少必填 sn 时提示补充，并引导先查设备清单")
    void rejectsMissingSn() {
        String result = registry.invoke(call("query_energy_half_year"));

        assertTrue(result.startsWith("❌ 参数有误"), "实际返回: " + result);
        assertTrue(result.contains("sn"));
    }

    @Test
    @DisplayName("日期格式不对时报错，不把脏值传给下游")
    void rejectsBadDateFormat() {
        String result = registry.invoke(
                call("query_energy_by_date_range", "sn", "mv-003", "startDate", "2026/06/01", "endDate", "2026-06-30"));

        assertTrue(result.startsWith("❌ 参数有误"), "实际返回: " + result);
        assertTrue(result.contains("startDate"));
    }

    // ==================== 不再猜参数槽位 ====================

    @Test
    @DisplayName("参数名对不上时交给校验层报错，不做模糊匹配硬塞")
    void doesNotGuessParameterSlots() {
        // 旧实现的双向 contains 匹配会让 "date" 命中 startDate（"startDate".contains("date")），
        // 匹配不上时还会按下标顺序硬塞 —— 猜错槽位却照样执行，产出的是「看起来正常」的错误数据
        String result = registry.invoke(
                call("query_energy_by_date_range", "sn", "mv-003", "date", "2026-06-01", "endDate", "2026-06-30"));

        assertTrue(result.startsWith("❌ 参数有误"), "实际返回: " + result);
        assertTrue(result.contains("startDate"), "应指出是 startDate 缺失，实际: " + result);
    }

    @Test
    @DisplayName("参数名大小写不一致仍能正确取值")
    void matchesParameterNameIgnoringCase() {
        String result = registry.invoke(call("query_energy_by_days", "SN", "mv-003", "DAYS", 7));

        assertFalse(result.startsWith("❌"), "实际返回: " + result);
        assertEquals(7, tsdb.lastQueriedDays);
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("参数合法时正常执行并返回工具结果")
    void invokesToolWithValidArguments() {
        String result = registry.invoke(call("query_energy_by_days", "sn", "mv-003", "days", 30));

        assertTrue(result.contains("设备能耗报告"), "实际返回: " + result);
        assertEquals(30, tsdb.lastQueriedDays);
    }

    @Test
    @DisplayName("无参工具（list_devices）可正常调用")
    void invokesNoArgTool() {
        String result = registry.invoke(call("list_devices", "keyword", ""));

        assertTrue(result.contains("设备清单"), "实际返回: " + result);
    }

    @Test
    @DisplayName("未知工具名与空调用都返回可读错误，不抛异常")
    void handlesUnknownAndNullCalls() {
        assertTrue(registry.invoke(call("no_such_tool")).contains("未知工具"));
        assertTrue(registry.invoke(null).contains("工具调用为空"));
        assertTrue(registry.invoke(new ToolCall(null, Map.of())).contains("工具调用为空"));
    }
}
