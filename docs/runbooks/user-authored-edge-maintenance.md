# Runbook · User Authored Edge Maintenance

## 适用条件

当 RhizoDelta 已经完成 Phase 2（`user-authored-edge`）部署，需要把历史
`Human_Post.author_id` 补齐为规范化的 `(:UserAccount)-[:AUTHORED]->(:Human_Post)` 图关系时，
使用本 runbook。

本 runbook 只覆盖：

- 历史 `AUTHORED` 回填
- 缺边审计
- Phase 2 回滚

本 runbook **不**覆盖：

- Phase 1 的 `UserProfile` 启动期 backfill
- 新帖写入链路（新帖在 `PostService` 中已同事务写入 `author_id + AUTHORED`）
- 前端作者展示逻辑

## 背景与边界

Phase 2 的设计约束是：

1. `AUTHORED` 是作者归属的权威语义
2. `author_id` 继续保留为兼容性投影
3. 历史补边是**发布后显式运维动作**，不是启动期自动迁移

这意味着：

- 新写入的人类帖子已经在事务内写入 `AUTHORED`
- 历史帖子需要单独补边
- 即使暂时还没回填，旧代码仍可继续依赖 `author_id`

相关设计来源：

- `openspec/changes/archive/2026-05-13-user-authored-edge/design.md`
- `openspec/changes/archive/2026-05-13-user-authored-edge/specs/user-authored-edge/spec.md`
- `docs/designs/user-domain-modeling-plan.md` §4 Phase 2

---

## 前置准备

1. 打开 Neo4j Browser：`http://<neo4j-host>:7474/browser/`
2. 使用当前环境的 Neo4j 凭据登录
3. 建议先在低风险环境验证，再在正式环境执行
4. 若历史 `Human_Post` 数量很大，优先选择维护窗口

---

## 历史回填

### 目标

把所有满足以下条件的历史帖子补成显式作者边：

- `(:Human_Post {author_id})`
- `author_id` 非空
- 存在对应 `(:UserAccount {user_id = author_id})`
- 当前没有 `(:UserAccount {user_id = author_id})-[:AUTHORED]->(:Human_Post)` 边

### 实施方式

回填**不在启动期执行**，由 `AuthoredMaintenanceService#runAuthoredBackfill()` 显式触发，
内部按批分页（默认 `rhizodelta.authored.backfill.batch-size=500`），循环直到可修复 pending 为 0：

1. **批次查询**：每次只取尚未有正确边、且作者账号存在的 ≤500 条帖子；
2. **单批 Cypher**：在同一事务内 `MATCH (UserAccount) MERGE (AUTHORED) ON CREATE SET created_at + authored_id`；
3. **authored_id 复合键**：`"$authorId:$postNodeId"` —— 与
   `rhizodelta_authored_id_unique` 关系唯一约束对齐，并发回填或并发写帖
   最多保留一条 AUTHORED；
4. **退出条件**：pending=0 或本批 createdCount=0（防御性退出）。

### 手动回填脚本（与 service 行为等价，供应急维护）

```cypher
:auto USING PERIODIC COMMIT 500
MATCH (p:Human_Post)
WHERE p.author_id IS NOT NULL
  AND EXISTS { MATCH (:UserAccount {user_id: p.author_id}) }
  AND NOT EXISTS { MATCH (:UserAccount {user_id: p.author_id})-[:AUTHORED]->(p) }
WITH p LIMIT 500
MATCH (u:UserAccount {user_id: p.author_id})
MERGE (u)-[r:AUTHORED]->(p)
  ON CREATE SET r.created_at  = coalesce(p.created_at, datetime()),
                r.authored_id = u.user_id + ':' + p.node_id
RETURN sum(CASE WHEN r.authored_id IS NULL THEN 0 ELSE 1 END) AS created;
```

> 多次执行直至 `created = 0`。

### 语义说明

- `MERGE` 保证同一用户到同一帖子最多一条 `AUTHORED`
- `authored_id` 的关系唯一约束阻断并发产生的重复边（第二个并发 MERGE 在
  CREATE 时会被约束拒绝并回滚事务）
- `ON CREATE SET` 保证只在首次补边时写入 `created_at` 与 `authored_id`，
  既有边的属性不被覆盖
- 找不到 `UserAccount` 的帖子不会被强行补边，也不会创建占位用户

### 预期结果

- 历史帖子若能解析到作者账号，则拥有 1 条 `AUTHORED`，且边上 `authored_id = userId:nodeId`
- 重复执行（service 或脚本）不会增加重复边或改写既有属性

---

## 漂移审计

### 目标

