# 累积式合并 & 分支上下文 — 手动黑盒测试用例

> 前置条件：系统正常运行，Neo4j / RabbitMQ 已连接，AI 模型可用
> 工具：浏览器前端 + Neo4j Browser（验证图结构）+ 后端日志

---

## TC-1: 首次合并 — 创建新 AI_Consensus

**目标**: 验证第一次 MERGE 仍然正常创建新的 AI_Consensus 节点

**步骤**:
1. 发帖 A（无 target_node_id）：`"量子计算的基本原理是利用量子叠加和纠缠来进行并行计算"`
2. 等待 A 创建完成，记录 `A.node_id`
3. 选中节点 A，回复帖子 B：`"量子计算利用叠加态实现并行，与经典位不同，量子位可以同时为 0 和 1"`
4. 观察 SSE 事件流

**预期结果**:
- SSE 收到 `ORCHESTRATION_STATUS` → `EVALUATION_STARTED` → `RECALL_COMPLETED` → `MERGE_QUEUED`
- SSE 收到 `EDGE_CREATED` (MERGED_INTO, consensus→A)
- SSE 收到 `EDGE_CREATED` (SYNTHESIZED_FROM, consensus→B)
- SSE 收到 `DECISION_COMPLETE` (type=MERGE)
- SSE 收到 `SUMMARY_GENERATED`（首次摘要）
- 图上出现一个 AI_Consensus 节点，MERGED_INTO 指向 A，SYNTHESIZED_FROM 指向 B

**Neo4j 验证**:
```cypher
MATCH (ai:AI_Consensus)-[:MERGED_INTO]->(a:Human_Post {node_id: '<A.node_id>'})
RETURN ai.node_id, ai.summary_content
```
应返回 1 行，记录 `consensus.node_id`

---

## TC-2: 累积合并 — 追加到已有 AI_Consensus

**目标**: 验证第二次相似回复不创建新 consensus，而是追加 SYNTHESIZED_FROM

**前置**: TC-1 完成，已有 AI_Consensus 挂在 A 上

**步骤**:
1. 选中节点 A，回复帖子 C：`"量子比特的叠加态使得量子计算机在特定问题上具有指数级加速优势"`
2. 观察 SSE 事件流
3. 在 Neo4j 中检查图结构

**预期结果**:
- AI 路由判定为 MERGE（内容高度相似）
- SSE 收到 `EDGE_CREATED` (SYNTHESIZED_FROM, consensus→C) — **注意：不应有新的 MERGED_INTO**
- SSE 收到 `DECISION_COMPLETE` (type=MERGE)
- SSE 收到 `SUMMARY_GENERATED`（增量摘要，融合了 B 和 C 的内容）
- **关键验证**：没有第二个 AI_Consensus 节点出现

**Neo4j 验证**:
```cypher
// 应该仍然只有 1 个 consensus
MATCH (ai:AI_Consensus)-[:MERGED_INTO]->(a:Human_Post {node_id: '<A.node_id>'})
WHERE NOT coalesce(ai._deleted, false)
RETURN count(ai) AS consensusCount
// 预期: 1

// 该 consensus 应有 2 条 SYNTHESIZED_FROM
MATCH (ai:AI_Consensus)-[:MERGED_INTO]->(a:Human_Post {node_id: '<A.node_id>'})
WHERE NOT coalesce(ai._deleted, false)
MATCH (ai)-[:SYNTHESIZED_FROM]->(source:Human_Post)
RETURN count(source) AS sourceCount
// 预期: 2 (B 和 C)
```

**日志验证**:
搜索 `mergeOrAppend completed` → 应出现 `appended=true`

---

## TC-3: 三次累积合并 — 验证持续追加

**前置**: TC-2 完成

**步骤**:
1. 选中节点 A，回复帖子 D：`"量子并行性是量子计算超越经典计算的核心机制"`
2. 选中节点 A，回复帖子 E：`"利用量子纠缠和叠加，量子处理器可以同时探索多个解空间"`

**预期结果**:
- 两次都追加到同一个 AI_Consensus
- 最终该 consensus 有 4 条 SYNTHESIZED_FROM（B, C, D, E）
- 摘要内容逐步丰富（每次增量更新）

**Neo4j 验证**:
```cypher
MATCH (ai:AI_Consensus)-[:MERGED_INTO]->(a:Human_Post {node_id: '<A.node_id>'})
WHERE NOT coalesce(ai._deleted, false)
MATCH (ai)-[:SYNTHESIZED_FROM]->(source:Human_Post)
WHERE NOT coalesce(source._deleted, false)
RETURN count(DISTINCT source) AS sourceCount, ai.summary_content
// 预期: sourceCount=4, summary 包含多方信息
```

---

## TC-4: BRANCH 不受影响 — 分叉帖不追加到 consensus

**目标**: 验证当 AI 判定为 BRANCH 时，不触发合并追加

**前置**: TC-1 完成

**步骤**:
1. 选中节点 A，回复一条**明显不同主题**的帖子 F：
   `"区块链技术通过分布式账本确保交易的不可篡改性，与量子计算是完全不同的技术领域"`
2. 观察 SSE 事件流

**预期结果**:
- AI 路由判定为 BRANCH（主题不同）
- SSE 收到 `EDGE_CREATED` (BRANCHED_FROM, F→候选节点)
- **没有** SYNTHESIZED_FROM 边产生
- AI_Consensus 的 SYNTHESIZED_FROM 数量不变

---

## TC-5: 分支上下文注入路由 — 深层回复的祖先链

**目标**: 验证深层回复时路由评估器能看到祖先链上下文

