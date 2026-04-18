# ceramicraft-search-intention-agent

> CeramiCraft 陶瓷电商平台 — AI 搜索意图 Agent 微服务

## 🏗 项目简介

本服务是 CeramiCraft 陶瓷电商平台多 Agent 编排系统中的**搜索意图 Agent**，基于 Spring AI + WebFlux 构建，提供以下核心能力：

- **语义搜索** — 自然语言查询 → 向量相似度匹配 → 商品推荐列表
- **RAG 智能推荐** — 向量检索 + LLM 增强生成，提供 AI 导购式推荐摘要
- **意图解析** — RAG + LLM 流式解析用户搜索意图为结构化 JSON
- **商品打标** — LLM 自动为商品生成 material / style / tags 等结构化标签
- **猜你想搜** — 基于搜索历史 + 热搜 + 向量知识的 RAG 智能推荐
- **Prompt 注入防护** — 三层安全机制（清洗 → 校验 → 风险检测）

### 核心架构

```
                          ┌─────────────────────────────────────────┐
                          │         ProductSearchController         │
                          │                                         │
  用户自然语言查询 ──────►│  /search        纯向量检索（~200ms）     │
                          │  /search/rag    向量 + LLM 推荐（~5s）   │
                          │  /search/rag/stream  流式 AI 推荐（SSE） │
                          └──────────────┬──────────────────────────┘
                                         │
                     ┌───────────────────┼───────────────────┐
                     ▼                   ▼                   ▼
              ┌─────────────┐   ┌──────────────┐   ┌──────────────┐
              │ Query Enhance│   │ Redis Vector │   │  LLM (GPT)   │
              │ (可选 LLM    │   │ Store 检索   │   │  RAG 增强生成 │
              │  查询改写)   │   │              │   │              │
              └─────────────┘   └──────────────┘   └──────────────┘
```

## 🛠 技术栈

| 层面       | 技术选型                                                         |
| ---------- | ---------------------------------------------------------------- |
| 核心框架   | Spring Boot 3.4.4                                                |
| Web 框架   | Spring WebFlux（Netty，全响应式，**非 spring-webmvc**）          |
| AI 框架    | Spring AI 1.0.1（BOM 管理）                                     |
| LLM        | OpenAI 兼容协议（GPT-4o 默认，支持自定义 base-url）             |
| 向量数据库 | Redis Stack（RediSearch 模块，spring-ai-starter-vector-store-redis） |
| 响应式缓存 | Spring Data Redis Reactive（Lettuce 驱动）                       |
| 消息队列   | Apache Kafka（商品打标异步管道）                                 |
| 可观测性   | Actuator + Micrometer Tracing + OTLP → MLflow                   |
| API 文档   | springdoc-openapi-starter-webflux-ui 2.5.0（Swagger UI）        |
| 安全       | Prompt 注入三层防护（PromptGuardUtils）                          |
| JDK        | Java 17                                                          |

## 📁 项目结构

```
src/main/java/com/ceramicraft/search/intention/
├── CeramicraftSearchIntentionAgentApplication.java   # 启动类
├── config/
│   ├── CeramicVectorStoreConfig.java       # Redis 向量库 & 陶瓷 Metadata 映射
│   ├── HttpClientConfig.java               # HTTP 代理（本地翻墙用）
│   ├── KafkaConfig.java                    # Kafka 消费者配置
│   ├── MlflowTracingConfig.java            # MLflow 链路追踪
│   ├── OpenApiConfig.java                  # Swagger/OpenAPI 配置
│   ├── ProductDataInitializer.java         # 启动时从商品服务同步数据到向量库
│   └── PromptConfig.java                   # 可外部化 Prompt 配置（record）
├── consumer/
│   └── ProductTaggingConsumer.java         # Kafka 消费者：商品上传事件 → 自动打标入库
├── controller/
│   ├── ProductSearchController.java        # 搜索 API（语义搜索 + RAG + 历史 + 热搜 + 推荐）
│   ├── IntentAgentController.java          # 意图解析 API（RAG + LLM 流式）
│   ├── ProductTaggingController.java       # 商品打标 API
│   └── TestIntentController.java           # 内部调试（@Hidden，不暴露到 Swagger）
├── model/
│   ├── ProductMessage.java                 # 商品消息（Kafka / 同步）
│   ├── ProductSearchItem.java              # 搜索结果项（向量库 Document → DTO）
│   ├── ProductUploadEvent.java             # 商品上传事件
│   ├── SearchResponse.java                 # 搜索响应（支持 recommendation 字段）
│   └── SuggestionResponse.java             # 猜你想搜响应
├── service/
│   ├── ProductSearchService.java           # 语义搜索 + RAG 智能搜索
│   ├── SearchIntentService.java            # RAG + LLM 意图解析
│   ├── ProductTaggingService.java          # LLM 商品打标
│   ├── ProductVectorService.java           # 打标 + 向量化入库
│   ├── ProductApiClient.java               # 商品后端 API 客户端
│   ├── SearchHistoryService.java           # 搜索历史 & 热搜（Redis）
│   └── SearchSuggestionService.java        # RAG 猜你想搜
└── util/
    ├── PromptGuardUtils.java               # Prompt 注入防护工具
    └── MarkdownUtils.java                  # LLM 输出清洗
```

