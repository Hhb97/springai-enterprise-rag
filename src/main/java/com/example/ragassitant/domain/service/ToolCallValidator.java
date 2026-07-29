package com.example.ragassitant.domain.service;

import java.util.Map;
import java.util.regex.Pattern;

public class ToolCallValidator {

    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    public String validate(String toolName, Map<String, Object> args) {
        switch (toolName) {
            case "get_device_detail":
            case "query_energy_half_year": {
                Object sn = args.get("sn");
                if (sn == null || sn.toString().isBlank()) {
                    return "缺少必填参数 sn（设备SN号）。请在调用该工具时提供明确的设备SN号，或先调用 list_devices 查看设备清单。";
                }
                return null;
            }
            case "query_energy_by_days": {
                Object sn = args.get("sn");
                Object days = args.get("days");
                if (sn == null || sn.toString().isBlank()) {
                    return "缺少必填参数 sn。请提供设备SN号。";
                }
                int d = -1;
                try { d = Integer.parseInt(days != null ? days.toString().replaceAll("[^0-9-]", "") : "-1"); } catch (Exception ignored) {}
                if (d <= 0) {
                    return "参数 days 必须是大于 0 的正整数。";
                }
                return null;
            }
            case "query_energy_by_date_range": {
                Object sn = args.get("sn");
                Object sd = args.get("startDate");
                Object ed = args.get("endDate");
                if (sn == null || sn.toString().isBlank()) return "缺少必填参数 sn。";
                if (sd == null || !DATE_PATTERN.matcher(sd.toString().trim()).matches()) return "参数 startDate 格式必须为 yyyy-MM-dd。";
                if (ed == null || !DATE_PATTERN.matcher(ed.toString().trim()).matches()) return "参数 endDate 格式必须为 yyyy-MM-dd。";
                return null;
            }
            default:
                return null;
        }
    }
}