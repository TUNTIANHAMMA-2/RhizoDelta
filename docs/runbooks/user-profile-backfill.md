# Runbook · UserProfile Backfill

## 适用条件

RhizoDelta 后端每次启动都会运行 `DatabaseInitializer.migrateLegacyUserProfile()`，把遗留的
`(:UserAccount {display_name: ...})` 迁移到 `(:UserProfile)` 节点上，并通过 `HAS_PROFILE` 关联。
本 runbook 描述：
- 什么时候会跑、跑了什么
- 如何确认迁移完成
- 失败/中断后如何恢复
- 如何回滚到 Phase 0（`display_name` 仍在 `UserAccount` 上）

## 背景

Phase 1（`user-profile-split`）把用户展示信息从 `UserAccount` 节点拆分到独立的 `UserProfile` 节点，
关系由 `(:UserAccount)-[:HAS_PROFILE]->(:UserProfile)` 承载。注册路径写入 profile；
读路径（`/api/auth/me`、login 响应等）通过 `OPTIONAL MATCH` 读 profile，缺失时回退 `username`。

背景详见：
- `openspec/changes/archive/2026-04-22-user-profile-split/proposal.md`
- `openspec/changes/archive/2026-04-22-user-profile-split/design.md`（D1–D7）
- `docs/designs/user-domain-modeling-plan.md` §4 Phase 1

---

## 启动时 backfill 行为

顺序（在 `initializeSchema()` 内）：

1. `verifyUserIdIntegrity()` —— 预检 `user_id` 空/空白/重复（Phase 0 行为）
2. 执行 `SCHEMA_QUERIES` 所有 DDL（包括新增的 `rhizodelta_user_profile_user_id_unique` 约束）
3. 创建 vector index
4. **`migrateLegacyUserProfile()` ← 本 runbook 的焦点**
5. `logConstraintStatus()` —— 输出已确认的 schema 对象

每次循环：

```cypher
-- 查 pending
MATCH (u:UserAccount)
WHERE u.display_name IS NOT NULL
  AND NOT (u)-[:HAS_PROFILE]->(:UserProfile)
RETURN count(u) AS pending
```

若 `pending = 0`，记录一条 `INFO` 日志 `UserProfile backfill: migrated=0, skipped=0` 并返回。

否则取一批（`LIMIT 500`）：

```cypher
MATCH (u:UserAccount)
WHERE u.display_name IS NOT NULL
  AND NOT (u)-[:HAS_PROFILE]->(:UserProfile)
WITH u LIMIT 500
OPTIONAL MATCH (existing:UserProfile {user_id: u.user_id})
WITH u, existing
MERGE (p:UserProfile {user_id: u.user_id})
  ON CREATE SET p.display_name = u.display_name,
                p.updated_at   = datetime()
MERGE (u)-[:HAS_PROFILE]->(p)
REMOVE u.display_name
RETURN
  sum(CASE WHEN existing IS NULL THEN 1 ELSE 0 END) AS migrated,
  sum(CASE WHEN existing IS NOT NULL THEN 1 ELSE 0 END) AS skipped
```

每批 `INFO` 日志：`UserProfile backfill: migrated=<N>, skipped=<M>`。循环直到 `pending=0`。

### 语义

- `migrated`：本批第一次创建 `UserProfile`（`ON CREATE SET` 生效）
- `skipped`：之前某次失败 backfill 留下了孤立 `UserProfile {user_id: u.user_id}`；本次 MERGE 复用既有 profile，display_name 以既有 profile 为准（`ON CREATE` 不触发）。
- **`REMOVE u.display_name` 与 `MERGE HAS_PROFILE` 在同一 Cypher 事务**：崩溃后绝不会留下"profile 已建但 account 仍有 display_name"的半成品状态。

---

## 如何确认迁移完成

### A. 查 pending 计数

```cypher
MATCH (u:UserAccount)
WHERE u.display_name IS NOT NULL
  AND NOT (u)-[:HAS_PROFILE]->(:UserProfile)
RETURN count(u) AS pending;
```

