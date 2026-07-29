package com.example.ragassitant.infrastructure.agent;

import com.example.ragassitant.domain.model.ToolCall;
import com.example.ragassitant.domain.spi.ToolRegistryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

@Component
public class SpringAiToolRegistry implements ToolRegistryPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiToolRegistry.class);

    private final DeviceTools deviceTools;
    private final Map<String, Method> toolMethods = new HashMap<>();
    private final Map<String, String> toolDescriptions = new LinkedHashMap<>();

    public SpringAiToolRegistry(DeviceTools deviceTools) {
        this.deviceTools = deviceTools;
        for (Method m : DeviceTools.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                Tool ann = m.getAnnotation(Tool.class);
                String name = ann.name().isEmpty() ? m.getName() : ann.name();
                m.setAccessible(true);
                toolMethods.put(name, m);
                toolDescriptions.put(name, ann.description());
                log.info("✅ 注册工具: {}", name);
            }
        }
        log.info("✅ ToolRegistry 初始化完成，共注册 {} 个工具", toolMethods.size());
    }

    @Override
    public List<String> listToolNames() {
        return new ArrayList<>(toolMethods.keySet());
    }

    @Override
    public Map<String, String> getToolDescriptions() {
        return new LinkedHashMap<>(toolDescriptions);
    }

    @Override
    public String validateArguments(String toolName, Map<String, Object> arguments) {
        return null; // 实际校验在 domain.service.ToolCallValidator
    }

    @Override
    public String invoke(ToolCall toolCall) {
        if (toolCall == null || toolCall.name == null) {
            return "❌ 工具调用为空";
        }
        Method method = toolMethods.get(toolCall.name);
        if (method == null) {
            return "❌ 未知工具: " + toolCall.name + "，可用工具：" + toolMethods.keySet();
        }
        try {
            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                String expectedName = getExpectedParamName(method, i);
                Object val = findArgumentValue(toolCall.arguments, expectedName, i);
                if (val == null) {
                    args[i] = defaultForType(paramTypes[i]);
                } else {
                    args[i] = convertType(val, paramTypes[i]);
                }
            }
            Object ret = method.invoke(deviceTools, args);
            return ret != null ? ret.toString() : "(工具无返回)";
        } catch (Exception e) {
            log.error("❌ 工具 {} 调用失败", toolCall.name, e);
            return "❌ 工具 " + toolCall.name + " 执行失败: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    private String getExpectedParamName(Method method, int idx) {
        try {
            Parameter p = method.getParameters()[idx];
            ToolParam a = p.getAnnotation(ToolParam.class);
            if (a != null) {
                if (p.isNamePresent()) return p.getName();
            }
            if (p.isNamePresent()) return p.getName();
        } catch (Exception ignored) {}
        return "arg" + idx;
    }

    private Object findArgumentValue(Map<String, Object> arguments, String expectedName, int idx) {
        if (arguments == null) return null;
        if (arguments.containsKey(expectedName)) return arguments.get(expectedName);
        for (Map.Entry<String, Object> e : arguments.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            String en = expectedName.toLowerCase(Locale.ROOT);
            if (k.equals(en) || k.contains(en) || en.contains(k)) {
                return e.getValue();
            }
        }
        List<Object> values = new ArrayList<>(arguments.values());
        return idx < values.size() ? values.get(idx) : null;
    }

    private Object defaultForType(Class<?> t) {
        if (t == String.class) return "";
        if (t == int.class || t == Integer.class) return 0;
        if (t == long.class || t == Long.class) return 0L;
        if (t == boolean.class || t == Boolean.class) return false;
        return null;
    }

    private Object convertType(Object val, Class<?> target) {
        if (val == null) return null;
        if (target.isInstance(val)) return val;
        String str = val.toString().trim();
        if (target == String.class) return str;
        if (target == int.class || target == Integer.class) {
            try { return Integer.parseInt(str.replaceAll("[^0-9-]", "")); } catch (Exception e) { return 0; }
        }
        if (target == long.class || target == Long.class) {
            try { return Long.parseLong(str.replaceAll("[^0-9-]", "")); } catch (Exception e) { return 0L; }
        }
        if (target == double.class || target == Double.class) {
            try { return Double.parseDouble(str); } catch (Exception e) { return 0.0; }
        }
        if (target == boolean.class || target == Boolean.class) {
            return "true".equalsIgnoreCase(str) || "1".equals(str) || "yes".equalsIgnoreCase(str);
        }
        return val;
    }

    private static class ObjectMapperHolder {
        // 预留位：JSON 序列化扩展
    }
}