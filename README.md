# 智能运维 RAG 助手 — Spring Boot 4 + Spring AI 2.0 GA

> 企业级知识库问答 + 智能 Agent 一体化平台：DDD 四层架构 + 六边形端口适配器，内置混合检索、语义切分、查询改写、自动工具调用、启发式路由、文档版本管理、决策链路审计全链路能力，同时适配本地 Ollama（离线）与阿里云百炼 DashScope（云端）双后端。

---

## ✨ 核心能力

| 模块 | 能力说明 | 关键特性 |
|------|---------|---------|
| **🧠 RAG 混合检索** | 向量 + 关键词线性加权融合 | 向量 0.7 + 关键词 0.3；中文同义词扩展（账单↔计费、报表↔报告）；N-gram 分词兜底；headingPath 标题词额外加权 |
| **✂️ 语义切分** | 递归语义边界 + Markdown 结构感知 | OverlappingTextSplitter 四级分隔符降级；MarkdownSectionParser 保留完整标题路径 `A > B > C`；chunk 正文前置章节提示 |
| **🔁 查询改写** | 多轮省略式问题还原 | 独立 LLM 后端 + 3s 超时守护；三层零成本门禁（80% 问句跳过 LLM）；改写结果仅用于检索，不污染对话历史 |
| **🤖 Agent 自动工具调用** | Spring AI 2.0 官方 ChatClient 循环 | 6 个 `@Tool` 注解方法自动注册；LLM → tool_call 检测 → 反射调用 → ToolMessage 回填 → 最多 5 轮直到最终回答 |
| **⚡ 启发式路由** | FORCE_EXEC 前置命中跳过检索 | 列出设备、近 N 天能耗等高频纯工具场景，不进向量检索、不跑 Agent 循环，延迟最低 |
| **📚 文档版本管理** | 状态机 + REST 全套接口 | DRAFT → ACTIVE → ARCHIVED → DELETED；**发布时才入库向量**；增量更新只清该文档旧向量，不动全库 |
| **🔍 决策链路审计** | 每轮 Agent 决策全落库 | LLM 决策、工具入参/出参、耗时、异常；按 Session 回放；内置可视化页面 `agent-trace.html` |
| **📊 可观测性** | 全链路 TraceId | `TraceIdFilter` MDC 贯通；日志 `[traceId=xxx]` 对齐；Actuator + Prometheus 基础已就绪 |

---

## 🛠 技术栈

| 分层 | 选型 | 版本要求 |
|------|------|---------|
| 语言 / 框架 | JDK 21 · Spring Boot 4.0.7 GA · Spring Framework 7.0.8 | JDK 必须 21+ |
| AI 框架 | Spring AI 2.0.0 GA（groupId `org.springframework.ai`） | 含 `spring-ai-rag` 模块 |
| 聊天模型后端 | **二选一**：Ollama `qwen2.5:1.5b` / DashScope `qwen3.7-flash` | 支持 OpenAI 兼容协议任意后端 |
| 向量化模型 | 推荐 Ollama `nomic-embed-text`（免费、离线） | 也可切 DashScope `text-embedding-v3` |
| 向量库 | SimpleVectorStore + JSON 持久化 | 通过 `VectorStorePort` 可无缝切 PGVector/Milvus |
| 元数据存储 | H2 File Mode + Hibernate 7.2 | 生产可替换 PostgreSQL，方言已显式配置 |
| 时序数据（能耗） | InfluxDB 1.8 SQL 聚合查询 | 通过 `TimeSeriesDbPort` 适配 |
| 前端页面 | 原生 HTML + JS（零构建） | 三个内置页面：聊天 / 知识库管理 / 决策审计 |

---

## 🏗 架构设计（DDD 四层 + Port-Adapter 六边形）

