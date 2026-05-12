# AGENTS.md — AI 代理协作指南

本文件为在 RhizoDelta 仓库中工作的 AI 代理（Claude Code、Codex、Gemini、Hermes 子代理等）
定义统一的代码规范、Git 工作流、测试要求与文档更新规则。

## 1. 代码规范

### 1.1 后端（Java 17 + Spring Boot 3）

- **包结构**：所有代码在 `com.rhizodelta` 下，按域分包：
  - `core` — 帖子、关联等核心域
  - `consensus` — 决策、审计、复核、回滚
  - `ai` — AI 编排（routing / context / summary / quality / shared）
  - `query` — 节点查询 API
  - `infrastructure` — config / messaging / persistence / security / sse / observability / user
- **技术约定以 CLAUDE.md §5 为准**：API 返回结构、不可变 DAG、关系/节点标签、request_id、角色枚举等已在 CLAUDE.md 中定义。此处不重复。
- **Agent 行为约束**：
  - 禁止 `UPDATE` 历史节点，任何数据修改通过新增节点完成
  - 发帖、决策接口必须透传 `request_id`
  - JWT roles 写 `USER` / `AGENT` / `ADMIN`，不带 `ROLE_` 前缀
- **不要将真实密钥/邮箱/手机号写入文档、示例或测试数据**

### 1.2 前端（React 19 + TypeScript + Zustand 5）

- **组件规范**：按功能分组（auth / brand / chrome / editor / graph / home / modals / panels / sidebar / settings / shared / search）
- **状态管理**：Zustand store 按域拆分（authStore / graphStore / sseStore / homeStore / uiStore）
- **设计系统**：Wikipedia/Notion 文学风，暖白底 `#FAFAF8`，衬线字体用于内容、无衬线用于控件
- **路由**：React Router 7，`AuthGuard` 保护认证路由
- **API 封装**：统一使用 `frontend/src/api/client.ts` + 各域 API 模块

### 1.3 通用规范

- **中文注释优先**（项目以中文团队为主）
- **Git 提交信息**：遵循 conventional commits
  - `feat:` / `fix:` / `docs:` / `refactor:` / `chore:` / `test:`
  - 例如：`feat(consensus): add PENDING_EVALUATION edge type`
## 2. Git 工作流

- **分支策略**：feature 分支 → `main`
- **提交前检查**：
  ```bash
  mvn test -Dspring.profiles.active=test   # 后端测试
  cd frontend && npm run lint              # 前端 lint
  ```
- **不要 force push 到 main**
- **gitignore 约定**：`Doc/`、`frontend/README.md` 已在历史上被 tracked，更新这些文件仍会进入 git diff；但其他 `.gitignore` 中的目录（`data/`、`Pic/`、`.idea/` 等）不要新增未跟踪文件

## 3. OpenSpec / opsx 工作流

本项目使用 OpenSpec 管理模块化变更提案。AI agent 改动 spec 是高频路径：

| 场景 | 命令/入口 | 说明 |
|------|----------|------|
| 提出新变更 | `/opsx:propose` 或 `openspec-propose` skill | 创建 `openspec/changes/<name>/` 包含 proposal / design / tasks |
| 实施已批准变更 | `/opsx:apply` 或 `openspec-apply-change` skill | 按 tasks.md 逐项落地 |
| 完成后归档 | `/opsx:archive` 或 `openspec-archive-change` skill | 移动到 `openspec/changes/archive/` |
| 探索现有 spec | `openspec-explore` skill | 了解已有提案与固化的 specs |

参见 `.claude/commands/opsx/` 和 `.claude/skills/openspec-*`。

## 4. 测试要求

### 4.1 后端

- **测试框架**：JUnit 5 + Spring Boot Test + Testcontainers（Neo4j / RabbitMQ）
- **测试 profile**：`-Dspring.profiles.active=test`（使用 testcontainers，不依赖外部服务）
- **运行命令**：
  ```bash
  mvn test -Dspring.profiles.active=test          # 全量
  mvn -Dtest=<TestClass> test -Dspring.profiles.active=test  # 单类
  ```
- **测试范围**：新功能必须有单元测试，关键路径（决策、编排、SSE）必须有集成测试
- **测试数据脱敏**：所有测试中的用户 ID、API Key 使用占位符

### 4.2 前端

- **测试工具**：vitest（已安装）
- **运行命令**：`cd frontend && npx vitest`
- **lint**：`cd frontend && npm run lint`

## 5. 文档更新规则

### 5.1 文档分类与更新触发条件

| 文档 | 更新触发条件 |
|------|-------------|
| CLAUDE.md | 技术栈变更、目录结构重组、重要新命令、新 feature flag |
| 白皮书 | 架构理念变更、核心设计原则调整 |
| 项目开发文档 | 后端 API 规范变更、数据模型变更、AI 编排路线调整 |
| 前端开发文档 | 设计系统变更、组件结构重组、技术栈升级 |
| 使用手册 | 启动流程变更、API 用法变更、排障步骤调整 |
| Feature flags runbook | 新增/移除 feature flag，默认值变更 |
| Observability runbook | 仪表盘、阈值、验证步骤变更 |
| 用户域 runbooks | 用户域 schema 变更、迁移流程调整 |
| 设计文档 | 新设计方案、旧方案废弃 |
| Code review 报告 | 每次大型 review 完成后 |
| 测试计划 | 新增手动测试场景 |
| OpenSpec 提案 | 新模块提案、已完成的变更归档到 `archive/` |
| README.md | 模块完成度变更、文档导航新增链接、快速启动步骤调整 |

### 5.2 更新原则

- **文档与代码同步是最高优先级** — 宁可文档超前于代码，不可代码超前于文档
- **修改功能时同步更新对应文档**
- **新增 feature flag 时**：同步更新 `FeatureFlagRegistry.java` 和 `docs/runbooks/feature-flags.md`
- **技术栈版本变更时**：更新 `CLAUDE.md` §2（技术栈）和对应的文档章节
- **OpenSpec 变更集完成时**：更新 `README.md` 的模块完成度表
- **不要删除历史文档**：过时的设计文档移动到 `docs/archive/`，code review 报告保留在 `docs/reviews/`

## 6. 多模型协作

本项目历史上使用多模型协作，相关入口与分工约定见 `.claude/commands/opsx/*`。
当前主要使用 Claude Code 作为开发代理。

## 7. 关键环境变量

| 变量 | 用途 |
|------|------|
| `DASHSCOPE_API_KEY` | DashScope LLM API 密钥（后端启动必需） |
| `NEO4J_AUTH` | Neo4j 认证（Docker Compose 使用） |
| `GRAFANA_ADMIN_PASSWORD` | Grafana 管理员密码 |
| `RHIZODELTA_FEATURE_*_ENABLED` | Feature flag 环境变量覆盖 |
## 8. 参考

- 项目全貌与速查：`CLAUDE.md`
- 设计文档：`Doc/` 目录
- 工程文档：`docs/` 目录
- 变更提案：`openspec/changes/`
- Claude Code 技能指南：参考 `.claude/skills/` 和 `~/.claude/skills/`
