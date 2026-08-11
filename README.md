# 智能运维 RAG + Agent 一体化平台

> 基于 Spring Boot 4.0.7 + Spring AI 2.0.0 GA + JDK 21 的企业级知识库问答与智能 Agent 平台。采用 **DDD 四层 + 六边形（Port-Adapter）** 架构，内置 **五种工作模式**：原生 Tool Calling Agent / 手动 ReAct 循环 / **Plan-then-Execute 规划模式** / 对比模式 / 纯 RAG 知识库。具备**混合检索**（向量 + 关键词线性加权融合）、**启发式路由 + 影子路由比对**、**Prompt Injection 防护**、**文档版本状态机**、**决策链路全审计**、**Basic Auth + Session 登录** 等完整能力，同时适配本地 Ollama（离线）与阿里云百炼 DashScope（云端）双后端。

---

## ✨ 核心能力一览

| 模块 | 能力说明 | 关键实现细节 |
|------|---------|-------------|
| **🧠 RAG 混合检索** | 向量检索 + 关键词检索线性加权融合 | 权重：向量 0.7 / 关键词 0.3；6 组业务同义词扩展（账单↔计费等）+ 中文 N-gram 滑窗分词；标题 headingPath 额外加权；**向量层全低于阈值时关键词层不许单独救回**；**检索为空直接返回不调 LLM** |
| **✂️ 语义切分** | Markdown 结构感知 + 四级分隔符递归降级 | `OverlappingTextSplitter` 按段落→换行→句末→字符逐级切分；`MarkdownSectionParser` 保留完整标题路径 `A > B > C`；Chunk 正文可前置章节前缀注入 embedding |
| **🔁 查询改写** | 多轮省略式问题还原（独立 LLM 后端） | 默认 Ollama `qwen3.7-plus`（10s 超时守护，可独立走 DashScope）；改写结果**仅用于检索，不污染对话历史**；改写关闭时退回启发式历史拼接；**纯 RAG 链路写入会话历史，改写可拿到上下文** |
| **🤖 原生 Tool Calling** | Spring AI 2.0 `ChatClient.tools()` 官方循环 | `@Tool` 注解自动注册 8 个工具（设备/能耗/时间/天气）；框架内部最多 5 轮循环；附带 **TextToolCallParser** 兜底识别模型把调用写成文本 JSON 的场景 |
| **🧠 手动 ReAct 循环** | Thought → Action → Observation 全流程 | `ReActAgentAppService` 手写 ReAct 循环；每一步严格 JSON 格式解析；支持 **SSE 流式推送**（step_start → thought_delta → action_start → final_delta → done） |
| **📋 Plan-then-Execute** | 先规划 → 并行执行 → 一次综合 | `PlanThenExecuteAppService` 三个阶段：Planner 一次产出完整计划（ReWOO `$N` 占位符引用后续步骤产物）→ Executor 拓扑调度、无依赖步骤并行 → Solver 一次 LLM 综合全部 Observation；普通版 + SSE 流式双入口 |
| **🔀 对比模式** | Agent vs ReAct 同请求双跑 | `POST /ai/compare-chat` 一份请求同时跑两条链路，返回 agent / react 两份完整结果（answer、sources、steps、elapsedMs），用于效果评估 |
| **⚡ 启发式路由** | FORCE_EXEC / RECOMMEND_LLM 双策略 | 列出设备、近 N 天能耗等高频纯工具场景，FORCE_EXEC 直接跳检索跳 LLM；**领域实词与追问标记分离判定，防止"如何创建用户呢"被误判为同话题** |
| **👥 影子路由** | 正则 vs LLM 异步灰盒比对 | 每次请求异步多问一次「LLM 会选哪个工具」，7 种判定结果写入 AgentTrace；**复用 QueryRewrite 轻量后端**，验证完可一键关闭或抽样 |
| **🛡️ 安全防护** | Prompt Injection 拦截 + 四级幻觉防线 | `PromptInjectionDetector` 纯代码模式匹配（不依赖模型判断）：忽略指令 / 角色篡改 / 规则绕过三类攻击；幻觉防护：空检索直接拒绝 + 相似度阈值 + 保底块标记 + 低置信度提示词降级 |
| **📚 文档版本管理** | DRAFT → ACTIVE → ARCHIVED 状态机 | **发布时才入库向量**；增量更新只清该文档旧向量，不动全库；REST 接口覆盖上传/更新/发布/归档/删除/入库 |
| **🔍 决策链路审计** | 每轮 Agent 决策全落库 | LLM 决策、工具入参/出参、耗时、异常；按 Session 回放；内置可视化页面 `agent-trace.html` |
| **🔐 登录认证** | Basic Auth + Session | `AuthController` 登录/登出/状态查询 + 根路径重定向；`BasicAuthFilter` 拦截未登录请求，页面请求返回内嵌登录页、API 请求返回 401 JSON |
| **📊 可观测性** | 全链路 TraceId | `TraceIdFilter` MDC 贯通；日志 `[traceId=xxx]` 对齐；Actuator + Prometheus 就绪 |

---

## 🛠 技术栈

| 分层 | 选型 | 版本要求 |
|------|------|---------|
| 语言 / 框架 | JDK 21 · Spring Boot **4.0.7** GA · Spring Framework 7.0.8 | JDK 必须 21+ |
| AI 框架 | Spring AI **2.0.0** GA（groupId `org.springframework.ai`） | 含 `spring-ai-rag` 模块（预留后续 BM25/RRF 升级接口） |
| 聊天模型 | 三选一：Ollama `qwen2.5:1.5b` / DashScope `qwen3.7-plus` / 任意 OpenAI 兼容协议 | 支持混用：聊天/改写/向量各选各的后端 |
| 向量化模型 | 推荐 Ollama `bge-m3`（默认）或 `nomic-embed-text`，或 DashScope `text-embedding-v3` | docker-compose 默认 `bge-m3` |
| 向量库 | SimpleVectorStore + JSON 持久化（`uploads/vectorstore.json`） | 通过 `VectorStorePort` 可无缝切换 PGVector/Milvus/RedisSearch |
| 元数据存储 | H2 File Mode + Hibernate 7.2 | 生产可替换 PostgreSQL，方言已显式配置 `H2Dialect` |
| 时序数据 | InfluxDB 1.8 SQL 聚合查询 | 通过 `TimeSeriesDbPort` 适配；未连接时自动回退模拟数据 |
| 前端 | 原生 HTML + JS（零构建） | 聊天 / 知识库管理 / 决策审计 三页内置，会话与模式记忆存 localStorage |

