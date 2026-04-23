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

- `openspec/changes/user-authored-edge/design.md`
- `openspec/changes/user-authored-edge/specs/user-authored-edge/spec.md`
- `docs/user-domain-modeling-plan.md` §4 Phase 2

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

### 幂等回填脚本

```cypher
MATCH (p:Human_Post)
WHERE p.author_id IS NOT NULL
MATCH (u:UserAccount {user_id: p.author_id})
MERGE (u)-[r:AUTHORED]->(p)
ON CREATE SET r.created_at = p.created_at
RETURN count(r) AS touched;
```

### 语义说明

- `MERGE` 保证同一用户到同一帖子最多一条 `AUTHORED`
- `ON CREATE SET` 保证只有首次补边时写入 `created_at`
- 找不到 `UserAccount` 的帖子不会被强行补边，也不会创建占位用户

### 预期结果

- 历史帖子若能解析到作者账号，则拥有 1 条 `AUTHORED`
- 重复执行该脚本不会增加重复边

---

## 缺边审计

### 目标

找出仍然只有 `author_id`、但缺少 `AUTHORED` 的帖子，供人工处理。

### 审计脚本

```cypher
MATCH (p:Human_Post)
WHERE p.author_id IS NOT NULL
OPTIONAL MATCH (u:UserAccount {user_id: p.author_id})-[:AUTHORED]->(p)
WITH p, u
WHERE u IS NULL
RETURN p.node_id AS nodeId, p.author_id AS authorId
LIMIT 100;
```

### 如何理解结果

- `nodeId`：缺边帖子
- `authorId`：帖子上记录的作者投影

若有结果，通常分成两类：

1. 该作者账号真实不存在  
处理方式：人工确认是脏数据还是待补账号

2. 作者账号存在，但 `AUTHORED` 漏写  
处理方式：重新执行回填脚本后再审计

### 完成判据

```cypher
MATCH (p:Human_Post)
WHERE p.author_id IS NOT NULL
OPTIONAL MATCH (u:UserAccount {user_id: p.author_id})-[:AUTHORED]->(p)
WITH p, u
WHERE u IS NULL
RETURN count(p) AS missingCount;
```

期望：

- `missingCount = 0`
- 或仅剩明确记录在案、暂不修复的历史脏数据

---

## 回滚

### 适用场景

如果需要撤销 Phase 2 的显式作者关系，但仍希望旧代码继续工作，可以只回滚图关系层。

### 回滚命令

```cypher
MATCH (:UserAccount)-[r:AUTHORED]->(:Human_Post)
DELETE r;

DROP INDEX rhizodelta_authored_created_at_idx IF EXISTS;
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

- OpenSpec change: `openspec/changes/user-authored-edge/`
- Phase 2 design: `openspec/changes/user-authored-edge/design.md`
- Phase 2 requirements: `openspec/changes/user-authored-edge/specs/user-authored-edge/spec.md`
- 顶层建模设计: `docs/user-domain-modeling-plan.md` §4 Phase 2
- Phase 1 runbook: `docs/runbooks/user-profile-backfill.md`
