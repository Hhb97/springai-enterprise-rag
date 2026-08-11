package com.example.ragassitant.application.controller;

import com.example.ragassitant.infrastructure.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证控制器和根路径重定向。
 *
 * <p>提供登录接口，用于验证用户凭据并返回认证令牌。
 * 同时处理根路径重定向到追踪页面。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthController {

    private static final String SESSION_USER_KEY = "authenticated_user";

    private final AuthProperties authProperties;

    /**
     * 根路径重定向到主页（聊天界面）。
     */
    @GetMapping("/")
    public ResponseEntity<Void> rootRedirect() {
        return ResponseEntity.status(302)
                .location(URI.create("/index.html"))
                .build();
    }

    /**
     * 登录接口。
     *
     * @param username 用户名
     * @param password 密码
     * @return 认证结果
     */
    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestParam String username,
                                   @RequestParam String password,
                                   HttpServletRequest request) {
        log.info("🔐 登录尝试: username={}", username);

        // 如果认证未启用，直接返回成功
        if (!authProperties.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "认证已禁用",
                    "token", "disabled"
            ));
        }

        // 验证凭据
        if (authProperties.getUsername().equals(username)
                && authProperties.getPassword().equals(password)) {

            // 生成 Base64 Basic Auth token
            String credentials = username + ":" + password;
            String base64Token = Base64.getEncoder().encodeToString(credentials.getBytes());

            // 写入 Session（用于后续 Filter 快速验证，无需每次解析 Header）
            HttpSession session = request.getSession(true);
            session.setAttribute(SESSION_USER_KEY, username);

            log.info("🔐 用户登录成功: {}", username);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "登录成功",
                    "token", base64Token,
                    "username", username,
                    "sessionId", session.getId()
            ));
        }

        log.warn("🔐 登录失败，用户名或密码错误: {}", username);
        return ResponseEntity.status(401).body(Map.of(
                "success", false,
                "message", "用户名或密码错误"
        ));
    }

    /**
     * 登出接口。
     */
    @PostMapping("/api/auth/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            String username = (String) session.getAttribute(SESSION_USER_KEY);
            session.invalidate();
            log.info("🔐 用户登出: {}", username);
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "登出成功"
        ));
    }

    /**
     * 检查认证状态。
     *
     * @return 当前认证状态
     */
    @GetMapping("/api/auth/status")
    public ResponseEntity<?> status(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        boolean authenticated = session != null && session.getAttribute(SESSION_USER_KEY) != null;
        String username = authenticated ? (String) session.getAttribute(SESSION_USER_KEY) : null;

        // 用 LinkedHashMap 而非 Map.of：未登录时 username 为 null，Map.of 对 null 值会抛 NPE。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", authProperties.isEnabled());
        body.put("authenticated", authenticated);
        body.put("username", username);
        return ResponseEntity.ok(body);
    }
}