> ⚠️ **关于 BM25 / RRF**：Spring AI 2.0.0 GA 已将 `BM25Retriever` + `EnsembleRetriever` 从 GA 包移除（目前仅在 M 系列里程碑中），本项目采用自定义的**线性加权融合检索**替代（六层防线设计见下文）。`pom.xml` 已引 `spring-ai-rag` 做预留，等后续版本加回 BM25/RRF 后，只需替换 `RagAppService.hybridSearch()` 内部实现即可，接口层零改动。

---

## 🏗 架构设计（DDD 四层 + Port-Adapter 六边形）

```
src/main/java/com/example/ragassitant/
│
├── application/                    # 【应用层】用例编排（业务服务 + 认证控制器）
│   ├── service/
│   │   ├── AgentAppService             # 三路编排：闲聊短路 → FORCE_EXEC → 原生 Tool Calling
│   │   ├── ReActAgentAppService        # 手动 ReAct 循环：普通 + SSE 流式 双入口
│   │   ├── PlanThenExecuteAppService   # Planner → Executor(并行) → Solver 三段式：普通 + SSE 流式
│   │   ├── RagAppService               # 混合检索（线性加权 + 幻觉防护四级闸门）
│   │   ├── IngestAppService            # 文件分派 → 切分 → 向量入库（全量/单文档增量）
│   │   ├── DocumentAppService          # 文档版本状态机 + 发布时向量清理
│   │   ├── QueryRewriteService         # 多轮省略问句改写 + 超时降级 + 长度门禁
│   │   └── ShadowToolRouter            # 影子路由：LLM vs 正则异步比对，7 类判定
│   └── controller/
│       └── AuthController.java         # /api/auth/login|logout|status + 根路径重定向
│
├── domain/                         # 【领域层】纯 POJO，零 Spring / 外部依赖
│   ├── model/                          # Device, EnergyRecord, ToolCall, PlanStep, RagAnswer, ChunkMetaKeys
│   ├── service/                        # HeuristicRouter, TextToolCallParser, PromptBuilder,
│   │                                   # PromptInjectionDetector, ToolCallValidator, EnergyAggregator
│   └── spi/                            # ← Port（端口接口，被 Application 层依赖）
│       ├── LlmPort                     # chat / chatWithRawMessages / chatWithAutoToolCalling / chatStream*
│       ├── VectorStorePort / TimeSeriesDbPort
│       ├── DocumentReaderPort / ConversationStorePort
│       └── ToolRegistryPort / AgentTraceStorePort
│
├── infrastructure/                 # 【基础设施层】Adapter 实现 Port
│   ├── llm/        SpringAiChatAdapter        # ChatClient + 自动 Tool Calling（@Primary）
│   ├── vector/     SpringAiSimpleVectorAdapter  # deleteAll / deleteByDocId 增量清理
│   ├── tsdb/       InfluxDB18TimeSeriesAdapter  # 内置 8 台演示设备 + 按日聚合查询 + 模拟数据回退
│   ├── session/    InMemoryConversationStore    # 多轮会话记忆 + TTL 定时清理（@Scheduled）
│   ├── agent/
│   │   ├── DeviceTools.java             # 8 个 @Tool 方法（设备/能耗/时间/天气）
│   │   ├── ToolCatalog.java             # 工具清单唯一真相源（ReAct / Plan / 影子路由提示词用）
│   │   ├── SpringAiToolRegistry.java    # ToolRegistryPort 实现（反射调用 + 参数校验）
│   │   └── TracingToolCallback.java     # 原生 Tool Calling 触发时打 trace
│   ├── reader/     SpringAiDocumentReader       # md/pdf/word/excel；MarkdownSectionParser + OverlappingTextSplitter
│   ├── config/     LlmBackendConfig（双后端 + 改写独立后端 + 配置总览打印）/
│   │               VectorStoreInfraConfig / AgentProperties / AuthProperties / DomainServiceConfig
│   └── persistence/jpa/
│       ├── DocumentVersionJpaEntity + Repository（文档状态机）
│       └── AgentTraceJpaEntity + Repository + JpaAgentTraceStoreAdapter
│
├── interfaces/                     # 【接口层】HTTP <-> DTO，零业务逻辑
│   └── rest/
│       ├── ChatController.java         # /ai/chat, /ai/agent-chat, /ai/react-agent-chat,
│       │                               # /ai/plan-chat, /ai/*-stream, /ai/compare-chat, /ai/ingest*, /ai/health
│       ├── DocumentController.java     # /api/docs/**（版本状态机 + 加库 + 重建）
│       └── AgentTraceController.java   # /api/agent/traces/**（审计 + 影子路由统计）
│
└── common/                         # 横切关注点
    ├── trace/      TraceIdFilter（MDC 注入 + X-Trace-Id 透传）
    ├── filter/     BasicAuthFilter（可开关 Basic 认证 + Session + 内嵌登录页）
    ├── utils/      SnUtils（SN 归一化去空格/括号）
    └── exception/  BizException + GlobalExceptionHandler
```

**依赖方向**：`Interfaces → Application → Domain ← Infrastructure`（依赖倒置：Ports 定义在 Domain 层，Adapters 实现在 Infrastructure 层）。

---

## 💡 核心逻辑详解一：Agent 五种实现 + 三路编排

### 1. 五种 Agent 实现对比

| 模式 | 实现类 | 循环方式 | 适用模型 | 流式输出 |
|------|-------|---------|---------|---------|
| **Spring AI 原生 Tool Calling** | `AgentAppService` | `ChatClient.tools()` 框架内部循环 | 支持 function calling：qwen3.7-plus / gpt-4o-mini 等 | ❌ 返回最终结果 |
| **手动 ReAct 循环** | `ReActAgentAppService` | 手写 `for (step 1..maxSteps)` + 严格 JSON 解析 | 不支持 function calling 的小模型：qwen2.5:1.5b 等 | ✅ SSE 逐 thought/action 推送 |
| **Plan-then-Execute** | `PlanThenExecuteAppService` | Planner 一次规划 → Executor 并行执行 → Solver 一次综合 | 中等模型以上效果更佳（需要一次产出多步计划） | ✅ SSE 逐步骤 + 最终 token 流式 |
| **对比模式** | 后端 `compareChat` 双跑 | 同请求并行调 `AgentAppService` + `ReActAgentAppService` | 任意 | ❌ 返回两者完整 JSON |
| **纯 RAG 知识库** | `RagAppService` | 改写 → 混合检索 → 幻觉四级闸门 → 单次 LLM | 任意 | ❌ 返回 answer + sources |

