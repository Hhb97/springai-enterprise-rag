package com.example.ragassitant.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动时的安全配置自检：不阻断启动（方便本地开发），但把风险项在日志里显式列出来。
 *
 * <p>这些问题的共同点是「配置文件看起来没问题、功能也都能用」，所以不主动检查就永远不会被发现。
 */
@Component
public class SecurityStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupCheck.class);

    private static final String DEFAULT_PASSWORD = "admin123";

    private final AuthProperties authProperties;
    private final boolean enabled;
    private final String dashscopeApiKey;
    private final boolean h2ConsoleEnabled;

    public SecurityStartupCheck(AuthProperties authProperties,
                                @Value("${security.startup-check.enabled:true}") boolean enabled,
                                @Value("${spring.ai.openai.api-key:}") String dashscopeApiKey,
                                @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled) {
        this.authProperties = authProperties;
        this.enabled = enabled;
        this.dashscopeApiKey = dashscopeApiKey;
        this.h2ConsoleEnabled = h2ConsoleEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        if (!enabled) return;
        List<String> risks = collectRisks();
        if (risks.isEmpty()) {
            log.info("🔒 安全自检通过");
            return;
        }
        log.warn("🔒 安全自检发现 {} 项风险（生产环境必须处理）:", risks.size());
        risks.forEach(r -> log.warn("   ⚠️ {}", r));
    }

    List<String> collectRisks() {
        List<String> risks = new ArrayList<>();
        if (!authProperties.isEnabled()) {
            risks.add("登录认证未启用（auth.enabled=false），所有接口匿名可访问");
        } else if (DEFAULT_PASSWORD.equals(authProperties.getPassword())) {
            risks.add("正在使用默认口令，请通过 AUTH_PASSWORD 环境变量设置");
        }
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank() || "not-configured".equals(dashscopeApiKey)) {
            risks.add("未配置 DASHSCOPE_API_KEY：云端模型调用会失败，将由熔断器降级到本地模型");
        }
        if (h2ConsoleEnabled) {
            risks.add("H2 控制台已开启（spring.h2.console.enabled=true），可直接读写整个数据库");
        }
        return risks;
    }
}
