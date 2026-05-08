# RhizoDelta 用户建模重构设计

> 作者：架构分析报告 · 2026-04-21 初稿 → 2026-05-06 对齐实际进度
> 依据：`src/main/java/com/rhizodelta/**` 实现现状
> 文档性质：**设计方案 + 实施进度追踪**
> 归档：原始版本见 `docs/archive/user-domain-modeling-plan_2026-04-21_original.md`

---

## 0. 摘要

本文档原始版本（2026-04-21）识别了用户域三个结构缺口，并给出四阶段渐进迁移方案。截至 2026-05-06，**Phase 0 ~ Phase 2 已全部落地，Phase 3 核心 CRUD 已落地，Phase 4 三项外移已完成**。

剩余待做项：
- `REVIEWED` / `OPERATED` 审计关系（Phase 3 深度能力）
- `PREFERS` 聚合投影 job（Phase 3 推荐前置依赖）
- 前端交互层：Follow/Mute UI、userProfileStore 缓存、For you 排序
- 头像存储迁移到 MinIO / S3

---

## 1. 已实现的数据模型（当前现状）

### 1.1 数据模型

| 维度 | 当前状态 | 实现证据 |
|---|---|---|
| 用户节点 | `(:UserAccount {user_id, username, password_hash, roles, status, created_at})` | `AuthController.java:62-86` |
| 画像节点 | `(:UserProfile {user_id, display_name, avatar_url, language, timezone, theme, notification_prefs, updated_at})` | `UserProfileController.java:34-45` |
| 节点关系 | `(:UserAccount)-[:HAS_PROFILE]->(:UserProfile)` (1:1) | `AuthController.java:71-77` |
| 唯一约束 | `UserAccount.username` UNIQUE + `UserAccount.user_id` UNIQUE + `UserProfile.user_id` UNIQUE | `DatabaseInitializer.java:103-106` |
| `user_id` 约束 | UNIQUE 约束（自带 lookup 索引） | `DatabaseInitializer.java:104` |
| `status` 索引 | `UserAccount.status` INDEX | `DatabaseInitializer.java:105` |
| 作者关联 | `Human_Post.author_id` 属性（投影）+ `(:UserAccount)-[:AUTHORED]->(:Human_Post)` 显式关系（权威） | `PostService.java:24-63` |
| 个性化关系 | `FOLLOWS` + `MUTED` 关系 + 索引 | `DatabaseInitializer.java:110-111` |
| 领域对象 | `Topic` 节点 + UNIQUE 约束 | `DatabaseInitializer.java:107` |

### 1.2 认证与授权

| 维度 | 当前状态 | 实现证据 |
|---|---|---|
| 认证协议 | 无状态 JWT，HS 签名，TTL 8h | `AuthController.java:40` |
| JWT claims | `sub=user_id`, `roles`, `username`, `display_name`, `jti` | `AuthController.java:228-239` |
| Refresh Token | ✅ Redis 存储，原子轮换 + 盗用检测 | `RefreshTokenService.java` |
| Token 撤销 | ✅ Redis 黑名单，jti 级 + 用户级双粒度 | `TokenBlacklistService.java` |
| 用户状态枚举 | `UserStatus.ACTIVE / SUSPENDED / DELETED` | `UserStatus.java` |
| 权限模型 | Spring Security `hasRole("ADMIN"/"AGENT")` | `SecurityConfig.java` |
| 作者身份源 | 写入时以 JWT `sub` 为准 + 写入前校验 author 存在 | `PostService.java:119, 245-253` |

### 1.3 写入与查询路径

- **写入**：`PostController → RabbitMQ → PostService.createHumanPost`，同一事务内写 `author_id` 属性 + `AUTHORED` 边。
- **读取**：`NodeQueryService` 核心查询（LINEAGE / CHILDREN）以 `n.author_id` 属性投影，后置批量补全 `authorUsername` / `authorDisplayName`（`enrichAuthorProjections` + `AUTHOR_PROJECTION_QUERY`）。
- **一致性保障**：`AuthoredMaintenanceService` 提供回填 + 对账能力。

### 1.4 前端现状

- `GraphNodeDTO` 含 `author_id` + `author_username` + `author_display_name`（`types.ts:37-39`）。
- `AuthorLabel` 通用组件（`components/shared/AuthorLabel.tsx`），5 处已替换。
- 优先级：`displayName > username > agentVersion > "Anonymous"`，不再暴露 UUID。
- `UserProfile` / `PublicUserProfile` / `FollowItem` / `MuteItem` / `FeedResponse` 等类型已定义。

---