**Plan-then-Execute 与 ReAct 的本质区别**：

```
                Planner(一次规划)    Executor(并行执行)     Solver(一次综合)
  ReAct         ✗ 逐轮思考           ✗ 每轮一个工具          ✗ 每轮一次 LLM
  Plan-Exec     一次 LLM 调用        无依赖步骤并行           一次 LLM 调用
```

```java
// ReWOO 思路：Planner 用 $N 占位符引用后续步骤产物，规划时无需真的看到中间结果
{
  "reasoning": "先查清单拿到 SN，再按 SN 查能耗",
  "steps": [
    {"step": 1, "tool": "list_devices", "arguments": {}, "depends_on": []},
    {"step": 2, "tool": "query_energy_half_year", "arguments": {"sn": "$1"}, "depends_on": [1]}
  ]
}
```

核心收益：无依赖的工具步骤并行执行（线程池上限 4），**墙钟时间 ≈ 最慢那一步而非步骤之和**；Observation 一次性交给 Solver，不给模型逐轮回灌中间结果，省 token。

### 2. AgentAppService：请求必走三路径之一（且仅走一条）

```
用户提问
  │
  ├─① 闲聊短路（你好/谢谢/你是谁/再见...）
  │    → handleSmallTalk()：不带历史、不带检索、不带工具，纯对话
  │
  ├─② Prompt Injection 检测（忽略指令/角色篡改/规则绕过）
  │    → 拦截，返回固定拒绝语
  │
  ├─③ 启发式命中 FORCE_EXEC 策略 + 非纯知识库模式
  │    → handleForcedTool()：直接调工具 + LLM 总结，跳过向量检索
  │
  └─④ 其他所有情况
       → handleAutoAgent()：
           a. RAG 混合检索（异常降级不阻断；保底块直接丢弃）
           b. 若策略为 RECOMMEND_LLM：把启发式选择作「建议」附到提示词末尾
           c. Spring AI 2.0 ChatClient.tools() 内部循环（最多 5 轮）
           d. 弱模型兜底：模型把工具调用写成文本 JSON → TextToolCallParser 补执行
```

三条路径统一由 `finish()` 收口：记 FINAL trace → 写会话历史 → 构造响应，避免各路径不一致。

### 3. 两段式路由决策：改写优先、拼接兜底

```
原问题 route → 未命中且有多轮历史 → Query 改写 → 改写后再 route → 仍未命中要调工具?
   │
   ├─ 改写命中：改写结果直接用于检索，工具建议来自改写后的意图
   │
   └─ 改写关闭：退回历史拼接启发式 routeByHistoryConcat()，
        拼接前先用 isLikelySameToolTopic() 判定是否同话题
```

【踩过的坑】上一轮「mv-005 的呢」查能耗，下一轮问「如何创建用户呢？」——若把"呢""如何"也算作同话题信号，拼接历史会继承 mv-005，把知识库问题劫持成能耗查询，还跳过向量检索。因此 **领域实词 vs 追问标记必须分离判定**：

1. 命中明确换话题/闲聊 → false
2. 命中领域实词（设备/能耗/按月/近30天等）→ true  ← 唯一正向确认信号
3. 只命中追问标记（呢/吗/如何/那个呢）：
   - 剥掉标记后残长 ≤2 字（如"那个呢"→""）→ 是纯代词追问，true
   - 否则（如"如何创建用户呢"→"创建用户"）→ 是新问题，false

### 4. 闲聊为什么必须前置？

这是从真实线上事故抠出来的防线：用户第一句「列出所有设备」（正确回答），第二句只说「你好」→ 走 RAG 检索必然召回电能质量 FAQ → FAQ 以「知识库参考内容」名义拼进提示词 → 模型回「我已经阅读了知识库中的内容…」并把 FAQ 整段总结。用户看到的是一次没人问过的知识库问答。

所以闲聊必须在 **改写 / 路由 / 检索** 三者之前一起短路。纯 RAG 链路、ReAct 链路、Plan 链路全部同步前置短路。

### 5. 影子路由：为架构升级积累决策依据

```
┌─────────────────────────────────────────────────────────────┐
│  ShadowToolRouter（异步、不阻塞、异常不外抛）                  │
│                                                             │
│  用户提问 → 启发式路由选了 ToolA                              │
│       │                                                     │
│       └─── 影子线程池（shadow-route）异步多问一次 LLM：        │
│            "根据工具清单和对话历史，该选哪个工具？"              │
│                 │                                           │
│                 ▼                                           │
│            7 类比对结果写入 AgentTrace（SHADOW_ROUTE 阶段）：   │
│              ✅ AGREE          工具+参数都一致                │
│              ⚠️ TOOL_DIFF      选了不同工具（最需要人工看）     │
│              ⚠️ ARGS_DIFF      同工具不同参数（口径差异）      │
│              ⚠️ HEURISTIC_ONLY 正则命中了 LLM 认为不该调       │
│              ⚠️ LLM_ONLY       LLM 认为该调 正则漏了           │
│              ✅ BOTH_NONE      两边都认为不需要调              │
│              ❓ UNKNOWN        LLM 输出解析失败                │
│                                                             │
│  【后端选择】复用 QueryRewrite 的轻量 LLM Bean：快、便宜、非推理 │
└─────────────────────────────────────────────────────────────┘
```

查看统计：`GET /api/agent/traces/shadow-stats`。验证完记得关（`AGENT_SHADOW_ENABLED=false`），它会让每次请求多一次 LLM 调用。

---

## 💡 核心逻辑详解二：Plan-then-Execute 三段式流水线

### 1. 阶段一：Planner（一次 LLM 调用产出完整计划）

用 `PromptBuilder.buildPlanSystemPrompt` 让模型输出严格 JSON 计划（`reasoning` + `steps[]`），解析时做五层防御：

