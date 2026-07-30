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

    // ========== 工具话题关键词：出现任何一个，才算「在聊运维/设备/能耗」，才有资格进补全 ==========
    // （上一条历史命中了工具，当前问题如果完全不沾这些词，说明用户已经换话题了，不能补全）
    private static final Pattern TOOL_TOPIC_WORD_PATTERN = Pattern.compile(
            "设备|能耗|用电|电量|kwh|电度|数据|趋势|曲线|详情|详细信息|清单|列表|聚合|汇总|统计|合计|月度|月度|季度|年度|周|天|日|近半年|半年|6个月|sn|编号|号|机组|" +
            "mv-|wzh_|呢|怎么样|如何|看下|看看|查下|查询下|这个|那个|它|那台|这台|上面那个|之前那个|刚才那个|同样|一样|换成|换成的|换个|另外|" +
            // 工具名本身的同义词（历史刚被调用过工具名，用户说「XX呢」才算追同话题）
            "能耗|设备|详情|清单");

    // ========== 反模式：命中这些=明确换话题/闲聊/通用问答，直接拒绝补全 ==========
    //  优先级比 TOOL_TOPIC 更高
    private static final Pattern TOPIC_SWITCH_CHAT_PATTERN = Pattern.compile(
            // 纯闲聊问候
            "^\\s*(你好|您好|hi|hello|嗨|在吗|在不在|你是谁|介绍下|自我介绍|你是什么|你叫什么|你是什么大?模型|你用什么模型|基于什么模型|你能做什么|你会什么|有什么功能|功能介绍|你能干嘛|谢谢|感谢|好的|ok|收到|明白|了解|行|可以|不错|真棒|厉害|哈哈|嘿嘿)\\s*[!！?？。.]*\\s*$|" +
            // 关于 AI 本身的身份/能力问题（不是运维话题）
            "(你是什么大?模型|用什么模型训练|基于什么模型|模型是什么|你是gpt|你是qwen|你是ollama|你是通义|你是什么公司|你是谁开发的|你多大|你是男是女|你住哪|你的名字|你有感情吗|你有意识吗)|" +
            // 明确切换话题的指示词
            "(换个话题|聊点别的|不说这个|不聊这个|问你个别的|另外|还有|顺便问下|对了还有|我想问下[^设备^能耗^数据]|想知道[^设备^能耗^数据]|解释下什么是|什么叫|怎么理解|定义是什么)");

    /**
     * 判断当前问题是否看起来「还是在聊工具相关话题」
     * 规则：先过 反模式（命中=换话题=false），再过 工具词匹配（命中=true）
     */
    public boolean isLikelySameToolTopic(String currentQuestion, String lastUserQuestion) {
        if (currentQuestion == null || currentQuestion.isBlank()) return false;
        String q = currentQuestion.toLowerCase(Locale.ROOT);

        // 1. 先命中「明确换话题」→ 直接判 false
        if (TOPIC_SWITCH_CHAT_PATTERN.matcher(q).find()) {
            return false;
        }

        // 2. 命中工具话题词 → true
        if (TOOL_TOPIC_WORD_PATTERN.matcher(q).find()) {
            return true;
        }

        // 3. 没命中但字数非常短（≤4个字符），可能是纯代词追答，需要看历史是否工具调用来决策
        //    比如 "mv-003" "呢" 这些 TOOL_TOPIC 已经包含在上面了，这里基本不会到
        //    兜底：如果字数<=2 且 历史上条是能耗/设备查询 → 放行，宁可小误判也不拦
        if (currentQuestion.strip().length() <= 2) {
            String last = lastUserQuestion == null ? "" : lastUserQuestion.toLowerCase(Locale.ROOT);
            if (ENERGY_WORD_PATTERN.matcher(last).find() || SN_PATTERN.matcher(last).find() || DEVICE_LIST_WORD_PATTERN.matcher(last).find()) {
                return true;
            }
        }

        return false;
    }

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