## 2. 设计原则（三条红线）

1. **`user_id` 是全局唯一、不可变的 canonical identity**；`username` / `display_name` 只是可变登录名或展示属性。
2. **图关系承载可遍历的个性化语义**；属性只保留必要投影和查询优化字段。
3. **认证层 / 资料层 / 个性化层逻辑分层**：读画像的查询不应天然接触凭据；读偏好的查询不应触发凭据反序列化。

---

## 3. 目标模型

### 3.1 节点 ✅ 已实现

```
(:UserAccount {user_id, username, password_hash, roles, status, created_at})
     │
     │ [:HAS_PROFILE] (1:1)
     ▼
(:UserProfile {user_id, display_name, avatar_url, language, timezone, theme, notification_prefs, updated_at})
```

### 3.2 关系

| 关系 | 语义 | 边属性 | 起止 | 状态 |
|---|---|---|---|---|
| `AUTHORED` | 用户创作了某内容节点 | `created_at` | `UserAccount → Human_Post` | ✅ 已实现 |
| `REVIEWED` | 用户审核了某个决策 | `decision_id`, `outcome`, `at` | `UserAccount → Decision` | 🔲 待做 |
| `OPERATED` | 用户执行过某次运营动作（admin） | `operation_id`, `at` | `UserAccount → GraphNode` | 🔲 待做 |
| `FOLLOWS` | 关注主题/节点/用户 | `since`, `follow_id` | `UserAccount → {Topic\|GraphNode\|UserAccount}` | ✅ 已实现 |
| `MUTED` | 屏蔽主题/用户 | `since`, `reason`, `mute_id` | `UserAccount → {Topic\|UserAccount}` | ✅ 已实现 |

> **实现细节偏差**：FOLLOWS / MUTED 的删除路径使用 `follow_id` / `mute_id` 寻址（而非原设计的 `target_id`），这是更好的 RESTful 设计，为边属性扩展留出空间。

### 3.3 个性化信号（不走边属性）

**热路径（✅ 已实现）**：`PreferenceEventService` 写事件节点。

```
(:UserAccount)-[:EMITTED]->(:PreferenceEvent {event_id, type, weight, at, source_node_id})-[:TOWARD]->(:Topic)
```

**冷路径（🔲 待做）**：离线或近线聚合成 `(:UserAccount)-[:PREFERS {score, updated_at}]->(:Topic)` 投影边，**投影由 job 重算，不在热路径写**。

### 3.4 投影属性（保留） ✅ 已实现

- `Human_Post.author_id`：**保留**。作为显式关系的查询投影，避免热路径读取都走两跳。
- 写入方向：以 `AUTHORED` 边为真实语义来源；`author_id` 属性在同一事务内同步。
- 读优先级：默认读属性；需要遍历能力（如"找出同一作者的其他帖子"）时走关系。

---

## 4. 分阶段迁移计划 — 实施进度

> 每个阶段都有：**schema 变更 · 代码变更 · 回填脚本 · 验证面 · 回滚方案**。

### Phase 0 · 固化身份 ✅ 已完成

**目标**：把 `user_id` 提升为真正受约束的 canonical key。

**Schema 变更**：

```cypher
-- ✅ 已在 DatabaseInitializer.SCHEMA_QUERIES 中
CREATE CONSTRAINT rhizodelta_user_account_user_id_unique
  IF NOT EXISTS FOR (n:UserAccount) REQUIRE n.user_id IS UNIQUE;
CREATE INDEX rhizodelta_user_account_status_idx
  IF NOT EXISTS FOR (n:UserAccount) ON (n.status);
```

**启动预检**：✅ `verifyUserIdIntegrity()` 在 `initializeSchema` 首行执行，检查空白 user_id 和重复 user_id，任一违规阻断启动。

**代码变更**：
- ✅ `CREATE_USER_QUERY` 写入 `status = UserStatus.ACTIVE.name()`
- ✅ `UserStatus` 枚举（ACTIVE / SUSPENDED / DELETED）
- ✅ 登录时检查 `status != ACTIVE` 则拒绝

---

### Phase 1 · 分层实体（认证/画像解耦） ✅ 已完成

**目标**：拆出 `UserProfile`，凭据读取不触及展示字段。

**Schema 变更**：

```cypher
-- ✅ 已在 DatabaseInitializer.SCHEMA_QUERIES 中
CREATE CONSTRAINT rhizodelta_user_profile_user_id_unique
  IF NOT EXISTS FOR (n:UserProfile) REQUIRE n.user_id IS UNIQUE;
```

