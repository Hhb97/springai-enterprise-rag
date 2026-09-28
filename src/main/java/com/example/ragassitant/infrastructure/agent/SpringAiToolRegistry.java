package com.example.ragassitant.infrastructure.agent;

import com.example.ragassitant.domain.model.ToolCall;
import com.example.ragassitant.domain.service.ToolCallValidator;
import com.example.ragassitant.domain.spi.ToolRegistryPort;
import com.example.ragassitant.infrastructure.governance.ToolGovernance;
import com.example.ragassitant.infrastructure.observability.AgentMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 启发式 FORCE_EXEC 路径的工具执行器：按名字反射调用 {@link DeviceTools} 上的 {@code @Tool} 方法。
 *
 * <p>它和 Spring AI 2.0 原生 Tool Calling 是<b>两条并行的路径</b>，不是一条：
 * <ul>
 *   <li>LLM 自主决定调用工具 → 由 {@code ChatClient.tools(deviceTools)} 走 Spring AI 内部实现；</li>
 *   <li>启发式路由抢先命中（FORCE_EXEC）→ 走本类，跳过 LLM 的工具决策以省一次往返。</li>
 * </ul>
 * 所以本类必须自己做参数校验：Spring AI 那条路上的参数处理在这里完全不生效。
 */
@Component
public class SpringAiToolRegistry implements ToolRegistryPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiToolRegistry.class);

    private final DeviceTools deviceTools;
    private final ToolCallValidator validator;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolGovernance governance;
    private final AgentMetrics metrics;
    private final Map<String, Method> toolMethods = new HashMap<>();

    public SpringAiToolRegistry(DeviceTools deviceTools, ToolCallValidator validator) {
        this(deviceTools, validator, ToolGovernance.permissive(), AgentMetrics.noop());
    }

    @Autowired
    public SpringAiToolRegistry(DeviceTools deviceTools, ToolCallValidator validator,
                                ToolGovernance governance, AgentMetrics metrics) {
        this.deviceTools = deviceTools;
        this.validator = validator;
        this.governance = governance;
        this.metrics = metrics;
        for (Method m : DeviceTools.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                Tool ann = m.getAnnotation(Tool.class);
                String name = ann.name().isEmpty() ? m.getName() : ann.name();
                toolMethods.put(name, m);
                log.info("✅ 注册工具: {}", name);
            }
        }
        log.info("✅ ToolRegistry 初始化完成，共注册 {} 个工具", toolMethods.size());
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

        // 先把入参按方法形参名归一化，再校验、再调用 —— 三步看到的是<b>同一份</b>参数视图。
        // 分两套匹配规则会立刻出问题：校验器按 args.get("sn") 精确取键，若调用侧另做忽略大小写匹配，
        // 那么 {"SN": "mv-003"} 会被校验判成「缺少 sn」而实际调用本来是取得到值的。
        Map<String, Object> resolved = resolveArguments(method, toolCall.arguments);

        // 【为什么校验必须在这里】原实现直接反射调用，参数缺失或解析失败会被 convertType 静默
        // 兜成 0/""（days=0 → 查 0 天 → 工具回「暂无数据」），用户看到的是「没有数据」而不是
        // 「参数不对」。把校验结果作为返回值交给 LLM，它才有机会纠正参数或向用户追问。
        String invalid = validator.validate(toolCall.name, resolved);
        if (invalid != null) {
            log.warn("⚠️ 工具 {} 参数校验未通过: {} | 原始参数={}", toolCall.name, invalid, toolCall.arguments);
            metrics.toolCall(toolCall.name, "registry", "invalid_args");
            return "❌ 参数有误：" + invalid;
        }

        // 治理闸与原生通道共用（见 ToolGovernance）：FORCE_EXEC / Plan / ReAct 绕不过它
        return governance.execute(toolCall.name, canonicalArgs(resolved), "registry",
                () -> doInvoke(toolCall.name, method, resolved));
    }

    /** 参数按键排序后序列化，保证同样的参数得到同样的幂等键。 */
    private static String canonicalArgs(Map<String, Object> resolved) {
        try {
            return JSON.writeValueAsString(new TreeMap<>(resolved));
        } catch (Exception e) {
            return String.valueOf(new TreeMap<>(resolved));
        }
    }

    private String doInvoke(String toolName, Method method, Map<String, Object> resolved) {
        try {
            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] callArgs = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                Object val = resolved.get(parameterName(method, i));
                callArgs[i] = val == null ? defaultForType(paramTypes[i]) : convertType(val, paramTypes[i]);
            }
            Object ret = method.invoke(deviceTools, callArgs);
            metrics.toolCall(toolName, "registry", "ok");
            return ret != null ? ret.toString() : "(工具无返回)";
        } catch (Exception e) {
            log.error("❌ 工具 {} 调用失败", toolName, e);
            metrics.toolCall(toolName, "registry", "error");
            return "❌ 工具 " + toolName + " 执行失败: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    /** 依赖 {@code -parameters} 编译参数保留形参名；拿不到时退化为 argN。 */
    private String parameterName(Method method, int idx) {
        Parameter p = method.getParameters()[idx];
        return p.isNamePresent() ? p.getName() : "arg" + idx;
    }

    /**
     * 把调用方传来的参数重新按方法形参名建索引，匹配不上的一律丢弃（后续由校验层报「缺少参数」）。
     *
     * <p>只做「精确匹配 → 忽略大小写匹配」两级，<b>不做</b>模糊包含匹配，也<b>不做</b>位置兜底。
     * 原实现用双向 {@code contains} 匹配再按下标硬塞：{@code query_energy_by_date_range}
     * 收到一个名为 {@code date} 的参数时，{@code "startDate".contains("date")} 成立，会把它填进 startDate；
     * 名字全对不上时还会按顺序依次填。宁可缺参数被明确报错，也不要猜错槽位还照样执行 ——
     * 后者产出的是「看起来完全正常」的错误数据，没有任何人能发现。
     */
    private Map<String, Object> resolveArguments(Method method, Map<String, Object> arguments) {
        Map<String, Object> resolved = new HashMap<>();
        if (arguments == null || arguments.isEmpty()) return resolved;
        for (int i = 0; i < method.getParameterCount(); i++) {
            String name = parameterName(method, i);
            if (arguments.containsKey(name)) {
                resolved.put(name, arguments.get(name));
                continue;
            }
            for (Map.Entry<String, Object> e : arguments.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                    resolved.put(name, e.getValue());
                    break;
                }
            }
        }
        return resolved;
    }

    private Object defaultForType(Class<?> t) {
        if (t == String.class) return "";
        if (t == int.class || t == Integer.class) return 0;
        if (t == long.class || t == Long.class) return 0L;
        if (t == boolean.class || t == Boolean.class) return false;
        return null;
    }

    private Object convertType(Object val, Class<?> target) {
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
}