期望：`pending = 0`。

### B. 查 `UserAccount.display_name` 是否还残留

```cypher
MATCH (u:UserAccount)
WHERE u.display_name IS NOT NULL
RETURN count(u) AS legacy;
```

期望：`legacy = 0`（除非有孤立账户——见下）。

### C. 反向：是否有孤立 `UserProfile`（profile 存在但没有 account）

```cypher
MATCH (p:UserProfile)
WHERE NOT ()-[:HAS_PROFILE]->(p)
RETURN p.user_id AS orphan_profile_user_id LIMIT 20;
```

期望：空。若有结果，说明之前 backfill 执行到一半异常，profile 被创建但 HAS_PROFILE 未写入——下次 backfill 会自动补齐（`OPTIONAL MATCH (existing)` 识别到它，用作 skipped 分支）。

---

## 失败/中断恢复

### 场景 1：启动时 backfill 抛 IllegalStateException

表现：Spring Boot 启动失败，日志里可见：

```
UserProfile backfill failed
```

原因通常是：

- Neo4j 不可达（检查 `spring.neo4j.uri` 与容器状态）
- 查询权限问题（`neo4j` 用户是否有 write 权限）
- 某行 `user_id` 为 null/空（应在 Phase 0 `verifyUserIdIntegrity()` 被拦截；若出现在此，说明启动序前置步骤被绕过）

恢复步骤：

1. 在 Neo4j Browser 里手动跑 pending 查询（上面 "如何确认"-A）查出异常行。
2. 针对性修复（通常是补 `user_id` 或手动插一个 profile 节点）。
3. 重启应用。backfill 是幂等的——已完成的行会自动跳过。

### 场景 2：中途被强制 kill（断电、OOMKilled 等）

表现：应用没跑完 backfill 就死了，但**数据不会不一致**（见"语义"一段）。

恢复：

1. 正常重启即可。下次启动 backfill 会从 `pending > 0` 的状态继续。
2. 用上面 A/B 查询确认 pending 归零。

---

## 回滚到 Phase 0

用途：revert 了 user-profile-split 代码、希望让旧版本（只读 `UserAccount.display_name`）重新工作。

### 1. 先把 profile 的 display_name 回写到 account

```cypher
MATCH (u:UserAccount)-[:HAS_PROFILE]->(p:UserProfile)
WHERE u.display_name IS NULL AND p.display_name IS NOT NULL
SET u.display_name = p.display_name
RETURN count(u) AS restored;
```

### 2.（可选）移除 HAS_PROFILE 边和 UserProfile 节点

```cypher
MATCH ()-[r:HAS_PROFILE]->() DELETE r;
MATCH (p:UserProfile) DETACH DELETE p;
```

### 3.（可选）删除 UNIQUE 约束

```cypher
DROP CONSTRAINT rhizodelta_user_profile_user_id_unique IF EXISTS;
```

### 4. 启动旧版本后回归验证

- `POST /api/auth/register` 可注册新用户
- `POST /api/auth/login` 返回 `display_name`
- `GET /api/auth/me` 返回 `display_name`

### 注意

- 回滚命令在应用停机或运行中都可以执行，Neo4j MERGE/DELETE 天然原子。
- 若本次只执行了步骤 1（保留 `UserProfile` 节点），之后重新上 Phase 1 构建，backfill 会识别到既有 profile 并通过 `skipped` 路径复用。不会丢数据。

---

## 相关规范

- OpenSpec change（已归档）: `openspec/changes/archive/2026-04-22-user-profile-split/`
- Requirements: `specs/user-profile-schema/spec.md`（ADDED）、`specs/user-identity-schema/spec.md`（MODIFIED Registration）
- 顶层设计: `docs/designs/user-domain-modeling-plan.md` §4 Phase 1
- 对照 Phase 0 的 identity runbook: `docs/runbooks/user-identity-integrity.md`
