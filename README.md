# RAG AI 助手 — Spring Boot 3.4 + Spring AI + Ollama

> 一个生产就绪的企业级 RAG 项目：基于 DDD 四层架构 + Port-Adapter（六边形）解耦，内置 Agent 工具调用（设备查询、能耗聚合）、决策链路审计、Prompt 工程化与启发式路由，适配 qwen2.5 等小模型场景。

---

## ✨ 功能总览

| 模块 | 能力 | 说明 |
|------|------|------|
| **RAG 知识库** | 混合检索 + 版本管理 | 向量 0.7 + 关键词 0.3 权重融合；文档草稿/发布/归档/删除状态机 |
| **Agent 智能体** | 工具调用 + 会话记忆 | 6 个内置工具（设备查询、日/周/月能耗聚合），多轮对话上下文 |
| **启发式路由** | FORCE_EXEC 强制工具执行 | 针对 `{SN} + 近半年/近30天/按月聚合` 等高频模式直接命中，解决 1.5B 小模型不调工具的问题 |
| **决策链路审计** | AgentTrace 持久化 | 每轮 LLM 决策 + 工具入参/出参/耗时/异常全链路落库 H2，可按 Session 回放 |
| **Prompt 工程化** | 配置驱动 | Agent/RAG 角色、最大对话轮次通过 `application.yml` 注入，非硬编码 |
| **横切关注点** | TraceId + 统一返回 | `TraceIdFilter` MDC 贯通；`ApiResponse<T>` 统一封装；`GlobalExceptionHandler` 分级处理 |

---

## 🛠 技术栈

| 分层 | 选型 |
|------|------|
| **语言 / 框架** | JDK 17 · Spring Boot 3.4.2 · Spring AI 1.1.6 |
| **模型服务** | Ollama · `qwen2.5:1.5b`（对话） · `nomic-embed-text`（向量化） |
| **向量库** | SimpleVectorStore（文件持久化 `./uploads/vectorstore.json`，通过 `VectorStorePort` 可无缝切 PGVector/Milvus） |
| **元数据存储** | H2 File Mode（`./data/ragdb`，生产可切 PG），JPA/Hibernate 自动建表 |
| **时序数据（能耗）** | InfluxDB 1.8（`TimeSeriesDbPort` 适配器 + InfluxDB SQL 聚合） |
| **文档解析** | MarkdownDocumentReader · PDF PageReader · TikaDocumentReader（Word/Excel/txt） |

---

## 🏗 架构设计（DDD 四层 + Port-Adapter）

```
src/main/java/com/example/ragassitant/
├── RagAssistantApplication.java
│
├── application/                    # 【应用层】用例编排，不含业务规则
│   └── service/
│       ├── AgentAppService.java        # Agent 多轮工具调用 + 启发式路由（核心编排器）
│       ├── ChatAppService.java         # 普通 RAG 问答（不带 Agent）
│       ├── DocumentAppService.java     # 文档版本管理 + 发布时清理旧向量
│       ├── IngestAppService.java       # 文档切片 → 向量入库
│       └── RagAppService.java          # 混合检索（向量+关键词加权）
│
├── domain/                         # 【领域层】纯 POJO，无 Spring/外部依赖
│   ├── model/                          # Device, EnergyRecord, ToolCall, DocumentChunk, RagAnswer
│   ├── service/                        # HeuristicRouter, ToolCallParser, ToolCallValidator, PromptBuilder, EnergyAggregator
│   └── spi/                            # ← Port（端口接口，被 Application 层依赖）
│       ├── LlmPort.java
│       ├── VectorStorePort.java
│       ├── TimeSeriesDbPort.java
│       ├── DocumentReaderPort.java
│       ├── ConversationStorePort.java
│       ├── ToolRegistryPort.java
│       └── AgentTraceStorePort.java
│
├── infrastructure/                 # 【基础设施层】Adapter（适配器）实现 Port
│   ├── llm/        SpringAiOllamaAdapter        → 实现 LlmPort
│   ├── vector/     SpringAiSimpleVectorAdapter  → 实现 VectorStorePort（含 deleteAll/deleteByDocId）
│   ├── tsdb/       InfluxDB18TimeSeriesAdapter  → 实现 TimeSeriesDbPort
│   ├── session/    InMemoryConversationStore    → 实现 ConversationStorePort
│   ├── agent/      SpringAiToolRegistry + DeviceTools
│   ├── reader/     SpringAiDocumentReader       → 实现 DocumentReaderPort
│   └── persistence/jpa/
│       ├── DocumentVersionJpaEntity / Repository
│       └── AgentTraceJpaEntity / Repository + JpaAgentTraceStoreAdapter
│
├── interfaces/                     # 【接口层】HTTP <-> DTO 转换，无业务逻辑
│   ├── rest/
│   │   ├── ChatController.java         # /ai/chat, /ai/agent/chat, /ai/ingest
│   │   ├── DocumentController.java     # /api/docs/**
│   │   └── AgentTraceController.java   # /api/agent/traces/**（决策链路审计）
│   └── dto/
│       ├── request/ChatRequestDTO.java
│       └── response/ {ChatResponseDTO, ApiResponse<T>}
│
└── common/                         # 横切关注点
    ├── aop/        （预留：AuditLogAspect · RateLimitAspect）
    ├── config/     （预留：LlmConfig · InfluxConfig 模块化）
    ├── exception/  BizException + GlobalExceptionHandler → 统一 ApiResponse<T>
    ├── trace/      TraceIdFilter → MDC 注入 traceId，全局日志/异常可见
    └── utils/      SnUtils
```