**步骤**:
1. 发帖 P1（根帖）：`"深度学习的核心是通过多层神经网络学习数据表示"`
2. 选中 P1，回复 P2：`"卷积神经网络是深度学习中处理图像数据的主要架构"`
   - 等待 AI 处理（可能 MERGE 或 BRANCH）
3. 选中 P2（或 P2 的分支节点），回复 P3：`"CNN 通过卷积核提取局部特征，池化层降维"`
4. 选中 P3（或其分支节点），回复 P4：`"深度学习模型通过反向传播和梯度下降优化参数"`

**预期结果**:
- P4 的路由评估时，后端日志中的 routing context 应包含祖先链信息
- 搜索日志关键词 `branch ancestors` 或 `AI routing evaluator invoking`

**日志验证**:
在 `AiRoutingEvaluatorService` 的日志中，`routingContext` 应包含：
- `--- branch ancestors (root → current) ---` 段落
- P1, P2, P3 的内容或 AI 摘要

---

## TC-6: 增量摘要 vs 全量摘要 — Token 效率

**目标**: 验证追加合并时用的是增量摘要（非全量重读）

**前置**: TC-2 完成（已有 1 个 consensus + 2 个 source）

**步骤**:
1. 选中节点 A，回复帖子 G：`"量子退相干是目前量子计算工程化的主要障碍"`
2. 检查后端日志

**预期结果**:
- 日志中出现 `Summary agent regenerating incrementally`（增量路径）
- **不出现** `Summary agent generating summary`（全量路径）
- 日志中 `new_contributors=1` — 仅传入了新帖 G

**反向对照**:
- TC-1 的首次合并应走 `Summary agent generating summary`（全量路径）

---

## TC-7: 回滚后再合并 — 创建新 consensus

**目标**: 验证已回滚的 consensus 不会被追加

**步骤**:
1. 发帖 X，回复 Y 触发首次 MERGE → 创建 consensus C1
2. 通过 API 回滚 C1：`POST /api/decisions/{C1.decision_id}/rollback`
3. 再回复 Z 到 X，触发 MERGE

**预期结果**:
- 回滚将 C1 标记为 `_deleted=true`
- Z 的 MERGE 应创建**新的** AI_Consensus C2（因为 C1 已被 OPTIONAL MATCH 跳过）
- C2 的 SYNTHESIZED_FROM 仅指向 Z（不包含 Y）

**Neo4j 验证**:
```cypher
MATCH (ai:AI_Consensus)-[:MERGED_INTO]->(x:Human_Post {node_id: '<X.node_id>'})
WHERE NOT coalesce(ai._deleted, false)
RETURN ai.node_id, ai.summary_content
// 应返回 1 行（C2），且 C2.node_id ≠ C1.node_id
```

---

## TC-8: 手动合并 API 不受影响 — 向后兼容

**目标**: 验证 `/api/decisions/merge` 手动 API 行为不变

**步骤**:
1. 创建帖子 M1, M2
2. 直接调用手动合并 API：
```bash
curl -X POST http://localhost:8090/api/decisions/merge \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "decision_id": "manual-merge-1",
    "request_id": "req-manual-1",
    "source_node_id": "<M1.node_id>",
    "agent_version": "manual",
    "summary_content": "手动合并摘要",
    "synthesized_from": ["<M2.node_id>"],
    "operator_type": "HUMAN",
    "operator_id": "test-user",
    "reason": "manual merge test"
  }'
```

**预期结果**:
- 返回 202 + DecisionResult
- 创建新的 AI_Consensus（走的是 `executeMerge` 而非 `mergeOrAppend`）
- SSE 正常发出 MERGED_INTO + SYNTHESIZED_FROM + DECISION_COMPLETE

---

## TC-9: 并发合并安全性（可选，高级）

**目标**: 验证并发回复同一节点不会产生重复 consensus

**步骤**:
1. 发帖 A
2. 用两个浏览器 tab / 两个用户，**几乎同时**回复 A：
   - 用户 1 回复 B1：`"量子计算的未来在于纠错码"`
   - 用户 2 回复 B2：`"量子纠错是实现容错量子计算的关键"`
3. 等待两个都处理完成

**预期结果**:
- 只有 **1 个** AI_Consensus 挂在 A 上
- 该 consensus 有 2 条 SYNTHESIZED_FROM（B1 + B2）
- 日志中一个显示 `appended=false`（先到者创建），另一个显示 `appended=true`（后到者追加）

**Neo4j 验证**:
```cypher
MATCH (ai:AI_Consensus)-[:MERGED_INTO]->(a:Human_Post {node_id: '<A.node_id>'})
WHERE NOT coalesce(ai._deleted, false)
RETURN count(ai)
// 预期: 1（不是 2）
```

---

## 快速检查清单

| # | 场景 | 关键断言 | 状态 |
|---|------|---------|------|
| 1 | 首次合并 | 新 consensus + MERGED_INTO + SYNTHESIZED_FROM | ☐ |
| 2 | 累积合并 | 追加到已有 consensus，不创建新的 | ☐ |
| 3 | 三次累积 | 4 条 SYNTHESIZED_FROM，1 个 consensus | ☐ |
| 4 | BRANCH 不受影响 | 无 SYNTHESIZED_FROM 追加 | ☐ |
| 5 | 祖先链上下文 | 路由日志中包含 ancestors | ☐ |
| 6 | 增量摘要 | 日志走 `regenerating incrementally` | ☐ |
| 7 | 回滚后再合并 | 创建全新 consensus | ☐ |
| 8 | 手动 API 兼容 | executeMerge 行为不变 | ☐ |
| 9 | 并发安全 | 同一 source 仅 1 个 consensus | ☐ |
