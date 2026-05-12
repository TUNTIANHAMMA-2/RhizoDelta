# CLAUDE.md

本文件为 Claude Code（及兼容 agent harness）在本仓库内工作时的索引与速查。
面向"已经了解项目大方向、但需要快速定位约定/命令/关键路径"的 AI 协作者。

更详细的设计与历史背景见 `Doc/` 与 `docs/` 目录，本文件不重复，只做指引。

## 1. 项目概述

RhizoDelta（RhizoDeltΔ）是一个**基于图谱的非线性讨论系统**：
传统论坛的线性聊天记录被重塑为一棵有主干、能分叉、可合并的知识树，
后台 AI 智能体把用户帖子清洗、归纳，自动路由为"共识合并（MERGE）"或
"异议分支（BRANCH）"，所有演进过程以**不可变的有向无环图（DAG）**
固化在 Neo4j。

关键设计原则：

- **版本演进 DAG + 语义关联层分离**：`BRANCHED_FROM` / `MERGED_INTO`
  仅用于无环 DAG；`CONCEPTUAL_OVERLAP` / `RELATES_TO` 归入语义层，
  允许成环但不参与版本溯源。
- **节点即内容快照**，历史节点默认不可变；任何修改都通过新增节点完成。
- **穿透式确权（SBOM）**：`AI_Consensus` 必须通过 `SYNTHESIZED_FROM`
  指向原始 `Human_Post`，形成可审计的贡献链路。
- **异步最终一致性**：用户发帖 → `202 Accepted` + RabbitMQ → 后台
  AI 编排状态机 → SSE 推送增量到前端。

## 2. 技术栈

| 维度 | 选型 | 关键版本 |
|---|---|---|
| 后端框架 | Spring Boot | 3.2.3，JDK 17 |
| 图数据库 | Neo4j 数据库 5.22 + Spring Data Neo4j（由 Spring Boot 3.2.3 管理） | 5.22 (Neo4j) / 3.2.3 (Spring Boot) |
| 异步消息 | RabbitMQ | 3-management |
| 缓存/复核 TTL | Redis | 7-alpine |
| AI 编排 | LangChain4j + LangGraph4j | 0.36.2 / 1.8.10 |
| LLM 提供方 | DashScope（OpenAI 兼容） | DeepSeek-V4-Flash + text-embedding-v3 |
| 鉴权 | Spring Security + JWT (jjwt) | 0.12.5 |
| 可观测性 | Micrometer + Prometheus + Grafana | — |
| 对象存储（可选） | MinIO | 8.5.10（默认关闭，头像上传用） |
| 前端框架 | React + TypeScript + Vite | 19 / 5.x / ^8.0.0 |
| 前端图谱 | @xyflow/react + @dagrejs/dagre | 12.x / latest |
| 前端状态 | Zustand | 5.x |
| 前端编辑器 | TipTap 3.x + tiptap-markdown 0.9 | TipTap 3.20.5 / tiptap-markdown 0.9.0 |

## 3. 目录结构