- 剥 ```` ``` ```` 代码块围栏 → 取首个 `{` 到末个 `}` → 反序列化 → **校验步骤数上限（5 步）**
- 任一步骤工具名不在 `ToolCatalog` 中 -> 剔除该步骤（其依赖者随之失效），不会打到未知工具
- 步骤参数值可用 `$N` 引用前面步骤的产物（ReWOO），Executor 执行前替换成该步 Observation（截断 2000 字防爆上下文）

### 2. 阶段二：Executor（拓扑调度 + 无依赖步骤并行）

```
每轮（wave）取「依赖全部完成」的步骤 → 并行执行（线程池上限 4）→ 结果写入 Map<step, observation>
   │
   ├─ 循环依赖 / 引用了不存在的步骤 → 剩余步骤标记失败，提前结束
   │
   └─ 失败隔离：单个工具抛异常不会拖垮同波其他步骤
        → 该步 Observation 记为错误文本，后续步骤照常执行
        → 依赖方经 $N 引用失败步骤时拿到的是错误说明而非空串，Solver 才能如实报告
```

### 3. 阶段三：Solver（一次 LLM 调用综合全部 Observation）

`PromptBuilder.buildPlanSolverSystemPrompt` 把「工具执行结果」+「RAG 资料」一次性给全，只调一次 LLM 生成最终答案。数据有硬约束：设备/能耗数据必须来自工具结果、文档内容必须来自 RAG 资料，禁止编造。

> **何时 Solver 没有工具可依赖**：Planner 输出 `"steps":[]`（如纯知识库问题），则 Solver 仅凭 RAG 上下文回答；Planner 异常时回退为直接 Solver。

---

## 💡 核心逻辑详解三：RAG 检索的幻觉防线

### 1. 为什么不用纯向量检索？中文 RAG 的典型盲区

```
用户问："手动账单流程"        文档写的是："手动计费流程"
          ↓ 纯向量（余弦相似度）↓
     命中 "支付宝对账说明"（语义接近"账单"）   完全漏了 "手动计费流程"（"账单"≠"计费"字面差太远）
```

→ **同义词 / 近义词盲区**，是中文 RAG 纯向量方案必踩的坑。

### 2. 本项目的 RAG 检索六层防线

| # | 防线 | 代码位置 | 解决的问题 |
|---|------|---------|-----------|
| ① | **同义词硬规则扩展** | `RagAppService.SYNONYM_MAP` + `expandChineseKeywords()` | 账单↔计费↔收费↔结算 / 流程↔步骤↔过程 / 用户↔账号↔账户 / 新增↔创建↔添加 / 设备↔装置 / 能耗↔用电↔耗电，固定 6 组高频业务词，零成本零误判 |
| ② | **中文 N-gram 分词兜底** | `expandChineseKeywords()` 滑窗 | 长词拆成 2~4 字滑窗："手动账单流程" → "手动 动账 账单 单流 流程 手动账..."，总有一个子串命中 |
| ③ | **双通道线性加权融合** | `linearWeightHybridSearch()` 融合公式 | `score = 0.7*向量分 + 0.3*关键词分`；向量负责模糊语义，关键词负责精确字面 |
| ④ | **向量层全低，关键词层禁用** | `linearWeightHybridSearch()` 开头判定 | 向量认定全库不相关时，关键词层不许单独把巧合命中救回（中文单字巧合几乎等于噪声） |
| ⑤ | **标题 / 章节路径加权** | `keywordSearchWithScore()` | 命中 title +0.5，命中关键词 +0.15；标题是作者标注的主题，语义最强 |
| ⑥ | **Chunk 正文前缀注入章节** | `SpringAiDocumentReader` + `inject-heading-prefix` | 每行前置 `【章节:计费管理>账单>手动计费】`，embedding 编码时把标题词混进向量 |

> **为什么不接标准 BM25 / RRF？**
> Spring AI 2.0 GA 已移除 BM25Retriever + EnsembleRetriever，硬引会导致 BOM 版本漂移。
> 上面 6 层防线在中文领域 ≈ 带业务权重的 RRF，效果不输给标准 BM25 融合，且零额外依赖，**部署不会炸**。

### 3. 幻觉防护四级闸门（现已全部接入代码）

| 层级 | 机制 | 代码位置 |
|------|------|---------|
| ① **空检索直接拒绝** | 检索结果为 0 块时，**直接返回确定性答案**「知识库中没有找到相关信息」，**不发起 LLM 调用**，从根上断掉「空上下文脑补」 | `RagAppService.askWithOptions()` |
| ② **阈值闸门** | `VectorStoreDocumentRetriever` 显式设置 `similarityThreshold`（YAML 默认 0.40），低于阈值直接丢弃；**设阈值后无命中不再回退无阈值接口** | `RagAppService.buildRetriever()` + `vectorSearch()` |
| ③ **保底块标记** | 即使都低于阈值，`min-keep-chunks` 也会留 N 块，但打上 `belowThreshold=true` 标签 | `RagAppService.applyThreshold()` |
| ④ **提示词降级** | 纯 RAG 链路：全是保底块 → 切换到「不许强行总结」的低置信度提示词；Agent/ReAct/Plan 链路：保底块直接丢弃（还有工具和常识能力，宁可空上下文也不要无关文档） | `askWithOptions()` + 各 Agent 服务的 `retrieveContext()` |

### 4. 文档切分的"不切断语义"原则

切分代码位于 `OverlappingTextSplitter.java`，四级分隔符**从粗到细递归降级**：
```
L0 段落（\n\n\r\n\r\n） → L1 换行（\n） → L2 中文句末（。！？；、） → L3 英文句末 → L4 强制按字符切
```

两个关键设计：
- **Overlap 不是硬切 N 字符**，而是「往回退到完整 atom 边界」——保证 overlap 开头一定是句子开头
- **headingPath 一路透传**：`MarkdownSectionParser` 用栈维护标题路径，每个 chunk 的 metadata 都带 `A > B > C`

> ⚠️ **改完 chunk-size / overlap 后，必须重新构建知识库（`POST /api/docs/ingest-all`）**，否则旧向量库还是旧切分结果。

---

## 🧠 完整工作流

### 1. 纯 RAG 问答（`/ai/chat`）

```
用户提问（"手动账单流程？"）
   │
   ▼
闲聊短路？ ──是──→ 不带上下文直接回答（fallback 兜底）
   │否
   ▼
Query 改写？（多轮场景 + 改写已开启）※ 纯 RAG 链路已写入会话历史
   │是
   ▼