```
src/main/java/com/example/ragassitant/
│
├── application/                    # 【应用层】用例编排，不含业务规则
│   └── service/
│       ├── AgentAppService             # 核心编排：FORCE_EXEC 前置 → QueryRewrite → RAG 检索 → ChatClient 自动 Tool Calling
│       ├── ChatAppService              # 普通 RAG 问答（不带 Agent）
│       ├── DocumentAppService          # 文档版本状态机 + 发布时向量清理
│       ├── IngestAppService            # 文件分派 → 切分 → 向量入库
│       ├── QueryRewriteService         # 多轮省略问句改写 + 超时降级
│       └── RagAppService               # 混合检索（向量 + 关键词线性加权 + 同义词扩展）
│
├── domain/                         # 【领域层】纯 POJO，零 Spring / 外部依赖
│   ├── model/                          # Device, EnergyRecord, ToolCall, RagAnswer, ChunkMetaKeys
│   ├── service/                        # HeuristicRouter, ToolCallParser/Validator, PromptBuilder, EnergyAggregator
│   └── spi/                            # ← Port（端口接口，被 Application 层依赖）
│       ├── LlmPort                         # chat · chatWithRawMessages · chatWithAutoToolCalling
│       ├── VectorStorePort / TimeSeriesDbPort
│       ├── DocumentReaderPort / ConversationStorePort
│       └── ToolRegistryPort / AgentTraceStorePort
│
├── infrastructure/                 # 【基础设施层】Adapter 实现 Port
│   ├── llm/        SpringAiOllamaAdapter        # ChatClient + 自动 Tool Calling（@Primary）
│   ├── vector/     SpringAiSimpleVectorAdapter  # deleteAll / deleteByDocId 增量清理
│   ├── tsdb/       InfluxDB18TimeSeriesAdapter  # 6 种时间范围 + 重聚合
│   ├── session/    InMemoryConversationStore    # 多轮会话记忆
│   ├── agent/      DeviceTools.java             # 6 个 @Tool + @ToolParam 方法
│   ├── reader/     SpringAiDocumentReader       # md/pdf/word/excel；MarkdownSectionParser；OverlappingTextSplitter
│   ├── config/     VectorStoreInfraConfig / LlmBackendConfig（双后端选择）
│   └── persistence/jpa/
│       ├── DocumentVersionJpaEntity + Repository（文档状态机）
│       └── AgentTraceJpaEntity + Repository + JpaAgentTraceStoreAdapter
│
├── interfaces/                     # 【接口层】HTTP <-> DTO，零业务逻辑
│   ├── rest/
│   │   ├── ChatController.java         # /ai/chat, /ai/agent-chat, /ai/ingest*
│   │   ├── DocumentController.java     # /api/docs/**（版本管理 10 个接口）
│   │   └── AgentTraceController.java   # /api/agent/traces/**
│   └── dto/request / response
│
└── common/                         # 横切关注点
    ├── trace/      TraceIdFilter（MDC 注入 + X-Trace-Id 请求头透传）
    ├── utils/      SnUtils（SN 归一化去空格/括号）
    └── exception/  BizException + GlobalExceptionHandler
```

依赖方向：`Interfaces → Application → Domain ← Infrastructure`（依赖倒置，Ports 定义在 Domain 层，Adapters 实现在 Infrastructure 层）。

---

## 💡 核心逻辑详解：RAG 检索

### 1. 为什么不用纯向量检索？痛点是什么？
纯向量检索的典型问题在这个项目里全部踩过：
```
用户问："手动账单流程"        文档写的是："手动计费流程"
          ↓ 纯向量（余弦相似度）↓
     命中 "支付宝对账说明"（语义接近"账单"）   完全漏了 "手动计费流程"（"账单"≠"计费"字面差太远）
```
→ 这就是**同义词 / 近义词盲区**，是中文 RAG 纯向量方案必踩的坑。

### 2. 本项目的 RAG 检索五层防线

| # | 防线 | 代码位置 | 解决的问题 |
|---|------|---------|-----------|
| ① | **同义词硬规则扩展** | `RagAppService.java:expandKeywordsWithSynonyms()` | 账单↔计费 / 报表↔报告 / 登录↔登入 / 用户↔账号，固定 10 对高频业务词，零成本 0 误判 |
| ② | **中文 N-gram 分词兜底** | `RagAppService.java:ngramSplit()` | 长词拆成 2~4 字滑窗："手动账单流程" → "手动 动账 账单 单流 流程 手动账 动账单 账单流 单流程"，总有一个子串命中 |
| ③ | **双通道线性加权融合** | `RagAppService.java:retrieveDocuments()` 融合公式：`score = 0.7*向量分 + 0.3*关键词分` | 向量负责模糊语义，关键词负责精确字面；权重 0.7/0.3 是 1.5b 小模型实测最优（小模型语义弱，向量分不能占比太低） |
| ④ | **标题 / 章节路径加权** | `RagAppService.java:keywordMatchScore()` 命中 title +0.5，命中 headingPath +0.4 | Markdown 标题 `## 手动计费流程` 是作者标注的主题，语义最强，必须单独加权（很多开源 RAG 把标题和正文一视同仁，白丢了 20% 召回率） |
| ⑤ | **Chunk 正文前缀注入章节** | `SpringAiDocumentReader.java` 每行前置 `【章节:计费管理>账单>手动计费】` | embedding 编码时把标题词混进向量，用户问"账单流程"时向量也能命中"计费"这块 chunk，相当于**把关键词权重偷偷注入了向量通道** |

