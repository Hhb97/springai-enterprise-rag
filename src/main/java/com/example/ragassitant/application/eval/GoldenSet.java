package com.example.ragassitant.application.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 评测集（{@code classpath:eval/<name>.json}）。
 *
 * <p>每条用例有两层断言：
 * <ul>
 *   <li>{@code route}：确定性断言，只验证路由层（闲聊短路 / 注入拦截 / 启发式工具选择），
 *       不调用 LLM，随单测每次构建都跑 —— 改正则、改闲聊词表时立刻知道有没有把别的用例带坏；</li>
 *   <li>{@code answer}：在线断言，需要真实模型，验证最终回答与实际调用的工具。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenSet(String name, String description, List<Case> cases) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static GoldenSet load(String name) {
        String path = "eval/" + name + ".json";
        try (InputStream in = GoldenSet.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("评测集不存在: classpath:" + path);
            return JSON.readValue(in, GoldenSet.class);
        } catch (IOException e) {
            throw new IllegalStateException("评测集解析失败: " + path + " - " + e.getMessage(), e);
        }
    }

    /**
     * @param mode      rag = 纯知识库链路；agent = Agent 链路（工具 + 知识库）
     * @param route     small_talk / injection / none / 工具名
     * @param routeArgs route 为工具名时，启发式应解析出的参数（只校验列出的键）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Case(String id, String category, String mode, String question,
                       String route, Map<String, Object> routeArgs, Answer answer) {
    }

    /**
     * @param contains    必须全部出现
     * @param containsAny 每组至少出现一个（组与组之间是「且」）
     * @param notContains 一个都不能出现
     * @param tools       必须被调用过的工具
     * @param notTools    不能被调用的工具
     * @param refusal     true = 期望拒答（知识库无相关信息）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Answer(List<String> contains, List<List<String>> containsAny, List<String> notContains,
                         List<String> tools, List<String> notTools, Boolean refusal) {
    }
}
