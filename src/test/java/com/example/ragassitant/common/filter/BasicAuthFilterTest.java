package com.example.ragassitant.common.filter;

import com.example.ragassitant.infrastructure.config.AuthProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BasicAuthFilterTest {

    private final AtomicReference<String> userInChain = new AtomicReference<>();
    private final FilterChain chain = (req, resp) -> userInChain.set(MDC.get(BasicAuthFilter.MDC_USER_KEY));

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private static BasicAuthFilter filter(boolean enabled) {
        AuthProperties props = new AuthProperties();
        props.setEnabled(enabled);
        props.setUsername("ops");
        props.setPassword("s3cret");
        return new BasicAuthFilter(props);
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void authDisabledPassesAsAnonymous() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter(false).doFilter(new MockHttpServletRequest("POST", "/ai/agent-chat"), resp, chain);
        assertEquals(200, resp.getStatus());
        assertEquals("anonymous", userInChain.get());
    }

    @Test
    void validCredentialsPassAndSetMdcUserOnlyDuringRequest() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/ai/agent-chat");
        req.addHeader("Authorization", basic("ops", "s3cret"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter(true).doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertEquals("ops", userInChain.get(), "下游（工具治理 / 审计）要能从 MDC 取到当前用户");
        assertNull(MDC.get(BasicAuthFilter.MDC_USER_KEY), "请求结束必须清理，否则线程复用会串身份");
    }

    @Test
    void sessionUserIsReused() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/ai/agent-chat");
        first.addHeader("Authorization", basic("ops", "s3cret"));
        filter(true).doFilter(first, new MockHttpServletResponse(), chain);

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/ai/agent-chat");
        second.setSession(first.getSession());
        MockHttpServletResponse resp = new MockHttpServletResponse();
        userInChain.set(null);
        filter(true).doFilter(second, resp, chain);
        assertEquals(200, resp.getStatus());
        assertEquals("ops", userInChain.get());
    }

    @Test
    void wrongPasswordIs401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/agent/traces/recent");
        req.addHeader("Authorization", basic("ops", "wrong"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter(true).doFilter(req, resp, chain);
        assertEquals(401, resp.getStatus());
        assertNull(userInChain.get(), "认证失败不能进入下游");
    }

    @Test
    void h2ConsoleIsNoLongerWhitelisted() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter(true).doFilter(new MockHttpServletRequest("GET", "/h2-console/login.do"), resp, chain);
        assertEquals(401, resp.getStatus(), "H2 控制台可以直接读写整库，必须登录");
    }

    @Test
    void whitelistedPagePassesWithoutCredentials() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter(true).doFilter(new MockHttpServletRequest("GET", "/index.html"), resp, chain);
        assertEquals(200, resp.getStatus());
    }
}
