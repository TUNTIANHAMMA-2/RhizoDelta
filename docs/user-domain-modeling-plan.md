# RhizoDelta 用户建模重构设计

> 作者：架构分析报告 · 2026-04-21
> 依据：`src/main/java/com/rhizodelta/**` 现状扫描 + codex 规划
> 文档性质：**设计与迁移方案**（不含代码变更，不立即实施）

---

## 0. 摘要

当前 RhizoDelta 用户域有三个结构缺口：

1. **`user_id` 不是真正的主键**。schema 仅对 `UserAccount.username` 做 UNIQUE 约束（`DatabaseInitializer.java:43`），而 JWT `sub`、`/api/auth/me`、`PostController` 全部以 `user_id` 作为权威身份（`AuthController.java:56, 175`、`PostController.java:111`）。**canonical 身份在业务层，schema 层缺失匹配约束**。
2. **作者关系是字符串属性而非图关系**。`Human_Post` 持有 `author_id` 属性（`PostService.java:45`），`NodeQueryService` 全部查询以 `n.author_id` 做投影（`NodeQueryService.java:81, 150, 179, 202`）。没有 `(:UserAccount)-[:AUTHORED]->(:Human_Post)` 显式关系，**Neo4j 最擅长的遍历能力没被用上**。
3. **凭据与画像混在一个节点**。`password_hash`、`roles`、`display_name` 同时挂在 `UserAccount` 上（`AuthController.java:69-71`）。任何读取资料的查询天然接触凭据字段，违反"认证态与画像态分层"的最佳实践。

本方案给出**四阶段渐进迁移**（schema → 分层 → 关系 → 个性化），每阶段独立可部署、可回滚，并明确哪些写操作需要双轨、哪些接口需要新增。

---

## 1. 现状事实清单

### 1.1 数据模型

| 维度 | 现状 | 证据 |
|---|---|---|
| 用户节点 | `(:UserAccount {user_id, username, display_name, password_hash, roles, created_at})` | `AuthController.java:64-78` |
| 唯一约束 | 仅 `UserAccount.username` | `DatabaseInitializer.java:43` |
| `user_id` 约束 | **无 UNIQUE**，**无 INDEX** | 同上 |
| 作者关联 | `Human_Post.author_id: string`，带索引 | `DatabaseInitializer.java:36`、`PostService.java:45` |
| 显式关系 | **无** `AUTHORED / REVIEWED / FOLLOWS / MUTED` | 全仓 grep 零结果 |
| Profile/Settings | **无专用节点或字段** | — |

### 1.2 认证与授权

| 维度 | 现状 | 证据 |
|---|---|---|
| 认证协议 | 无状态 JWT，HS 签名，TTL 8h | `AuthController.java:46, 82-91` |
| JWT claims | `sub=user_id`, `roles`, `username`, `display_name` | `AuthController.java:172-182` |
| Refresh Token | **无** | — |
| Token 撤销 | **无黑名单机制** | — |
| 权限模型 | Spring Security `hasRole("ADMIN"/"AGENT")` | `SecurityConfig.java:55-62` |
| 作者身份源 | 写入时以 JWT `sub` 为准，不信任前端 `author_id` | `PostController.java:111` ✅ 安全方向正确 |

### 1.3 写入与查询路径

- **写入**：`PostController → RabbitMQ → PostService.createHumanPost → Neo4j upsert`（`PostService.java:112`），节点上设置 `author_id = JWT.sub`。
- **读取**：`NodeQueryService` 所有 Cypher 以 `n.author_id` 作为作者投影，不走关系遍历。

### 1.4 前端事实（上轮已探明）

- `authStore` 已保存当前登录用户的 `userId / username / displayName`（`frontend/src/stores/authStore.ts:45`）。
- `GraphNodeDTO.author_id` 是 DTO 中唯一的用户引用（`frontend/src/api/types.ts:37`），**无 `author_username`**。
- 视图层 5 处直接渲染 `author_id`：`RhizomeCard.tsx:117`、`NodeDetailPanel.tsx:104`、`ProvenancePanel.tsx:69`、`HumanPostNode.tsx:53`、`RhizoneList.tsx:77`。

