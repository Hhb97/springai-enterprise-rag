package com.example.ragassitant.domain.service;

import com.example.ragassitant.domain.model.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型的<b>文本</b>输出里识别工具调用，仅作为弱模型的兼容兜底。
 *
 * <p><b>这不是主路径</b>。主路径是 Spring AI 2.0 原生 Tool Calling：模型通过结构化的
 * {@code tool_calls} 字段发起调用，框架自动执行。本类只处理一种故障：
 * <b>模型不支持（或没走）原生通道，把调用当普通文本打印出来</b>。
 *
 * <p>线上实测（{@code qwen2.5:1.5b} + Ollama，关掉启发式路由后）：
 * 用户问「列出所有设备」，模型返回 52 个字符的
 * {@code [{"name":"list_devices","arguments":{"keyword":""}}]} 文本。
 * 由于没有 {@code tool_calls} 字段，框架把它当成最终答案；
 * 再经 {@code cleanupAnswer} 的历史正则清洗，剩下 3 个字符 {@code [}]} 直接展示给用户。
 * 识别出来并真正执行它，是让小模型也能用的最低成本兜底。
 *
 * <p>和被删掉的旧 {@code ToolCallParser} 的区别：旧的用 {@code \{[^}]*\}} 之类的正则直接抠字段，
 * 处理不了嵌套的 arguments 对象；这里只用正则<b>定位</b> JSON 边界，解析交给 Jackson。
 */
public class TextToolCallParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 定位第一个 JSON 数组或对象；贪婪匹配到最后一个闭合符，把嵌套结构整体交给 Jackson */
    private static final Pattern JSON_ARRAY = Pattern.compile("\\[\\s*\\{.*}\\s*]", Pattern.DOTALL);
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

    /**
     * @return 解析出的工具调用；不是工具调用文本时返回空列表（<b>不抛异常</b>，调用方据此决定是否兜底）
     */
    public List<ToolCall> parse(String text) {
        List<ToolCall> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;

        String cleaned = text.replace("```json", "").replace("```", "").trim();

        Matcher arr = JSON_ARRAY.matcher(cleaned);
        if (arr.find()) {
            readArray(arr.group(), result);
            if (!result.isEmpty()) return result;
        }
        Matcher obj = JSON_OBJECT.matcher(cleaned);
        if (obj.find()) {
            readNode(obj.group(), result);
        }
        return result;
    }

    private void readArray(String json, List<ToolCall> out) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.isArray()) root.forEach(node -> addIfToolCall(node, out));
        } catch (Exception ignored) {
            // 不是合法 JSON 就当普通文本，交回调用方原样展示
        }
    }

    private void readNode(String json, List<ToolCall> out) {
        try {
            addIfToolCall(MAPPER.readTree(json), out);
        } catch (Exception ignored) {
        }
    }

    private void addIfToolCall(JsonNode node, List<ToolCall> out) {
        if (node == null || !node.isObject()) return;
        JsonNode nameNode = node.get("name");
        if (nameNode == null || nameNode.isNull()) return;
        String name = nameNode.asText("").trim();
        if (name.isEmpty()) return;

        Map<String, Object> args = new LinkedHashMap<>();
        JsonNode argsNode = node.get("arguments");
        if (argsNode != null && argsNode.isObject()) {
            argsNode.properties().forEach(e -> {
                JsonNode v = e.getValue();
                if (v == null || v.isNull()) return;
                args.put(e.getKey(), v.isNumber() ? v.numberValue() : v.asText());
            });
        }
        out.add(new ToolCall(name, args));
    }
}
