package com.example.ragassitant.infrastructure.agent;

import com.example.ragassitant.domain.model.Device;
import com.example.ragassitant.domain.model.EnergyRecord;
import com.example.ragassitant.domain.service.EnergyAggregator;
import com.example.ragassitant.domain.spi.TimeSeriesDbPort;
import com.example.ragassitant.infrastructure.tsdb.InfluxDB18TimeSeriesAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 设备/能耗工具的数据正确性回归。这几条都是「界面上看起来完全正常、但数字是错的」这一类问题。
 */
class DeviceToolsTest {

    private static InfluxDB18TimeSeriesAdapter adapter(boolean mock) {
        return new InfluxDB18TimeSeriesAdapter(null, new EnergyAggregator(), "db", mock, "America/Toronto");
    }

    // ==================== 设备表 ====================

    @Test
    @DisplayName("8 台设备 SN 唯一，八号设备可以被查到")
    void deviceSnsAreUnique() {
        InfluxDB18TimeSeriesAdapter tsdb = adapter(true);
        List<Device> all = tsdb.listBuiltinDevices("");
        assertEquals(all.size(), all.stream().map(Device::getSn).distinct().count(),
                "存在重复 SN：按 SN 查设备会静默返回另一台");

        Device eighth = all.stream().filter(d -> d.getId() == 8L).findFirst().orElseThrow();
        assertEquals(8L, tsdb.findDeviceBySn(eighth.getSn()).getId(), "八号设备按自己的 SN 查到了别的设备");
    }

    // ==================== 数据源不可用 ====================

    @Test
    @DisplayName("数据源不可用且未开演示模式：明确报错，而不是返回假数据或「暂无数据」")
    void unavailableSourceIsReportedNotFaked() {
        DeviceTools tools = new DeviceTools(adapter(false), new EnergyAggregator());

        String result = tools.queryEnergyByDays("mv-003", 30);

        assertTrue(result.contains("数据源") && result.contains("不可用"), "实际: " + result);
        assertFalse(result.matches("(?s).*\\d+\\.\\d{2}\\s*kWh.*"), "数据源不可用时仍输出了能耗数字");
    }

    @Test
    @DisplayName("演示模式：每份报告首行显式标注「演示数据」")
    void demoDataIsLabeled() {
        DeviceTools tools = new DeviceTools(adapter(true), new EnergyAggregator());

        String result = tools.queryEnergyByDays("mv-003", 7);

        assertTrue(result.startsWith("> ⚠️ **演示数据**"), "模拟值没有标注，会被当成真实能耗: " + result);
    }

    @Test
    @DisplayName("演示数据是确定性的：同一问题两次答案一致（原先用 Math.random）")
    void demoDataIsDeterministic() {
        InfluxDB18TimeSeriesAdapter tsdb = adapter(true);
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 7);

        List<EnergyRecord> a = tsdb.queryEnergyByDateRange("mv-003", from, to);
        List<EnergyRecord> b = tsdb.queryEnergyByDateRange("mv-003", from, to);

        assertEquals(a.stream().map(EnergyRecord::getValue).toList(),
                b.stream().map(EnergyRecord::getValue).toList());
    }

    @Test
    @DisplayName("演示数据遵守请求的日期范围（原先从 now 倒推，范围查询返回错误日期）")
    void demoDataRespectsDateRange() {
        InfluxDB18TimeSeriesAdapter tsdb = adapter(true);

        List<EnergyRecord> records = tsdb.queryEnergyByDateRange(
                "mv-003", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7));

        assertEquals(7, records.size());
        assertTrue(records.get(0).getTime().startsWith("2026-06-0"), "首条日期不在请求范围内: " + records.get(0).getTime());
    }

    // ==================== aggregate_energy ====================

    @Test
    @DisplayName("aggregate_energy 遵守 days 参数（原先固定查 180 天）")
    void aggregateRespectsDays() {
        RecordingTsdb tsdb = new RecordingTsdb();
        DeviceTools tools = new DeviceTools(tsdb, new EnergyAggregator());

        tools.aggregateEnergy("mv-003", "week", 30);
        assertEquals(30, tsdb.lastDays, "问近 30 天，却按其它天数聚合");

        tools.aggregateEnergy("mv-003", "month", null);
        assertEquals(180, tsdb.lastDays, "未指定 days 时默认应为近半年");
    }

    @Test
    @DisplayName("aggregate_energy 报表写明统计范围，避免口径误读")
    void aggregateReportShowsRange() {
        DeviceTools tools = new DeviceTools(new RecordingTsdb(), new EnergyAggregator());

        assertTrue(tools.aggregateEnergy("mv-003", "week", 30).contains("最近 30 天"));
    }

    @Test
    @DisplayName("get_weather 是随机占位数据，必须显式标注")
    void weatherIsLabeledAsDemo() {
        DeviceTools tools = new DeviceTools(new RecordingTsdb(), new EnergyAggregator());

        assertTrue(tools.getWeather("北京").contains("演示数据"));
    }

    /** 记录查询天数的桩 */
    static class RecordingTsdb implements TimeSeriesDbPort {
        int lastDays = -1;

        @Override
        public List<Device> listBuiltinDevices(String keyword) {
            return List.of(device());
        }

        @Override
        public Device findDeviceBySn(String sn) {
            return device();
        }

        @Override
        public List<EnergyRecord> queryEnergyByDays(String sn, int days) {
            lastDays = days;
            return List.of(new EnergyRecord("2026-06-01T00:00:00Z", 10.0), new EnergyRecord("2026-06-09T00:00:00Z", 20.0));
        }

        @Override
        public List<EnergyRecord> queryEnergyByDateRange(String sn, LocalDate s, LocalDate e) {
            return List.of();
        }

        @Override
        public java.util.Map<String, Double> aggregate(List<EnergyRecord> records, String granularity) {
            return new EnergyAggregator().aggregate(records, granularity);
        }

        private static Device device() {
            return new Device(1L, "一号设备", "mv-003", "A栋", 1, "2025-01-15");
        }
    }
}