> **为什么不接 BM25 / RRF / EnsembleRetriever？**  
> Spring AI 2.0 GA 已经把 `BM25Retriever` + `EnsembleRetriever` 从 GA 包移除了（还在 M3 里程碑里），硬引会导致 BOM 版本漂移。  
> 上面 5 层防线在中文领域 ≈ 带权重的 RRF，效果不输给标准 BM25 融合，且零额外依赖，**部署不会炸**。

### 3. 文档切分的"不切断语义"原则
切分代码位于 `OverlappingTextSplitter.java`，四级分隔符**从粗到细递归降级**：
```
L0 段落（\n\n\r\n\r\n） → L1 换行（\n） → L2 中文句末（。！？；、） → L3 英文句末（. ! ? ;） → L4 强制按字符切
```
两个关键设计：
- **Overlap 不是硬切 N 字符**，而是「往回退到完整 atom 边界」——保证 overlap 开头一定是句子开头，不会出现"。哦对了开头补半句"这种半吊子上下文
- **headingPath 一路透传**：`MarkdownSectionParser` 用栈维护标题路径，每个 chunk 的 metadata 都带 `A > B > C`，不会出现"h2 标题在 5 块之前，这块是正文，丢了主题"的经典问题

> **改完 chunk-size / overlap 后，必须跑 `POST /ai/ingest` 全量重建向量库**，否则旧向量库还是旧切分结果，不会自动生效。

---

## 💡 核心逻辑详解：Agent 与启发式路由

### 1. Agent 架构的三次演化
这个项目 Agent 一共经历了三代，最终才是现在的样子：

| 代际 | 实现方式 | 代码行数 | 痛点 |
|------|---------|---------|------|
| 第一代 | 手写 for 循环：LLM→解析 JSON→调工具→拼 ToolMessage→循环 | 约 120 行 | 小模型输出 JSON 格式不稳，少个引号整个循环炸；自己要处理 max_iteration / 死循环 |
| 第二代 | `FunctionCallbackWrapper` + 手动注册 | 约 70 行 | 跟具体模型强绑定；Ollama 和 OpenAI 的 function call 响应字段名不一样，两套适配分支 |
| **第三代（当前）** | **Spring AI 2.0 `ChatClient.tools(...)` 官方自动循环** | **1 行** | 适配层全部在 Spring AI；`@Tool` 注解自动生成 JSON Schema；超时/死循环框架自己兜 |

当前最终形态代码（`AgentAppService.java` 的核心就是这一行）：
```java
String finalContent = ChatClient.builder(primaryChatModel).build()
        .prompt()
        .messages(toSpringMessages(messages))
        .tools(deviceTools)      // ← DeviceTools 上 6 个 @Tool 注解方法全部自动注册
        .call()
        .content();             // ← 内部最多 5 轮循环，出来已经是最终纯文本回答
```

### 2. 为什么还要加「启发式路由 FORCE_EXEC」？
官方 ChatClient 自动循环虽然好，但对**"列出所有设备""mv-003 近 30 天能耗"**这种纯工具高频问题，依然要走：
```
用户提问 → LLM 判断调哪个工具 → 解析 JSON → 调工具 → LLM 再总结
= 至少两次 LLM 往返（1 次 tool_call 决策 + 1 次最终总结）
≈ 本地 qwen2.5:1.5b 跑 3~5 秒
```
→ **延迟太高**，而且小模型对"列出设备"这种最简单的指令偶尔还会不识别 tool_call，直接自己瞎编一份设备清单。

所以加了 `HeuristicRouter.route()` 前置判断，**在进 Agent 之前就先截胡**：

