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
            new Device(8L, "八号设备-数据中心", "mv-0032", "F栋IDC机房", 1, "2025-08-12")
    );

    @Nullable
    private final InfluxDB influxDB;
    private final EnergyAggregator aggregator = new EnergyAggregator();

    @Value("${influxdb.database:acucloud}")
    private String database;

    public InfluxDB18TimeSeriesAdapter(@Nullable InfluxDB influxDB) {
        this.influxDB = influxDB;
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
        String startTime = startDate.atStartOfDay().atZone(ZoneId.of("America/Toronto")).toInstant().toString();
        String endTime = endDate.plusDays(1).atStartOfDay().atZone(ZoneId.of("America/Toronto")).toInstant().toString();
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
            log.warn("⚠️ InfluxDB 未连接，返回模拟数据");
            return generateMockData(normalizedSn, startTime, endTime);
        }

        String queryStr = String.format(
                "SELECT (sum(\"-_diff_acc_param_-EP_IMP_kWh\")) " +
                "FROM \"device_data\" " +
                "WHERE device::tag='%s' " +
                "AND '%s' <= time AND time < '%s' " +
                "GROUP BY time(1d) " +
                "tz('America/Toronto')",
                normalizedSn, startTime, endTime
        );
        log.info("📊 执行查询: {}", queryStr);

        try {
            Query query = new Query(queryStr, database);
            QueryResult result = influxDB.query(query);
            return parseResult(result);
        } catch (Exception e) {
            log.error("❌ InfluxDB 查询失败: {}", e.getMessage());
            return Collections.emptyList();
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

    private List<EnergyRecord> generateMockData(String sn, String startTime, String endTime) {
        List<EnergyRecord> dataPoints = new ArrayList<>();
        try {
            Instant start = Instant.parse(startTime);
            Instant end = Instant.parse(endTime);
            long days = ChronoUnit.DAYS.between(start, end);
            double baseValue = 50.0 + (sn.hashCode() % 30);
            for (long i = days; i >= 0; i--) {
                Instant time = Instant.now().minus(i, ChronoUnit.DAYS);
                double randomFactor = 0.8 + (Math.random() * 0.4);
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