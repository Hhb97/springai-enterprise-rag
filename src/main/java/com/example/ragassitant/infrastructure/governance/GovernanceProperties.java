package com.example.ragassitant.infrastructure.governance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具治理配置，对应 {@code application-governance.yml}。
 *
 * <p>当前 8 个工具全是只读查询，所以这些能力暂时「看不出作用」。它们是为运维 Agent 从
 * 「查询助手」走向「操作助手」准备的：一旦加入「重启设备」「修改阈值」「下发配置」这类工具，
 * 没有风险分级、权限、人工确认和审计，就不能上线。
 */
@Data
@ConfigurationProperties(prefix = "governance")
public class GovernanceProperties {

    /** 未单独配置的工具默认风险等级。 */
    private ToolRisk defaultRisk = ToolRisk.READ_ONLY;

    /** 按工具名配置策略；未出现的工具使用默认值。 */
    private Map<String, ToolPolicy> tools = new LinkedHashMap<>();

    private Approval approval = new Approval();
    private Idempotency idempotency = new Idempotency();
    private Audit audit = new Audit();
    private DirectOutput directOutput = new DirectOutput();
    private ToolRetrieval toolRetrieval = new ToolRetrieval();

    @Data
    public static class ToolPolicy {
        /** 为 null 时取 defaultRisk。 */
        private ToolRisk risk;
        private boolean enabled = true;
        /** 允许调用的登录用户；为空 = 所有已登录用户。 */
        private List<String> allowedUsers = new ArrayList<>();
        /**
         * 工具输出直接作为最终回答返回，跳过模型的总结轮。为 null 时看 directOutput.defaultForReadOnly。
         * 适用于输出本身就是排版好的成品（设备清单、能耗报表）的工具。
         */
        private Boolean returnDirect;
    }

    @Data
    public static class Approval {
        /** 待确认操作多久未处理自动过期。 */
        private long ttlMinutes = 30;
    }

    @Data
    public static class Idempotency {
        /** 写操作去重窗口：同一会话、同一工具、同一参数在窗口内重复调用，直接返回上次结果。 */
        private long windowSeconds = 60;
    }

    @Data
    public static class Audit {
        private boolean enabled = true;
        /** 只读工具是否也写审计（默认只审计写操作与危险操作，避免审计表被查询淹没）。 */
        private boolean includeReadOnly = false;
    }

    @Data
    public static class DirectOutput {
        /** 总开关。 */
        private boolean enabled = true;
        /** 只读工具在未单独配置时是否默认直出。默认 false：只对显式列出的工具直出。 */
        private boolean defaultForReadOnly = false;
    }

    @Data
    public static class ToolRetrieval {
        /**
         * 工具检索：按问题语义只挑 top-K 个工具注册给模型。工具少时没必要（全量注册更准），
         * 工具数达到 minTools 才生效。
         */
        private boolean enabled = false;
        private int minTools = 20;
        private int topK = 8;
        /** 无论检索结果如何都始终注册的工具（兜底能力，如设备清单）。 */
        private List<String> alwaysInclude = new ArrayList<>(List.of("list_devices"));
    }
}