---

## 2. 设计原则（三条红线）

1. **`user_id` 是全局唯一、不可变的 canonical identity**；`username` / `display_name` 只是可变登录名或展示属性。
2. **图关系承载可遍历的个性化语义**；属性只保留必要投影和查询优化字段。
3. **认证层 / 资料层 / 个性化层逻辑分层**：读画像的查询不应天然接触凭据；读偏好的查询不应触发凭据反序列化。

---

## 3. 目标模型

### 3.1 节点

```
(:UserAccount {user_id, username, password_hash, roles, status, created_at})
     │
     │ [:HAS_PROFILE] (1:1)
     ▼
(:UserProfile {user_id, display_name, avatar_url, language, timezone, theme, notification_prefs, updated_at})
```

- `UserAccount` 只承载**认证与授权**：凭据、角色、账号状态（ACTIVE/SUSPENDED/DELETED）。
- `UserProfile` 只承载**可展示的画像**：display_name 迁出账户节点，解决"凭据读取顺带泄露画像 / 画像读取顺带暴露凭据"的耦合。
- `user_id` 在两个节点上都保留一份，作为跨节点 join key。

### 3.2 关系

| 关系 | 语义 | 边属性 | 起止 |
|---|---|---|---|
| `AUTHORED` | 用户创作了某内容节点 | `created_at` | `UserAccount → Human_Post` |
| `REVIEWED` | 用户审核了某个决策 | `decision_id`, `outcome`, `at` | `UserAccount → Decision` |
| `OPERATED` | 用户执行过某次运营动作（admin） | `operation_id`, `at` | `UserAccount → GraphNode` |
| `FOLLOWS` | 关注主题/节点/用户 | `since` | `UserAccount → {Topic\|GraphNode\|UserAccount}` |
| `MUTED` | 屏蔽主题/用户 | `since`, `reason` | `UserAccount → {Topic\|UserAccount}` |

### 3.3 个性化信号（不走边属性）

**反模式**：`(:UserAccount)-[:PREFERS {weight, ts, ...}]->(:Topic)` —— 高频写入边属性会使向量检索与图遍历共享的存储路径退化。

**推荐**：用独立事件节点承载上下文。

```
(:UserAccount)-[:EMITTED]->(:PreferenceEvent {event_id, type, weight, at, source_node_id})-[:TOWARD]->(:Topic)
```

读路径：离线或近线聚合成 `(:UserAccount)-[:PREFERS {score, updated_at}]->(:Topic)` 投影边，**投影由 job 重算，不在热路径写**。

### 3.4 投影属性（保留）

- `Human_Post.author_id`：**保留**。作为显式关系的查询投影，避免热路径读取都走两跳。
- 写入方向：以 `AUTHORED` 边为真实语义来源；`author_id` 属性在同一事务内同步。
- 读优先级：默认读属性；需要遍历能力（如"找出同一作者的其他帖子"）时走关系。

---

## 4. 分阶段迁移计划

> 每个阶段都有：**schema 变更 · 代码变更 · 回填脚本 · 验证面 · 回滚方案**。

### Phase 0 · 固化身份（最小破坏）

**目标**：把 `user_id` 提升为真正受约束的 canonical key。

**Schema 变更**（追加到 `DatabaseInitializer.SCHEMA_QUERIES`）：

```cypher
CREATE CONSTRAINT rhizodelta_user_account_user_id_unique
  IF NOT EXISTS FOR (n:UserAccount) REQUIRE n.user_id IS UNIQUE;
CREATE INDEX rhizodelta_user_account_status_idx
  IF NOT EXISTS FOR (n:UserAccount) ON (n.status);
```

