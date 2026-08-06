package com.example.ragassitant.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证配置属性。
 *
 * <p>用于配置登录认证的用户名和密码。
 * 凭证存储在 application.yml 中，通过此配置类注入到 Spring 容器。
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /**
     * 是否启用登录认证。
     * 默认 false（不启用），设为 true 则开启认证。
     */
    private boolean enabled = false;

    /**
     * 登录用户名。
     */
    private String username = "admin";

    /**
     * 登录密码。
     */
    private String password = "admin123";
}