**依赖方向**：Interfaces → Application → Domain ← Infrastructure（依赖倒置，Ports 在 Domain，Adapters 在 Infrastructure）。
即 Application 层只依赖 Port 接口，完全不知道具体是 SimpleVectorStore 还是 PGVector，也不知道底层用的是 Ollama 还是其他大模型服务。

---

## 🚀 快速开始

### 1. 环境准备

```bash
# 启动 Ollama（模型按需拉取）
ollama pull qwen2.5:1.5b
ollama pull nomic-embed-text

# 修改 application.yml 的 Ollama / InfluxDB 地址
vim src/main/resources/application.yml
```

### 2. 构建运行

```bash
# 编译打包
mvn clean package -DskipTests

# 启动（默认 8900）
java -jar target/rag-assistant-1.0.0.jar

# 构建知识库（一次性加载 classpath:docs/ 所有文档到向量库）
curl -X POST http://localhost:8900/ai/ingest

# 页面入口
http://localhost:8900/

# H2 控制台（开发环境）
http://localhost:8900/h2-console   # jdbc:h2:file:./data/ragdb   sa/<空密码>
```

### 3. 关键配置项（application.yml）

```yaml
# ===== LLM 模型 =====
spring.ai.ollama:
  base-url: http://192.168.221.130:11434
  chat.model: qwen2.5:1.5b
  chat.options: { temperature: 0.3, num-predict: 1024 }
  embedding.model: nomic-embed-text

# ===== 能耗数据（InfluxDB 1.8） =====
influxdb:
  url: http://192.168.221.130:8086
  username: influxdb
  password: 123456
  database: acucloud-dev

# ===== RAG 检索 =====
rag:
  top-k: 3
  hybrid-search: { keyword-weight: 0.3, vector-weight: 0.7 }

# ===== Prompt 工程化（可热更新） =====
prompt:
  agent.role: 你是智能运维助手，支持设备查询、能耗分析与知识库问答
  agent.max-history: 20
  rag.role: 你是专业的后端管理平台 AI 助手

storage.upload-dir: ./uploads
```

---

## 📬 API 清单