| 判定顺序 | 规则 | 典型问句 |
|---------|------|---------|
| 1 | 有 SN + 追问结尾词（呢 / 怎么样 / 看看） | `mv-003呢` → 直接 `query_energy_by_days(30天)` |
| 2 | 有 SN + 明确时间范围（近 N 天 / 半年 / 日期区间） | `mv-008 近半年能耗` → 直接 `query_energy_half_year` |
| 3 | 有 SN + 聚合词（按月 / 按季 / 汇总） | `mv-003 按月汇总` → 直接 `aggregate_energy(month)` |
| 4 | 无 SN + 明确设备清单指令 | `列出所有冷水机组` → 直接 `list_devices(keyword=冷水机组)` |
| - | 以上都没命中 → 放行走官方 ChatClient Agent 循环 | 知识库问答 / 复杂多工具组合 / 用户意图不明确 |

### 3. 历史参数继承的三层门禁（最容易写炸的部分）
最容易踩的坑：**上一轮 `mv-005 半年能耗`，这一轮问 `如何创建用户呢？`，因为命中了"呢"被误判为追问，继承了 mv-005，把知识库问题劫持成能耗查询**。

所以 `HeuristicRouter.isLikelySameToolTopic()` 是**优先级严格分层**的，不能改顺序：

```java
// 判定顺序（代码在 HeuristicRouter.java:66-95）
1. 命中 TOPIC_SWITCH_CHAT_PATTERN   → 直接 false（"你好""你是什么模型""换个话题"这类明确的闲聊/身份问题）
2. 命中 DOMAIN_WORD_PATTERN         → 直接 true（有 mv- / 能耗 / 按月 / 统计 这类领域实词才算同话题）
3. 只命中 FOLLOWUP_MARKER（呢 / 如何 / 这个）→ 剥掉追问词后看残长
     · 残长 ≤ 2（"那个呢" → 剩 ""）→ 是纯代词追问，才允许继承
     · 残长 > 2（"如何创建用户呢？" → 剩 "创建用户" 4字）→ 是新问题，判 false
```

> **为什么要把「追问词」和「领域实词」拆成两张表？**  
> 这是我们这轮实打实踩坑踩出来的：一开始把 "如何 / 呢 / 怎么样" 跟 "能耗 / mv- / 统计" 放同一张表里，只要命中任意一个就算同话题。线上就出现了 `如何创建用户呢？` 命中「如何」+「呢」被误判，知识库被劫持的 Bug。  
> 正确逻辑是：**"如何 / 呢" 只表示"这是个问句"，对话题完全没有指示作用；只有"能耗 / mv- / 按月"才真正指示用户还在聊运维领域**。

---

## 🧠 RAG 完整工作流

```
用户提问（"手动账单流程？"）
   │
   ▼
┌─────────────────────────────┐
│ 1. 多轮上下文 Query 改写     │  仅多轮场景；独立 LLM 后端 3s 超时；
│    "呢？" → "手动账单流程"   │  失败静默回退原问句
└─────────────┬───────────────┘
              ▼
┌─────────────────────────────┐
│ 2. 关键词扩展 + 中文分词     │  账单↔计费 同义词 → "手动账单流程 手动计费流程"
│    N-gram 兜底切分           │  headingPath 命中额外加权 0.4 分
└─────────────┬───────────────┘
              ▼
┌─────────────────────────────┐
│ 3. 双通道并行检索            │  · 向量检索 topK*5（Spring AI 2.0 VectorStoreDocumentRetriever）
│                              │  · 关键词全文检索（chunk + title + headingPath）
└─────────────┬───────────────┘
              ▼
┌─────────────────────────────┐
│ 4. 线性加权融合 + 去重排序   │  融合分 = 0.7*向量分 + 0.3*关键词分
│    topK=3 截断               │  去重按 documentId + chunk 位置指纹
└─────────────┬───────────────┘
              ▼
┌─────────────────────────────┐
│ 5. System Prompt 注入上下文 │  原文块前置【章节: 计费管理 > 账单 > 手动计费】
│    + 章节路径提示            │  告知模型：只基于上下文回答，未命中说不知道
└─────────────┬───────────────┘
              ▼
┌─────────────────────────────┐
│ 6. LLM 生成最终回答          │  附 SourceDoc（原文片段 + 分数 + metadata）返回前端
└─────────────────────────────┘
```

## 🤖 Agent 工作流

