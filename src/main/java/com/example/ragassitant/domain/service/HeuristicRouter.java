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
    private static final Pattern FOLLOWUP_PATTERN = Pattern.compile(
            "(呢|吗|怎么样|如何|看下|看看|查下|查询下|这个呢|那个呢|这个|那个)\\s*[?？。.!！,，]*\\s*$");

    // ========== 领域实词：出现即可确认「还在聊设备/能耗」 ==========
    // 时间词必须带数量或聚合语义（近30天/按月/季度），单独的「天」「日」「周」太泛，
    // 「今天怎么办」这种会被误判成能耗话题。
    private static final Pattern DOMAIN_WORD_PATTERN = Pattern.compile(
            "设备|能耗|用电|电量|kwh|电度|机组|编号|sn|mv-|wzh_|" +
            "趋势|曲线|清单|列表|聚合|汇总|统计|合计|" +
            "\\d+\\s*天|近\\d+天|按月|每月|月度|按周|每周|周统计|按季|季度|按年|年度|近半年|半年|6个月");

    // ========== 追问标记：只说明「这是个追问」，对话题毫无指示作用 ==========
    // 【踩过的坑】改造前把这两类词混在一张表里，只要命中任意一个就判定同话题。
    // 而「如何…呢？」是最普通的中文问句形式 —— 线上出现过上一轮查 mv-005 能耗、
    // 下一轮问「如何创建用户呢？」，因命中「如何」「呢」被判为同话题，
    // 拼接历史继承了 mv-005，把知识库问题劫持成了能耗查询，还跳过了向量检索。
    private static final Pattern FOLLOWUP_MARKER_PATTERN = Pattern.compile(
            "呢|吗|怎么样|如何|多少|看下|看看|查下|查询下|" +
            "这个|那个|它|他|她|那台|这台|这些|那些|上面那个|之前那个|刚才那个|" +
            "同样|一样|换成|换个|另外|再|还有");

    // ========== 闲聊/问候/AI 身份 ==========
    // 【和「换话题」必须分开】这类问题既不该进工具路由，也不该进向量检索：
    // 线上出现过用户只说了句「你好」，因为 Agent 链路无条件检索知识库、召回又没有相似度下限，
    // 模型被塞进一整段电能质量 FAQ，于是回了「我已经阅读了知识库中的内容…」并总结起 FAQ 来。
    // 而「解释下什么是电能质量」虽然也是「换话题」，却是标准的知识库问题，必须继续走检索 ——
    // 所以下面 TOPIC_SWITCH 里那些换话题指示词绝不能混进这张表。
    private static final Pattern SMALL_TALK_PATTERN = Pattern.compile(
            // 纯闲聊问候（整句匹配，避免「你好，帮我查下 mv-003 能耗」被误杀）
            "^\\s*(你好|您好|hi|hello|嗨|在吗|在不在|你是谁|介绍下|自我介绍|你是什么|你叫什么|你是什么大?模型|你用什么模型|基于什么模型|你能做什么|你会什么|有什么功能|功能介绍|你能干嘛|谢谢|感谢|好的|ok|收到|明白|了解|行|可以|不错|真棒|厉害|哈哈|嘿嘿)\\s*[!！?？。.，,~～]*\\s*$|" +
            // 关于 AI 本身的身份/能力问题（不是运维话题，也不在知识库里）
            "(你是什么大?模型|用什么模型训练|基于什么模型|模型是什么|你是gpt|你是qwen|你是ollama|你是通义|你是什么公司|你是谁开发的|你多大|你是男是女|你住哪|你的名字|你有感情吗|你有意识吗)");

    // ========== 反模式：命中这些=明确换话题/闲聊/通用问答，直接拒绝补全 ==========
    //  优先级比 TOOL_TOPIC 更高
    private static final Pattern TOPIC_SWITCH_CHAT_PATTERN = Pattern.compile(
            // 闲聊与身份问题
            SMALL_TALK_PATTERN.pattern() + "|" +
            // 明确切换话题的指示词
            // 【原写法的坑】「我想问下[^设备^能耗^数据]」里的方括号是字符类而非否定预查，
            // 它排除的是「设备能耗数据^」这几个单字，语义和意图完全不符，改成否定预查。
            "(换个话题|聊点别的|不说这个|不聊这个|问你个别的|另外|还有|顺便问下|对了还有|我想问下(?!设备|能耗|数据)|想知道(?!设备|能耗|数据)|解释下什么是|什么叫|怎么理解|定义是什么)");

    /**
     * 是否为闲聊/问候/AI 身份类问题。
     *
     * <p>命中后调用方应当<b>同时跳过</b>：Query 改写、启发式工具路由、向量检索 —— 三者都会把
     * 一句「你好」放大成一次无依据的业务回答（改写可能把它补成设备查询、检索必然召回无关文档）。
     *
     * <p>声明为 {@code static} 是为了让不持有 Router 实例的调用方（如 Query 改写门禁）也能复用同一份判定，
     * 避免两处各写一张闲聊词表后逐渐漂移。
     */
    public static boolean isSmallTalk(String question) {
        if (question == null || question.isBlank()) return false;
        return SMALL_TALK_PATTERN.matcher(question.toLowerCase(Locale.ROOT)).find();
    }

    /**
     * 判断当前问题是否「还在聊设备/能耗话题」，决定能否从历史继承 SN 等参数。
     *
     * <p>判定顺序：
     * <ol>
     *   <li>命中明确换话题/闲聊 → false</li>
     *   <li>命中<b>领域实词</b> → true（这是唯一的正向确认信号）</li>
     *   <li>只命中<b>追问标记</b>：仅当剥掉标记后几乎不剩实词时才算纯代词追问（「那个呢」「它呢」），
     *       否则说明用户在问一件<b>新的具体的事</b>（「如何创建用户呢？」），判 false</li>
     * </ol>
     */
    public boolean isLikelySameToolTopic(String currentQuestion, String lastUserQuestion) {
        if (currentQuestion == null || currentQuestion.isBlank()) return false;
        String q = currentQuestion.toLowerCase(Locale.ROOT);

        // 1. 先命中「明确换话题」→ 直接判 false
        if (TOPIC_SWITCH_CHAT_PATTERN.matcher(q).find()) {
            return false;
        }

        // 2. 领域实词是唯一的正向确认信号
        if (DOMAIN_WORD_PATTERN.matcher(q).find()) {
            return true;
        }

        // 3. 纯代词式追问：剥掉追问标记和标点后剩不下实词，才认为是在追上一个话题。
        //    「那个呢」→ 剩 ""     → 是追问
        //    「如何创建用户呢？」→ 剩「创建用户」 → 是新问题，不能继承历史
        if (FOLLOWUP_MARKER_PATTERN.matcher(q).find()) {
            String residue = FOLLOWUP_MARKER_PATTERN.matcher(q).replaceAll("")
                    .replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]", "");
            if (residue.length() <= 2) {
                String last = lastUserQuestion == null ? "" : lastUserQuestion.toLowerCase(Locale.ROOT);
                return ENERGY_WORD_PATTERN.matcher(last).find()
                        || SN_PATTERN.matcher(last).find()
                        || DEVICE_LIST_WORD_PATTERN.matcher(last).find();
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
            // 「近 30 天按周汇总」要把 30 天传下去；原先 aggregate_energy 不接受时间范围、固定查 180 天
            Matcher daysMatcher = N_DAYS_PATTERN.matcher(question);
            if (daysMatcher.find()) {
                try {
                    int days = Integer.parseInt(daysMatcher.group(1));
                    if (days > 0 && days <= 730) args.put("days", days);
                } catch (NumberFormatException ignored) {
                    // 保持默认范围
                }
            } else if (HALF_YEAR_PATTERN.matcher(q).find()) {
                args.put("days", 180);
            }
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