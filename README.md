# ceramicraft-search-intention-agent

> CeramiCraft Ceramic E-commerce Platform — AI Search Agent Microservice

## Overview

The **Search Intention Agent** is an AI-powered semantic search and recommendation microservice for the CeramiCraft platform. Built on **Spring AI with Tool Calling**, the LLM autonomously decides which tools to invoke (vector search, search history, trending data) to fulfill user queries — making it a true agent, not just a pipeline.

### Core Capabilities

- **Agent Architecture** — LLM autonomously invokes `@Tool`-annotated methods via Spring AI tool calling, deciding its own search strategy per query
- **Semantic Search** — Natural language queries matched via Redis RediSearch vector similarity
- **RAG Recommendations** — Agent retrieves products, then generates personalized recommendation summaries with streaming SSE typewriter effect
- **Intent Parsing** — Agent extracts structured intent (category, price, material, style, occasion, confidence) from free-text queries
- **Product Tagging** — LLM auto-tags products with material, style, origin, and enriched descriptions
- **Smart Suggestions** — Agent-driven "guess what you want to search" with history + trending + catalog exploration
- **Prompt Injection Defense** — 3-layer security (sanitize → pattern detect → cumulative risk)

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Framework | Spring Boot 3.4.5 |
| Web | Spring WebFlux (Netty, fully reactive) |
| AI Framework | Spring AI 1.0.5 (ChatClient + @Tool + VectorStore) |
| LLM | GPT-4o via OpenAI-compatible API |
| Vector DB | Redis Stack (RediSearch) |
| Cache | Spring Data Redis Reactive (Lettuce) |
| Message Queue | Apache Kafka (KRaft) |
| Observability | Micrometer + OTLP → MLflow |
| API Docs | SpringDoc OpenAPI 2.5.0 (Swagger UI) |
| JDK | Java 17 (Amazon Corretto) |

## Agent Architecture

```
User Query → Prompt Guard (3-layer) → ChatClient.prompt().tools(searchAgentTools)
                                              │
                                    ┌─────────┴──────────┐
                                    │  LLM decides which  │
                                    │  tools to call:     │
                                    │                     │
                                    │  • vectorSearch()   │
                                    │  • getUserHistory() │
                                    │  • getTrending()    │
                                    │  • findSimilar()    │
                                    └─────────┬──────────┘
                                              │
                                    Spring AI Agent Loop
                                    (auto tool call → execute → return → reason)
                                              │
                                              ▼
                                    Structured Output / SSE Stream
```

The `SearchAgentTools` class provides 4 `@Tool`-annotated methods that the LLM can invoke autonomously:

| Tool | Description |
|------|-------------|
| `vectorSearch` | Semantic product search in Redis vector store |
| `getUserSearchHistory` | Retrieve user's recent search keywords |
| `getTrendingSearches` | Get platform-wide trending searches |
| `findSimilarProducts` | Find products similar to a given product |

## Project Structure

```
src/main/java/com/ceramicraft/search/intention/
├── tools/
│   └── SearchAgentTools.java           # @Tool methods for LLM agent
├── controller/
│   ├── ProductSearchController.java    # Search, RAG, history, suggestions (10 endpoints)
│   ├── IntentAgentController.java      # Intent parsing SSE stream
│   ├── ProductTaggingController.java   # Product tagging endpoints
│   └── TestIntentController.java       # Internal debugging (local profile only)
├── service/
│   ├── ProductSearchService.java       # Semantic search + agent RAG
│   ├── SearchIntentService.java        # Agent-based intent parsing
│   ├── ProductTaggingService.java      # LLM auto-tagging
│   ├── ProductVectorService.java       # Tag + embed + store orchestration
│   ├── SearchHistoryService.java       # Redis Sorted Set history & trending
│   ├── SearchSuggestionService.java    # Agent-based suggestions
│   └── ProductApiClient.java          # HTTP client → product backend
├── consumer/
│   └── ProductTaggingConsumer.java     # Kafka listener for product_changed
├── config/                            # PromptConfig, VectorStore, MLflow, Kafka, etc.
├── model/                             # DTOs: SearchResponse, ProductSearchItem, etc.
└── util/
    ├── PromptGuardUtils.java          # 3-layer prompt injection defense
    └── MarkdownUtils.java             # LLM output cleaning

src/test/java/com/ceramicraft/search/intention/
├── tools/
│   └── SearchAgentToolsTest.java      # Tool method unit tests (6 tests)
├── util/
│   └── PromptGuardUtilsTest.java      # Prompt guard unit tests (59 tests)
└── evaluation/
    └── SearchAgentEvaluationTest.java  # AI quality evaluation (5 tests, requires API key)
```