> **备注**：Neo4j 5 的 UNIQUE 约束自带后备 lookup 索引，无需再额外 `CREATE INDEX rhizodelta_user_account_user_id_idx`；约束和独立索引在同一字段上互斥，强制并存会被数据库判为 "There already exists an index"。`MATCH (:UserAccount {user_id: $id})` 的查询计划由约束后备索引提供 `NodeUniqueIndexSeek`，即可兑现"lookup index"验收面。

**启动预检**（加在 `initializeSchema` 中执行一次，先空/空白，再重复）：

```cypher
MATCH (u:UserAccount)
WHERE u.user_id IS NULL OR trim(u.user_id) = ''
RETURN u.username AS username LIMIT 20;

MATCH (u:UserAccount)
WITH u.user_id AS uid, collect(u.username)[0..20] AS names, count(*) AS n
WHERE n > 1
RETURN uid, names, n LIMIT 20;
```

任何一条返回非空：**启动失败，阻断应用**。人工补齐后重启。两条查询皆为只读，`LIMIT 20` 与内层 `[0..20]` 切片同时约束组数与每组 username 数，避免日志膨胀。

**代码变更**：
- `AuthController.FIND_USER_BY_USERNAME_QUERY` 保持不变（登录仍按 username 查）。
- `AuthController.CREATE_USER_QUERY` 的 `ON CREATE SET` 新增 `user.status = 'ACTIVE'`。
- 登录成功后的内部查询路径，增加"按 `user_id` 重新校验"的 trace（可选，作为并发冲突防御）。

**回滚**：

```cypher
DROP CONSTRAINT rhizodelta_user_account_user_id_unique IF EXISTS;
DROP INDEX rhizodelta_user_account_status_idx IF EXISTS;
```

无数据变更；被旧版本读到的 `status = 'ACTIVE'` 会被忽略。

**验证**：
- 重复 `user_id` 插入直接抛 `Neo.ClientError.Schema.ConstraintValidationFailed`。
- `EXPLAIN MATCH (u:UserAccount {user_id: $id}) RETURN u` 计划包含 `NodeUniqueIndexSeek`（或等价 IndexSeek variant）。
- `/api/auth/me` 行为不变。

---

### Phase 1 · 分层实体（认证/画像解耦）

**目标**：拆出 `UserProfile`，为后续"公开画像 API"铺路，同时让凭据读取不触及展示字段。

**Schema 变更**：

```cypher
CREATE CONSTRAINT rhizodelta_user_profile_user_id_unique
  IF NOT EXISTS FOR (n:UserProfile) REQUIRE n.user_id IS UNIQUE;
```

**代码变更**：
1. `AuthController.CREATE_USER_QUERY` 拆为两段事务：
   - MERGE `UserAccount`（去掉 `display_name` 字段）
   - CREATE `UserProfile`，MERGE `(account)-[:HAS_PROFILE]->(profile)`
2. `/api/auth/me` 改为从 `UserProfile` 读 `display_name`（LEFT JOIN）。
3. JWT claim `display_name` **保留**（避免强制所有客户端重新解析）。

**回填脚本**（一次性，通过 flyway 或启动期 migration）：

```cypher
MATCH (u:UserAccount) WHERE u.display_name IS NOT NULL
MERGE (p:UserProfile {user_id: u.user_id})
  ON CREATE SET p.display_name = u.display_name, p.created_at = datetime()
MERGE (u)-[:HAS_PROFILE]->(p)
REMOVE u.display_name;
```

**新增接口**（纯内部迁移期接口，不对外扩能）：
- `GET /api/users/me/profile` → 完整个人 profile
- `PUT /api/users/me/profile` → 更新 display_name / avatar / language / theme

**回滚**：
- 将 `p.display_name` 回写 `u.display_name`。
- 删除 `HAS_PROFILE` 边和 `UserProfile` 节点。

**验证**：
- 登录响应保持兼容（`display_name` 仍在 payload 中）。
- 单独查询凭据的 query `PROFILE` 不再访问 `display_name`（通过 `EXPLAIN` 确认）。

