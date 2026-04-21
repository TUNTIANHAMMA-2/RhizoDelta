# Runbook · UserAccount user_id integrity violation

## 适用条件

当 RhizoDelta 后端启动失败并抛出以下 `IllegalStateException` 之一时使用本 runbook：

- `UserAccount user_id integrity violation: blank user_id usernames=[...]`
- `UserAccount user_id integrity violation: duplicate user_id entries=[...]`

> 这是 `DatabaseInitializer.verifyUserIdIntegrity()` 在执行 schema DDL **之前** 运行的只读预检。失败时应用会主动抛 `IllegalStateException` 并阻断启动（非零退出），避免在脏数据上创建 `rhizodelta_user_account_user_id_unique` 约束时出现无法溯源的 `Neo.ClientError.Schema.ConstraintValidationFailed`。

## 前置准备

1. 打开 Neo4j Browser：`http://<neo4j-host>:7474/browser/`（本地 WSL 默认 `http://127.0.0.1:7474/browser/`）。
2. 使用 `.env` / `docker-compose.yml` 中配置的 `NEO4J_AUTH` 登录。
3. 所有修复命令请先在 Browser 里读查询、确认影响面后再写入。

---

## 场景 A · 存在 `null` 或空白 `user_id`

### 1. 查出问题行

```cypher
MATCH (u:UserAccount)
WHERE u.user_id IS NULL OR trim(u.user_id) = ''
RETURN u.username AS username, u.user_id AS user_id
ORDER BY username
LIMIT 20;
```

### 2. 若确认是需要保留的账号，补一个新 UUID

```cypher
MATCH (u:UserAccount)
WHERE u.user_id IS NULL OR trim(u.user_id) = ''
SET u.user_id = randomUUID()
RETURN u.username AS username, u.user_id AS repaired_user_id
ORDER BY username;
```

### 3. 重启应用

修复后重新启动后端。预检在 DDL 之前运行，若仍有残余会再次抛错。

### 注意事项

- 这条修复会改变账号的 canonical identity，**只适合本地或测试数据**。
- 如果这些账号已经被外部系统（OAuth 绑定、对外 URL、审计日志）引用，不要直接改 UUID，应先核对映射关系后再决定。

---

## 场景 B · 存在重复 `user_id`

### 1. 查出重复值和对应用户名

```cypher
MATCH (u:UserAccount)
WITH u.user_id AS uid, collect(u.username)[0..20] AS usernames, count(*) AS n
WHERE n > 1
RETURN uid, usernames, n
ORDER BY uid
LIMIT 20;
```

错误消息里的 `total=N` 和这里 `n` 的值对应，可直接核对。

### 2. 若确认其中某条是无效测试数据，删除冲突行

```cypher
MATCH (u:UserAccount {username: $username})
DETACH DELETE u;
```

在 Browser 里把 `$username` 替换成实际用户名后执行。

### 3. 重新校验并重启

删除后再跑一次上面的重复检查查询，确认该 `uid` 只剩一条记录，然后重启应用。

### 注意事项

- **不要一次性删除整组重复记录**，至少保留一条权威数据。
- 生产环境如果无法判断哪条该删，先用 `RETURN u` 导出结果并交给业务或 DBA 确认。

---

## 回滚（紧急去除新 schema 对象）

```cypher
DROP CONSTRAINT rhizodelta_user_account_user_id_unique IF EXISTS;
DROP INDEX rhizodelta_user_account_status_idx IF EXISTS;
```

> 本次 Phase 0 不再创建独立的 `rhizodelta_user_account_user_id_idx`（Neo4j 5 与 UNIQUE 约束互斥），因此 **rollback 仅两条 DROP**。`status = 'ACTIVE'` 属性可留在历史行，旧版本读取时会被忽略。

---

## 相关规范

- OpenSpec change: `openspec/changes/user-identity-constraint/`
- Requirements: `specs/user-identity-schema/spec.md` (R1–R5)
- 设计决策: `design.md` D1–D5
- 顶层设计: `docs/user-domain-modeling-plan.md` §4 Phase 0
