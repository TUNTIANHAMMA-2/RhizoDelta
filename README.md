# RhizoDeltΔ

RhizoDelta 是一个基于图谱的非线性讨论系统——它把传统论坛的线性聊天记录重塑为一棵有主干、能分叉、可合并的知识之树。用户只需像在论坛一样发言，后台的 AI 智能体会自动将观点清洗、归纳、路由为"共识合并"或"异议分支"，所有演进过程以不可变的有向无环图（DAG）固化在原生图数据库中。

## 项目概述

| 维度 | 说明 |
|------|------|
| **核心理念** | 共识主干 + 异议分支的根茎状（Rhizome）知识沉积 |
| **后端** | Java 17 · Spring Boot 3 · Spring Data Neo4j · LangChain4j / LangGraph4j |
| **前端** | React 19 · TypeScript · React Flow (@xyflow/react 12) · Zustand 5 · Vite |
| **图数据库** | Neo4j 5 — 不可变 DAG + 语义关联双层架构 |
| **异步中间件** | RabbitMQ（消息队列）· Redis（人工复核 TTL 存储） |
| **实时推送** | Server-Sent Events（SSE）— 编排状态 + 图谱增量 |
| **AI 编排** | LangGraph4j 10 节点状态机管线，向量召回 + LLM 裁决 + PreCommit 守卫 |

## 架构概览

```
用户浏览器                         Spring Boot 后端
┌──────────────┐                ┌─────────────────────────────┐
│  React 19    │  POST /posts   │  Controller                 │
│  React Flow  │ ─────────────► │    ↓ 校验 + JWT 鉴权         │
│  Zustand     │  202 Accepted  │    ↓ RabbitMQ 入队           │
│              │ ◄───────────── │                             │
│              │                │  PostConsumer               │
│              │  SSE stream    │    ↓ 创建 Human_Post 节点    │
│              │ ◄═════════════ │    ↓ Embedding 生成          │
│              │  NODE_CREATED  │    ↓ AI 编排管线启动          │
│              │  EDGE_CREATED  │                             │
│              │  DECISION_*    │  LangGraph4j 状态机          │
└──────────────┘                │    VECTOR_RECALL            │
                                │    → CONTEXT_PRUNE          │
     Neo4j 5                    │    → LLM_EVALUATE           │
┌──────────────┐                │    → PRE_COMMIT_GUARD       │
│ DAG 版本演进  │ ◄──────────── │    → EXECUTE_MERGE/BRANCH   │
│ 语义关联层    │  Cypher 写入   │                             │
│ 向量索引      │                └─────────────────────────────┘
└──────────────┘
```

## 模块完成度

下表对齐 `openspec/changes/` 中的各变更集与 `Doc/` 文档章节的映射关系。

| 模块 | openspec 变更集 | 对应文档章节 | 完成度 |
|------|----------------|-------------|--------|
| 核心图存储 | `core-graph-foundation` | 白皮书 §一、开发文档 §6 | ✅ 已完成 |
| 决策引擎 | `decision-engine` | 白皮书 §三.3、开发文档 §7.1 | ✅ 基本完成 |
| 语义关联层 | `semantic-association-layer` | 白皮书 §三.2、开发文档 §6.6 | ✅ 基本完成 |
| 审计与治理 | `audit-governance-layer` | 白皮书 §五、开发文档 §7.3 | ✅ 基本完成 |
| 向量嵌入与搜索 | `embedding-vector-search` | 白皮书 §三.1、开发文档 §7.1 | ✅ 已完成 |
| P0/P1 后端基础 | `p0-p1-backend-foundation` | 开发文档 §2、§5 | ✅ 基本完成 |
| AI 编排层 | `ai-orchestration-layer` | 开发文档 §12 | ✅ 已完成 |
| 前端 DAG 渲染 | — | 前端开发文档 全文 | ✅ 已完成 |
| 动态声誉系统 | — | 白皮书 §六 | ❌ 未启动 |

## 文档导航