### ① 对话 & 知识库

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET`  | `/` | 聊天页面（纯前端 demo，内置会话 ID） |
| `POST` | `/ai/chat` | 普通 RAG 问答（不带 Agent） |
| `POST` | `/ai/agent/chat` | **Agent 模式**（工具调用 + 启发式路由 + 多轮记忆） |
| `POST` | `/ai/ingest` | 重建知识库：清空向量库 → 加载 `classpath:docs/*.md` |
| `POST` | `/ai/ingest/{versionId}` | 发布单版本入库（删除该文档历史向量 → 切片 → 入库） |
| `GET`  | `/ai/health` | 健康检查（含 LLM / InfluxDB 连通性提示） |

**POST /ai/agent/chat**
```json
{
  "question": "mv-003 近半年能耗，按月聚合，输出月度增长趋势",
  "sessionId": "session-xxx",
  "useRag": true
}
```

### ② 文档 & 版本管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/docs/upload` | 上传文档 → 生成 DRAFT v1 |
| `POST` | `/api/docs/{documentId}/update` | 更新文档 → 生成新版本号 vN+1 |
| `POST` | `/api/docs/versions/{versionId}/publish` | **发布**：DRAFT/ARCHIVED → ACTIVE，自动入库向量库并清理该文档旧向量 |
| `POST` | `/api/docs/versions/{versionId}/archive` | 归档：ACTIVE → ARCHIVED |
| `DELETE` | `/api/docs/{documentId}` | 删除文档（含所有版本 + 清理向量） |
| `DELETE` | `/api/docs/versions/{versionId}` | 删除单版本（若为 ACTIVE 同步清理向量） |
| `GET`  | `/api/docs/list[?status=ACTIVE]` | 文档列表，支持按状态过滤 |
| `GET`  | `/api/docs/search?keyword=xxx` | 文档名模糊搜索 |
| `GET`  | `/api/docs/{documentId}` | 最新版本详情 |
| `GET`  | `/api/docs/{documentId}/versions` | 版本历史 |

### ③ 决策链路审计（AgentTrace）
> 用于排查"模型到底调没调工具"、"工具返回的数据是什么"等线上问题。

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/agent/traces/session/{sessionId}` | 按会话查看完整决策链路（stage=HEURISTIC_TOOL/LLM_DECIDE/TOOL_INVOKE/FINAL） |
| `GET` | `/api/agent/traces/recent?limit=50` | 最近 N 条决策日志（含 durationMs / heuristicHit / toolName） |
| `GET` | `/api/agent/traces/stats` | 近 24h / 7d 调用量统计 |

---

## 🧠 Agent 工作流（小模型友好）

```
用户提问
   │
   ▼
┌──────────────────────────┐
│  HeuristicRouter 匹配？  │  如：mv-003 近半年能耗 → extractSn + {工具:aggregate_energy, month}
└────┬──────────────┬──────┘
     │是             │否
     ▼               ▼
  FORCE_EXEC    ┌──────────────┐
  直接调工具     │ LLM 自主判断 │
     │          └──────┬───────┘
     │                 │有工具调用？
     │            ┌────┴────┐
     │            │否       │是
     │            ▼         ▼
     │      直接返回    调工具 → 结果回灌 LLM → 最多 5 轮
     │            │         │
     └────┬───────┴─────────┘
          ▼
      最终回答 + 会话记忆 + AgentTrace 落库
```

针对 qwen2.5:1.5b 等 7B 以下小模型"看懂意图但不输出 JSON 工具调用"的通病，用 **启发式规则兜底 + LLM 总结** 的方式，高频 SN + 时间类查询的工具调用率可稳定在 100%。

---

## 🧩 内置工具清单（Infrastructure/Agent 层）

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `list_devices` | keyword(可选) | 按名称/SN 模糊查询设备列表 |
| `get_device_detail` | sn(必填) | 单台设备详情 |
| `query_energy_half_year` | sn(必填) | 近半年每日能耗（InfluxDB `GROUP BY time(1d)`） |
| `query_energy_by_days` | sn, days | 最近 N 天每日能耗 |
| `query_energy_by_date_range` | sn, startDate, endDate | 自定义时间范围（ISO8601） |
| `aggregate_energy` | sn, granularity=[week\|month\|quarter\|year] | 按粒度聚合日级能耗，输出同比/环比趋势 |

---

## 🔁 扩展路线（已预留 SPI 口子）

| 优先级 | 方向 | 对应 SPI / 切入点 |
|--------|------|-------------------|
| **P2** | 多模态 + 高级检测（图片/OCR/表格结构识别） | `DocumentReaderPort` 扩展 OCR Adapter |
| **P3** | 熔断降级（LLM/InfluxDB 超时失败率）+ 限流 + 缓存 | `common/aop/` + Resilience4j + Redis |
| **P4** | 向量库升级（PGVector/Milvus） | `VectorStorePort` 新增实现，无需改 Application |
| **P5** | MCP 协议对接（工具注册 + 外部系统暴露） | `ToolRegistryPort` → MCP ToolServer |
| **P6** | 可观测性：LLM 调用延迟/Token 用量/命中率 Metrics | Micrometer + Prometheus 埋点 |

---

## 📄 License

MIT © 2026