**代码变更**：
- ✅ `CREATE_USER_QUERY` 单事务写 `UserAccount` + `UserProfile` + `HAS_PROFILE`，`FOREACH` 守护
- ✅ `FIND_USER` 查询追加 `OPTIONAL MATCH HAS_PROFILE`，从 profile 读 `display_name`
- ✅ `resolveDisplayName` fallback helper
- ✅ JWT claim 保留 `display_name`

**启动期回填**：✅ `migrateLegacyUserProfile()` — 幂等、分批（500/批）、fail-close。

**新增接口**：
- ✅ `GET /api/users/me/profile`
- ✅ `PUT /api/users/me/profile`（含 empty body → 400 校验）
- ✅ `GET /api/users/{user_id}/profile`（提前于原 Phase 2 计划落地，含 UNAVAILABLE 状态处理）
- ✅ `GET /api/users/{user_id}/avatar`（超出原设计，AvatarStorageService + presigned URL）

---

### Phase 2 · 显式作者关系（双轨期） ✅ 已完成

**目标**：引入 `(:UserAccount)-[:AUTHORED]->(:Human_Post)` 作为权威语义，`author_id` 降级为投影。

**Schema 变更**：

```cypher
-- ✅ 已在 DatabaseInitializer.SCHEMA_QUERIES 中
CREATE INDEX rhizodelta_authored_created_at_idx
  IF NOT EXISTS FOR ()-[r:AUTHORED]-() ON (r.created_at);
```

**写路径**：✅ `PostService.createHumanPost` 同事务写 `author_id` 属性 + MERGE `AUTHORED` 边。写入前校验 author 存在。

**读路径**：
- ✅ Batch A：核心查询（LINEAGE / CHILDREN / NODE_SUMMARY）仍用 `n.author_id` 属性投影
- ✅ 后置补全：`enrichAuthorProjections()` 批量查 `AUTHOR_PROJECTION_QUERY`，读 `HAS_PROFILE` 补全 `authorUsername` / `authorDisplayName`
- ✅ `FeedService` 已使用 `AUTHORED` 边遍历（`-[:AUTHORED]->`）

> **实现路径偏差**：原设计为 LINEAGE_QUERY 内联 `OPTIONAL MATCH HAS_PROFILE`；实际使用分离的后置补全查询。语义等价，已解决 N+1 问题。

**回填 + 对账**：✅ `AuthoredMaintenanceService` 提供 `runAuthoredBackfill()` + `findMissingAuthoredEdges()`。

**DTO 变更**：✅ `GraphNodeDTO` 含 `author_username` / `author_display_name`；`LineageNode` 含对应字段。

---

### Phase 3 · 个性化关系 ⚠️ 部分完成

**目标**：把"关注 / 屏蔽 / 审核 / 偏好"纳入图关系，为推荐与治理提供可遍历语义。

**Schema 变更**：

```cypher
-- ✅ 已在 DatabaseInitializer.SCHEMA_QUERIES 中
CREATE INDEX rhizodelta_follows_since_idx
  IF NOT EXISTS FOR ()-[r:FOLLOWS]-() ON (r.since);
CREATE INDEX rhizodelta_muted_since_idx
  IF NOT EXISTS FOR ()-[r:MUTED]-() ON (r.since);
CREATE CONSTRAINT rhizodelta_topic_topic_id_unique
  IF NOT EXISTS FOR (n:Topic) REQUIRE n.topic_id IS UNIQUE;
```

**已落地的接口**：

| 方法 | 路径 | 含义 | 状态 |
|---|---|---|---|
| GET | `/api/users/me/follows` | 我关注的所有对象 | ✅ |
| POST | `/api/users/me/follows` | 关注 | ✅ |
| DELETE | `/api/users/me/follows/{follow_id}` | 取消关注 | ✅ |
| GET | `/api/users/me/mutes` | 我屏蔽的对象 | ✅ |
| POST | `/api/users/me/mutes` | 屏蔽 | ✅ |
| DELETE | `/api/users/me/mutes/{mute_id}` | 取消屏蔽 | ✅ |
| GET | `/api/users/me/feed` | 个性化 feed | ✅ |
| GET/POST | `/api/users/me/online-status` | 在线状态 | ✅ 超出原设计 |

**Feed 实现详情**：三路候选（关注用户 AUTHORED → 内容、关注 Topic → 内容、关注 GraphNode → 后代）+ MUTED 过滤 + 无关注时回退全局最新。

**偏好信号**：
- ✅ 热路径：`PreferenceEventService` 写事件节点。
- 🔲 离线 job：聚合成 `PREFERS {score, updated_at}` 边投影 — **未实现**。