---

### Phase 2 · 显式作者关系（双轨期）

**目标**：引入 `(:UserAccount)-[:AUTHORED]->(:Human_Post)` 作为权威语义，`author_id` 降级为投影。

**Schema 变更**：

```cypher
CREATE INDEX rhizodelta_authored_created_at_idx
  IF NOT EXISTS FOR ()-[r:AUTHORED]-() ON (r.created_at);
```

**写路径变更**（`PostService.upsertByRequestId`）：
- 在同一事务内，MERGE `(:UserAccount {user_id: $authorId})-[:AUTHORED {created_at: $createdAt}]->(post)`。
- `author_id` 属性**继续写**（投影）。

**读路径变更**（分两批）：
- Batch A（本阶段）：`NodeQueryService` 全部查询保持用 `n.author_id` 投影 → **零读取变更**。
- Batch B（Phase 3 启动前）：为需要"同作者其他帖"等遍历型查询，添加新查询方法走 `AUTHORED` 关系。

**回填脚本**（幂等，可重跑）：

```cypher
MATCH (p:Human_Post) WHERE p.author_id IS NOT NULL
MATCH (u:UserAccount {user_id: p.author_id})
MERGE (u)-[r:AUTHORED]->(p)
ON CREATE SET r.created_at = p.created_at;
```

**一致性对账**（运维脚本，周期运行）：

```cypher
MATCH (p:Human_Post) WHERE p.author_id IS NOT NULL
OPTIONAL MATCH (u:UserAccount {user_id: p.author_id})-[:AUTHORED]->(p)
WITH p, u
WHERE u IS NULL
RETURN p.node_id AS missing_authored_edge, p.author_id
LIMIT 100;
```

告警阈值：一致率 < 99.99% 触发人工介入。

**DTO 变更**（非破坏性）：
- `GraphNodeDTO` 可选新增 `author_username?: string` 字段。
- `NodeQueryService.LINEAGE_QUERY` 和 `NODE_SUMMARY_QUERY` 增补一段 `OPTIONAL MATCH (u:UserAccount {user_id: n.author_id})-[:HAS_PROFILE]->(p:UserProfile)` 并 `RETURN p.display_name AS authorDisplayName`。
- 老客户端不消费新字段仍可运行。

**新增接口**：
- `GET /api/users/{user_id}/profile` → 公开字段投影（`user_id, username, display_name, avatar_url`），用于前端解析他人 id→name。
  - 权限：已认证即可读。
  - 被 ban/deleted 的账户只返回 `{user_id, status: "UNAVAILABLE"}`。

**回滚**：
- 保留 `author_id` 属性不受影响，删除 `AUTHORED` 边即可恢复到 Phase 1 状态。

**验证**：
- 新写入的 `Human_Post` 必有 `AUTHORED` 边（通过消费端集成测试）。
- `DELETE (u:UserAccount)` 失败：由业务层防御（用户删除不可物理删除，改 `status=DELETED`），避免孤边。
- 回填后，`author_id` ↔ `AUTHORED` 一致率 100%。

---

### Phase 3 · 个性化关系

**目标**：把"关注 / 屏蔽 / 审核 / 偏好"纳入图关系，为推荐与治理提供可遍历语义。

**Schema 变更**：

```cypher
CREATE INDEX rhizodelta_follows_since_idx
  IF NOT EXISTS FOR ()-[r:FOLLOWS]-() ON (r.since);
CREATE INDEX rhizodelta_muted_since_idx
  IF NOT EXISTS FOR ()-[r:MUTED]-() ON (r.since);
CREATE CONSTRAINT rhizodelta_topic_topic_id_unique
  IF NOT EXISTS FOR (n:Topic) REQUIRE n.topic_id IS UNIQUE;
```

**新增领域对象**：`Topic` 节点（由 Tag 或 RootNode 语义承载；本文档不展开）。

**新增接口**：

