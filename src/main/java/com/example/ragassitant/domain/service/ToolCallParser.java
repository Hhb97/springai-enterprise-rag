package com.example.ragassitant.domain.service;

import com.example.ragassitant.domain.model.ToolCall;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ToolCallParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TOOL_CALL_JSON_PATTERN = Pattern.compile(
            "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{[^}]*\\})\\s*\\}",
            Pattern.DOTALL
    );
    private static final Pattern TOOL_CALL_ARRAY_PATTERN = Pattern.compile(
            "\\[\\s*(\\{\\s*\"name\"[^\\]]*\\})\\s*\\]",
            Pattern.DOTALL
    );

    public List<ToolCall> parse(String text) {
        List<ToolCall> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;
        String cleaned = text.replaceAll("```json", "").replaceAll("```", "").trim();

        try {
            Matcher arrMatcher = TOOL_CALL_ARRAY_PATTERN.matcher(cleaned);
            while (arrMatcher.find()) {
                String arrStr = arrMatcher.group(0);
                List<Map<String, Object>> list = MAPPER.readValue(arrStr, new TypeReference<>() {});
                for (Map<String, Object> item : list) {
                    String name = (String) item.get("name");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = (Map<String, Object>) item.get("arguments");
                    if (name != null) {
                        result.add(new ToolCall(name, args != null ? args : Map.of()));
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            Matcher objMatcher = TOOL_CALL_JSON_PATTERN.matcher(cleaned);
            while (objMatcher.find()) {
                final String foundName = objMatcher.group(1);
                if (result.stream().anyMatch(tc -> tc.name.equals(foundName))) continue;
                String argsStr = objMatcher.group(2);
                Map<String, Object> args = MAPPER.readValue(argsStr, new TypeReference<>() {});
                result.add(new ToolCall(foundName, args != null ? args : Map.of()));
            }
        } catch (Exception ignored) {}

        if (result.isEmpty()) {
            try {
                String jsonPart = cleaned.replaceFirst("(?s).*?(\\[.*\\]|\\{.*name.*arguments.*\\})", "$1");
                if (jsonPart.startsWith("[")) {
                    List<Map<String, Object>> list = MAPPER.readValue(jsonPart, new TypeReference<>() {});
                    for (Map<String, Object> item : list) {
                        String name = (String) item.get("name");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> args = (Map<String, Object>) item.get("arguments");
                        if (name != null) result.add(new ToolCall(name, args != null ? args : Map.of()));
                    }
                } else if (jsonPart.contains("\"name\"")) {
                    Map<String, Object> item = MAPPER.readValue(jsonPart, new TypeReference<>() {});
                    String name = (String) item.get("name");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = (Map<String, Object>) item.get("arguments");
                    if (name != null) result.add(new ToolCall(name, args != null ? args : Map.of()));
                }
            } catch (Exception ignored) {}
        }

        return result;
    }
}