## 🚀 本地启动

### 环境要求

- **JDK 17**（推荐 Amazon Corretto 17）
- **Docker Desktop**（用于启动 Redis Stack + Kafka）
- **OpenAI API Key**（或兼容 OpenAI 协议的 LLM 服务）

### Step 1：启动基础设施（Redis + Kafka）

```bash
docker compose up -d
```

这会启动：
- **Redis Stack**：`localhost:6379`（含 RediSearch 向量搜索模块）+ `localhost:8001`（RedisInsight UI）
- **Kafka**：`localhost:9092`（KRaft 单节点模式，无需 ZooKeeper）

验证：
```bash
docker compose ps          # 确认所有容器 healthy
redis-cli ping             # 返回 PONG
```

可选：启动 Kafka UI 查看消息
```bash
docker compose --profile ui up -d
# 访问 http://localhost:9090
```

### Step 2：配置环境变量

```bash
# 必需 — OpenAI API Key
export OPENAI_API_KEY=sk-your-api-key

# 可选 — 如果使用非 OpenAI 的 LLM 服务
export OPENAI_BASE_URL=https://api.openai.com   # 默认值
export LLM_MODEL_NAME=gpt-4o                     # 默认值

# 可选 — 国内访问 OpenAI 需要代理
export PROXY_HOST=127.0.0.1
export PROXY_PORT=7890
```

> **Windows PowerShell**：使用 `$env:OPENAI_API_KEY="sk-xxx"` 设置环境变量

### Step 3：启动应用

```bash
# Maven Wrapper 启动
./mvnw spring-boot:run

# 或先编译再运行
./mvnw clean package -DskipTests
java -jar target/ceramicraft-search-intention-agent-1.0.0-SNAPSHOT.jar
```

应用默认运行在 `http://localhost:8070`，Profile 默认 `local`。

### Step 4：验证

```bash
# 健康检查
curl http://localhost:8070/actuator/health

# Swagger UI
open http://localhost:8070/swagger-ui.html

# OpenAPI JSON
curl http://localhost:8070/v3/api-docs
```

### Docker 打包

```bash
docker build -t search-agent:1.0 .
docker run -p 8070:8070 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e OPENAI_API_KEY=sk-xxx \
  -e REDIS_VECTOR_URL=redis://host:6379 \
  -e REDIS_HOST=host \
  -e KAFKA_BOOTSTRAP_SERVERS=host:9092 \
  -e PRODUCT_API_BASE_URL=http://host:8090 \
  -e MLFLOW_TRACKING_URI=http://host:5000 \
  search-agent:1.0
```

## 📡 API 接口总览

基础 URL：`http://localhost:8070`

### 🔍 Product Search（商品搜索）