```
RhizoDelta/
├── src/main/java/com/rhizodelta/
│   ├── RhizoDeltaApplication.java       # Spring Boot 入口
│   ├── core/                            # 帖子、关联等核心域
│   │   ├── api/                         #   PostController / AssociationController
│   │   ├── domain/  repository/  service/  validation/
│   ├── consensus/                       # 决策、审计、复核、回滚
│   │   ├── api/                         #   DecisionController / AuditController / ReviewController
│   │   ├── domain/  event/  repository/  service/
│   ├── ai/                              # AI 编排
│   │   ├── routing/                     #   LangGraph4j 状态机、规则前置过滤、Reflection
│   │   ├── context/                     #   向量召回 + 上下文剪枝
│   │   ├── summary/                     #   摘要 Agent
│   │   ├── quality/                     #   质量评估 Agent
│   │   └── shared/                      #   ModelRouter / 共享枚举
│   ├── query/                           # 节点查询 API（lineage / children / provenance）
│   └── infrastructure/
│       ├── config/  exception/  web/
│       ├── messaging/                   # RabbitMQ 配置 + PostConsumer
│       ├── persistence/                 # Neo4j schema 初始化器
│       ├── security/                    # JWT 过滤器、AuthController、SecurityConfig
│       ├── sse/                         # SSE 事件总线 + 控制器
│       ├── observability/               # FeatureFlagRegistry、MeteredChatLanguageModel
│       └── user/                        # 用户域（UserProfile / Topic 等）
├── src/main/resources/
│   ├── application.yml                  # 通用配置 + 默认值
│   ├── application-local.yml            # 本地默认（.gitignore，含本地 LLM key）
│   ├── application-test.yml             # 测试 profile
│   └── application.yml.example          # 部署模板
├── src/test/java/                       # JUnit 5 + Spring Boot Test + Testcontainers (Neo4j / RabbitMQ)
├── frontend/
│   ├── src/
│   │   ├── App.tsx                      # 路由根 + AuthGuard
│   │   ├── components/                  # auth / brand / chrome / editor / feedback / forms / graph / home /
│   │   │                                # modals / panels / sidebar / settings / shared / search
│   │   ├── components/GraphWorkspace.tsx  # 主工作区
│   │   ├── api/                         # 后端 API 封装（client / posts / decisions / ...）
│   │   ├── stores/                      # Zustand（authStore / graphStore / sseStore / homeStore / uiStore / ...）
│   │   ├── hooks/                       # useSse / useGraphInteractions / useCommandPalette
│   │   ├── lib/                         # 通用工具与布局算法
│   │   └── styles/                      # tokens.css 等
│   ├── vite.config.ts                   # /api → http://localhost:8080 代理
│   └── package.json
├── docker-compose.yml                   # Neo4j / RabbitMQ / Redis / MinIO / Prometheus / Grafana
├── prometheus/                          # Prometheus 抓取规则
├── grafana/                             # Grafana provisioning（数据源 + 仪表盘）
├── README.md                           # 项目入口与文档导航
├── frontend/README.md                  # 前端启动、JWT 调试说明
├── pom.xml                              # Maven 配置
├── .env / .env.example                  # Docker + Spring 读取的环境变量
├── Doc/                                 # 项目级设计文档（白皮书、开发文档、使用手册）
├── docs/                                # 工程级文档（runbooks / designs / reviews / test-plans / archive）
├── openspec/                            # OpenSpec 变更提案（changes/ + specs/）
└── data/                                # 本地数据（头像上传等，已 gitignore）
```

> 提示：`Doc/`、`Pic/`、`frontend/README.md`、`data/`、`.idea/`、
> `MyResponse/`、`docs/reviews/answers/`、`openspec/` 等已加入 `.gitignore`，
> 但其中 `Doc/` 与 `frontend/README.md` 在历史上已被 tracked，更新这些
> 文件仍会进入 git diff。请遵循 `.gitignore` 中的"不要新增此类未跟踪文件"约定。

## 4. 常用命令

> 工作目录默认为仓库根 `~/workspace/RhizoDelta`。前端命令需要 `cd frontend`。

### 4.1 启动本地依赖

```bash
docker compose up -d neo4j rabbitmq redis
# 可选：可观测性
docker compose up -d prometheus grafana
# 可选：头像上传（需把 application.yml 的 rhizodelta.minio.enabled=true）
docker compose up -d minio
```

### 4.2 后端

```bash
# 启动（默认 profile=local）
export DASHSCOPE_API_KEY=your_dashscope_api_key
mvn spring-boot:run

# 测试（带 testcontainers，需可达 docker daemon）
mvn test -Dspring.profiles.active=test

# 单测
mvn -Dtest=DecisionServiceTest test
```

启动时会做的硬性检查（任何一项失败就拒绝启动）：

