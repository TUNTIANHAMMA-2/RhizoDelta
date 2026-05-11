# Feature Gap Analysis — Excluding Dynamic Reputation

**Date**: 2026-05-08
**Scope**: 全仓代码 × openspec 变更集 × 白皮书 / 开发文档 / 前端开发文档 的交叉比对
**Exclusion**: 动态声誉系统（白皮书 §六，README 已标记为 ❌ 未启动）

本文档梳理除动态声誉外，当前 RhizoDelta 项目仍缺失的功能模块、治理闭环与工程能力，并给出优先级建议。结论基于：

- `src/main/java` 与 `frontend/src` 的实际实现
- `openspec/changes/` 下所有未归档变更集的 `tasks.md`
- `Doc/RhizoDeltΔ 白皮书.md` 附录 A 的实施决策纪要
- `Doc/项目开发文档.md` §7 与 §12 的需求描述

---

## 一、核心 AI / 图谱语义能力（白皮书已要求但未实现）

### 1. 漫游智能体（Background Sweeper Agent）/ 自动跨域缝合

- 白皮书 §三.2、开发文档 §7.2 明确要求"开发者在编写定时任务时，需实现该类智能体在系统低负载时段的跨域扫描逻辑"；白皮书附录 A 亦将其标记为"自动化漫游智能体待规划"。
- 当前只有 `AssociationService` 的手工 CRUD（`POST /api/associations`），没有任何定时任务 / 调度器扫描不同主干以生成 `CONCEPTUAL_OVERLAP` / `RELATES_TO` 候选。
- 代码中搜不到 `Sweeper`、`@Scheduled` 相关的跨域扫描逻辑。
- **影响**：白皮书的"根茎状知识网络"实际仍依赖人工画连线。

### 2. 偏好信号聚合任务（PreferenceEvent → PREFERS 投影边）

- `phase3-4-personalization-volatile-migration` 中 `PreferenceEvent` 节点只完成了"追加写入"（task 7.1~7.4），但 task 7.5 明确写着 "Define PREFERS projection edge schema contract in domain model (no aggregation job implementation)"。
- 即：事件在写，但没有任何 job 把 `PreferenceEvent` 聚合成 `PREFERS` 边，也没有推荐排序读取这些数据。个性化 feed 目前仅基于 `FOLLOWS` / `MUTED` 的图遍历，没用上浏览 / 停留 / 展开信号。

### 3. 社区"共识重组提案" / 人工仲裁（Pull Request 式）

- 白皮书 footnote [^5] 定义了"高权重用户一键发起共识重组提案，强制阻断 AI 自动 Merge，交由社区委员会投票裁决"的机制。
- 当前 `ReviewTaskService` 只处理 AI 主动抛出的复核（PreCommitGuard 分流），没有用户主动挑战 AI 既有决策、拉取社区投票的端点或 UI。
- 只能靠 `ROLE_ADMIN` 的 `POST /api/decisions/{id}/rollback` 一刀切回滚，中间没有讨论 / 投票的治理流程。

---

## 二、治理与运营能力

### 4. 内容举报 / 内容审核

- 代码与前端全仓 grep，没有 "report / 举报" 的接口或组件。
- 没有帖子隐藏、打标签、冻结节点的运营操作；`OPERATED` 关系目前只挂在 rollback 路径上。

### 5. 管理员后台 / 审核控制台

- `SecurityConfig` 声明了 `ROLE_ADMIN` 并保护 rollback 端点，但没有：
  - 管理员专用的前端页面（`frontend/src/components/auth`、`settings` 下没有 admin UI）
  - 用户列表 / 用户状态变更界面（`UserStatusService` 存在，但没有对应的管理端 API 和 UI）
  - 模型切换、阈值调优的运行时配置入口

### 6. 节点 / 帖子级别的作者自助撤回

- 作者本人不能对自己刚发的帖子撤回；只有决策级 rollback 走管理员路径。

---

## 三、可观测性与运维

### 7. 指标 / 监控

- `src/main` 全仓没有 `micrometer`、`prometheus`、`@Timed`、`management.endpoints`。
- `SecurityConfig` 放通了 `/actuator/health`，但 `application.yml` 不暴露任何 management endpoint、也没配置 metrics registry。
- AI 编排的关键数据（LLM 延迟、token 消耗、规则跳过率、反思重试次数）没有持续可观测的输出。

### 8. 分布式追踪

- 无 OpenTelemetry / Sleuth。一条请求跨 `PostController → RabbitMQ → PostConsumer → LangGraph4j → Neo4j → SSE` 时没有串起来的 trace id。

### 9. 审计导出 / 保留策略

- `/api/audit/decisions` 只支持游标分页查询，无批量导出、无保留期归档策略。

---

## 四、检索与发现

### 10. 全文检索 / 关键字搜索

