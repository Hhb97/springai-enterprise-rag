package com.example.ragassitant.application.eval;

import com.example.ragassitant.application.service.AgentAppService;
import com.example.ragassitant.application.service.RagAppService;
import com.example.ragassitant.domain.spi.AgentTraceStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 在线评测：把评测集逐条跑过真实链路（RagAppService / AgentAppService），打分、出报告、和基线比对。
 *
 * <p>每条用例用独立的 sessionId（{@code eval-<runId>-<caseId>}），保证用例之间不共享对话历史，
 * 也方便从 trace 里取出这条用例实际调用了哪些工具。
 *
 * <p>串行执行：评测要的是可复现，而不是快；并发跑会让限流、熔断、线程池状态互相影响结果。
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Set<String> TOOL_STAGES = Set.of("HEURISTIC_TOOL", "NATIVE_TOOL_CALL", "TEXT_TOOL_CALL_DETECTED");

    private final AgentAppService agentAppService;
    private final RagAppService ragAppService;
    private final AgentTraceStorePort traceStore;
    private final EvalProperties props;

    public EvalRunner(AgentAppService agentAppService, RagAppService ragAppService,
                      AgentTraceStorePort traceStore, EvalProperties props) {
        this.agentAppService = agentAppService;
        this.ragAppService = ragAppService;
        this.traceStore = traceStore;
        this.props = props;
    }

    /**
     * @param setName  评测集名，null = 默认
     * @param category 只跑某一类，null = 全部
     * @param limit    最多跑几条，<=0 = 不限
     */
    public Map<String, Object> run(String setName, String category, int limit) {
        GoldenSet set = GoldenSet.load(setName == null || setName.isBlank() ? props.getGoldenSet() : setName);
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        List<GoldenSet.Case> cases = set.cases().stream()
                .filter(c -> category == null || category.isBlank() || category.equals(c.category()))
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .toList();
        log.info("🧪 开始评测 {}（run={}）：{} 条用例", set.name(), runId, cases.size());

        List<EvalScorer.CaseResult> results = new ArrayList<>();
        for (GoldenSet.Case c : cases) {
            results.add(runOne(runId, c));
        }

        Map<String, Object> report = summarize(set.name(), runId, results);
        attachBaselineDiff(report, results);
        String file = save(set.name(), runId, report);
        report.put("reportFile", file);
        log.info("🧪 评测完成 {}：通过 {}/{}（{}），报告 {}", set.name(), report.get("passed"), report.get("total"),
                report.get("passRate"), file);
        return report;
    }

    private EvalScorer.CaseResult runOne(String runId, GoldenSet.Case c) {
        String sessionId = "eval-" + runId + "-" + c.id();
        long start = System.currentTimeMillis();
        String answer;
        try {
            if ("rag".equals(c.mode())) {
                Object a = ragAppService.askWithOptions(c.question(), true, null, sessionId).get("answer");
                answer = a == null ? "" : a.toString();
            } else {
                answer = agentAppService.chat(c.question(), sessionId, true, true, null).answer;
            }
        } catch (Exception e) {
            answer = "";
            log.warn("🧪 用例 {} 执行异常: {}", c.id(), e.toString());
        }
        long latency = System.currentTimeMillis() - start;
        EvalScorer.CaseResult r = EvalScorer.score(c, answer, toolsUsed(sessionId, c), latency);
        log.info("🧪 [{}] {} {}ms {}", r.passed() ? "PASS" : "FAIL", c.id(), latency,
                r.passed() ? "" : r.failures());
        return r;
    }

    /** trace 是异步批量写入的：用例需要校验工具时，等到 FINAL 记录落库再取。 */
    private List<String> toolsUsed(String sessionId, GoldenSet.Case c) {
        boolean needTools = c.answer() != null && (c.answer().tools() != null || c.answer().notTools() != null);
        long deadline = System.currentTimeMillis() + (needTools ? props.getTraceWaitMs() : 0);
        List<Map<String, Object>> traces;
        do {
            traces = traceStore.queryBySession(sessionId);
            boolean done = traces.stream().anyMatch(t -> "FINAL".equals(t.get("stage")) || "SECURITY_BLOCK".equals(t.get("stage")));
            if (done || !needTools) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (System.currentTimeMillis() < deadline);

        List<String> tools = new ArrayList<>();
        for (Map<String, Object> t : traces) {
            Object stage = t.get("stage");
            Object tool = t.get("toolName");
            if (stage != null && TOOL_STAGES.contains(stage.toString()) && tool != null && !tools.contains(tool.toString())) {
                tools.add(tool.toString());
            }
        }
        return tools;
    }

    static Map<String, Object> summarize(String setName, String runId, List<EvalScorer.CaseResult> results) {
        Map<String, int[]> byCategory = new TreeMap<>();
        long totalLatency = 0;
        for (EvalScorer.CaseResult r : results) {
            int[] agg = byCategory.computeIfAbsent(r.category(), k -> new int[2]);
            agg[0]++;
            if (r.passed()) agg[1]++;
            totalLatency += r.latencyMs();
        }
        long passed = results.stream().filter(EvalScorer.CaseResult::passed).count();
        Map<String, Object> categories = new LinkedHashMap<>();
        byCategory.forEach((k, v) -> categories.put(k, Map.of("total", v[0], "passed", v[1],
                "passRate", ratio(v[1], v[0]))));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("set", setName);
        report.put("runId", runId);
        report.put("total", results.size());
        report.put("passed", passed);
        report.put("passRate", ratio(passed, results.size()));
        report.put("avgLatencyMs", results.isEmpty() ? 0 : totalLatency / results.size());
        report.put("byCategory", categories);
        report.put("failures", results.stream().filter(r -> !r.passed()).toList());
        report.put("results", results);
        return report;
    }

    /** 回归 = 基线里通过、本次失败的用例。它比总通过率更重要：通过率持平也可能是「修好两个、弄坏两个」。 */
    @SuppressWarnings("unchecked")
    private void attachBaselineDiff(Map<String, Object> report, List<EvalScorer.CaseResult> results) {
        File baseline = new File(props.getBaselineFile());
        if (!baseline.isFile()) {
            report.put("baseline", null);
            report.put("regressions", List.of());
            return;
        }
        try {
            Map<String, Object> base = JSON.readValue(baseline, Map.class);
            Set<String> basePassed = new HashSet<>();
            for (Object o : (List<Object>) base.getOrDefault("results", List.of())) {
                Map<String, Object> m = (Map<String, Object>) o;
                if (Boolean.TRUE.equals(m.get("passed"))) basePassed.add(String.valueOf(m.get("id")));
            }
            List<String> regressions = results.stream()
                    .filter(r -> !r.passed() && basePassed.contains(r.id()))
                    .map(EvalScorer.CaseResult::id)
                    .toList();
            List<String> fixed = results.stream()
                    .filter(r -> r.passed() && !basePassed.contains(r.id()))
                    .map(EvalScorer.CaseResult::id)
                    .toList();
            report.put("baseline", Map.of("runId", String.valueOf(base.get("runId")),
                    "passRate", base.getOrDefault("passRate", 0)));
            report.put("regressions", regressions);
            report.put("newlyPassing", fixed);
        } catch (IOException e) {
            log.warn("🧪 基线文件读取失败，跳过比对: {}", e.getMessage());
            report.put("baseline", null);
            report.put("regressions", List.of());
        }
    }

    private String save(String setName, String runId, Map<String, Object> report) {
        File dir = new File(props.getReportDir());
        if (!dir.isDirectory() && !dir.mkdirs()) {
            log.warn("🧪 无法创建报告目录 {}", dir);
            return null;
        }
        File out = new File(dir, setName + "-" + runId + ".json");
        try {
            JSON.writeValue(out, report);
            return out.getPath();
        } catch (IOException e) {
            log.warn("🧪 报告写入失败: {}", e.getMessage());
            return null;
        }
    }

    /** 把某份报告设为基线。 */
    public void promoteToBaseline(String reportFileName) throws IOException {
        File src = new File(props.getReportDir(), new File(reportFileName).getName());
        if (!src.isFile()) throw new IllegalArgumentException("报告不存在: " + src.getName());
        java.nio.file.Files.copy(src.toPath(), new File(props.getBaselineFile()).toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public List<String> listReports() {
        File[] files = new File(props.getReportDir()).listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return List.of();
        return Arrays.stream(files).map(File::getName).sorted(Comparator.reverseOrder()).toList();
    }

    private static double ratio(long a, long b) {
        return b == 0 ? 0 : Math.round(a * 1000.0 / b) / 1000.0;
    }
}