同义词扩展 + 中文 N-gram + headingPath 加权
   │
   ▼
双通道并行检索：向量检索(topK*2) + 关键词全文检索
   │
   ▼
向量层全低于阈值？ ──是──→ 跳过关键词层救回 → 结果可能为空
   │否
   ▼
线性加权融合 + 阈值闸门 + 去重排序 → topK 截断
   │
   ▼
检索为空？ ──是──→ 直接返回「无相关信息」，**不调用 LLM**
   │否
   ▼
全是保底块？ ──是──→ 低置信度提示词（不许强行总结）
   │否
   ▼
正常 RAG 提示词 + System Prompt 注入上下文
   │
   ▼
LLM 生成最终回答 + SourceDoc 返回前端
```

### 2. Agent 自动 Tool Calling（`/ai/agent-chat`）

```
用户提问（"mv-003 近 30 天能耗按月汇总"）
   │
   ▼
① Prompt Injection 检测？ ──是──→ 拦截返回固定拒绝语
   │否
   ▼
② 闲聊短路？ ──是──→ handleSmallTalk()
   │否
   ▼
③ 两段式路由决策：原问题 route → 改写/拼接后再 route
   │
   ├─ FORCE_EXEC 命中 + 非纯知识库模式 → 直接调工具 → LLM 总结（跳过向量检索）
   │
   └─ 未命中
        │
        ├─ RAG 混合检索（向量层全低则保底块直接丢弃，避免注入无关文档）
        │
        ├─ RECOMMEND_LLM 命中：把工具建议附到提示词末尾，LLM 拍板
        │
        └─ Spring AI 2.0 ChatClient.tools(DeviceTools @Tool ×8)
              · LLM 通过原生 tool_calls 通道识别
              · TracingToolCallback 打 trace
              · 最多 5 轮直到最终纯文本回答
              · 兜底：模型把调用写成文本 JSON → TextToolCallParser 补执行
   │
   ▼
统一 finish()：FINAL trace → 会话历史持久化 → 响应返回
```

### 3. ReAct 手动循环 + SSE 流式（`/ai/react-agent-chat-stream`）

```
用户提问
   │
   ├─ 闲聊短路：流式吐 final_delta → done
   │
   ├─ Prompt Injection 拦截：发 security_block → final_answer → done
   │
   ▼
Query 改写 + RAG 检索 → 构造 ReAct 系统提示词（内嵌 Tool Catalog + RAG 资料）
   │
   ▼
for step 1..maxSteps:
   │
   ├─ step_start 事件（携带 step/maxSteps）
   │
   ├─ Thought 阶段：流式 thought_delta → 攒全量 → thought_end（携带 tool 名或 final 标识）
   │
   ├─ 给出 Final Answer？ ──是──→ final_start → final_delta（打字机效果）→ done
   │
   ├─ 识别到 Tool Call？
   │     │
   │     ├─ action_start（工具名+参数）→ 真正执行 → action_end（Observation + durationMs）
   │     │
   │     └─ Observation 塞回 messages，继续下一轮 step
   │
   └─ 未识别到工具调用也不是 Final Answer → 直接输出内容，结束循环
   │
   ▼
done 事件（携带总步骤、耗时、sources、会话信息）
```

### 4. Plan-then-Execute + SSE 流式（`/ai/plan-chat-stream`）

```
用户提问
   │
   ├─ 闲聊短路 / Prompt Injection：流式 final_delta / security_block → done（同一套判定）
   │
   ▼
Planner（静默一次生成完整 JSON 计划）── 解析失败 → 回退为直接 Solver
   │
   ▼
Executor（拓扑调度，事件逐个推送）：
   │
   ├─ step_start（step/maxSteps）
   ├─ thought_end（thought="计划步骤 N：调用 tool"）
   ├─ action_start（tool + arguments）
   └─ 工具并行执行完 → action_end（observation + durationMs）
   │
   ▼
Solver：solve_start → final_delta*N（最终答案按 token 打字机）→ done
```

**SSE 事件序列一览**（ReAct 与 Plan 共用前端 `handleSseEvent` / `renderReActSteps` 渲染）：

| 事件名 | 触发时机 | 负载 |
|-------|---------|------|
| `rewrite` | Query 改写生效 | `{from, to, ms}` |
| `rag_ready` | RAG 检索返回了结果 | `{count}` |
| `step_start` | 每步推理开始 | `{step, maxSteps}` |
| `thought_delta` | （ReAct）Thought 阶段流式 token | `{step, delta}` |
| `thought_end` | Thought 阶段结束 / （Plan）每步开始即发 | `{step, thought, isFinalAnswer, tool?, arguments?}` |
| `action_start` | 工具执行前 | `{step, tool, arguments}` |
| `action_end` | 工具执行完 | `{step, observation, durationMs, tool}` |
| `solve_start` | （Plan）Solver 综合阶段开始 | `{}` |
| `final_answer` | （ReAct）安全拦截后补发完整拒绝语 | `{answer}` |
| `final_start` | 最终回答打字机前 | `{length}` |
| `final_delta` | 最终回答逐字/逐词 | `{delta}` |
| `security_block` | Prompt Injection 被拦 | `{type, attackType, answer}` |
| `error` | 任意环节出错 | `{step?, message}` |
| `done` | **必定发出**，流结束标志 | `{answer, sources, mode, totalMs, steps, stepCount, sessionId, ...}` |

---

## 🚀 快速开始

### 第一步：环境准备

```bash
# ============== 1. JDK 21 ==============
java -version      # 必须 openjdk version "21.x"

# ============== 2. 模型（后端可混搭）==============
# 【方案 A：完全离线 Ollama】
ollama pull qwen2.5:1.5b          # 聊天模型（约 1GB，CPU 可跑，推荐用于 ReAct 模式）
ollama pull bge-m3                # 向量编码（yaml 默认项）；或 pull nomic-embed-text

# 【方案 B：云端 DashScope】
# 登录 https://bailian.console.aliyun.com/ 创建 API Key，通过环境变量 DASHSCOPE_API_KEY 注入

# ============== 3. InfluxDB（能耗工具用，可选）==============
# 没装也能启动，Agent 调能耗工具时会回退模拟数据，不影响知识库问答
```

### 第二步：编译运行

```bash
# 编译打包
mvn clean package -DskipTests