**未实现的关系**：
- 🔲 `REVIEWED`：用户审核决策后无显式关系，结果仅作为 Decision 属性记录。
- 🔲 `OPERATED`：管理员运营动作无独立关系，operator_id 仅作为边属性存在。

---

### Phase 4 · 高频易失态外移 ✅ 基本完成

**目标**：把不属于图的数据挪出 Neo4j。

| 数据 | 目标存储 | 状态 | 实现证据 |
|---|---|---|---|
| Token 撤销黑名单 | Redis（TTL = JWT exp） | ✅ | `TokenBlacklistService.java`，jti 级 + 用户级双粒度 |
| Refresh Token | Redis（原子轮换 + 盗用检测） | ✅ | `RefreshTokenService.java` |
| 在线状态 / 最近活跃 | Redis（TTL 5min 心跳） | ✅ | `OnlineStatusService.java` |
| 头像大图 / 附件 | 对象存储（MinIO / S3） | ⚠️ | `AvatarStorageService.java` 已实现 presigned URL，但当前存储在本地文件系统 |

**不引入 PostgreSQL**。只有当出现账单、强事务 OLTP、外部 IAM 对接、复杂表式报表时才单独评估。

---

## 5. 前端联动设计

### 5.1 类型扩展 ✅ 已实现

```ts
// frontend/src/api/types.ts — 已落地
export interface GraphNodeDTO {
  node_id: string;
  label: NodeLabel;
  content?: string;
  summary_content?: string;
  author_id?: string;
  author_username?: string;     // ✅ Phase 2 已落地
  author_display_name?: string; // ✅ Phase 2 已落地
  agent_version?: string;
  created_at: string;
  has_embedding: boolean;
  quality_overall?: number;
}
```

附加类型 `UserProfile` / `PublicUserProfile` / `FollowItem` / `MuteItem` / `FeedResponse` / `OnlineStatus` / `AuthSessionPayload` 均已定义。

### 5.2 通用作者标签组件 ✅ 已实现

```tsx
// frontend/src/components/shared/AuthorLabel.tsx — 已落地
// 优先级：displayName > username > agentVersion > "Anonymous"
// 不再回退到 author_id（UUID 不应外露）
```

**5 处替换**：✅ `RhizomeCard.tsx` · `NodeDetailPanel.tsx` · `ProvenancePanel.tsx` · `HumanPostNode.tsx` · `RhizoneList.tsx`。

> **实现偏差**：实际组件接收 `displayName / username / authorId / agentVersion` 四个独立 props，而非原设计的 `{ node: GraphNodeDTO }`。更灵活。

### 5.3 其他用户解析 🔲 待做

```ts
// frontend/src/stores/userProfileStore.ts — 未实现
// LRU 缓存 user_id → UserProfile，TTL 10 min
// 当 DTO 未带 author_username 时，按需拉 /api/users/{user_id}/profile
```

当前后端已通过 `enrichAuthorProjections` 在服务端批量补全，前端直接消费 DTO 中的 `author_display_name`，因此 **userProfileStore 的必要性降低**，仅在需要他人完整 profile 面板时有价值。

### 5.4 关注 / 屏蔽 UI 🔲 待做

- 🔲 节点详情面板头部增加 ⭐ Follow / 🔇 Mute 按钮。
- 🔲 Home 侧栏 `Streams` 增加 `Following` 入口。
- 🔲 `HomeMainColumn` 排序新增 `For you`（基于 FOLLOWS + PREFERS 投影）。

> 后端 API（Follow / Mute / Feed）已全部就绪，前端消费层是当前最大短板。

---

## 6. 验证面（每阶段都要过）

| 验证项 | 对应阶段 | 状态 | 方法 |
|---|---|---|---|
| `user_id` 唯一性 | P0 | ✅ | 启动预检 + UNIQUE 约束 |
| `username` 改名后历史归属不变 | P0+P2 | ✅ | `author_id` 不可变，`AUTHORED` 边不变 |
| 凭据读路径不触及 `display_name` | P1 | ✅ | `display_name` 迁至 UserProfile 节点 |
| 作者边与属性一致率 100% | P2 | ✅ | `AuthoredMaintenanceService` 回填 + 对账 |
| 用户删除后内容与审计保留 | P2+ | ✅ | `status=DELETED` 软删，`AUTHORED` 保留 |
| FOLLOWS/MUTED 遍历语义 | P3 | ✅ | FeedService 三路候选已走关系遍历 |
| 旧客户端兼容 | 全程 | ✅ | `author_username` 缺失时 AuthorLabel 降级 |
| 迁移可回滚 | 每阶段 | ✅ | 原文档回滚脚本仍适用 |