| 方法 | 路径 | 含义 |
|---|---|---|
| GET | `/api/users/me/follows` | 我关注的所有对象 |
| POST | `/api/users/me/follows` | 关注（payload: `target_type`, `target_id`） |
| DELETE | `/api/users/me/follows/{target_id}` | 取消关注 |
| GET | `/api/users/me/mutes` | 我屏蔽的对象 |
| POST/DELETE | `/api/users/me/mutes[/{target_id}]` | 屏蔽 / 取消屏蔽 |
| GET | `/api/users/me/feed` | 个性化 feed（基于 FOLLOWS + MUTED 过滤） |

**偏好信号**：
- 热路径：只写 `(:PreferenceEvent)` 事件节点（如浏览、点赞、展开、停留）。
- 离线/近线 job：聚合成 `PREFERS {score, updated_at}` 边投影，服务于推荐。
- **不在热路径直接写边属性**。

**回滚**：个性化关系为独立能力，删除相关边不影响核心业务流。

---

### Phase 4 · 高频易失态外移

**目标**：把不属于图的数据挪出 Neo4j。

| 数据 | 目标存储 | 理由 |
|---|---|---|
| Token 撤销黑名单 | Redis（TTL = JWT exp） | 高频写 / 易失 / 小对象 |
| Refresh Token | Redis（或独立 `:RefreshToken` 短生命节点 + 定期清理 job） | 与图语义无关 |
| 在线状态 / 最近活跃 | Redis | 高频覆盖 |
| 头像大图 / 附件 | 对象存储（MinIO / S3） | 大对象不入图 |

**不引入 PostgreSQL**。只有当出现账单、强事务 OLTP、外部 IAM 对接、复杂表式报表时才单独评估。

---

## 5. 前端联动设计

### 5.1 类型扩展（非破坏）

```ts
// frontend/src/api/types.ts
export interface GraphNodeDTO {
  node_id: string;
  label: NodeLabel;
  content?: string;
  summary_content?: string;
  author_id?: string;
  author_username?: string;     // ← Phase 2 新增
  author_display_name?: string; // ← Phase 2 新增
  agent_version?: string;
  created_at: string;
  has_embedding: boolean;
  quality_overall?: number;
}
```

### 5.2 通用作者标签组件

```tsx
// frontend/src/components/user/AuthorLabel.tsx（新增）
export function AuthorLabel({ node }: { node: GraphNodeDTO }) {
  const me = useAuthStore(s => ({ userId: s.userId, name: s.displayName ?? s.username }));
  if (!node.author_id) return <>Agent</>;
  if (node.author_id === me.userId && me.name) return <>{me.name}</>;
  if (node.author_display_name) return <>{node.author_display_name}</>;
  if (node.author_username) return <>@{node.author_username}</>;
  return <>{node.author_id.slice(0, 8)}…</>;
}
```

**5 处替换**：`RhizomeCard.tsx:117` · `NodeDetailPanel.tsx:104` · `ProvenancePanel.tsx:69` · `HumanPostNode.tsx:53` · `RhizoneList.tsx:77`。

### 5.3 其他用户解析（Phase 2 生效后）

```ts
// frontend/src/stores/userProfileStore.ts（新增）
// LRU 缓存 user_id → UserProfile，TTL 10 min
// 当 DTO 未带 author_username 时，按需拉 /api/users/{user_id}/profile
```

### 5.4 关注 / 屏蔽 UI（Phase 3）

- 节点详情面板头部增加 ⭐ Follow / 🔇 Mute 按钮。
- Home 侧栏 `Streams` 增加 `Following` 入口。
- `HomeMainColumn` 排序新增 `For you`（基于 FOLLOWS + PREFERS 投影）。

---

## 6. 验证面（每阶段都要过）

