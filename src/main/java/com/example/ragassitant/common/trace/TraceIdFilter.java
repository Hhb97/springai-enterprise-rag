package com.example.ragassitant.common.trace;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class TraceIdFilter implements Filter {

    public static final String TRACE_ID_KEY = "traceId";

    /**
     * 会话 id 的 MDC 键，由 {@code AgentAppService} 在处理请求时写入。
     *
     * <p>用途：Spring AI 原生 Tool Calling 在框架内部循环里执行工具，回调拿不到会话上下文。
     * 而工具执行是在<b>同一个请求线程</b>上完成的（日志里 NATIVE_TOOL_CALL 和请求同为
     * http-nio-*-exec-N），所以经 MDC 传递即可，不必把 sessionId 硬塞进 LlmPort 的签名。
     */
    public static final String SESSION_ID_KEY = "sessionId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String traceId = req.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }
}