| 方法 | 路径 | 说明 | 耗时 |
| ---- | ---- | ---- | ---- |
| `GET` | `/api/v1/search` | 语义搜索（纯向量检索） | ~200ms |
| `GET` | `/api/v1/search/rag` | RAG 智能搜索（向量 + LLM 推荐摘要） | ~3-8s |
| `GET` | `/api/v1/search/rag/stream` | RAG 流式推荐（SSE 打字机效果） | 首 token ~1s |
| `GET` | `/api/v1/search/similar` | 相似商品推荐 | ~200ms |
| `GET` | `/api/v1/search/history` | 获取用户搜索历史 | ~50ms |
| `DELETE` | `/api/v1/search/history` | 清空用户搜索历史 | ~50ms |
| `GET` | `/api/v1/search/hot` | 热搜排行榜 | ~50ms |
| `GET` | `/api/v1/search/suggestions` | 猜你想搜（匿名/登录） | ~200ms/~3s |
| `GET` | `/api/v1/search/suggestions/stream` | 猜你想搜（SSE 流式） | 首 token ~1s |

### 🧠 Intent Analysis（意图解析）

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| `GET` | `/api/v1/intent/stream` | RAG + LLM 流式意图解析（SSE） |

### 🏷 Product Tagging（商品打标）

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| `POST` | `/api/v1/tagging` | LLM 商品自动打标（非流式） |
| `GET` | `/api/v1/tagging/stream` | LLM 商品打标（SSE 流式） |

---

## 🎯 前端 API 调用逻辑

### 场景一：搜索结果页（两阶段渲染，推荐方案）

**策略**：先快速展示商品列表，再异步加载 AI 推荐文案。

```
时间轴：
0ms     用户点击搜索
        ├─ ① GET /api/v1/search?query=xxx&limit=10     ← 快速搜索
200ms   ✅ 商品列表渲染完成
        ├─ ② GET /api/v1/search/rag/stream?query=xxx   ← 异步 AI 推荐
500ms   🔤 AI 推荐开始逐字显示（打字机效果）
5s      ✅ AI 推荐显示完毕
```

**前端代码示例（React）**：

```jsx
const handleSearch = async (query) => {
  setProducts([]);
  setAiText('');

  // ① 快速搜索 — 200ms 返回商品列表
  const res = await fetch(`/api/v1/search?query=${query}&limit=10`, {
    headers: { 'X-User-Id': userId }   // 可选：记录搜索历史
  });
  const data = await res.json();
  setProducts(data.products);           // 立刻渲染商品卡片

  // ② 流式 RAG — 异步加载 AI 推荐
  const sse = new EventSource(`/api/v1/search/rag/stream?query=${query}`);
  sse.onmessage = (e) => setAiText(prev => prev + e.data);
  sse.addEventListener('complete', () => sse.close());
  sse.addEventListener('error', () => sse.close());
};
```

**页面布局**：

```
┌──────────────────────────────────────────┐
│  🔍 搜索框                          [搜索] │
├──────────────────────────────────────────┤
│  🤖 AI 推荐（逐字打出，打字机效果）         │
│  "根据您的需求，首推这款龙泉青瓷茶杯..."    │
├──────────────────────────────────────────┤
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐    │
│  │ 商品1 │ │ 商品2 │ │ 商品3 │ │ 商品4 │    │
│  │ ¥168 │ │ ¥298 │ │ ¥128 │ │ ¥358 │    │
│  └──────┘ └──────┘ └──────┘ └──────┘    │
└──────────────────────────────────────────┘
```

### 场景二：不需要 AI 推荐，只要商品列表

```jsx
// 单接口调用，200ms 返回
const res = await fetch(`/api/v1/search?query=${query}&limit=10`);
const { products } = await res.json();
// products 即可直接渲染
```

### 场景三：RAG 非流式（后端调用 / 一次性展示）

```jsx
// 等 3-8 秒，返回完整 JSON（商品列表 + AI 推荐摘要）
const res = await fetch(`/api/v1/search/rag?query=${query}&limit=5`);
const data = await res.json();
// data.products       → 商品列表
// data.recommendation → AI 推荐文案（字符串）
```

**响应示例**：