# ===== 启动（N 种方式按需选，默认需登录 admin/admin123，可用 AUTH_ENABLED=false 关闭）=====

# 方式 1：纯离线 Ollama（默认，改 IP 就行）
java -jar target/rag-assistant-1.0.0.jar \
  --OLLAMA_BASE_URL=http://你的OllamaIP:11434

# 方式 2：全云端 DashScope（最省事，不用本地 Ollama）
java -jar target/rag-assistant-1.0.0.jar \
  --CHAT_BACKEND=dashscope \
  --EMBED_BACKEND=dashscope \
  --DASHSCOPE_API_KEY=sk-你的百炼Key

# 方式 3：混合模式（推荐：聊天用云端 qwen3.7-plus，改写与向量本地离线）
java -jar target/rag-assistant-1.0.0.jar \
  --CHAT_BACKEND=dashscope \
  --DASHSCOPE_API_KEY=sk-你的百炼Key \
  --OLLAMA_BASE_URL=http://你的OllamaIP:11434

# 方式 4：纯知识库模式（禁用设备/能耗工具，只走 RAG）
java -jar target/rag-assistant-1.0.0.jar \
  --RAG_PURE_MODE=true \
  --OLLAMA_BASE_URL=http://你的OllamaIP:11434

# ===== 构建知识库 =====
curl -X POST http://localhost:8900/api/docs/ingest-all
# 返回：{"success":true,"chunkCount":42,"message":"知识库已重建，共 42 个向量块"}
```

### 第三步：Docker Compose 一键启动

项目内置 `Dockerfile`（多阶段构建：Maven 依赖缓存层 + 运行 JRE 镜像仅 ~280MB）+ `docker-compose.yml`（DashScope 版示例，含持久化卷与健康检查），使用方法：

```bash
# 1) 把 docker-compose.yml 里的 DASHSCOPE_API_KEY 改成你的真实 Key
# 2) 构建启动
docker compose up -d --build

# 3) 看日志 / 停止
docker compose logs -f rag
docker compose down

# 访问：http://localhost:8900   admin / admin123
```

> 纯 Ollama 环境：把 `CHAT_BACKEND` / `EMBED_BACKEND` 改成 `ollama`，并把 `OLLAMA_BASE_URL` 指向宿主机 Ollama（Windows/Mac 用 `http://host.docker.internal:11434`，Linux 可用 host 网络）。

### 第四步：验证页面一览

| 页面 | 地址 | 说明 |
|------|------|------|
| **聊天首页** | http://localhost:8900/ | **五种模式**（Agent / ReAct / 对比 / Plan-then-Execute / 知识库）+ 多会话历史，模式随会话记忆 |
| **知识库管理** | http://localhost:8900/docs.html | 文档上传、版本列表、发布/归档、加入知识库、全量重建 |
| **决策链路审计** | http://localhost:8900/agent-trace.html | Agent 每轮决策可视化、工具入参出参回放、影子路由统计 |
| **H2 控制台** | http://localhost:8900/h2-console | JDBC `jdbc:h2:file:./data/ragdb`，用户名 `sa` 密码空 |
| **Actuator 健康检查** | http://localhost:8900/actuator/health | Spring Boot 健康检查 + Prometheus 指标 |

---

## ⚙️ 核心配置详解

配置文件按职责拆分（`application.yml` 用 `spring.profiles.include` 聚合），加载顺序：
`server → db → ai → rag → agent → storage → prompt → auth`（`application.yml` 中额外预留了 `websearch`（Tavily/SerpAPI 网页搜索）的引用位，当前未启用对应实现）。

### ① Agent：Tool Calling、启发式、影子路由（`application-agent.yml`）

```yaml
agent:
  max-tool-calls: ${AGENT_MAX_TOOL_CALLS:5}     # 原生 Tool Calling 最大轮次 / ReAct 最大 step 数 / Plan 最大波次
  memory-size: ${AGENT_MEMORY_SIZE:20}          # 每个会话最多带入多少轮历史
  session-ttl-minutes: ${AGENT_SESSION_TTL_MIN:120}   # 会话内存保留时长
  max-sessions: ${AGENT_MAX_SESSIONS:500}       # 最多同时保留会话数
  session-cleanup-interval-ms: 600000           # 定时清理周期（默认 10 分钟，@Scheduled 触发）

  heuristic:
    enabled: ${AGENT_HEURISTIC_ENABLED:true}
    strategy: ${AGENT_HEURISTIC_STRATEGY:FORCE_EXEC}  # FORCE_EXEC（最快，错判无纠正） / RECOMMEND_LLM（容错好）

    shadow:                   # 影子路由：比对 LLM vs 正则，验证是否可替换正则路由
      enabled: ${AGENT_SHADOW_ENABLED:false}   # 【代码默认 true，YAML 默认 false】验证完请关掉，会多一次 LLM 调用
      sample-every: ${AGENT_SHADOW_SAMPLE_EVERY:1}   # 抽样：1=每次都比，10=10% 抽样
      max-history-pairs: 3
```

### ② RAG：检索、切分、幻觉防护、Query 改写（`application-rag.yml`）

```yaml
rag:
  top-k: 3
  hybrid-search:
    keyword-weight: 0.3
    vector-weight:  0.7

  # === 幻觉防护（现已全部接入代码）===
  similarity-threshold: ${RAG_SIMILARITY_THRESHOLD:0.40}  # 只卡向量余弦分，不卡混合分
  min-keep-chunks: ${RAG_MIN_KEEP:1}                      # 全低时保底留 N 块（设为 0 则不保底）
  pure-knowledge-mode: ${RAG_PURE_MODE:false}             # true=禁用所有设备/能耗工具，只走 RAG

  # === 文档切分（改完必须重新 ingest-all）===
  chunking:
    chunk-size-chars: 600     # 按字符，不是 token（中文 token 误差大）
    overlap-chars: 90         # 约 15%，实际会收紧到句子边界
    min-chunk-chars: 50       # 丢弃过短碎片
    inject-heading-prefix: true  # chunk 正文前置【章节: A > B > C】
    merge-barrier-level: 1    # 合并时不跨 h1（0=不限制）

  # === Query 改写 ===
  query-rewrite:
    enabled: true
    backend: ${REWRITE_BACKEND:ollama}   # 独立后端，与主聊天后端解耦；可用 dashscope
    model: ${REWRITE_MODEL:qwen3.7-plus} # 用非推理模型（推理模型会先思考撞超时）
    timeout-ms: 10000         # 超时静默回退，不阻断主流程
    max-history-pairs: 3
    max-length-ratio: 3.0     # 改写后超原长度 3 倍 → 判定跑飞，回退
```

