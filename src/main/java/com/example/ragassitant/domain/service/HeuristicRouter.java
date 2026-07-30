package com.example.ragassitant.domain.service;

import com.example.ragassitant.domain.model.ToolCall;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeuristicRouter {

    private static final Pattern SN_PATTERN = Pattern.compile("(mv-[A-Za-z0-9_-]+|wzh_[A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\s*[到至-]\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern N_DAYS_PATTERN = Pattern.compile("近?(\\d{1,3})\\s*天(?:的|能耗|数据|用电)?");
    private static final Pattern HALF_YEAR_PATTERN = Pattern.compile("近半年|半年|6个月|最近六个月");
    private static final Pattern AGG_WORD_PATTERN = Pattern.compile("(聚合|汇总|统计|合计).*?(月|周|季度|年|日|天)|(月|周|季度|年).*?(聚合|汇总|统计|合计)");
    private static final Pattern ENERGY_WORD_PATTERN = Pattern.compile("能耗|用电|电量|kwh|电度|数据|趋势|曲线");
    private static final Pattern DEVICE_LIST_WORD_PATTERN = Pattern.compile("列出?设备|设备清单|设备列表|有哪些设备|所有设备");
    private static final Pattern DEVICE_DETAIL_WORD_PATTERN = Pattern.compile("设备详情|详细信息|详情");
    private static final Pattern WEEK_PATTERN = Pattern.compile("按周|每周|周统计|周汇总");
    private static final Pattern MONTH_PATTERN = Pattern.compile("按月|每月|月度|月统计|月汇总");
    private static final Pattern QUARTER_PATTERN = Pattern.compile("按季|季度|每季度|季统计");
    private static final Pattern YEAR_PATTERN = Pattern.compile("按年|每年|年度|年统计|年汇总");
    private static final Pattern FOLLOWUP_PATTERN = Pattern.compile("(^|\\s|,|，|。|呢|那|这个|那个|这个呢|那个呢|怎么样|如何|看下|看看|查下|查询下)[\\s]*$");

    public ToolCall route(String question) {
        if (question == null || question.isBlank()) return null;
        String q = question.toLowerCase(Locale.ROOT);

        Matcher snMatcher = SN_PATTERN.matcher(question);
        String sn = snMatcher.find() ? snMatcher.group(1) : null;

        if (sn == null && DEVICE_LIST_WORD_PATTERN.matcher(q).find()) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("keyword", "");
            return new ToolCall("list_devices", args);
        }

        if (sn == null) return null;

        boolean energyWord = ENERGY_WORD_PATTERN.matcher(q).find();
        boolean aggWord = AGG_WORD_PATTERN.matcher(q).find();

        String granularity = null;
        if (aggWord) {
            if (WEEK_PATTERN.matcher(q).find()) granularity = "week";
            else if (QUARTER_PATTERN.matcher(q).find()) granularity = "quarter";
            else if (YEAR_PATTERN.matcher(q).find()) granularity = "year";
            else granularity = "month";
        }

        if (granularity != null) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("sn", sn);
            args.put("granularity", granularity);
            return new ToolCall("aggregate_energy", args);
        }

        Matcher dm = DATE_RANGE_PATTERN.matcher(question);
        if (dm.find() && energyWord) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("sn", sn);
            args.put("startDate", dm.group(1));
            args.put("endDate", dm.group(2));
            return new ToolCall("query_energy_by_date_range", args);
        }

        Matcher nd = N_DAYS_PATTERN.matcher(question);
        if (nd.find()) {
            try {
                int days = Integer.parseInt(nd.group(1));
                if (days > 0 && days <= 730) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("sn", sn);
                    args.put("days", days);
                    return new ToolCall("query_energy_by_days", args);
                }
            } catch (Exception ignored) {}
        }

        if (HALF_YEAR_PATTERN.matcher(q).find() || energyWord) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("sn", sn);
            return new ToolCall("query_energy_half_year", args);
        }

        if (DEVICE_DETAIL_WORD_PATTERN.matcher(q).find()) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("sn", sn);
            return new ToolCall("get_device_detail", args);
        }

        // ========== 兜底：有 SN 但上面都没命中 → 当作追答/省略式问句，默认查「近30天能耗」 ==========
        // 场景：用户上一条问了"mv-008近30天能耗"，这条说"mv-003呢"/"mv-003"/"mv-003怎么样"
        boolean looksLikeFollowup =
                FOLLOWUP_PATTERN.matcher(q).find()
                        || question.trim().equalsIgnoreCase(sn)
                        || question.trim().replaceAll("\\s+", "").equalsIgnoreCase(sn + "呢")
                        || question.trim().length() <= sn.length() + 6;
        if (looksLikeFollowup) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("sn", sn);
            args.put("days", 30);
            return new ToolCall("query_energy_by_days", args);
        }

        return null;
    }
}