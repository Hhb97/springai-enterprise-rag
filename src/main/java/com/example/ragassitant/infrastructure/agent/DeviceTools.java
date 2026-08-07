package com.example.ragassitant.infrastructure.agent;

import com.example.ragassitant.domain.model.Device;
import com.example.ragassitant.domain.model.EnergyRecord;
import com.example.ragassitant.domain.service.EnergyAggregator;
import com.example.ragassitant.domain.spi.TimeSeriesDbPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class DeviceTools {

    private static final Logger log = LoggerFactory.getLogger(DeviceTools.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TimeSeriesDbPort timeSeriesDbPort;
    private final EnergyAggregator energyAggregator;

    private final Random random = new Random();

    public DeviceTools(TimeSeriesDbPort timeSeriesDbPort, EnergyAggregator energyAggregator) {
        this.timeSeriesDbPort = timeSeriesDbPort;
        this.energyAggregator = energyAggregator;
    }

    @Tool(name = "list_devices", description =
            "查询设备列表，返回所有设备的id、name、sn号、安装位置、在线状态。" +
            "当用户询问设备列表、有哪些设备、设备清单、设备列表有哪些、所有设备信息时使用此工具。" +
            "可以传入可选的搜索关键词按设备名称或SN模糊查找。")
    public String listDevices(
            @ToolParam(description = "可选的搜索关键词，按设备名称或SN号模糊搜索，不填则返回全部设备")
            String keyword) {

        log.info("🔧 调用工具 list_devices, keyword={}", keyword);
        try {
            List<Device> devices = timeSeriesDbPort.listBuiltinDevices(keyword);
            if (devices.isEmpty()) return "未找到匹配的设备。";

            StringBuilder sb = new StringBuilder();
            sb.append("## 设备清单（共 ").append(devices.size()).append(" 台）\n\n");
            sb.append("| ID | 设备名称 | SN号 | 安装位置 | 状态 |\n");
            sb.append("|:---:|:---|:---|:---|:---:|\n");
            for (Device d : devices) {
                String status = d.getStatus() != null && d.getStatus() == 1 ? "✅ 在线" : "⚠️ 离线";
                sb.append("| ").append(d.getId())
                  .append(" | ").append(d.getName())
                  .append(" | `").append(d.getSn()).append("`")
                  .append(" | ").append(d.getLocation())
                  .append(" | ").append(status)
                  .append(" |\n");
            }
            sb.append("\n**提示**: 如需查询某台设备的详细信息或能耗数据，请提供具体的 SN 号。");
            return sb.toString();
        } catch (Exception e) {
            log.error("list_devices 执行失败", e);
            return "❌ 查询设备列表失败: " + e.getMessage();
        }
    }

    @Tool(name = "get_device_detail", description =
            "根据设备 SN 号查询单个设备的详细信息，包括 ID、设备名称、SN、安装位置、在线状态、安装时间。" +
            "当用户给出具体 SN 号并询问设备信息、设备详情、设备在哪里、设备状态时使用此工具。")
    public String getDeviceDetail(
            @ToolParam(description = "设备 SN 号，例如: wzh_device009", required = true)
            String sn) {

        log.info("🔧 调用工具 get_device_detail, sn={}", sn);
        Device device = timeSeriesDbPort.findDeviceBySn(sn);
        if (device == null) {
            return "❌ 未找到 SN 号为 **" + sn + "** 的设备。\n\n" +
                   "请确认 SN 号是否正确，可以先调用 `list_devices` 查看所有设备清单。";
        }
        String status = device.getStatus() != null && device.getStatus() == 1 ? "✅ 在线运行中" : "⚠️ 当前离线";
        StringBuilder sb = new StringBuilder();
        sb.append("## 设备详细信息\n\n");
        sb.append("| 字段 | 值 |\n");
        sb.append("|:---|:---|\n");
        sb.append("| **设备 ID** | ").append(device.getId()).append(" |\n");
        sb.append("| **设备名称** | ").append(device.getName()).append(" |\n");
        sb.append("| **SN 号** | `").append(device.getSn()).append("` |\n");
        sb.append("| **安装位置** | ").append(device.getLocation()).append(" |\n");
        sb.append("| **当前状态** | ").append(status).append(" |\n");
        sb.append("| **安装时间** | ").append(device.getInstallTime()).append(" |\n");
        return sb.toString();
    }

    @Tool(name = "query_energy_half_year", description =
            "查询指定设备近半年（约 180 天）的每日能耗统计数据，返回每日的用电量 kWh。" +
            "结果包含：统计摘要（累计能耗、日均能耗、峰值）以及每日数据表格。" +
            "当用户询问近半年、半年、6个月能耗时使用此工具。")
    public String queryEnergyHalfYear(
            @ToolParam(description = "设备 SN 号，例如: wzh_device009", required = true)
            String sn) {

        log.info("🔧 调用工具 query_energy_half_year, sn={}", sn);
        return doQueryEnergy(sn, 180, "近半年（180天）");
    }

    @Tool(name = "query_energy_by_days", description =
            "查询指定设备最近 N 天的每日能耗统计数据（kWh）。" +
            "例如：近7天=一周、近30天=一个月、近90天=三个月、近180天=半年。" +
            "结果包含统计摘要（累计、日均、峰值）和每日数据表格。")
    public String queryEnergyByDays(
            @ToolParam(description = "设备 SN 号，例如: wzh_device009", required = true)
            String sn,
            @ToolParam(description = "查询天数，例如: 7=近一周, 30=近一月, 90=近三月, 180=近半年", required = true)
            int days) {

        log.info("🔧 调用工具 query_energy_by_days, sn={}, days={}", sn, days);
        return doQueryEnergy(sn, days, "最近 " + days + " 天");
    }

    @Tool(name = "query_energy_by_date_range", description =
            "按指定日期范围查询设备的能耗数据。" +
            "当用户明确给出开始和结束日期时使用，例如 查询 2026年6月1日到6月30日 的能耗。" +
            "日期格式请使用 yyyy-MM-dd，如 2026-06-01")
    public String queryEnergyByDateRange(
            @ToolParam(description = "设备 SN 号，例如: wzh_device009", required = true)
            String sn,
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd，例如 2026-06-01", required = true)
            String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd，例如 2026-06-30", required = true)
            String endDate) {

        log.info("🔧 调用工具 query_energy_by_date_range, sn={}, {} ~ {}", sn, startDate, endDate);
        try {
            Device device = timeSeriesDbPort.findDeviceBySn(sn);
            if (device == null) return "❌ 未找到 SN 号为 **" + sn + "** 的设备。";
            LocalDate start = LocalDate.parse(startDate, DATE_FMT);
            LocalDate end = LocalDate.parse(endDate, DATE_FMT);
            List<EnergyRecord> dataPoints = timeSeriesDbPort.queryEnergyByDateRange(sn, start, end);
            String rangeDesc = start.format(DATE_FMT) + " ~ " + end.format(DATE_FMT);
            return formatEnergyResult(device, dataPoints, rangeDesc);
        } catch (Exception e) {
            log.error("query_energy_by_date_range 执行失败", e);
            return "❌ 查询能耗失败: " + e.getMessage();
        }
    }

    private String doQueryEnergy(String sn, int days, String timeLabel) {
        try {
            Device device = timeSeriesDbPort.findDeviceBySn(sn);
            if (device == null) {
                return "❌ 未找到 SN 号为 **" + sn + "** 的设备。\n\n" +
                       "请先调用 `list_devices` 查看设备清单，确认 SN 号是否正确。";
            }
            List<EnergyRecord> dataPoints = timeSeriesDbPort.queryEnergyByDays(sn, days);
            return formatEnergyResult(device, dataPoints, timeLabel);
        } catch (Exception e) {
            log.error("能耗查询执行失败", e);
            return "❌ 查询能耗失败: " + e.getMessage();
        }
    }

    private String formatEnergyResult(Device device, List<EnergyRecord> dataPoints, String timeLabel) {
        if (dataPoints == null || dataPoints.isEmpty()) {
            return "## 设备 " + device.getName() + "（" + device.getSn() + "）" + timeLabel + " 能耗\n\n" +
                   "⚠️ 该时间段内暂无能耗数据。可能原因：\n" +
                   "- 设备在该时间段未上线运行\n" +
                   "- InfluxDB 中暂无该设备采集数据\n" +
                   "- 时间范围设置不正确";
        }
        double total = dataPoints.stream().mapToDouble(p -> p.getValue() != null ? p.getValue() : 0.0).sum();
        double avg = dataPoints.stream().mapToDouble(p -> p.getValue() != null ? p.getValue() : 0.0).average().orElse(0.0);
        double max = dataPoints.stream().mapToDouble(p -> p.getValue() != null ? p.getValue() : 0.0).max().orElse(0.0);
        double min = dataPoints.stream().mapToDouble(p -> p.getValue() != null ? p.getValue() : 0.0).min().orElse(0.0);

        Map<String, Double> monthly = energyAggregator.aggregate(dataPoints, "month");

        StringBuilder sb = new StringBuilder();
        sb.append("## 设备能耗报告\n\n");
        sb.append("- **设备名称**: ").append(device.getName()).append("\n");
        sb.append("- **SN 号**: `").append(device.getSn()).append("`\n");
        sb.append("- **安装位置**: ").append(device.getLocation()).append("\n");
        sb.append("- **统计周期**: ").append(timeLabel).append("\n");
        sb.append("- **有效数据**: ").append(dataPoints.size()).append(" 天\n\n");

        sb.append("### 📊 统计摘要\n\n");
        sb.append("| 指标 | 数值 |\n");
        sb.append("|:---|---:|\n");
        sb.append("| **累计用电量** | **").append(String.format(Locale.ROOT, "%.2f", total)).append(" kWh** |\n");
        sb.append("| **日均用电** | ").append(String.format(Locale.ROOT, "%.2f", avg)).append(" kWh/天 |\n");
        sb.append("| **单日峰值** | ").append(String.format(Locale.ROOT, "%.2f", max)).append(" kWh |\n");
        sb.append("| **单日最低** | ").append(String.format(Locale.ROOT, "%.2f", min)).append(" kWh |\n\n");

        if (monthly.size() > 1) {
            sb.append("### 📈 月度用电汇总\n\n");
            sb.append("| 月份 | 用电量 (kWh) | 月占比 |\n");
            sb.append("|:---|---:|---:|\n");
            for (Map.Entry<String, Double> e : monthly.entrySet()) {
                double pct = total > 0 ? (e.getValue() / total) * 100.0 : 0;
                sb.append("| ").append(e.getKey())
                  .append(" | ").append(String.format(Locale.ROOT, "%.2f", e.getValue()))
                  .append(" | ").append(String.format(Locale.ROOT, "%.1f%%", pct))
                  .append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("### 📅 每日用电明细\n\n");
        sb.append("| 日期 | 用电量 (kWh) |\n");
        sb.append("|:---|---:|\n");

        List<EnergyRecord> reversed = new ArrayList<>(dataPoints);
        Collections.reverse(reversed);
        int showLimit = Math.min(reversed.size(), 30);
        for (int i = 0; i < showLimit; i++) {
            EnergyRecord p = reversed.get(i);
            String date = p.getTime() != null && p.getTime().length() >= 10 ? p.getTime().substring(0, 10) : p.getTime();
            String val = p.getValue() != null ? String.format(Locale.ROOT, "%.2f", p.getValue()) : "N/A";
            sb.append("| ").append(date).append(" | ").append(val).append(" |\n");
        }

        if (reversed.size() > 30) {
            sb.append("\n_仅展示最近 30 天数据，共 ").append(reversed.size()).append(" 条记录_");
        }
        return sb.toString();
    }

    @Tool(name = "aggregate_energy", description = "对已查询到的能耗数据，按指定粒度再次聚合（比如按月、按周、按季度汇总）。" +
            "注意：如果用户还没查过某设备的能耗数据，必须先调 query_energy_half_year 或 query_energy_by_days 拿每日明细，再用本工具聚合；" +
            "不能直接调用本工具，否则没有数据源。参数 granularity 只能是：day/week/month/quarter/year，month 是默认值。")
    public String aggregateEnergy(
            @ToolParam(description = "设备 SN 号，例如 wzh_device001、mv-003", required = true) String sn,
            @ToolParam(description = "聚合粒度：day=按天，week=按周，month=按月，quarter=按季度，year=按年，默认 month", required = false) String granularity) {
        log.info("🔧 调用工具 aggregate_energy, sn={}, granularity={}", sn, granularity);
        String g = granularity == null || granularity.isBlank() ? "month" : granularity.trim().toLowerCase(Locale.ROOT);

        Device device = timeSeriesDbPort.findDeviceBySn(sn);
        if (device == null) {
            return "❌ 未找到 SN 为 [" + sn + "] 的设备，请先调用 list_devices 确认 SN 是否正确（或按名称搜索）。";
        }

        List<EnergyRecord> dataPoints = timeSeriesDbPort.queryEnergyByDays(device.getSn(), 180);
        if (dataPoints == null || dataPoints.isEmpty()) {
            return "⚠️ 设备 " + device.getName() + "（" + device.getSn() + "）暂无数据，无法按 " + g + " 聚合。";
        }

        Map<String, Double> agg = timeSeriesDbPort.aggregate(dataPoints, g);
        if (agg.isEmpty()) return "⚠️ 聚合失败。";

        double total = agg.values().stream().mapToDouble(Double::doubleValue).sum();
        StringBuilder sb = new StringBuilder();
        sb.append("## 设备能耗").append(gLabel(g)).append("聚合报表\n\n");
        sb.append("- **设备名称**: ").append(device.getName()).append("\n");
        sb.append("- **SN 号**: `").append(device.getSn()).append("`\n");
        sb.append("- **聚合粒度**: **").append(gLabel(g)).append("**\n");
        sb.append("- **聚合分组**: ").append(agg.size()).append(" 组\n");
        sb.append("- **累计用电量**: **").append(String.format(Locale.ROOT, "%.2f", total)).append(" kWh**\n\n");

        sb.append("| ").append(gLabel(g)).append(" | 用电量 (kWh) | 占比 |\n");
        sb.append("|:---|---:|---:|\n");
        for (Map.Entry<String, Double> e : agg.entrySet()) {
            double pct = total > 0 ? (e.getValue() / total) * 100.0 : 0;
            sb.append("| ").append(e.getKey())
              .append(" | ").append(String.format(Locale.ROOT, "%.2f", e.getValue()))
              .append(" | ").append(String.format(Locale.ROOT, "%.1f%%", pct))
              .append(" |\n");
        }
        return sb.toString();
    }


    @Tool(name = "getCurrentTime", description = "获取当前系统时间，支持指定时区格式")
    public ZonedDateTime getCurrentTime(
            @ToolParam(description = "时区，如 Asia/Shanghai、UTC，默认为系统时区", required = false) String timezone
    ) {
        if (timezone != null && !timezone.isEmpty()) {
            return ZonedDateTime.now(ZoneId.of(timezone));
        }
        return ZonedDateTime.now();
    }

    @Tool(name = "get_weather",
            description = "获取指定城市的当前天气信息，包括温度、天气状况、湿度、风速等")
    public String getWeather(
            @ToolParam(description = "城市名称，例如：'北京', '上海'", required = true) String city) {

        // 生成随机天气数据
        String[] weatherConditions = {"晴朗", "多云", "阴天", "小雨", "中雨", "阵雨", "雷阵雨", "小雪", "中雪", "雾", "霾"};
        String condition = weatherConditions[random.nextInt(weatherConditions.length)];

        int temperature = random.nextInt(35) - 5;  // -5 ~ 30°C
        int humidity = random.nextInt(70) + 20;    // 20% ~ 90%
        int windSpeed = random.nextInt(40) + 5;    // 5 ~ 45 km/h

        // 当前时间
        ZonedDateTime now = ZonedDateTime.now();
        String timeStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH"));

        return String.format(
                "【%s】%s 天气：%s，温度：%d°C，湿度：%d%%，风速：%d km/h",
                timeStr, city, condition, temperature, humidity, windSpeed
        );
    }

    private static String gLabel(String g) {
        switch (g) {
            case "year": return "按年";
            case "quarter": return "按季度";
            case "week": return "按周";
            case "day": return "按天";
            case "month":
            default: return "按月";
        }
    }
}