```json
{
  "code": 200,
  "query": "送长辈的青瓷茶杯",
  "total": 5,
  "products": [
    { "id": 42, "name": "手工龙泉青瓷茶杯", "price": 168, "score": 0.92 },
    { "id": 17, "name": "景德镇高档青瓷品茗杯", "price": 298, "score": 0.87 }
  ],
  "recommendation": "根据您「送长辈」的需求，首推这款龙泉青瓷茶杯——传统梅子青釉色典雅大气，168元性价比很高。如果预算充裕，景德镇品茗杯礼盒装更适合送礼场景。",
  "hint": "结果来自 AI RAG 智能推荐，包含语义检索 + LLM 推荐分析"
}
```

### 场景四：意图解析（SSE 流式）

```jsx
const sse = new EventSource(`/api/v1/intent/stream?query=${query}`);
let result = '';
sse.onmessage = (e) => { result += e.data; };
sse.addEventListener('complete', () => {
  sse.close();
  const intent = JSON.parse(result);
  // intent = { category, priceRange, material, style, keywords, occasion, confidence }
});
```

### 场景五：猜你想搜

```jsx
// 匿名用户 → 返回平台热门商品名称
// 登录用户 → RAG + LLM 智能推荐
const res = await fetch('/api/v1/search/suggestions?limit=8', {
  headers: { 'X-User-Id': userId }   // 不传则为匿名
});
const { suggestions } = await res.json();
// suggestions = [{ keyword, reason, type }, ...]
```

### SSE 通用处理模板

所有 SSE 流式接口遵循统一协议：

```
data: token1          ← 正常数据帧（onmessage 接收）
data: token2
...
event: complete       ← 正常结束信号
data: [DONE]

event: error          ← 异常信号
data: 错误描述文本
```

```jsx
function connectSSE(url, { onToken, onComplete, onError }) {
  const sse = new EventSource(url);
  sse.onmessage = (e) => onToken(e.data);
  sse.addEventListener('complete', () => { sse.close(); onComplete?.(); });
  sse.addEventListener('error', (e) => { sse.close(); onError?.(e); });
  return sse;
}
```

## ⚙️ 配置说明

### Profile 机制

| Profile | 激活方式 | 用途 |
| ------- | -------- | ---- |
| `local` | 默认（无需配置） | 本地开发，中文输出，连接本地 Redis/Kafka |
| `prod`  | `SPRING_PROFILES_ACTIVE=prod` | 生产部署，英文输出，环境变量注入地址 |

### 关键环境变量

| 变量 | 默认值 | 说明 |
| ---- | ------ | ---- |
| `OPENAI_API_KEY` | — | OpenAI API Key（**必需**） |
| `OPENAI_BASE_URL` | `https://api.openai.com` | LLM 网关地址 |
| `LLM_MODEL_NAME` | `gpt-4o` | 模型名称 |
| `SPRING_PROFILES_ACTIVE` | `local` | 激活的 Profile |
| `CERAMIC_QUERY_ENHANCE_ENABLED` | `true` | 是否启用 LLM 查询增强 |
| `CERAMIC_LANGUAGE` | `en` | 输出语言（`zh` / `en` / `auto`） |
| `PROXY_HOST` / `PROXY_PORT` | — | HTTP 代理（国内访问 OpenAI） |

### Prompt 外部化

System Prompt 支持通过以下方式覆盖（优先级由高到低）：

1. 环境变量 `CERAMIC_INTENT_SYSTEM_PROMPT`
2. `application.yml` 中的 `ceramic.intent.system-prompt`
3. 代码内置默认模板（`PromptConfig.DEFAULT_SYSTEM_PROMPT`）

Prompt 模板中使用 `%s` 作为 RAG 领域知识注入占位符。

## 📊 可观测性

| 端点 | 说明 |
| ---- | ---- |
| `GET /actuator/health` | 健康检查 |
| `GET /actuator/info` | 应用信息 |
| `GET /actuator/metrics` | 指标数据 |
| `GET /actuator/prometheus` | Prometheus 格式指标 |

链路追踪通过 OTLP 自动推送至 MLflow（本地 `http://127.0.0.1:5000`）。

## 📖 Swagger UI

启动后访问：

- **Swagger UI**：http://localhost:8070/swagger-ui.html
- **OpenAPI JSON**：http://localhost:8070/v3/api-docs

> ⚠️ SSE 流式接口（`text/event-stream`）无法在 Swagger UI 中测试，请使用 `curl -N` 或浏览器 `EventSource`。