1. Neo4j 连通性（`bolt://localhost:7687`）
2. 自动创建约束 / 索引 / 向量索引
3. `langchain4j.open-ai.*.api-key` 非空且非占位符
4. `UserAccount.user_id` 完整性预检（详见 `docs/runbooks/user-identity-integrity.md`）

### 4.3 前端

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173 → /api → http://localhost:8080
npm run build      # tsc -b && vite build
npm run lint
# vitest（package.json 未列脚本，但 vitest 已装；按需 npx vitest）
```

### 4.4 验证 LLM / Observability

```bash
# 后端自身指标
curl -s http://localhost:8080/actuator/prometheus | grep -E "^(ai_llm_|sweeper_)"
# Prometheus targets
open http://127.0.0.1:9090/targets
# Grafana "AI Cost & Latency"
open http://127.0.0.1:3000      # admin / ${GRAFANA_ADMIN_PASSWORD}
```

## 5. 关键约定

- **API 统一返回结构**：`{ code, message, data }`，常用包装类 `infrastructure/web/ApiResponse`。
- **不可变 DAG**：禁止 `UPDATE` 历史节点 / 重写历史关系。任何修订都通过新增节点完成。
  合规纠错与隐私删除有独立 review/rollback 流程（`consensus/service/RollbackService`、`ReviewTaskService`）。
- **request_id 全链路透传**：用于幂等与去重；发帖、决策接口都必填。
- **operator_type=AGENT / HUMAN**：决策审计字段，AI 与人工的写入都走同一通道。
- **节点标签固化**：`Human_Post`、`AI_Consensus`（系统级），以及 `UserAccount`、
  `UserProfile`、`Topic` 等用户域节点（详见 `docs/designs/user-domain-modeling-plan.md`）。
- **关系类型固化**：版本演进层 `BRANCHED_FROM`、`MERGED_INTO`、`CONTINUES_FROM`、
  `SYNTHESIZED_FROM`；语义层 `CONCEPTUAL_OVERLAP`、`RELATES_TO`；用户域 `AUTHORED`、
  `FOLLOWS`、`MUTED`、`HAS_PROFILE`。
- **角色枚举**：JWT 中 `roles` 字段写 `USER` / `AGENT` / `ADMIN`，**不**带 `ROLE_` 前缀，
  后端鉴权阶段自动加前缀。
- **embedding 维度**：1024（`rhizodelta.embedding.dimension`），切换模型时必须同步迁移向量索引。
- **测试 profile**：使用 testcontainers，CI 与本地都不依赖外部 Neo4j/RabbitMQ。
- **不要把真实密钥/邮箱/手机号写进文档、示例、测试数据**。文档与种子脚本中所有用户 ID
  必须使用脱敏占位符（如 `your_mock_user_id_here`）。
- **决策细节**：合并 / 分支的实施约束、Cypher 草案、SSE 事件清单见
  `Doc/项目开发文档.md` §6 / §7 / §12 与 `Doc/使用手册.md`。

## 6. Feature Flags

命名约定：`rhizodelta.feature.<module>.enabled = true | false`，
环境变量等价 `RHIZODELTA_FEATURE_<MODULE>_ENABLED`（`-` 转 `_` 并大写）。

源代码登记表：`infrastructure/observability/FeatureFlagRegistry.java`。
文档登记表：`docs/runbooks/feature-flags.md`（两处必须同步更新）。

当前已注册的 flag：

| Module | Default | 关闭副作用 |
|---|---|---|
| `observability` | `true` | 失去 AI metric，业务功能不变 |
| `sweeper` | `false` | 漫游智能体不调度（依赖此 flag 的 change 待落地） |
| `proposal` | `false` | 提案系统所有 API 返回 503 |
| `prefers-aggregation` | `false` | PreferenceEvent 仍记录但不再聚合到 PREFERS 边 |
| `prefers-feed-ranking` | `false` | Feed 退化为只用 FOLLOWS 排序 |

切换方式与级联效果见 `docs/runbooks/feature-flags.md`。

## 7. 重要文件路径

| 用途 | 路径 |
|---|---|
| 后端入口 | `src/main/java/com/rhizodelta/RhizoDeltaApplication.java` |
| Security 配置 | `src/main/java/com/rhizodelta/infrastructure/security/config/SecurityConfig.java` |
| Auth REST | `src/main/java/com/rhizodelta/infrastructure/security/api/AuthController.java` |
| 帖子提交 | `src/main/java/com/rhizodelta/core/api/PostController.java` |
| 帖子消费（异步管线起点） | `src/main/java/com/rhizodelta/infrastructure/messaging/consumer/PostConsumer.java` |
| 决策（合并/分支/注入/回滚） | `src/main/java/com/rhizodelta/consensus/service/DecisionService.java` |
| AI 状态机 | `src/main/java/com/rhizodelta/ai/routing/service/AiRoutingWorkflowService.java` |
| 规则前置过滤 | `src/main/java/com/rhizodelta/ai/routing/service/RuleBasedPreFilterService.java` |
| Reflection | `src/main/java/com/rhizodelta/ai/routing/service/ReflectionCriticService.java` |
| SSE 事件总线 | `src/main/java/com/rhizodelta/infrastructure/sse/service/` |
| LLM 计量装饰器 | `src/main/java/com/rhizodelta/infrastructure/observability/MeteredChatLanguageModel.java` |
| Feature flag 登记 | `src/main/java/com/rhizodelta/infrastructure/observability/FeatureFlagRegistry.java` |
| Neo4j schema 初始化 | `src/main/java/com/rhizodelta/infrastructure/persistence/` |
| 通用配置 | `src/main/resources/application.yml` |
| 本地配置（gitignored） | `src/main/resources/application-local.yml` |
| 部署模板 | `src/main/resources/application.yml.example` |
| 前端入口 | `frontend/src/App.tsx` |
| 主工作区 | `frontend/src/components/GraphWorkspace.tsx` |
| 登录页 | `frontend/src/components/auth/LoginPage.tsx` |
| API 客户端 | `frontend/src/api/client.ts` |
| 图谱状态 | `frontend/src/stores/graphStore.ts` |
| SSE Hook | `frontend/src/hooks/useSse.ts` |
| Docker 编排 | `docker-compose.yml` |
| Maven 配置 | `pom.xml` |
| 观测看板 provisioning | `grafana/provisioning/` |
| Prometheus 抓取 | `prometheus/prometheus.yml` |

## 8. 文档导航

| 主题 | 路径 |
|---|---|
| 项目愿景 / 理论框架 | `Doc/RhizoDeltΔ 白皮书.md` |
| 后端开发文档（架构 / API / 数据模型 / AI 编排） | `Doc/项目开发文档.md` |
| 前端开发文档（设计系统 / 组件规范） | `Doc/前端开发文档.md` |
| 实操指南（启动 / API 用法 / 排障） | `Doc/使用手册.md` |
| 可观测性 runbook | `docs/runbooks/observability.md` |
| Feature flags runbook | `docs/runbooks/feature-flags.md` |
| 用户身份完整性 | `docs/runbooks/user-identity-integrity.md` |
| UserProfile 回填 | `docs/runbooks/user-profile-backfill.md` |
| AUTHORED 边维护 | `docs/runbooks/user-authored-edge-maintenance.md` |
| 用户域建模设计 | `docs/designs/user-domain-modeling-plan.md` |
| 已归档设计 | `docs/archive/` |
| Code review 报告 | `docs/reviews/` |
| 手动测试用例 | `docs/test-plans/` |
| OpenSpec 提案 | `openspec/changes/`（含 `archive/`） |
| OpenSpec 已固化 spec | `openspec/specs/` |

## 9. AI Agent 协作

详见根目录 `AGENTS.md`：代码规范、Git 工作流、测试要求、文档更新规则、
多模型协作（Claude / Codex / Gemini）的分工边界等。
