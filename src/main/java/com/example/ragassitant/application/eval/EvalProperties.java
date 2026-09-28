package com.example.ragassitant.application.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 评测配置，对应 {@code application-eval.yml}。 */
@Data
@ConfigurationProperties(prefix = "eval")
public class EvalProperties {

    /** 默认评测集名（classpath:eval/<name>.json）。 */
    private String goldenSet = "golden-v1";

    /** 评测报告输出目录。 */
    private String reportDir = "./eval-reports";

    /** 基线报告；存在时每次评测都会和它比对，列出「基线通过、本次失败」的回归用例。 */
    private String baselineFile = "./eval-reports/baseline.json";

    /** 单条用例等待 trace 落库的最长时间（trace 是异步写的，取工具调用记录前要等它刷盘）。 */
    private long traceWaitMs = 3000;

    private Gate gate = new Gate();

    @Data
    public static class Gate {
        /** 在线评测门禁：通过率下限。 */
        private double minPassRate = 0.8;
        /** 在线评测门禁：允许的回归用例数（基线通过、本次失败）。 */
        private int maxRegressions = 0;
    }
}