仅"缺边审计"不足以保证 `author_id ↔ AUTHORED` 一致：还要识别
**重复匹配边**、**错误作者来源边**、**多源混合**等漂移情况。
`AuthoredMaintenanceService#auditDrift()` / `findDriftSamples(int)`
提供多维分类汇总与样本。

### 审计 Cypher（与 service 等价）

```cypher
MATCH (p:Human_Post)
WHERE p.author_id IS NOT NULL
OPTIONAL MATCH (u:UserAccount)-[:AUTHORED]->(p)
WITH p,
     count(u) AS total,
     sum(CASE WHEN u IS NOT NULL AND u.user_id = p.author_id THEN 1 ELSE 0 END) AS matching
WHERE matching <> 1 OR total <> matching
RETURN p.node_id AS nodeId,
       p.author_id AS authorId,
       total      AS totalAuthoredEdges,
       matching   AS matchingAuthoredEdges
LIMIT 100;
```

### 漂移分类

| `matching` | `total` | 含义 | 处置 |
|---|---|---|---|
| 0 | 0 | **MISSING** —— 缺正确边 | 跑回填即可；若 `author_id` 对应账号缺失，先核实账号 |
| 1 | 1 | HEALTHY | 无动作 |
| ≥2 | ≥2 | **DUPLICATE_MATCHING** —— 同一正确作者的多条 AUTHORED | 手动 `DELETE` 多余边（保留任一条） |
| 1 | >1 | **EXTRANEOUS_ALONGSIDE_MATCH** —— 正确边 + 来源不对的额外边 | 删除非匹配作者的边 |
| 0 | >0 | **EXTRANEOUS_WITHOUT_MATCH** —— 全是错误作者来源的边 | 删除全部 AUTHORED，跑回填补正确边 |

### 完成判据

```cypher
MATCH (p:Human_Post)
WHERE p.author_id IS NOT NULL
OPTIONAL MATCH (u:UserAccount)-[:AUTHORED]->(p)
WITH p,
     count(u) AS total,
     sum(CASE WHEN u IS NOT NULL AND u.user_id = p.author_id THEN 1 ELSE 0 END) AS matching
RETURN
  sum(CASE WHEN matching = 1 AND total = 1 THEN 1 ELSE 0 END) AS healthyCount,
  sum(CASE WHEN matching = 0 AND total = 0 THEN 1 ELSE 0 END) AS missingCount,
  sum(CASE WHEN matching >= 2                THEN 1 ELSE 0 END) AS duplicateMatchingCount,
  sum(CASE WHEN matching = 1 AND total > 1 THEN 1 ELSE 0 END) AS extraneousAlongsideMatchCount,
  sum(CASE WHEN matching = 0 AND total > 0 THEN 1 ELSE 0 END) AS extraneousWithoutMatchCount;
```

期望：除 `healthyCount` 外其余全部为 0，或仅剩明确记录在案、暂不修复的历史脏数据。

---

## 回滚

### 适用场景

如果需要撤销 Phase 2 的显式作者关系，但仍希望旧代码继续工作，可以只回滚图关系层。

### 回滚命令

```cypher
MATCH (:UserAccount)-[r:AUTHORED]->(:Human_Post)
DELETE r;

DROP INDEX rhizodelta_authored_created_at_idx IF EXISTS;
DROP CONSTRAINT rhizodelta_authored_id_unique IF EXISTS;
```

### 回滚后的行为

- `author_id` 属性仍然保留
- 旧代码和未升级客户端仍可继续使用 `author_id`
- 新增的作者展示投影会退化为旧的 `author_id` 显示

### 不需要做的事

- 不需要删除 `Human_Post.author_id`
- 不需要回滚 `UserProfile`
- 不需要变更 `UserAccount.status`

---

## 兼容性说明

Phase 2 的关键兼容策略是：

- `AUTHORED` 提供权威图语义
- `author_id` 继续提供兼容性投影

所以：

- 回填前：旧读路径依然可用
- 回填后：旧读路径依然可用
- 回滚后：旧读路径依然可用

换句话说，`author_id` 是全流程保底兼容层。

---

## 相关规范

- OpenSpec change（已归档）: `openspec/changes/archive/2026-05-13-user-authored-edge/`
- Phase 2 design: `openspec/changes/archive/2026-05-13-user-authored-edge/design.md`
- Phase 2 requirements: `openspec/changes/archive/2026-05-13-user-authored-edge/specs/user-authored-edge/spec.md`
- 顶层建模设计: `docs/designs/user-domain-modeling-plan.md` §4 Phase 2
- Phase 1 runbook: `docs/runbooks/user-profile-backfill.md`
