package com.example.ragassitant;

import com.example.ragassitant.application.service.AgentAppService;
import com.example.ragassitant.application.service.QueryRewriteService;
import com.example.ragassitant.application.service.RagAppService;
import com.example.ragassitant.domain.spi.LlmPort;
import com.example.ragassitant.infrastructure.resilience.ResilientLlmPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文装配验证。
 *
 * <p>Query 改写引入了<b>第二个 LlmPort bean</b>，并给主实现加了 {@code @Primary}；
 * 同时 RagAppService / AgentAppService 的构造器都变了。这类装配问题单元测试一概抓不到，
 * 只有真正把 Spring 上下文拉起来才能发现（Phase A 就吃过「单测全绿但线上是坏的」的亏）。
 *
 * <p>用内存 H2 + 空的向量库目录，避免依赖磁盘上的既有状态；模型 bean 只是构造，不发网络请求。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ctxtest;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "storage.upload-dir=./target/ctx-test-uploads",
        "spring.main.web-application-type=none"
})
class ApplicationContextTest {

    @Autowired
    private LlmPort primaryLlmPort;

    @Autowired
    @Qualifier("queryRewriteLlmPort")
    private LlmPort rewriteLlmPort;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private RagAppService ragAppService;

    @Autowired
    private AgentAppService agentAppService;

    @Test
    @DisplayName("上下文能启动，改造后的构造器全部装配成功")
    void contextLoads() {
        assertNotNull(ragAppService);
        assertNotNull(agentAppService);
        assertNotNull(queryRewriteService);
    }

    @Test
    @DisplayName("存在两个 LlmPort，按类型注入解析到 @Primary 的主实现")
    void twoLlmPortsCoexistWithoutAmbiguity() {
        assertNotNull(primaryLlmPort);
        assertNotNull(rewriteLlmPort);
        // 注入给业务层的必须是带熔断/降级的装饰器，直接拿到裸适配器就绕过了韧性层
        assertInstanceOf(ResilientLlmPort.class, primaryLlmPort);
        assertInstanceOf(ResilientLlmPort.class, rewriteLlmPort);
        assertNotSame(primaryLlmPort, rewriteLlmPort,
                "改写用的应是独立实例，否则 rag.query-rewrite.backend 配置不起作用");
    }
}