### ③ LLM 双后端 + QueryRewrite 独立后端（`application-ai.yml`）

```yaml
spring:
  ai:
    chat-backend:    ${CHAT_BACKEND:ollama}       # ollama / dashscope / openai
    embedding-backend: ${EMBED_BACKEND:ollama}

    ollama:
      base-url: ${OLLAMA_BASE_URL:http://192.168.221.130:11434}
      default-chat-options:
        model: ${OLLAMA_CHAT_MODEL:qwen2.5:1.5b}
        temperature: 0.3
        num-predict: 1024
      embedding:
        options:
          model: ${OLLAMA_EMBED_MODEL:bge-m3}      # 默认 bge-m3（nomic-embed-text 也可）

    openai:   # DashScope 走 OpenAI 兼容协议
      api-key:  ${DASHSCOPE_API_KEY:}
      base-url: ${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      default-chat-options:
        model: ${DASHSCOPE_CHAT_MODEL:qwen3.7-plus}   # qwen3.7-plus / qwen3.7-flash / qwen-plus / qwen-max ...
        temperature: 0.3
```

> ⚠️ **安全提醒**：`application-ai.yml` 里 `openai.api-key` 请通过环境变量 `DASHSCOPE_API_KEY` 注入，**不要提交真实 Key 到仓库**，避免密钥泄漏。启动时会打印脱敏后的 LLM 配置总览供核对。

### ④ 登录认证（`application-auth.yml`）

```yaml
auth:
  enabled: ${AUTH_ENABLED:true}       # 是否启用登录认证（true=启用，false=关闭）
  username: ${AUTH_USERNAME:admin}    # 登录用户名
  password: ${AUTH_PASSWORD:admin123} # 登录密码
```

---

## 📬 API 清单