- 仅有两种：节点向量相似搜索（依赖 `has_embedding=true`）+ 前端 Command Palette 对已加载节点做 content 子串匹配。
- Neo4j full-text index（`CALL db.index.fulltext.createNodeIndex`）未建立；当节点 embedding 为空或用户想要精确关键字时，无法检索。

### 11. Topic 目录 / 热门话题

- Phase 3 已经写了 `Topic` 节点和 `rhizodelta_topic_topic_id_unique` 约束，但前端没有"按 Topic 浏览"的入口、没有"热门 Topic"排行，Topic 只是 `FOLLOWS` 的一种目标类型。

---

## 五、用户端 / 社交

### 12. 他人公开资料页

- 后端 `GET /api/users/{user_id}/profile` 已实现（`user-authored-edge` change），但前端只有 `/login`、`/`、`/workspace`、`/settings` 四条路由，没有 `/users/:user_id` 页面，点别人头像或 `AuthorLabel` 无处可去。

### 13. 关注者列表 / 互关状态

- `FOLLOWS` 只做了自己关注谁的 CRUD，没有"谁关注了我"、"是否互关"的 API 与 UI。

---

## 六、AI 透明度 / 可解释性

### 14. 完整决策回放 / LangGraph4j 节点执行时间线

- `DecisionCard` 展示的是 `DecisionExplanation` 汇总；但 `AiRoutingState.EXECUTED_NODES`（appender channel）承载了完整执行链，前端没有把它展开成节点级别的时间线 / 耗时。

### 15. 用户手动覆写 AI 决策

- 不存在"把这次 MERGE 改成 BRANCH 重放"的端点，只能通过 rollback 完全回退后重新发帖。

---

## 七、文档 / 任务跟踪漂移（小坑）

这些不算功能缺失，但反映状态与文档不一致：

- **README 写着**"当前项目没有登录接口"，实际 `AuthController` 有 `/api/auth/register|login|logout|refresh`，前端也有 `LoginPage.tsx`。
- **openspec 未归档的已完成 change**：
  - `user-authored-edge/tasks.md` 所有条目仍是 `[ ]`，但代码里 `AUTHORED` 边、`AuthoredMaintenanceService`、`DatabaseInitializerAuthoredSchemaIntegrationTest` 都已存在且集成测试通过——change 未走 archive。
- **openspec tasks 漏勾**（对照代码实际上部分已完成，需回勾 / 补测）：
  - `semantic-association-layer`：4.1、4.3、5.15、6.3
  - `p0-p1-backend-foundation`：3.5（非法 target_node_id 单测）、5.3（read-only cycle check 回归）、7.6（manual ack + 3 retry）、8.9（dimension mismatch 单测）、9.1、9.9
  - `decision-engine`：3.3 / 4.2 的原子 Cypher、6.3 / 6.6 / 6.7 / 6.8 的多项集成测试
  - `audit-governance-layer`：2.1 / 2.2 的 `decision_id` 关系属性索引（`DatabaseInitializer` 里确实没看到这两条 index DDL）

---

## 建议的优先级

| 优先级 | 缺失项 | 理由 |
|---|---|---|
| **P0** | 漫游智能体、PreferenceEvent 聚合 | 白皮书核心卖点，现在跑的是"裸 GraphRAG"，不符合"根茎"叙事 |
| **P0** | 可观测性（metrics / tracing） | AI 编排 4 阶段已落地但无法衡量成本 / 质量回归 |
| **P1** | 内容举报 / 管理员后台 / 共识重组提案 | 治理闭环缺位，生产上线风险高 |
| **P1** | 全文检索、Topic 目录、他人资料页 | 用户侧 DAU / 留存核心路径 |
| **P2** | AI 决策时间线、手动覆写、审计导出 | 运营调参与信任度建设 |
| **P2** | openspec tasks 回勾 / README 文案校准 | 文档与实现对齐，降低新人上手成本 |

这些缺口中没有一个重新开工量堪比"动态声誉"，但 P0 的两项（漫游智能体 + 可观测性）是目前把 AI 编排层从 demo 推向生产必须补齐的；P1 的治理与社交则是向外开放注册前必须打平的。

---

## 验证路径（复现本分析的方式）

1. **代码侧**
   - `grep -r "Sweeper\|@Scheduled" src/main` → 确认无跨域扫描任务
   - `grep -r "micrometer\|management.endpoints" src/main` → 确认无可观测性配置
   - `grep -r "report\|举报" src frontend/src` → 确认无内容举报路径
   - `grep -r "/users/:user_id" frontend/src` → 确认无公开资料页路由

2. **openspec 侧**
   - `ls openspec/changes/` vs `ls openspec/changes/archive/` → 识别未归档但已落地的 change
   - 逐个 `tasks.md` 对照 `src` 代码回勾

3. **文档侧**
   - 对照 `Doc/RhizoDeltΔ 白皮书.md` 附录 A 的"选定实施策略"列与代码目录
   - 对照 `Doc/项目开发文档.md` §7.2（跨域缝合）和 §12（AI 编排路线）