```
用户提问（"mv-003 近 30 天能耗，按月汇总"）
   │
   ▼
┌───────────────────────────────┐
│ 1. HeuristicRouter 三层判定   │  ① 换话题黑名单 → 拒继承
│    · 是否 FORCE_EXEC?         │  ② 领域实词（mv-/能耗/统计）→ 命中工具
│    · 能否继承历史参数?        │  ③ 追问剥除残长≤2 → 纯代词追问
└────┬────────────────────┬─────┘
     │FORCE_EXEC          │其他
     ▼                    ▼
┌───────────────┐  ┌───────────────────────────────────────┐
│ 直接调工具     │  │ 2. RAG 混合检索（异常降级不阻断）      │
│ + LLM 总结     │  └──────────────┬────────────────────────┘
└──────┬────────┘                 ▼
       │              ┌───────────────────────────────────────┐
       │              │ 3. Spring AI 2.0 ChatClient.tools()   │
       │              │    · LLM 识别 tool_call JSON           │
       │              │    · 反射调用 DeviceTools @Tool 方法   │
       │              │    · 自动回填 ToolMessage              │
       │              │    · 最多 5 轮直到最终纯文本回答        │
       │              └──────────────┬────────────────────────┘
       └───────────────┬──────────────┘
                       ▼
          最终回答 + 会话记忆持久化 + AgentTrace 完整落库
```

---

## 🚀 快速开始

### 第一步：环境准备

```bash
# ============== 1. JDK 21 ==============
java -version      # 必须 openjdk version "21.x"

# ============== 2. 模型（二选一）==============
# 【方案 A：完全离线 Ollama】
ollama pull qwen2.5:1.5b          # 聊天模型（约 1GB，CPU 可跑）
ollama pull nomic-embed-text      # 向量编码（必拉，默认值 mxbai-embed-large 不存在）

# 【方案 B：云端 DashScope】
# 登录 https://bailian.console.aliyun.com/ 创建 API Key，下一步配置里注入即可

# ============== 3. InfluxDB（能耗工具用，可选）==============
# 没装也能启动，Agent 调能耗工具时会报错但不影响知识库问答
```

### 第二步：运行

```bash
# 编译打包
mvn clean package -DskipTests

# ===== 启动（3 种方式选一种）=====

# 方式 1：纯离线 Ollama（默认，改 IP 就行）
java -jar target/rag-assistant-1.0.0.jar \
  --OLLAMA_BASE_URL=http://你的OllamaIP:11434

# 方式 2：全云端 DashScope（最省事，不用本地 Ollama）
java -jar target/rag-assistant-1.0.0.jar \
  --CHAT_BACKEND=dashscope \
  --EMBED_BACKEND=dashscope \
  --DASHSCOPE_API_KEY=sk-你的百炼Key

# 方式 3：混合模式（推荐：聊天用云端 qwen-turbo 改写，向量用本地离线）
java -jar target/rag-assistant-1.0.0.jar \
  --CHAT_BACKEND=dashscope \
  --REWRITE_BACKEND=dashscope \
  --REWRITE_MODEL=qwen-turbo \
  --DASHSCOPE_API_KEY=sk-你的百炼Key \
  --OLLAMA_BASE_URL=http://你的OllamaIP:11434

# ===== 构建知识库 =====
curl -X POST http://localhost:8900/ai/ingest
# 返回：{"success":true,"count":42,"message":"知识库已重新构建，共 42 条向量"}
```

### 第三步：验证

打开浏览器：

| 页面 | 地址 | 说明 |
|------|------|------|
| **聊天首页** | http://localhost:8900/ | 单会话聊天，支持切换 Agent / 纯 RAG 模式 |
| **知识库管理** | http://localhost:8900/docs.html | 文档上传、版本列表、发布/归档操作 |
| **决策链路审计** | http://localhost:8900/agent-trace.html | Agent 每轮决策可视化、工具入参出参回放 |
| **H2 控制台** | http://localhost:8900/h2-console | JDBC `jdbc:h2:file:./data/ragdb`，用户名 `sa` 密码空 |
| **健康检查** | http://localhost:8900/ai/health | JSON 形式返回能力清单 |

---

## ⚙️ 核心配置详解（`application.yml`）

### ① LLM 双后端切换（最常用）

