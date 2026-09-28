package com.example.ragassitant.interfaces.rest;

import com.example.ragassitant.application.eval.EvalRunner;
import com.example.ragassitant.interfaces.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 在线评测接口。一次完整评测会串行调用几十次真实模型，耗时分钟级，只在调参 / 换模型 / 改提示词后手动触发。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalRunner evalRunner;

    public EvalController(EvalRunner evalRunner) {
        this.evalRunner = evalRunner;
    }

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestParam(value = "set", required = false) String set,
                                                @RequestParam(value = "category", required = false) String category,
                                                @RequestParam(value = "limit", defaultValue = "0") int limit) {
        try {
            return ApiResponse.ok("评测完成", evalRunner.run(set, category, limit));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("EVAL_SET_NOT_FOUND", e.getMessage());
        }
    }

    @GetMapping("/reports")
    public ApiResponse<List<String>> reports() {
        return ApiResponse.ok("评测报告", evalRunner.listReports());
    }

    /** 把某份报告设为基线，之后的评测都和它比对回归。 */
    @PostMapping("/baseline")
    public ApiResponse<String> promote(@RequestParam("report") String report) {
        try {
            evalRunner.promoteToBaseline(report);
            return ApiResponse.ok("已设为基线", report);
        } catch (Exception e) {
            return ApiResponse.fail("BASELINE_FAILED", e.getMessage());
        }
    }
}