---

## 7. 风险与对策（当前状态）

| 风险 | 原始评估 | 当前状态 |
|---|---|---|
| Phase 0 启动预检发现历史 `user_id` 为空 | 中概率 | ✅ 已解决，预检已上线 |
| Phase 2 热路径增加 MERGE 边写入 | 中概率 | ✅ 已落地，同事务写入 |
| 回填脚本超时 | 低概率 | ✅ 已实现分批回填 |
| 一致性漂移（属性与边） | 中概率 | ✅ `AuthoredMaintenanceService` 定期对账 |
| 软删除与 AUTHORED 保留冲突 | 低概率 | ✅ UserStatus 枚举 + 公开 profile 返回 UNAVAILABLE |
| JWT 黑名单未外移 | 已知 | ✅ 已外移 Redis |

---

## 8. 不做 / 延后

- ✅ ~~**不换关系型数据库**~~。维持。
- ✅ ~~**不强行改 JWT 为 Session**~~。维持，Redis 黑名单已补齐撤销能力。
- ✅ ~~**不为偏好边写带权重属性**~~。维持，`PreferenceEvent` 节点 + 聚合投影替代。
- ~~**不在 Phase 2 之前动前端视图层**~~。Phase 2 已完成，前端联动已部分实现。

---

## 9. 已确认的决策

> 原第 9 节为"待确认"问题，以下为实际采纳的方向。

1. **个性化范围**：全部纳入，分阶段落地。核心 CRUD 已就绪，推荐信号聚合推迟。
2. **中期存储边界**：用户域全在 Neo4j，易失态外移 Redis。已采纳并实施。
3. **`UserAccount` 一等公民**：`AUTHORED` 为权威语义，`author_id` 为投影。已采纳并实施。
4. **Phase 2 回填窗口**：在线执行（`AuthoredMaintenanceService`），支持重跑。

---

## 10. 原始设计路线对比

| 原始计划 | 实际产出 | 偏差说明 |
|---|---|---|
| 开 OpenSpec `user-identity-constraint` (P0) | ✅ `openspec/changes/p0-p1-backend-foundation/` | 与 P1 合并实施 |
| 开 OpenSpec `user-profile-split` (P1) | ✅ 同上 | — |
| 开 OpenSpec `user-authored-edge` (P2) | ✅ `openspec/changes/user-authored-edge/` | 独立实施 |
| Phase 3 / 4 按需启动 | ⚠️ `openspec/changes/phase3-4-personalization-volatile-migration/` | 已部分实施 |

---

## 11. 待做清单（Backlog）

### 高优先级 — 前端消费层

| 项目 | 依赖 | 说明 |
|---|---|---|
| Follow/Mute 前端 UI | 后端 API 已就绪 | 节点详情面板 ⭐/🔇 按钮 + 侧栏 Following 入口 |
| `userProfileStore` 前端缓存 | `GET /api/users/{userId}/profile` 已就绪 | LRU 缓存，仅在需要他人完整 profile 面板时有价值 |
| Home `For you` 排序入口 | 后端 Feed API 已就绪 | 前端侧栏/排序选项 |

### 中优先级 — 治理与推荐深度能力

| 项目 | 依赖 | 说明 |
|---|---|---|
| `REVIEWED` 关系 | ReviewController 审核流程已有 | 审核结果写入 `(:UserAccount)-[:REVIEWED]->(:Decision)` 边，替代当前仅作为属性记录的方式。服务于审计面板"谁审核了什么"。 |
| `OPERATED` 关系 | DecisionController 运营流程已有 | 管理员操作写入 `(:UserAccount)-[:OPERATED]->(:GraphNode)` 边，替代当前 operator_id 仅作为 CONTINUES_FROM 边属性的方式。服务于运维审计追踪。 |
| `PREFERS` 聚合投影 job | `PreferenceEventService` 已收集事件 | 定时 job 扫描 PreferenceEvent 节点，聚合计算偏好分数，写入 `(:UserAccount)-[:PREFERS {score, updated_at}]->(:Topic)` 投影边。服务于推荐引擎。 |

### 低优先级

| 项目 | 说明 |
|---|---|
| 头像存储迁移 MinIO / S3 | 当前 `AvatarStorageService` 使用本地文件系统，迁移到对象存储提升可靠性 |

---

**本文档既是设计方案也是实施进度追踪。任何新增功能应单独走 OpenSpec proposal 并获得确认。**