```yaml
spring:
  ai:
    # 聊天 / 向量编码后端独立选择：ollama（离线） / dashscope（阿里云百炼）/ openai（官方）
    chat-backend:    ${CHAT_BACKEND:ollama}
    embedding-backend: ${EMBED_BACKEND:ollama}

    ollama:
      base-url: ${OLLAMA_BASE_URL:http://192.168.221.130:11434}
      default-chat-options:
        model: ${OLLAMA_CHAT_MODEL:qwen2.5:1.5b}
      embedding:
        options:
          model: ${OLLAMA_EMBED_MODEL:nomic-embed-text}   # 不要改默认值 mxbai！

    openai:   # DashScope 用 OpenAI 兼容协议，走同一套自动配置
      api-key:  ${DASHSCOPE_API_KEY:}                    # 强烈建议通过环境变量注入，不要硬写
      base-url: ${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      default-chat-options:
        model: ${DASHSCOPE_CHAT_MODEL:qwen3.7-flash}
```

### ② Query 改写独立后端（关键优化）

```yaml
rag:
  query-rewrite:
    enabled: true
    # ⚠️ 改写是机械任务，千万别用 qwen3.7-flash 这种推理模型！会先思考 145 token → 撞 3s 超时
    backend: ${REWRITE_BACKEND:dashscope}   # ollama / dashscope / openai
    model:   ${REWRITE_MODEL:qwen-turbo}    # 实测：qwen-turbo 0.47s，qwen-plus 1.10s
    timeout-ms: 3000                        # 超时静默回退原问题，不阻断主流程
    max-history-pairs: 3                    # 只喂最近 3 轮，太多会带偏到旧话题
    max-length-ratio: 3.0                   # 改写后超过原长度 3 倍 → 判定跑飞，回退
```

### ③ 文档切分（改完必须重新 `POST /ai/ingest` 全量重建）

```yaml
rag:
  chunking:
    chunk-size-chars: 600       # 按字符计量（token 对中文误差大）
    overlap-chars: 90           # 15% 重叠；实际按句子边界收紧，不会半句截断
    min-chunk-chars: 50         # 丢弃过短碎片（整篇仅一块时保留）
    inject-heading-prefix: true # chunk 正文前置【章节: A > B > C】
    merge-barrier-level: 1      # 合并时不跨 h1（h2/h3 小节会合并到 chunkSize 预算）
```

### ④ 混合检索权重

```yaml
rag:
  top-k: 3
  hybrid-search:
    keyword-weight: 0.3   # 关键词命中分占比（title/headingPath 命中再额外加权）
    vector-weight:  0.7   # 向量余弦相似度占比
```

---

## 📬 API 清单