## CI/CD Workflows

| Workflow | Trigger | Description |
|----------|---------|-------------|
| **Test** | All branches | JDK 17 + `mvn test` (70 tests) |
| **Lint** | All branches | `mvn compile` type/syntax check |
| **Snyk** | main + PRs | Dependency vulnerability scan (pom.xml) |
| **Sonar** | main + PRs | Code quality + JaCoCo coverage → SonarQube |
| **Trivy** | main + PRs | Docker image vulnerability scan |
| **AI Evaluation** | main + PRs | Spring AI RelevancyEvaluator + FactCheckingEvaluator (requires CI_OPENAI_API_KEY) |
| **Deploy** | Manual | Build Docker → push DockerHub → update ArgoCD |
| **Release** | Tags | Create GitHub release |

## API Endpoints

Base path: `/search-agent/v1/customer/`

| Method | Path | Description | Latency |
|--------|------|-------------|---------|
| GET | `/search` | Semantic search | ~200ms |
| GET | `/search/rag` | RAG search + AI recommendation | ~5-12s |
| GET | `/search/rag/stream` | Streaming RAG (SSE) | First token ~3s |
| GET | `/search/similar` | Similar products | ~200ms |
| GET | `/search/history` | User search history | ~50ms |
| DELETE | `/search/history` | Clear history | ~50ms |
| GET | `/search/hot` | Trending searches | ~50ms |
| GET | `/search/suggestions` | Smart suggestions | ~200ms-5s |
| GET | `/intent/stream` | Intent parsing (SSE) | First token ~3s |
| POST | `/tagging` | Product tagging | ~2-5s |
| GET | `/tagging/stream` | Streaming tagging (SSE) | First token ~500ms |

## Local Development

### Prerequisites

- JDK 17 (Amazon Corretto)
- Docker Desktop (Redis Stack + Kafka)
- OpenAI API Key

### Quick Start

```bash
# 1. Start infrastructure
docker compose up -d

# 2. Set API key
export OPENAI_API_KEY=sk-your-key

# 3. Run
./mvnw spring-boot:run

# 4. Verify
curl http://localhost:8070/search-agent/actuator/health
open http://localhost:8070/search-agent/swagger-ui.html
```

### Docker

```bash
docker build -t search-agent:latest .
docker run -p 8070:8070 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e OPENAI_API_KEY=sk-xxx \
  -e REDIS_VECTOR_URL=redis://host:6379 \
  -e REDIS_HOST=host \
  -e KAFKA_BOOTSTRAP_SERVERS=host:9092 \
  -e PRODUCT_API_BASE_URL=http://host:8090 \
  -e MLFLOW_TRACKING_URI=http://host:5000 \
  search-agent:latest
```

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENAI_API_KEY` | — | **Required** |
| `OPENAI_BASE_URL` | `https://api.openai.com` | LLM gateway |
| `LLM_MODEL_NAME` | `gpt-4o` | Model name |
| `SPRING_PROFILES_ACTIVE` | `local` | `local` or `prod` |
| `CERAMIC_LANGUAGE` | `en` | Output language (`zh`/`en`/`auto`) |
| `CERAMIC_QUERY_ENHANCE_ENABLED` | `true` | Enable LLM query rewriting |