### ① 对话 & 知识库构建（`/ai/**`）

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/ai/chat` | 纯 RAG 问答，不带 Agent；**空检索直接返回不调 LLM** |
| `POST` | `/ai/agent-chat` | Agent 模式：三路编排 + 启发式路由 + 混合检索 + 多轮记忆 |
| `POST` | `/ai/react-agent-chat` | ReAct 模式：手写 Thought-Action-Observation 循环，返回 JSON（含 step 详情） |
| `POST` | `/ai/react-agent-chat-stream` | **ReAct SSE 流式**：step_start → thought_delta → action_start → final_delta → done |
| `POST` | `/ai/plan-chat` | **Plan-then-Execute 普通版**：返回计划步骤 + 执行步骤 + Solver 综合答案 |
| `POST` | `/ai/plan-chat-stream` | **Plan SSE 流式**：step_start → thought_end → action_start → action_end → solve_start → final_delta → done |
| `POST` | `/ai/compare-chat` | **对比模式**：同请求跑 Agent + ReAct 两条链路，返回 agent/react 两份完整结果 |
| `POST` | `/ai/ingest` | **全量重建**：清空向量库 → 加载 `classpath:docs/**/*` 全部文档（别名 `/ai/ingestAll`） |
| `POST` | `/ai/ingest/{versionId}` | **单版本入库**：只删除该文档历史向量 → 切片入库（增量） |
| `GET`  | `/ai/health` | 健康检查：当前框架组合 `spring-ai-2.0.0 + ollama + rag + agent + ddd-4layer + bm25-hybrid` 与能力列表 |

**`POST /ai/chat` / `/ai/agent-chat` / `/ai/react-agent-chat` / `/ai/plan-chat` / `/ai/compare-chat` 共用请求体**：
```json
{
  "question": "mv-003 近 30 天能耗按月汇总",
  "sessionId": "session-001",
  "enableHybridSearch": true,
  "topK": 3
}
```
> ⚠️ 注意：`useRag` 参数在 ChatController 中是写死 `true` 的，DTO 里没有该字段。

### ② 文档 & 版本管理（`/api/docs/**`）

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/upload` | 上传文档 → 生成 `DRAFT` v1 |
| `POST` | `/{documentId}/update` | 更新文档 → 自动新版本号 vN+1 |
| `POST` | `/versions/{versionId}/publish` | **发布**：DRAFT/ARCHIVED → ACTIVE，**自动触发入库向量** + 清理旧向量 |
| `POST` | `/versions/{versionId}/archive` | 归档：ACTIVE → ARCHIVED |
| `POST` | `/versions/{versionId}/add-to-kb` | **加入知识库**：对该版本重新切分 + 向量化，替换该文档旧向量 |
| `POST` | `/ingest-all` | **重建整个知识库**：清空向量库 → 重读 `classpath:docs` + 所有 ACTIVE 版本文档 |
| `DELETE` | `/{documentId}` | 删文档（所有版本 + 向量全清） |
| `DELETE` | `/versions/{versionId}` | 删单版本（ACTIVE 同步清向量） |
| `GET`  | `/list[?status=ACTIVE]` | 列表过滤 DRAFT/ACTIVE/ARCHIVED |
| `GET`  | `/search?keyword=账单` | 文档名模糊搜索 |
| `GET`  | `/{documentId}` | 最新版本详情 |
| `GET`  | `/{documentId}/versions` | 完整版本历史 |
| `GET`  | `/{documentId}/versions/{version}` | 指定版本详情 |

### ③ Agent 决策链路审计 & 影子路由统计

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/agent/traces/session/{sessionId}` | 该会话完整决策链路（按轮次排序） |
| `GET` | `/api/agent/traces/recent?limit=50` | 最近 N 条全局决策日志 |
| `GET` | `/api/agent/traces/stats` | 近 24h / 近 7 天调用量统计 |
| `GET` | `/api/agent/traces/shadow-stats?days=7&samples=20` | **影子路由统计**：7 类判定占比 / 一致率 / 分歧详情 |

### ④ 认证（`/api/auth/**`）

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/auth/login?username=&password=` | 登录，返回 Basic Auth token + SessionId（认证关闭时返回 `disabled`） |
| `POST` | `/api/auth/logout` | 注销当前 Session |
| `GET`  | `/api/auth/status` | 查询当前登录状态 / 用户名 / 认证开关 |
| `GET`  | `/` | 根路径重定向 → `/index.html` |

---

## 🧩 内置工具清单

`DeviceTools.java` 共 **8 个** 方法，全部通过 `@Tool` + `@ToolParam` 标准注解声明：
- 走原生 Tool Calling：Spring AI 2.0 `ChatClient.tools()` 自动注册
- 走 ReAct / Plan 模式：`ToolCatalog.renderForPrompt()` 生成工具清单（带参数 schema）嵌入系统提示词
- 启发式 FORCE_EXEC：`SpringAiToolRegistry` 按名字反射调用 + `ToolCallValidator` 参数校验

| 工具名 | 参数 | 典型用户问句 |
|--------|------|-------------|
| `list_devices` | `keyword?` | "列出所有冷水机组设备"、"有哪些 mv 开头的设备" |
| `get_device_detail` | `sn` | "mv-003 的详情"、"看看 wzh_冷站01 的参数" |
| `query_energy_half_year` | `sn` | "mv-008 近半年能耗"、"这台设备半年用了多少电" |
| `query_energy_by_days` | `sn, days` | "mv-003 近 30 天能耗趋势"、"最近 7 天的电量" |
| `query_energy_by_date_range` | `sn, startDate, endDate` | "2026-01-01 到 2026-03-31 mv-005 的能耗" |
| `aggregate_energy` | `sn, granularity`（day/week/month/quarter/year） | "mv-003 能耗按月汇总"、"按季度做同比环比" |
| `getCurrentTime` | `timezone?` | "现在几点了"、"UTC 时间" |
| `get_weather` | `city` | "北京今天天气怎么样" |

---

## ❗ 常见故障排查

| 报错 / 现象 | 根因 | 解决 |
|------------|------|------|
| `不支持发行版本 21` | 命令行 JAVA_HOME 还是 JDK 17 | IDEA Terminal 里跑 mvn，或改系统环境变量 |
| `Unable to determine Dialect without JDBC metadata` | Hibernate 7.2 不再自动猜方言 | `application-db.yml` 已内置 `hibernate.dialect: H2Dialect` |
| `HTTP 404 model "mxbai-embed-large" not found` | 框架默认 embedding 模型名 | `ollama pull bge-m3` 或 `nomic-embed-text` + 配 `spring.ai.ollama.embedding.options.model` |
| IAM 权限 / 登录接口 401 / 页面提示未认证 | 默认账号密码被改 / 认证开关打开 | 用 `AUTH_USERNAME` / `AUTH_PASSWORD` / `AUTH_ENABLED` 环境变量控制 |
| 模型把 JSON 数组当最终答案返回（显示 `[}]` 三个字符） | 小模型把工具调用写成文本，没走原生通道 | AgentAppService 已有 `TextToolCallParser` 兜底；切 qwen3.7-plus 这类支持 function calling 的更佳 |
| Plan 模式执行停滞 / 全部步骤标失败 | Planner 产出了循环依赖或引用了不存在的步骤 | 日志搜 `⚠️ Plan 执行停滞`，已安全降级为标记失败；换更强模型规划 |
| "mv-003呢" 查能耗，下一条"你是什么模型"还在调能耗 | 老版本 `HeuristicRouter` 误判 | 已修复：TOPIC_SWITCH_CHAT_PATTERN + 追问剥除残长判定 + 闲聊前置短路 |
| Query 改写永远"超时回退" | 改写后端用了推理模型，先思考撞超时 | 配非推理模型 + 调大 `rag.query-rewrite.timeout-ms` |
| 改了 chunk-size/overlap 但检索效果没变 | 只改了配置没重新入库 | 跑一次 `POST /api/docs/ingest-all` 全量重建向量库 |
| 打开影子路由后延迟翻倍 | 每次请求多了一次 LLM 调用（异步但耗资源） | 验证完关掉 `agent.heuristic.shadow.enabled=false`，或调大 `sample-every` 做抽样 |
| 纯 RAG 模式下多轮改写永远拿不到上下文 | 改造前 RAG 链路漏写会话历史 | 现已修复：`RagAppService.finish()` 会写会话历史 |
| 能耗查询只有模拟数据 | InfluxDB 未连接 / 配置错误 | `application-storage.yml` 配 `influxdb.url` / 用户名密码；未连接自动回退模拟，不影响知识库 |
| 启动报 profile 相关错误（websearch） | `application.yml` 的 include 引用了 `websearch` 而文件未提供 | 删除该 include 配置或补齐 `application-websearch.yml`（预留的 Tavily/SerpAPI 接入点） |
| `.well-known/appspecific/com.chrome.devtools.json` WARN | Chrome 自动探测端点 | 已按 WARN 单列处理，可忽略 |

---

## 🔌 扩展点（对应 SPI 直接新增实现）

| 方向 | 切入点 | 说明 |
|------|--------|------|
| 向量库升级 PGVector / Milvus / RedisSearch | `VectorStorePort` 新增实现 | 配置里替换 `@Primary` Bean 即可，Rag/Ingest 代码零改动 |
| OCR 扫描件 / 图片入库 | `DocumentReaderPort` 扩展 OCR Adapter | Tika 基础已就绪，加 PaddleOCR / 阿里云 OCR 包装一层 |
| 熔断限流缓存 | 加 Resilience4j + Redis AOP | LLM 调用、能耗查询都是高延迟外部 IO，适合加缓存 1min |
| MCP 协议对接外部系统工具 | `ToolRegistryPort` → 包 MCP ToolServer | DeviceTools 8 个方法直接暴露成 MCP Server 端点 |
| Small-to-Big 父文档召回 | `ChunkMetaKeys.PARENT_ID` 已预埋 | RagAppService 返回 TopK 后批量查 parentId 对应全文，替换子 chunk |
| Rerank 重排 | RagAppService 融合排序之后加一步 | 接 DashScope text-rerank-v1 或 bge-reranker，TopK*2 → Top3 |
| 网页搜索（Tavily / SerpAPI） | `application-websearch.yml` 预留接入点 | application.yml 已预留 websearch profile，自行实现 `ToolRegistryPort` 新工具即可 |
| BM25 / RRF 替换自定义关键词检索 | 升级到 Spring AI 2.0 M3+ 后替换 `RagAppService.hybridSearch()` | `pom.xml` 已引 `spring-ai-rag` 做预留，接口层零改动 |

---

## 📄 License

MIT © 2026