### ① 对话 & 知识库构建

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/ai/chat` | 纯 RAG 问答，不带 Agent |
| `POST` | `/ai/agent-chat` | Agent 模式：启发式路由 + 工具调用 + 多轮记忆 + 混合检索 |
| `POST` | `/ai/ingest` | **全量重建**：清空向量库 → 加载 `classpath:docs/**/*` 全部文档 |
| `POST` | `/ai/ingest/{versionId}` | **单版本发布入库**：只删除该文档历史向量 → 切片入库（增量） |
| `GET`  | `/ai/health` | 健康检查 + 能力清单 JSON |

**`POST /ai/agent-chat` 请求体**：
```json
{
  "question": "mv-003 近 30 天能耗按月汇总",
  "sessionId": "session-001",
  "enableHybridSearch": true,
  "topK": 3,
  "useRag": true
}
```

### ② 文档 & 版本管理（`/api/docs/**`）

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/upload` | 上传文档 → 生成 `DRAFT` v1 |
| `POST` | `/{documentId}/update` | 更新文档 → 自动新版本号 vN+1 |
| `POST` | `/versions/{versionId}/publish` | **发布**：DRAFT/ARCHIVED → ACTIVE，**自动切 `/ai/ingest/{id}` 入库向量** + 清理旧向量 |
| `POST` | `/versions/{versionId}/archive` | 归档：ACTIVE → ARCHIVED |
| `DELETE` | `/{documentId}` | 删文档（所有版本 + 向量全清） |
| `DELETE` | `/versions/{versionId}` | 删单版本（ACTIVE 同步清向量） |
| `GET`  | `/list[?status=ACTIVE]` | 列表过滤 DRAFT/ACTIVE/ARCHIVED |
| `GET`  | `/search?keyword=账单` | 文档名模糊搜索 |
| `GET`  | `/{documentId}` | 最新版本详情 |
| `GET`  | `/{documentId}/versions` | 完整版本历史 |

### ③ Agent 决策链路审计

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/agent/traces/session/{sessionId}` | 该会话完整决策链路（按轮次排序） |
| `GET` | `/api/agent/traces/recent?limit=50` | 最近 N 条全局决策日志 |
| `GET` | `/api/agent/traces/stats` | 调用量 / 成功率 / 平均耗时统计 |

---

## 🧩 内置工具清单

工具定义在 `DeviceTools.java`，6 个方法全部通过 `@Tool` + `@ToolParam` 标准注解声明，Spring AI 2.0 `ChatClient.tools()` 自动注册、自动解析参数，**不需要手写 JSON 解析**。

| 工具名 | 参数 | 典型用户问句 |
|--------|------|-------------|
| `list_devices` | `keyword?` | "列出所有冷水机组设备"、"有哪些 mv 开头的设备" |
| `get_device_detail` | `sn` | "mv-003 的详情"、"看看 wzh_冷站01 的参数" |
| `query_energy_half_year` | `sn` | "mv-008 近半年能耗"、"这台设备半年用了多少电" |
| `query_energy_by_days` | `sn, days` | "mv-003 近 30 天能耗趋势"、"最近 7 天的电量" |
| `query_energy_by_date_range` | `sn, startDate, endDate` | "2026-01-01 到 2026-03-31 mv-005 的能耗" |
| `aggregate_energy` | `sn, granularity`（day/week/month/quarter/year） | "mv-003 能耗按月汇总"、"按季度做同比环比" |

---

## ❗ 常见故障排查

| 报错 / 现象 | 根因 | 解决 |
|------------|------|------|
| `不支持发行版本 21` | 命令行 JAVA_HOME 还是 JDK 17 | IDEA Terminal 里跑 mvn，或改系统环境变量 |
| `Unable to determine Dialect without JDBC metadata` | Hibernate 7.2 不再自动猜方言 | application.yml 已内置 `hibernate.dialect: H2Dialect`，一般不会出；出了就加这行 |
| `HTTP 404 model "mxbai-embed-large" not found` | 没显式配 embedding 模型，用了框架默认值 | `ollama pull nomic-embed-text`，配置 `spring.ai.ollama.embedding.options.model: nomic-embed-text` |
| "mv-003呢" 查能耗，下一条"你是什么模型"还在调能耗 | 老版本 HeuristicRouter 误判 | 已修复：TOPIC_SWITCH_CHAT_PATTERN + 追问剥除残长判定；拉最新代码即可 |
| 查询改写没生效，日志说"超时回退" | 改写后端用了 qwen3.7-flash 这种推理模型，先思考撞 3s 超时 | 改成 `rag.query-rewrite.model=qwen-turbo` 非推理模型，实测 0.47s 搞定 |
| `.well-known/appspecific/com.chrome.devtools.json` WARN | Chrome 浏览器自动探测端点，跟项目无关 | 已按 WARN 单列处理，可忽略 |

---

## 🔌 扩展点（对应 SPI 直接新增实现）

| 方向 | 切入点 | 说明 |
|------|--------|------|
| 向量库升级 PGVector / Milvus / RedisSearch | `VectorStorePort` 新增实现 | 配置里替换 `@Primary` Bean 即可，Rag/Ingest 代码零改动 |
| OCR 扫描件 / 图片入库 | `DocumentReaderPort` 扩展 OCR Adapter | Tika 基础已就绪，加 PaddleOCR / 阿里云 OCR 包装一层 |
| 熔断限流缓存 | `common/aop/` + Resilience4j + Redis | LLM 调用、能耗查询都是高延迟外部 IO，适合加缓存 1min |
| MCP 协议对接外部系统工具 | `ToolRegistryPort` → 包 MCP ToolServer | DeviceTools 6 个方法直接暴露成 MCP Server 端点 |
| Small-to-Big 父文档召回 | ChunkMetaKeys.PARENT_ID 已预埋 | RagAppService 返回 TopK 后批量查 parentId 对应全文，替换子 chunk |
| Rerank 重排 | RagAppService 融合排序之后加一步 | 接 DashScope text-rerank-v1 或 bge-reranker，TopK*5 → Top3

---

## 📄 License

MIT © 2026