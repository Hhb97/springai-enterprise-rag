# RAG AI 助手 — Spring Boot 4.0 + Spring AI 2.0 GA + Ollama

> 企业级 RAG 项目：DDD 四层架构 + Port-Adapter（六边形）解耦，Spring AI 2.0 官方自动 Tool Calling Agent，内置设备查询/能耗聚合工具、决策链路审计、Prompt 工程化与启发式路由前置优化，适配 qwen2.5 等小模型场景。

---

## ✨ 功能总览

| 模块 | 能力 | 说明 |
|------|------|------|
| **RAG 知识库** | 混合检索 + 版本管理 | 向量 0.7 + 关键词 0.3 线性权重融合；基于 Spring AI 2.0 `VectorStoreDocumentRetriever`；文档支持草稿/发布/归档/删除状态机 |
| **Agent 智能体** | 自动 Tool Calling + 会话记忆 | 6 个内置 `@Tool` 注解工具，通过 `ChatClient.tools()` 自动完成工具解析、调用、消息回填、多轮循环；支持多轮对话上下文 |
| **启发式路由** | FORCE_EXEC 前置执行 | 列出设备、近半年能耗等高频纯工具类问题，直接命中跳过向量检索与 Agent 循环 |
| **决策链路审计** | AgentTrace 持久化 | 每轮 LLM 决策、工具入参/出参、耗时、异常全链路落库 H2，可按 Session 回放 |
| **Prompt 工程化** | 配置驱动 | Agent/RAG 角色、最大对话轮次通过 `application.yml` 注入 |
| **横切关注点** | TraceId + 统一返回 + 异常分级 | `TraceIdFilter` MDC 贯通；`ApiResponse<T>` 统一封装；静态资源未找到按 WARN 单列处理 |

---

## 🛠 技术栈

| 分层 | 选型 | 注意事项 |
|------|------|---------|
| **语言 / 框架** | JDK 21 · Spring Boot 4.0.7 GA · Spring Framework 7.0.8 | JDK 版本要求 21+ |
| **AI 框架** | Spring AI 2.0.0 GA（groupId: `org.springframework.ai`） | 包含 `spring-ai-rag` 模块；`ChatClient.tools()` 提供官方 Agent 自动循环 |
| **模型服务** | Ollama · `qwen2.5:1.5b`（对话） · `nomic-embed-text`（向量化） | Embedding 模型需显式配置，避免走框架默认值 |
| **向量库** | SimpleVectorStore，文件持久化路径 `./uploads/vectorstore.json` | 通过 `VectorStorePort` 可无缝切换 PGVector/Milvus 等实现 |
| **元数据存储** | H2 File Mode（`./data/ragdb`）· Hibernate 7.2 | 生产环境可替换为 PostgreSQL；需显式配置 Hibernate 方言 |
| **时序数据（能耗）** | InfluxDB 1.8 | 通过 `TimeSeriesDbPort` 适配器 + SQL 聚合查询 |
| **文档解析** | MarkdownDocumentReader · PDF PageReader · TikaDocumentReader | 支持 Markdown/PDF/Word/Excel/纯文本入库 |

---

## 🏗 架构设计（DDD 四层 + Port-Adapter）

```
src/main/java/com/example/ragassitant/
├── RagAssistantApplication.java
│
├── application/                    # 【应用层】用例编排，不含业务规则
│   └── service/
│       ├── AgentAppService.java        # 核心编排器：FORCE_EXEC 前置 → RAG 检索（失败降级）→ Spring AI 2.0 官方自动 Tool Calling
│       ├── ChatAppService.java         # 普通 RAG 问答（不带 Agent）
│       ├── DocumentAppService.java     # 文档版本管理 + 发布时清理旧向量
│       ├── IngestAppService.java       # 文档切片 → 向量入库
│       └── RagAppService.java          # 混合检索（向量 + 关键词线性加权）
│
├── domain/                         # 【领域层】纯 POJO，无 Spring/外部依赖
│   ├── model/                          # Device, EnergyRecord, ToolCall, DocumentChunk, RagAnswer
│   ├── service/                        # HeuristicRouter, ToolCallParser, ToolCallValidator, PromptBuilder, EnergyAggregator
│   └── spi/                            # ← Port（端口接口，被 Application 层依赖）
│       ├── LlmPort.java                    # chat · chatWithRawMessages · chatWithAutoToolCalling
│       ├── VectorStorePort.java
│       ├── TimeSeriesDbPort.java
│       ├── DocumentReaderPort.java
│       ├── ConversationStorePort.java
│       ├── ToolRegistryPort.java
│       └── AgentTraceStorePort.java
│
├── infrastructure/                 # 【基础设施层】Adapter 实现 Port
│   ├── llm/        SpringAiOllamaAdapter        # LlmPort 实现；ChatClient + 自动 Tool Calling
│   ├── vector/     SpringAiSimpleVectorAdapter  # VectorStorePort 实现；支持 deleteAll / deleteByDocId
│   ├── tsdb/       InfluxDB18TimeSeriesAdapter  # TimeSeriesDbPort 实现
│   ├── session/    InMemoryConversationStore    # ConversationStorePort 实现
│   ├── agent/      DeviceTools.java             # 6 个 @Tool + @ToolParam 注解方法
│   │              SpringAiToolRegistry.java     # 工具调用通用注册与执行
│   ├── reader/     SpringAiDocumentReader       # DocumentReaderPort 实现
│   ├── config/     VectorStoreInfraConfig.java  # SimpleJsonVectorStore 持久化配置
│   └── persistence/jpa/
│       ├── DocumentVersionJpaEntity / Repository
│       └── AgentTraceJpaEntity / Repository + JpaAgentTraceStoreAdapter
│
├── interfaces/                     # 【接口层】HTTP <-> DTO 转换，无业务逻辑
│   ├── rest/
│   │   ├── ChatController.java         # /ai/chat, /ai/agent/chat, /ai/ingest
│   │   ├── DocumentController.java     # /api/docs/**
│   │   └── AgentTraceController.java   # /api/agent/traces/**
│   └── dto/
│       ├── request/ChatRequestDTO.java
│       └── response/ {ChatResponseDTO, ApiResponse<T>}
│
└── common/                         # 横切关注点
    ├── aop/
    ├── config/
    ├── exception/  BizException + GlobalExceptionHandler
    ├── trace/      TraceIdFilter（MDC 注入 traceId）
    └── utils/      SnUtils
```