| 验证项 | 对应阶段 | 方法 |
|---|---|---|
| `user_id` 唯一性 | P0 | 集成测试：并发注册、重复 UUID 写入 |
| `username` 改名后历史归属不变 | P0+P2 | 改名后历史 `Human_Post.author_id` 不变，`AUTHORED` 边不变 |
| 凭据读路径不触及 `display_name` | P1 | `EXPLAIN` Cypher 计划包含节点列表 |
| 作者边与属性一致率 100% | P2 | 回填后对账 + 定时核查 |
| 用户删除后内容与审计保留 | P2+ | `status=DELETED` 软删，`AUTHORED` 保留 |
| FOLLOWS/MUTED 遍历语义 | P3 | "关注同一主题的人还关注了谁" 能 2 跳内返回 |
| 旧客户端兼容 | 全程 | 无 `author_username` 时仍能降级显示 |
| 迁移可回滚 | 每阶段 | 回滚脚本干跑一次，数据不丢 |

---

## 7. 风险与对策

| 风险 | 概率 | 影响 | 对策 |
|---|---|---|---|
| Phase 0 启动预检发现历史 `user_id` 为空 | 中 | 阻断启动 | 预检先以报告模式跑一次，人工补齐 |
| Phase 2 热路径增加一条 MERGE 边写入 | 中 | 发帖延迟 +10~30ms | 边属性精简；必要时异步化（先写 `author_id`，边由管线补） |
| 回填脚本超时 | 低 | 迁移延期 | 分批：`SKIP/LIMIT` + 按 `created_at` 切片 |
| 一致性漂移（属性与边） | 中 | 查询结果不一致 | 定时对账 + 告警；以边为准修属性 |
| 软删除与 AUTHORED 保留冲突 | 低 | 前端显示已删除用户 | DTO 返回 `author_status`，`AuthorLabel` 渲染"[已注销]" |
| JWT 黑名单未外移前仍需刷 token | 已知 | 撤销延迟到 TTL 到期 | P4 外移 Redis 前保持现状；告知运维 |

---

## 8. 不做 / 延后

- **不换关系型数据库**。主业务是图遍历、谱系、分支、合并、审计、向量检索，PostgreSQL 对这些能力无加成。
- **不强行改 JWT 为 Session**。无状态 JWT 仍是首选；撤销能力由 Redis 黑名单补齐。
- **不为偏好边写带权重属性**。用 `PreferenceEvent` 节点 + 聚合投影替代。
- **不在 Phase 2 之前动前端视图层**。前端改造依赖 DTO 新字段就绪。

---

## 9. 待你确认（影响后续路线）

1. **个性化范围**：是否包括 `PREFERS / recommendation signal / audit relationship`，还是只限 `profile + settings`？
   - 默认推荐：全部纳入，但分阶段落地（P3）。
2. **中期存储边界**：是否接受"用户域仍全在 Neo4j，P4 仅外移易失态到 Redis"？
   - 默认推荐：接受。
3. **`UserAccount` 一等公民**：是否同意把 `AUTHORED` 作为权威语义、`author_id` 降级投影？
   - 默认推荐：同意。
4. **Phase 2 回填窗口**：回填脚本是在线执行（阻塞时间 = 数据量相关）还是单独维护窗口？
   - 依赖当前 `Human_Post` 规模，建议先压测再决定。

---

## 10. 下一步（建议）

| 顺序 | 动作 | 产出 |
|---|---|---|
| 1 | 你对第 9 节四个问题拍板 | 本文档 revision |
| 2 | 开 OpenSpec proposal：`user-identity-constraint`（= Phase 0 全部） | `openspec/changes/user-identity-constraint/` |
| 3 | 实施 Phase 0 + 集成测试 | PR |
| 4 | 开 proposal：`user-profile-split`（= Phase 1） | 同上 |
| 5 | 开 proposal：`user-authored-edge`（= Phase 2） | 同上 |
| 6-8 | Phase 3 / 4 按需启动 | 同上 |

**本文档仅为设计方案；任何代码变更、schema 变更、回填执行都应单独走 OpenSpec proposal 并获得确认。**
