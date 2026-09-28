package com.example.ragassitant.common.filter;

import com.example.ragassitant.infrastructure.config.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Basic Auth 认证过滤器。
 *
 * <p>对所有 API 请求进行 Basic Auth 验证（除了登录相关的静态资源和接口）。
 * 验证通过后，将认证信息写入 session。
 *
 * <p>验证逻辑：
 * <ol>
 *   <li>从请求头 {@code Authorization} 中提取 Base64 编码的用户名:密码</li>
 *   <li>与配置的凭据进行比对</li>
 *   <li>验证通过则放行，否则返回 401 Unauthorized</li>
 * </ol>
 *
 * <p>排除路径（无需认证）：
 * <ul>
 *   <li>登录页面：{@code /agent-trace.html}</li>
 *   <li>登录接口：{@code /api/auth/login}</li>
 *   <li>静态资源：{@code /.static/}</li>
 *   <li>（H2 控制台已移出白名单：它能直接读写整库，必须登录；且默认关闭，见 application-security.yml）</li>
 * </ul>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class BasicAuthFilter implements Filter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BASIC_PREFIX = "Basic ";
    private static final String SESSION_USER_KEY = "authenticated_user";
    /** 当前登录用户在 MDC 中的键；认证关闭时为 {@code anonymous}。 */
    public static final String MDC_USER_KEY = "user";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuthProperties authProperties;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String path = req.getRequestURI();

        // 如果认证未启用，直接放行
        if (!authProperties.isEnabled()) {
            proceed(chain, request, response, "anonymous");
            return;
        }

        // 排除无需认证的路径
        if (isExcluded(path)) {
            chain.doFilter(request, response);
            return;
        }

        // 检查 session 中是否已有认证信息
        var session = req.getSession(false);
        if (session != null && session.getAttribute(SESSION_USER_KEY) != null) {
            proceed(chain, request, response, String.valueOf(session.getAttribute(SESSION_USER_KEY)));
            return;
        }

        // 从 Authorization header 提取 Basic Auth 凭据
        String authHeader = req.getHeader(AUTH_HEADER);
        if (authHeader != null && authHeader.startsWith(BASIC_PREFIX)) {
            String base64Credentials = authHeader.substring(BASIC_PREFIX.length());
            String credentials;
            try {
                base64Credentials = base64Credentials.trim();
                byte[] decodedBytes = Base64.getDecoder().decode(base64Credentials);
                credentials = new String(decodedBytes, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                log.warn("🔐 Base64 解码失败: {}", base64Credentials);
                sendUnauthorized(req, resp, "Invalid credentials format");
                return;
            }

            // 解析 username:password
            int colonIndex = credentials.indexOf(':');
            if (colonIndex > 0) {
                String username = credentials.substring(0, colonIndex);
                String password = credentials.substring(colonIndex + 1);

                // 验证凭据
                if (authProperties.getUsername().equals(username)
                        && authProperties.getPassword().equals(password)) {

                    // 认证成功，写入 session
                    if (session == null) {
                        session = req.getSession(true);
                    }
                    session.setAttribute(SESSION_USER_KEY, username);
                    log.info("🔐 用户登录成功（通过 Header）: {}", username);
                    proceed(chain, request, response, username);
                    return;
                } else {
                    log.warn("🔐 登录失败，用户名或密码错误: {}", username);
                }
            }
        }

        // 认证失败，返回 401
        sendUnauthorized(req, resp, "Authentication required");
    }

    /**
     * 放行并把当前用户写入 MDC（键 {@link #MDC_USER_KEY}），工具治理据此做权限判断与审计署名。
     * 请求结束必须移除：Tomcat 线程是复用的，不清理会把上一个人的身份带给下一个请求。
     */
    private void proceed(FilterChain chain, ServletRequest request, ServletResponse response, String user)
            throws IOException, ServletException {
        MDC.put(MDC_USER_KEY, user);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_USER_KEY);
        }
    }

    /**
     * 判断路径是否在排除列表中。
     */
    private boolean isExcluded(String path) {
        return path.equals("/index.html")
                || path.equals("/agent-trace.html")
                || path.equals("/docs.html")
                || path.equals("/")
                || path.equals("/api/auth/login")
                || path.equals("/api/auth/status")
                || path.startsWith("/.static/")
                || path.equals("/favicon.ico");
    }

    /**
     * 判断是否为 API 请求。
     */
    private boolean isApiRequest(HttpServletRequest req) {
        String path = req.getRequestURI();
        String accept = req.getHeader("Accept");
        String xhr = req.getHeader("X-Requested-With");

        // 路径以 /api/ 开头，明确是 API 请求
        if (path.startsWith("/api/")) {
            return true;
        }

        // Accept 头明确要求 JSON
        if (accept != null && (accept.contains("application/json") || accept.contains("*/*"))) {
            // 如果是 fetch 发起的请求（Accept: */*）且路径不包含 .html，则认为是 API 请求
            if (!path.contains(".html") && !path.equals("/")) {
                return "XMLHttpRequest".equals(xhr) || accept.contains("application/json");
            }
        }

        return false;
    }

    /**
     * 发送 401 Unauthorized 响应。
     * <p>
     * API 请求返回 JSON，页面请求返回 HTML 登录页。
     */
    private void sendUnauthorized(HttpServletRequest req, HttpServletResponse resp, String message) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        if (isApiRequest(req)) {
            // API 请求：返回 JSON
            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
            resp.setCharacterEncoding("UTF-8");
            JSON.writeValue(resp.getWriter(), Map.of(
                    "error", "Unauthorized",
                    "message", message,
                    "loginUrl", "/agent-trace.html"
            ));
        } else {
            // 页面请求：返回 HTML 登录页
            resp.setContentType(MediaType.TEXT_HTML_VALUE);
            resp.setCharacterEncoding("UTF-8");

            String html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>登录 - 智能运维助手</title>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                            min-height: 100vh;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                        }
                        .login-box {
                            background: #fff;
                            padding: 48px;
                            border-radius: 24px;
                            box-shadow: 0 20px 60px rgba(0,0,0,0.3);
                            width: 400px;
                            max-width: 90vw;
                            text-align: center;
                        }
                        .icon {
                            width: 80px;
                            height: 80px;
                            background: linear-gradient(135deg, #4f46e5, #7c3aed);
                            border-radius: 20px;
                            display: inline-flex;
                            align-items: center;
                            justify-content: center;
                            font-size: 40px;
                            margin-bottom: 24px;
                        }
                        h1 { font-size: 28px; color: #1f2937; margin-bottom: 8px; }
                        p { color: #6b7280; margin-bottom: 32px; font-size: 15px; }
                        .error {
                            background: #fef2f2;
                            border: 1px solid #fecaca;
                            color: #dc2626;
                            padding: 12px;
                            border-radius: 8px;
                            margin-bottom: 16px;
                            font-size: 14px;
                        }
                        input {
                            width: 100%;
                            padding: 14px 16px;
                            border: 2px solid #e5e7eb;
                            border-radius: 10px;
                            font-size: 15px;
                            margin-bottom: 16px;
                            outline: none;
                            transition: border-color 0.2s;
                        }
                        input:focus { border-color: #4f46e5; }
                        button {
                            width: 100%;
                            padding: 14px;
                            background: linear-gradient(135deg, #4f46e5, #7c3aed);
                            color: #fff;
                            border: none;
                            border-radius: 10px;
                            font-size: 16px;
                            font-weight: 600;
                            cursor: pointer;
                            transition: transform 0.2s;
                        }
                        button:hover { transform: translateY(-2px); }
                        .hint {
                            margin-top: 20px;
                            font-size: 12px;
                            color: #9ca3af;
                        }
                    </style>
                </head>
                <body>
                    <div class="login-box">
                        <div class="icon">🔐</div>
                        <h1>智能运维助手</h1>
                        <p>请输入登录凭证</p>
                        <div class="error" style="display:none;"></div>
                        <form id="loginForm">
                            <input type="text" id="username" placeholder="用户名" required>
                            <input type="password" id="password" placeholder="密码" required>
                            <button type="submit">登录</button>
                        </form>
                        <p class="hint">默认凭证: admin / admin123</p>
                    </div>
                    <script>
                        document.getElementById('loginForm').addEventListener('submit', async (e) => {
                            e.preventDefault();
                            const username = document.getElementById('username').value;
                            const password = document.getElementById('password').value;
                            const errorEl = document.querySelector('.error');
                            errorEl.style.display = 'none';
                            try {
                                const resp = await fetch('/api/auth/login', {
                                    method: 'POST',
                                    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                                    body: 'username=' + encodeURIComponent(username) + '&password=' + encodeURIComponent(password)
                                });
                                const data = await resp.json();
                                if (data.success) {
                                    localStorage.setItem('authToken', data.token);
                                    localStorage.setItem('username', data.username);
                                    window.location.href = '/agent-trace.html';
                                } else {
                                    errorEl.textContent = data.message || '登录失败';
                                    errorEl.style.display = 'block';
                                }
                            } catch (e) {
                                errorEl.textContent = '网络错误，请重试';
                                errorEl.style.display = 'block';
                            }
                        });
                    </script>
                </body>
                </html>
                """;
            resp.getWriter().write(html);
        }
    }
}