依赖方向：Interfaces → Application → Domain ← Infrastructure（依赖倒置原则：Ports 定义在 Domain 层，Adapters 实现在 Infrastructure 层）。

---

## 🧠 Agent 工作流

```
用户提问
   │
   ▼
┌───────────────────────────────────┐
│ HeuristicRouter 命中 FORCE_EXEC? │
└────┬─────────────────────────┬────┘
     │是                        │否
     ▼                         ▼
┌────────────────────┐   ┌──────────────────────────────────────────┐
│ FORCE_EXEC 前置执行 │   │ RAG 向量检索（try-catch 异常降级）        │
│ · 跳过向量检索       │   └────────────┬─────────────────────────────┘
│ · 跳过 Agent 循环    │                │
│ · 直接执行工具        │                ▼
│ · LLM 生成最终总结   │   ┌──────────────────────────────────────────┐
└──────┬─────────────┘   │ Spring AI 2.0 ChatClient.tools()         │
       │                 │ · LLM 识别 tool_call                      │
       │                 │ · 反射调用 @Tool 注解方法                  │
       │                 │ · 自动回填 ToolMessage                    │
       │                 │ · 最多 5 轮循环直到最终纯文本              │
       │                 └────────────┬─────────────────────────────┘
       └───────────────┬──────────────┘
                       ▼
          最终回答 + 会话记忆 + AgentTrace 持久化
```

设计说明：
- FORCE_EXEC 前置命中场景（列出设备、近 N 天能耗等）：不触发向量检索、不进入官方 Agent 循环，延迟最低，对 Embedding 模型是否可用无依赖
- Spring AI 2.0 官方自动循环：用于知识库问答、复杂多工具组合调用场景

---

## 🚀 快速开始

### 1. 环境准备

```bash
# JDK 21
java -version

# Ollama 模型
ollama pull qwen2.5:1.5b
ollama pull nomic-embed-text

# 调整配置
# 编辑 src/main/resources/application.yml 中的 Ollama / InfluxDB 地址
```

### 2. 构建运行

```bash
# 编译打包
mvn clean package -DskipTests

# 启动（默认端口 8900）
java -jar target/rag-assistant-1.0.0.jar

# 构建知识库（加载 classpath:docs/ 下所有文档）
curl -X POST http://localhost:8900/ai/ingest

# 聊天页面
http://localhost:8900/

# H2 控制台
http://localhost:8900/h2-console
# JDBC URL: jdbc:h2:file:./data/ragdb
# 用户名: sa  密码: 空

# Actuator
curl http://localhost:8900/actuator/health
```

### 3. 核心配置项（application.yml）

```yaml
server:
  port: 8900

spring:
  application:
    name: rag-assistant

  datasource:
    url: jdbc:h2:file:./data/ragdb;AUTO_SERVER=TRUE
    username: sa
    password:
  h2:
    console:
      enabled: true

  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect

  ai:
    ollama:
      base-url: http://192.168.221.130:11434
      default-chat-options:
        model: qwen2.5:1.5b
        temperature: 0.3
        num-predict: 1024
      chat:
        options:
          model: qwen2.5:1.5b
          temperature: 0.3
          num-predict: 1024
      embedding:
        options:
          model: nomic-embed-text
      default-embedding-options:
        model: nomic-embed-text

influxdb:
  url: http://192.168.221.130:8086
  username: influxdb
  password: 123456
  database: acucloud-dev

rag:
  top-k: 3
  hybrid-search:
    keyword-weight: 0.3
    vector-weight: 0.7

prompt:
  agent:
    role: 你是智能运维助手，支持设备查询、能耗分析与知识库问答
    max-history: 20
  rag:
    role: 你是专业的后端管理平台 AI 助手

storage:
  upload-dir: ./uploads
```