| 文档 | 路径 | 说明 |
|------|------|------|
| **白皮书** | [Doc/RhizoDeltΔ 白皮书.md](Doc/RhizoDeltΔ%20白皮书.md) | 项目愿景、理论框架与实施决策纪要 |
| **项目开发文档** | [Doc/项目开发文档.md](Doc/项目开发文档.md) | 后端技术架构、API 规范、数据模型、AI 编排路线 |
| **前端开发文档** | [Doc/前端开发文档.md](Doc/前端开发文档.md) | 前端设计系统、组件规范、React Flow 集成、交互流程 |
| **使用手册** | [Doc/使用手册.md](Doc/使用手册.md) | 面向开发者的实操指南：启动、调试、API 用法 |
| **前端 README** | [frontend/README.md](frontend/README.md) | 前端启动、JWT 调试、页面使用说明 |
| **openspec 任务** | [openspec/changes/](openspec/changes/) | 7 个变更集的 proposal / design / tasks |
| **可观测性 Runbook** | [docs/runbooks/observability.md](docs/runbooks/observability.md) | Prometheus / Grafana 访问、看板解读、容量阈值 |
| **Feature Flags Runbook** | [docs/runbooks/feature-flags.md](docs/runbooks/feature-flags.md) | 全项目 feature flag 命名约定与切换流程 |

## 快速启动

### 1. 准备本地依赖

需要先准备：

- JDK 17+
- Maven
- Node.js 与 npm
- Docker Compose
- 可用的 `SILICON_FLOW_API_KEY`

### 2. 准备 Docker 环境变量

先复制 `.env`：

```bash
cp .env.example .env
```

本地 profile 默认使用的 Neo4j 密码在
`src/main/resources/application-local.yml` 中写死为 `12345678`。
因此建议把 `.env` 至少改成下面这样，避免 Docker 中的 Neo4j 密码和后端配置不一致：

```dotenv
NEO4J_AUTH=neo4j/12345678
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=12345678
NEO4J_URI=bolt://localhost:7687
```

### 3. 启动 Neo4j / RabbitMQ / Redis

```bash
docker compose up -d neo4j rabbitmq redis
```

常用地址：

- Neo4j Browser: `http://localhost:7474`
- Neo4j Bolt: `bolt://localhost:7687`
- RabbitMQ Management: `http://localhost:15672`
- Redis: `localhost:6379`

### 4. 导出 AI 模型 API Key

后端启动时会校验 `langchain4j.open-ai.*.api-key`，为空会直接启动失败。
本地 profile 通过环境变量读取 `SILICON_FLOW_API_KEY`：

```bash
export SILICON_FLOW_API_KEY=your_api_key_here
```

### 5. 启动后端

```bash
mvn spring-boot:run
```

默认会使用 `local` profile。

### 6. 启动前端

```bash
cd frontend
npm install
npm run dev
```

Vite 开发服务器会把 `/api` 代理到 `http://localhost:8080`。

### 7. 准备 JWT

当前项目没有登录接口。前端和 SSE 都依赖浏览器里的
`localStorage.jwt_token`。

JWT 的生成方式、角色说明和浏览器注入步骤见：

- [项目使用手册](Doc/使用手册.md#jwt-本地调试)
- [前端说明](frontend/README.md#jwt-调试)

## 常用验证命令

后端测试：

```bash
mvn test -Dspring.profiles.active=test
```

前端构建：

```bash
cd frontend
npm run build
```

前端 Lint：

```bash
cd frontend
npm run lint
```

## 可观测性

项目自带 Prometheus + Grafana 监控栈，对所有 LLM 调用透明埋点（token / latency / errors）。详见 [docs/runbooks/observability.md](docs/runbooks/observability.md)。一行启用：`docker compose up -d prometheus grafana`，访问 http://127.0.0.1:3000 看 "AI Cost & Latency" 看板。可观测性与所有 AI 能力都有独立 feature flag，详见 [docs/runbooks/feature-flags.md](docs/runbooks/feature-flags.md)。

## 项目入口

| 入口 | 路径 |
|------|------|
| 后端启动类 | `src/main/java/com/rhizodelta/RhizoDeltaApplication.java` |
| 后端配置 | `src/main/resources/application.yml` |
| 本地配置 | `src/main/resources/application-local.yml` |
| 前端入口 | `frontend/src/App.tsx` |
| 主工作区 | `frontend/src/components/GraphWorkspace.tsx` |
| Docker 编排 | `docker-compose.yml` |
