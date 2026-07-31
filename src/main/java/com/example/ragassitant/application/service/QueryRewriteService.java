package com.example.ragassitant.application.service;

import com.example.ragassitant.domain.service.PromptBuilder;
import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO;
import com.example.ragassitant.domain.spi.LlmPort.MessageDTO.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Query 改写：把多轮对话里的省略/指代问句还原成可独立检索的完整问句。
 *
 * <p><b>产物仅用于检索</b>。送进对话消息的始终是用户原话 —— 改写一旦回流到对话上下文，
 * 后续轮次会在改写结果上继续改写，误差逐轮累积。
 *
 * <p>三条硬性约束：
 * <ol>
 *   <li><b>不能每次都调</b>。改写是一次额外的 LLM 往返，绝大多数首轮提问和完整问句必须走零成本路径；</li>
 *   <li><b>不能阻断主流程</b>。异常、超时、跑飞一律静默回退原问题；</li>
 *   <li><b>结果必须可观测</b>。改写前后写进 AgentTrace，否则线上无从判断它是在帮忙还是在添乱。</li>
 * </ol>
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    /** 指代词：出现即认为问句可能不自包含 */
    private static final Pattern ANAPHORA = Pattern.compile(
            "它|他|她|这个|那个|这台|那台|这些|那些|上面|前面|刚才|之前|上次|同样|一样|" +
            "换成|换个|另外|再|还有|其他|别的");

    /** 省略式追问的收尾助词 */
    private static final Pattern ELLIPTIC_TAIL = Pattern.compile("(呢|吗|怎么样|如何|多少|是多少)\\s*[?？。.!！]*\\s*$");

    /** 自包含实体：带完整 SN 的问句通常不需要补全 */
    private static final Pattern SELF_CONTAINED_ENTITY = Pattern.compile(
            "(mv-[A-Za-z0-9_-]+|wzh_[A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    /** 短问句阈值：低于此长度大概率是追问 */
    private static final int SHORT_QUESTION_CHARS = 15;

    /** 守护线程池，仅用于给同步的 LLM 调用套超时；不阻塞 JVM 退出 */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "query-rewrite");
        t.setDaemon(true);
        return t;
    });

    private final LlmPort rewriteLlmPort;
    private final PromptBuilder promptBuilder;
    private final AgentTraceStorePort traceStore;

    private final boolean enabled;
    private final long timeoutMs;
    private final int maxHistoryPairs;
    private final double maxLengthRatio;

    public QueryRewriteService(@Qualifier("queryRewriteLlmPort") LlmPort rewriteLlmPort,
                               PromptBuilder promptBuilder,
                               AgentTraceStorePort traceStore,
                               @Value("${rag.query-rewrite.enabled:true}") boolean enabled,
                               @Value("${rag.query-rewrite.timeout-ms:3000}") long timeoutMs,
                               @Value("${rag.query-rewrite.max-history-pairs:3}") int maxHistoryPairs,
                               @Value("${rag.query-rewrite.max-length-ratio:3.0}") double maxLengthRatio) {
        this.rewriteLlmPort = rewriteLlmPort;
        this.promptBuilder = promptBuilder;
        this.traceStore = traceStore;
        this.enabled = enabled;
        this.timeoutMs = timeoutMs;
        this.maxHistoryPairs = maxHistoryPairs;
        this.maxLengthRatio = maxLengthRatio;
        log.info("🔧 Query 改写配置: enabled={}, timeout={}ms, 历史轮数={}, 长度比上限={}",
                enabled, timeoutMs, maxHistoryPairs, maxLengthRatio);
    }

    /**
     * 改写用于检索的查询。
     *
     * <p>返回 {@code List} 而非单个 String 是为 Multi-Query（生成多个表述分别检索再 RRF 融合）预留，
     * 当前恒返回 1 条，届时扩展不必改签名。第一个元素始终是最适合检索的查询。
     */
    public List<String> rewrite(String question, List<Map.Entry<String, String>> history, String sessionId) {
        if (question == null || question.isBlank()) return List.of("");
        if (!enabled || history == null || history.isEmpty()) return List.of(question);
        if (!needsRewrite(question)) {
            log.debug("⏭️ 问句已自包含，跳过改写: {}", question);
            return List.of(question);
        }

        long start = System.currentTimeMillis();
        String rewritten = null;
        String error = null;
        try {
            rewritten = callWithTimeout(question, history);
        } catch (TimeoutException e) {
            error = "timeout after " + timeoutMs + "ms";
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long elapsed = System.currentTimeMillis() - start;

        String cleaned = sanitize(rewritten, question);
        boolean changed = !question.equals(cleaned);

        traceStore.recordDecision(sessionId, 1, "QUERY_REWRITE",
                null, null, null,
                question, cleaned,
                false, changed ? "REWRITTEN" : "UNCHANGED",
                elapsed, error == null ? "OK" : "ERROR", error);

        if (error != null) {
            log.warn("⚠️ Query 改写失败，已回退原问题（不影响主流程）: {}", error);
        } else if (changed) {
            log.info("✏️ Query 改写: '{}' → '{}' ({}ms)", question, cleaned, elapsed);
        } else {
            log.debug("✏️ Query 改写未产生变化 ({}ms)", elapsed);
        }
        return List.of(cleaned);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 零成本门禁：只有「看起来不自包含」的问句才值得付一次 LLM 往返。
     * <p>包级可见，便于单测直接覆盖判定规则。
     */
    static boolean needsRewrite(String question) {
        if (question == null || question.isBlank()) return false;
        String q = question.strip();

        // 已带完整 SN 的问句信息足够，不需要补全
        if (SELF_CONTAINED_ENTITY.matcher(q).find()) return false;

        return q.length() < SHORT_QUESTION_CHARS
                || ANAPHORA.matcher(q).find()
                || ELLIPTIC_TAIL.matcher(q).find();
    }

    private String callWithTimeout(String question, List<Map.Entry<String, String>> history)
            throws InterruptedException, ExecutionException, TimeoutException {
        Future<String> future = executor.submit(() -> {
            List<MessageDTO> messages = new ArrayList<>();
            messages.add(new MessageDTO(Role.SYSTEM, promptBuilder.buildQueryRewritePrompt(formatHistory(history))));
            messages.add(new MessageDTO(Role.USER, question));
            return rewriteLlmPort.chatWithRawMessages(messages);
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 取消只是停止等待，底层 HTTP 请求仍会跑完；守护线程不会拖住进程退出
            future.cancel(true);
            throw e;
        }
    }

    /** 只喂最近几轮：喂太多历史反而会把改写带偏到旧话题 */
    private String formatHistory(List<Map.Entry<String, String>> history) {
        int from = Math.max(0, history.size() - Math.max(1, maxHistoryPairs));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> pair : history.subList(from, history.size())) {
            sb.append("用户: ").append(truncate(pair.getKey(), 200)).append('\n');
            sb.append("助手: ").append(truncate(pair.getValue(), 200)).append('\n');
        }
        return sb.toString();
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    /**
     * 输出后处理。小模型几乎必然会加前缀、引号或解释，跑飞时必须能识别并回退。
     */
    private String sanitize(String raw, String original) {
        if (raw == null || raw.isBlank()) return original;

        String s = raw.strip();
        // 只取第一行：模型常在问句后面附加解释
        int newline = s.indexOf('\n');
        if (newline > 0) s = s.substring(0, newline).strip();
        // 剥常见前缀
        s = s.replaceFirst("^(改写(结果|后)?|输出|结果|问题|Query|Answer)\\s*[:：]\\s*", "");
        // 剥包裹的引号/书名号
        s = s.replaceAll("^[\"'“”‘’「」『』《》]+", "").replaceAll("[\"'“”‘’「」『』《》]+$", "");
        s = s.strip();

        if (s.isBlank()) return original;
        // 跑飞判定：改写结果远长于原问题，说明模型在扩写或直接作答了
        if (s.length() > original.length() * maxLengthRatio) {
            log.warn("⚠️ 改写结果过长（{} → {} 字符），判定为跑飞，回退原问题", original.length(), s.length());
            return original;
        }
        return s;
    }
}