---

## 📬 API 清单

### ① 对话 & 知识库

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET`  | `/` | 聊天页面 |
| `POST` | `/ai/chat` | 普通 RAG 问答（不带 Agent） |
| `POST` | `/ai/agent/chat` | Agent 模式（工具调用 + 启发式路由 + 多轮记忆） |
| `POST` | `/ai/ingest` | 重建知识库：清空向量库 → 加载 `classpath:docs/*.md` |
| `POST` | `/ai/ingest/{versionId}` | 发布单版本：删除该文档历史向量 → 切片 → 入库 |
| `GET`  | `/ai/health` | 健康检查 |

`POST /ai/agent/chat` 请求示例：
```json
{
  "question": "列出所有设备",
  "sessionId": "session-xxx",
  "useRag": true
}
```

---

### ② 文档 & 版本管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/docs/upload` | 上传文档 → 生成 DRAFT v1 |
| `POST` | `/api/docs/{documentId}/update` | 更新文档 → 生成新版本号 vN+1 |
| `POST` | `/api/docs/versions/{versionId}/publish` | 发布：DRAFT/ARCHIVED → ACTIVE，自动入库向量库并清理旧向量 |
| `POST` | `/api/docs/versions/{versionId}/archive` | 归档：ACTIVE → ARCHIVED |
| `DELETE` | `/api/docs/{documentId}` | 删除文档（含所有版本 + 清理向量） |
| `DELETE` | `/api/docs/versions/{versionId}` | 删除单版本（若为 ACTIVE 同步清理向量） |
| `GET`  | `/api/docs/list[?status=ACTIVE]` | 文档列表，支持按状态过滤 |
| `GET`  | `/api/docs/search?keyword=xxx` | 文档名模糊搜索 |
| `GET`  | `/api/docs/{documentId}` | 最新版本详情 |
| `GET`  | `/api/docs/{documentId}/versions` | 版本历史 |

---

### ③ 决策链路审计（AgentTrace）

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/agent/traces/session/{sessionId}` | 按会话查看完整决策链路 |
| `GET` | `/api/agent/traces/recent?limit=50` | 最近 N 条决策日志 |
| `GET` | `/api/agent/traces/stats` | 调用量统计 |

---

## 🧩 内置工具清单

工具定义于 `DeviceTools.java`，通过 `@Tool` + `@ToolParam` 标准注解声明，Spring AI 2.0 `ChatClient.tools()` 自动注册。

| 工具名 | 参数 | 适用场景 |
|--------|------|---------|
| `list_devices` | keyword（可选） | 设备清单、按名称/SN 模糊搜索 |
| `get_device_detail` | sn（必填） | 单台设备详情查询 |
| `query_energy_half_year` | sn（必填） | 近半年每日能耗统计 |
| `query_energy_by_days` | sn, days（必填） | 最近 N 天每日能耗统计 |
| `query_energy_by_date_range` | sn, startDate, endDate（必填） | 自定义日期范围能耗查询 |
| `aggregate_energy` | sn, granularity（day/week/month/quarter/year） | 按粒度重聚合能耗，输出同比/环比趋势 |

---

## ❗ 常见故障排查

### 1. 启动报错：`NoClassDefFoundError: org/springframework/core/retry/RetryTemplate`
- 原因：当前 JDK / Spring Boot 版本组合低于 Spring AI 2.0 要求
- 解决：确认使用 JDK 21 + Spring Boot 4.0.x

### 2. 启动报错：`Unable to determine Dialect without JDBC metadata`
- 原因：Hibernate 7.2 不再自动推断方言
- 解决：配置 `spring.jpa.properties.hibernate.dialect = org.hibernate.dialect.H2Dialect`

### 3. Agent 调用报错：`HTTP 404 - model "mxbai-embed-large" not found`
- 原因：未显式指定 Embedding 模型，使用了框架默认值
- 解决：
  - Ollama 拉取模型：`ollama pull nomic-embed-text`
  - 配置 `spring.ai.ollama.embedding.options.model = nomic-embed-text`

### 4. 日志出现 `.well-known/appspecific/com.chrome.devtools.json` 告警
- 原因：Chrome 浏览器自动发起的标准端点探测
- 说明：应用已按 WARN 单列处理，不影响业务功能，可忽略

---

## 🔁 扩展方向

| 方向 | 对应 SPI / 切入点 |
|------|-------------------|
| 向量库升级（PGVector/Milvus/RedisSearch） | `VectorStorePort` 新增实现 |
| 多模态 + OCR（图片/扫描件入库） | `DocumentReaderPort` 扩展 OCR Adapter |
| 熔断降级 + 限流 + 缓存 | `common/aop/` + Resilience4j + Redis |
| MCP 协议对接（工具注册 + 外部系统暴露） | `ToolRegistryPort` + DeviceTools → MCP ToolServer |
| 可观测性：LLM 延迟/Token 用量/命中率 Metrics | Micrometer + Prometheus（已有 Actuator 基础） |

---

## 📄 License

MIT © 2026