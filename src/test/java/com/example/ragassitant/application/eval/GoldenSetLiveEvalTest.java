package com.example.ragassitant.application.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 在线评测门禁：需要真实模型与已发布的知识库，默认不运行。
 *
 * <pre>
 * mvn test -Deval.live=true -Dtest=GoldenSetLiveEvalTest
 * </pre>
 *
 * 门禁条件见 application-eval.yml 的 eval.gate：通过率不低于下限，且相对基线没有回归。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "eval.live", matches = "true")
class GoldenSetLiveEvalTest {

    @Autowired
    EvalRunner runner;

    @Autowired
    EvalProperties props;

    @Test
    @SuppressWarnings("unchecked")
    void goldenSetPassesGate() {
        Map<String, Object> report = runner.run(null, null, 0);
        double passRate = ((Number) report.get("passRate")).doubleValue();
        List<String> regressions = (List<String>) report.get("regressions");

        assertTrue(passRate >= props.getGate().getMinPassRate(),
                "通过率 " + passRate + " 低于门禁 " + props.getGate().getMinPassRate()
                        + "，失败用例见 " + report.get("reportFile"));
        assertTrue(regressions.size() <= props.getGate().getMaxRegressions(),
                "相对基线出现回归: " + regressions);
    }
}
