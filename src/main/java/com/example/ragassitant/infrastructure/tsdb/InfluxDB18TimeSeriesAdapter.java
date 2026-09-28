package com.example.ragassitant.infrastructure.tsdb;

import com.example.ragassitant.common.utils.SnUtils;
import com.example.ragassitant.domain.model.Device;
import com.example.ragassitant.domain.model.EnergyRecord;
import com.example.ragassitant.domain.service.EnergyAggregator;
import com.example.ragassitant.domain.spi.TimeSeriesDbPort;
import jakarta.annotation.Nullable;
import org.influxdb.InfluxDB;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class InfluxDB18TimeSeriesAdapter implements TimeSeriesDbPort {

    private static final Logger log = LoggerFactory.getLogger(InfluxDB18TimeSeriesAdapter.class);

    private static final List<Device> BUILTIN_DEVICES = Arrays.asList(
            new Device(1L, "一号设备-总电", "mv-003", "A栋1层配电房", 1, "2025-01-15"),
            new Device(2L, "二号设备-楼层配电", "mv-0032", "A栋3层机房", 1, "2025-02-20"),
            new Device(3L, "三号设备-空调系统", "mv-005", "B栋2层空调机房", 0, "2025-03-10"),
            new Device(4L, "四号设备-照明系统", "mv-006", "C栋公共区域", 1, "2025-04-05"),
            new Device(5L, "五号设备-动力系统", "mv-007", "D栋动力车间", 1, "2025-05-18"),
            new Device(6L, "六号设备-冷水机组", "mv-008", "A栋地下室冷站", 1, "2025-06-22"),
            new Device(7L, "七号设备-办公区", "mv-009", "E栋办公区总配", 1, "2025-07-30"),
            // 【修正】原为 mv-0032，与二号设备重复：findDeviceBySn("mv-0032") 永远返回二号，
            // 八号设备查不到、且问八号时拿到的是二号的数据。⚠️ mv-0033 为占位值，须与真实设备台账核对
            new Device(8L, "八号设备-数据中心", "mv-0033", "F栋IDC机房", 1, "2025-08-12")
    );

    static {
        // 启动即校验 SN 唯一：重复 SN 会导致静默查错设备，这类错误用户完全无法察觉
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Device d : BUILTIN_DEVICES) {
            if (!seen.add(d.getSn().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("内置设备表存在重复 SN: " + d.getSn());
            }
        }
    }

    @Nullable
    private final InfluxDB influxDB;

    /**
     * 注入 Spring 管理的那一个实例。
     *
     * <p>改造前这里是 {@code new EnergyAggregator()}，而 {@code DomainServiceConfig} 里还注册了一个同类 bean
     * （{@code DeviceTools} 用的是那个）—— 同一份聚合逻辑存在两个实例。目前它无状态所以看不出问题，
     * 一旦聚合规则要配置化，就会出现「改了 bean 但适配器用的是另一个」这种极难定位的偏差。
     */
    private final EnergyAggregator aggregator;

    /** 用构造注入而非字段 {@code @Value}：与本类其余依赖保持一致，也让单测能直接 new 出来。 */
    private final String database;

    /**
     * 数据源不可用时是否返回演示数据。默认 false：生产环境连不上 InfluxDB 必须明确报错，
     * 绝不能静默返回模拟数字。本地演示时用 TSDB_MOCK_WHEN_UNAVAILABLE=true 打开，且输出会标注「演示数据」。
     */
    private final boolean mockWhenUnavailable;

    /** 按天分桶与日期边界使用的时区（原先硬编码 America/Toronto）。 */
    private final ZoneId zone;

    public InfluxDB18TimeSeriesAdapter(@Nullable InfluxDB influxDB,
                                       EnergyAggregator aggregator,
                                       @Value("${influxdb.database:acucloud}") String database,
                                       @Value("${tsdb.mock-when-unavailable:false}") boolean mockWhenUnavailable,
                                       @Value("${tsdb.timezone:America/Toronto}") String timezone) {
        this.influxDB = influxDB;
        this.aggregator = aggregator;
        this.database = database;
        this.mockWhenUnavailable = mockWhenUnavailable;
        this.zone = ZoneId.of(timezone);
        if (influxDB == null) {
            if (mockWhenUnavailable) {
                log.warn("⚠️ InfluxDB 未连接：已开启演示模式，能耗查询将返回【标注为演示数据】的模拟值");
            } else {
                log.error("❌ InfluxDB 未连接：能耗类工具将返回「数据源不可用」。本地演示可设 TSDB_MOCK_WHEN_UNAVAILABLE=true");
            }
        }
    }

    @Override
    public boolean isDemoData() {
        return influxDB == null && mockWhenUnavailable;
    }

    @Override
    public List<Device> listBuiltinDevices(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return new ArrayList<>(BUILTIN_DEVICES);
        }
        String kw = keyword.trim().toLowerCase(Locale.ROOT);
        return BUILTIN_DEVICES.stream()
                .filter(d -> d.getName().toLowerCase(Locale.ROOT).contains(kw)
                        || d.getSn().toLowerCase(Locale.ROOT).contains(kw)
                        || d.getLocation().toLowerCase(Locale.ROOT).contains(kw))
                .collect(Collectors.toList());
    }

    @Override
    public Device findDeviceBySn(String sn) {
        if (sn == null) return null;
        String normalized = SnUtils.normalize(sn);
        Device exact = BUILTIN_DEVICES.stream()
                .filter(d -> d.getSn().equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(null);
        if (exact != null) return exact;
        final String rawSn = sn.trim();
        return BUILTIN_DEVICES.stream()
                .filter(d -> d.getSn().equalsIgnoreCase(rawSn)
                        || d.getName().toLowerCase(Locale.ROOT).contains(rawSn.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<EnergyRecord> queryEnergyByDays(String sn, int days) {
        String endTime = Instant.now().toString();
        String startTime = Instant.now().minus(days, ChronoUnit.DAYS).toString();
        return queryByTimeRange(sn, startTime, endTime);
    }

    @Override
    public List<EnergyRecord> queryEnergyByDateRange(String sn, LocalDate startDate, LocalDate endDate) {
        String startTime = startDate.atStartOfDay().atZone(zone).toInstant().toString();
        String endTime = endDate.plusDays(1).atStartOfDay().atZone(zone).toInstant().toString();
        return queryByTimeRange(sn, startTime, endTime);
    }

    @Override
    public Map<String, Double> aggregate(List<EnergyRecord> records, String granularity) {
        return aggregator.aggregate(records.stream()
                .map(r -> new com.example.ragassitant.domain.model.EnergyRecord(r.getTime(), r.getValue()))
                .collect(Collectors.toList()), granularity);
    }

    private List<EnergyRecord> queryByTimeRange(String sn, String startTime, String endTime) {
        String normalizedSn = SnUtils.normalize(sn);
        log.info("📊 查询能耗数据: sn={}, 时间范围: {} ~ {}", normalizedSn, startTime, endTime);

        if (influxDB == null) {
            if (!mockWhenUnavailable) {
                throw new TimeSeriesUnavailableException("InfluxDB 未连接");
            }
            return generateMockData(normalizedSn, startTime, endTime);
        }

        String queryStr = String.format(
                "SELECT (sum(\"-_diff_acc_param_-EP_IMP_kWh\")) " +
                "FROM \"device_data\" " +
                "WHERE device::tag='%s' " +
                "AND '%s' <= time AND time < '%s' " +
                "GROUP BY time(1d) " +
                "tz('%s')",
                normalizedSn, startTime, endTime, zone.getId()
        );
        log.info("📊 执行查询: {}", queryStr);

        try {
            Query query = new Query(queryStr, database);
            QueryResult result = influxDB.query(query);
            return parseResult(result);
        } catch (Exception e) {
            // 原先返回空列表：查询失败被伪装成「该时段无数据」，用户和模型都会得出错误结论
            log.error("❌ InfluxDB 查询失败: {}", e.getMessage());
            throw new TimeSeriesUnavailableException("InfluxDB 查询失败: " + e.getMessage(), e);
        }
    }

    private List<EnergyRecord> parseResult(QueryResult result) {
        List<EnergyRecord> dataPoints = new ArrayList<>();
        if (result.getResults() == null) return dataPoints;
        for (QueryResult.Result r : result.getResults()) {
            if (r.getSeries() == null) continue;
            for (QueryResult.Series series : r.getSeries()) {
                List<List<Object>> values = series.getValues();
                if (values == null) continue;
                for (List<Object> value : values) {
                    if (value.size() < 2 || value.get(1) == null) continue;
                    EnergyRecord point = new EnergyRecord();
                    point.setTime(String.valueOf(value.get(0)));
                    try {
                        point.setValue(((Number) value.get(1)).doubleValue());
                        dataPoints.add(point);
                    } catch (Exception e) {
                        log.warn("⚠️ 解析数据点失败: {}", value);
                    }
                }
            }
        }
        return dataPoints;
    }

    /**
     * 演示数据：<b>确定性</b>（按 SN + 日期播种），同一个问题每次答案一致。
     *
     * <p>改造前两个问题：{@code Math.random()} 导致同一问题每次数字不同；
     * 且日期从 {@code now} 往回倒推，完全无视请求的时间范围 —— 按日期范围查询时返回的是错误日期。
     */
    private List<EnergyRecord> generateMockData(String sn, String startTime, String endTime) {
        List<EnergyRecord> dataPoints = new ArrayList<>();
        try {
            Instant start = Instant.parse(startTime);
            Instant end = Instant.parse(endTime);
            long days = ChronoUnit.DAYS.between(start, end);
            double baseValue = 50.0 + Math.floorMod(sn.hashCode(), 30);
            for (long i = 0; i < days; i++) {
                Instant time = start.plus(i, ChronoUnit.DAYS);
                long seed = (sn + "|" + time.atZone(zone).toLocalDate()).hashCode();
                double randomFactor = 0.8 + (new java.util.Random(seed).nextDouble() * 0.4);
                double value = baseValue * randomFactor;
                EnergyRecord point = new EnergyRecord();
                point.setTime(time.toString());
                point.setValue(Math.round(value * 100.0) / 100.0);
                dataPoints.add(point);
            }
        } catch (Exception e) {
            log.warn("生成模拟数据失败: {}", e.getMessage());
        }
        return dataPoints;
    }
}