# PREFERS Aggregation Runbook

> 把 `PreferenceEvent` 历史聚合成 `(:UserAccount)-[:PREFERS]->(:Topic)` 投影边的周期性 job 的运行手册。
> 实施变更：`prefers-aggregation-job` (archived 2026-05-13 之后)。

## 一句话总览

每 5 分钟，扫一遍 24 小时窗内的 `PreferenceEvent`，按"事件类型 × 时间衰减"聚合成 `PREFERS.weight`。`FeedService` 在 `prefers-feed-ranking` flag 开启时把这个 weight 当作主排序键，让用户最近活跃的 Topic 浮到前面。

两个 flag 都默认 `false`。这份 runbook 解释怎么把它们打开、怎么诊断异常、怎么回滚。

## 涉及的两个 Feature Flag

| Flag | 默认 | 作用 |
|---|---|---|
| `rhizodelta.feature.prefers-aggregation.enabled` | `false` | 控制聚合 job 是否真正跑。关闭时 job 仍按 5 分钟节奏被调度，但立刻递增 `outcome="skipped"` 后返回。 |
| `rhizodelta.feature.prefers-feed-ranking.enabled` | `false` | 控制 `FeedService` 是否在 Cypher 里 OPTIONAL MATCH PREFERS 边并用 weight 作为主排序键。关闭时 Cypher 与本变更引入前**字节级一致**。 |

flag 切换不需要重启：聚合 job 在每次 tick 通过 `Environment.getProperty` 读取；`FeedService` 在每次请求时读取。

## 推荐上线顺序（两个 flag，一个方向）

**任何时候都不要先开 ranking 后开 aggregation**——这会让 feed 读取从未生成的 PREFERS 数据，应用会落到 `coalesce(weight, 0)=0` 的退化排序，并在 JVM 生命周期内输出一行 WARN：

```
Mismatched feature flags: rhizodelta.feature.prefers-feed-ranking.enabled=true
but rhizodelta.feature.prefers-aggregation.enabled=false. ...
```

正确顺序：

1. **Phase 0 — 代码已部署，两个 flag 均关闭**。Job 类已实例化、已调度，但每 tick 都 `recordSkipped()` 后立刻返回。Feed 行为与代码部署前完全一致。
2. **Phase 1 — 开 aggregation，保持 ranking 关闭**。设置 `RHIZODELTA_FEATURE_PREFERS_AGGREGATION_ENABLED=true`。从下一个 tick 起 PREFERS 边开始落库，Grafana 面板的 `prefers_aggregation_edges_upserted_total` 应该开始爬坡。Feed 排序此时仍然是今天的 FOLLOWS-only。**建议在此阶段停留 24 小时**：让 duration 指标稳定下来，让至少一次完整衰减周期跑过，让监控基线建立。
3. **Phase 2 — 开 ranking**。设置 `RHIZODELTA_FEATURE_PREFERS_FEED_RANKING_ENABLED=true`。Feed 排序立即切换；已经互动过的 Topic 下的内容会浮到前面，未互动过的 Topic 下的内容仍然出现，只是在按 `created_at` 排序之后。
4. **回滚**：按相反顺序——先关 `prefers-feed-ranking`（feed 立刻回到 FOLLOWS-only），再关 `prefers-aggregation`（停止写入；已有的 PREFERS 边留在图里，要么自然衰减到零，要么手动清理见末节）。

## 算法摘要（详见 change `prefers-aggregation-job` 的 design.md）

对窗口内每个 `(userId, topicId)` 对：

```
weight = Σᵢ baseWeight(typeᵢ) · 0.5 ^ (Δᵢ / halfLifeDays)
clampedWeight = min(max(weight, 0), 1000)
```

事件类型基础权重：

| 类型 | 基础权重 | 触发场景（约定）|
|---|---|---|
| `VIEW`   | 0.5 | 节点详情被打开 |
| `EXPAND` | 1.0 | 节点子图被展开 |
| `DWELL`  | 1.5 | 视图停留超过阈值 |
| `LIKE`   | 2.0 | 显式点赞 |
| `SHARE`  | 3.0 | 转发 / 引用 |

默认参数：

- `rhizodelta.preference.half-life-days = 30`（半衰期 30 天，即 30 天前的事件贡献减半）
- `rhizodelta.preference.window-hours = 24`（每 tick 扫最近 24 小时事件）
- `rhizodelta.preference.weight-floor = 0.0`
- `rhizodelta.preference.weight-ceiling = 1000.0`（防止一次密集 burst 把一个 topic 永久钉在 feed 顶端）
- `rhizodelta.preference.aggregation-interval-ms = 300000`（5 分钟）

## 监控指标

四个 Micrometer 指标，挂在 `metric_registry_*` 看板：

| 指标 | 类型 | 看什么 |
|---|---|---|
| `prefers_aggregation_run_total{outcome=ok\|skipped\|error}` | Counter | tick 频率、跳过比例、错误比例。`rate(events) / rate(run{ok})` 是"在跑但失败"的早期信号。 |
| `prefers_aggregation_events_processed_total` | Counter | 单 tick 处理的事件量。突然冲高 ⇒ 用户行为 burst；突然归零 ⇒ 写路径异常或全站静默。 |
| `prefers_aggregation_edges_upserted_total` | Counter | 单 tick 写入的边数。 |
| `prefers_aggregation_duration_seconds` | Timer | 端到端耗时。p99 接近 5 分钟意味着调度即将赶不上节奏，准备 scaling out。 |

读侧两个指标（由 `FeedService` 在每次请求时埋点）：

| 指标 | 类型 | 看什么 |
|---|---|---|
| `rhizodelta_feed_query_total{variant=prefers\|plain\|global}` | Counter | 当前请求究竟走了哪个 Cypher。`rate(variant="prefers") / rate(...)` 反映 ranking flag 在用户侧的实际覆盖比例。 |
| `rhizodelta_feed_items_returned_total{has_prefers_weight=true\|false}` | Counter | **只在 PREFERS 变体下递增**。`rate(has_prefers_weight="true") / rate(...)` ≈ PREFERS 命中率——告诉你"排序到底动没动到行"。接近 0 意味着 ranking flag 开了但聚合数据稀薄，feed 仍然事实上按 created_at 排，应继续等 aggregation 富集再观察。 |

## Phase 1 → 2 Promotion Checklist

把"建议停留 24 小时"翻译成可核对的量化绿灯。**所有项绿灯**才把 `RHIZODELTA_FEATURE_PREFERS_FEED_RANKING_ENABLED` 翻 true。任何一项红灯：维持现状，按"诊断手册"定位问题。

写侧（aggregation 已经开了 ≥ 24h）：

1. `rate(prefers_aggregation_run_total{outcome="error"}[24h]) == 0`
   —— 24h 内没有任何失败 tick。如非 0，先解决错误。
2. `rate(prefers_aggregation_events_processed_total[24h]) > 0`
   —— 至少持续地在处理事件，不是干跑。
3. `histogram_quantile(0.99, rate(prefers_aggregation_duration_seconds_bucket[5m])) < 60s`
   —— p99 端到端耗时远小于 5 分钟节奏，没有积压。
4. **PREFERS 覆盖率**：
   ```cypher
   MATCH (u:UserAccount {status: 'ACTIVE'})
   OPTIONAL MATCH (u)-[:PREFERS]->(t:Topic)
   WITH u, count(t) AS prefersCount
   RETURN sum(CASE WHEN prefersCount > 0 THEN 1 ELSE 0 END) * 1.0 / count(u) AS coverage_ratio
   ```
   coverage_ratio ≥ 0.5（粗略目标，可按业务现状调整）。低于这个值意味着大多数活跃用户的 feed 还没攒到足够 PREFERS 边，翻开 ranking 会让大量用户落到 `weight=0` 的退化排序，体验上等同于今天。

ranking 翻开后（24h 内必须看到这条）：

5. `rate(rhizodelta_feed_items_returned_total{has_prefers_weight="true"}[5m]) > 0`
   —— 至少有用户的 feed 命中过 PREFERS 边。如果翻开 24h 后这条始终为 0，说明读侧实际上没用到 PREFERS 数据（可能是 `topic_id` 缺失、Cypher schema 漂移、PREFERS 边方向反了等），立刻 `RHIZODELTA_FEATURE_PREFERS_FEED_RANKING_ENABLED=false` 回滚并排查。

## 诊断手册

### "Job 在跑但没数据"
`run_total{outcome=ok}` 增长正常，`events_processed` 长期为零。
- 检查 `PreferenceEvent` 是否真的在写：`MATCH (e:PreferenceEvent) WHERE e.at >= datetime() - duration('PT24H') RETURN count(e)`
- 检查 `NodeQueryController:226` 的 `recordEvent` 调用是否被异常吞掉（`PreferenceEventService` 在写失败时只输出 DEBUG 日志）

### "Edges 数变化反常"
单 tick `edges_upserted` 比平时高数倍。
- 通常是用户行为变化（大事件），不是 bug
- 但如果同时 `duration_seconds` 也飙高，可能预示窗口内积压

### "`outcome=error` 持续递增"
- 看 ERROR 日志，常见原因：Neo4j 不可达、Cypher 因 schema 改动需要调整、Spring context 启动期没完成
- Job 不会因为单次失败停止；只要下游恢复，下一 tick 就会成功

### "WARN 日志：Mismatched feature flags"
ranking on 但 aggregation off。按上面"推荐上线顺序"的回滚段处理。

## 操作 Cypher

### 立刻补跑一轮聚合（不等 5 分钟）

通过 actuator 端点直接触发 `PrefersAggregationJob.runOnce()`：

```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN_JWT" \
     http://localhost:8090/actuator/prefers-aggregation | jq
```

可能的响应：

```jsonc
// flag on，本轮完成（典型）
{
  "status": "OK",
  "invoked_at": "2026-05-17T08:32:15.412Z",
  "result": {
    "events_processed": 142,
    "edges_upserted": 38,
    "window_start": "2026-05-16T08:32:15.412Z"
  },
  "error_message": ""
}

// aggregation flag 关闭
{ "status": "SKIPPED", "invoked_at": "...", "result": {}, "error_message": "" }

// 聚合期间抛错（Neo4j 不可达、Cypher schema 漂移等）
{ "status": "ERROR", "invoked_at": "...", "result": {}, "error_message": "..." }
```

端点要求 `ROLE_ADMIN`；任何其他角色返回 403，未认证返回 401。endpoint 自身不重复校验 flag——
flag 关闭时仍然 200 + `status=SKIPPED`，方便运营在确认状态后再决定是否翻开 flag。

历史背景：早期版本 runbook 标注「无 actuator 端点」，运维 replay 只能直连 Neo4j 跑下面的 Cypher。
端点接入后下面那条直连 Cypher 路径仍然保留，作为 backend 不可用时的最后兜底。

### 冷启动 / Replay：用全部历史 PreferenceEvent 重建 PREFERS

把窗口扩到无限大，临时执行一次（注意：在事件量大时会很慢）：

```cypher
MATCH (u:UserAccount)-[:EMITTED]->(e:PreferenceEvent)-[:TOWARD]->(t:Topic)
WITH u, t,
     sum(
       CASE e.type
         WHEN 'VIEW'   THEN 0.5
         WHEN 'EXPAND' THEN 1.0
         WHEN 'DWELL'  THEN 1.5
         WHEN 'LIKE'   THEN 2.0
         WHEN 'SHARE'  THEN 3.0
         ELSE 0.0
       END *
       (0.5 ^ (duration.inSeconds(e.at, datetime()).seconds / (86400.0 * 30.0)))
     ) AS rawWeight,
     max(e.at) AS lastEventAt
WITH u, t,
     CASE WHEN rawWeight > 1000.0 THEN 1000.0 ELSE rawWeight END AS clampedWeight,
     lastEventAt
WHERE clampedWeight > 0
MERGE (u)-[r:PREFERS]->(t)
ON CREATE SET r.created_at = datetime()
SET r.weight = clampedWeight,
    r.last_event_at = lastEventAt,
    r.updated_at = datetime()
RETURN count(r)
```

数字与 Java 端 `PrefersAggregationPolicy` 保持一致——改 policy 时同步改这个 Cypher。

### 衰减-only 道（清理半年未互动的 PREFERS 边）

```cypher
MATCH (u:UserAccount)-[r:PREFERS]->(t:Topic)
WHERE r.updated_at < datetime() - duration('P180D')
DELETE r
```

### 永久回滚：全部清除 PREFERS

```cypher
MATCH ()-[r:PREFERS]->() DELETE r
```

执行前确认两个 flag 都已经关闭，否则下一 tick 会重建一部分。

## Scaling

`PreferenceEvent.at` 在 `DatabaseInitializer.SCHEMA_QUERIES` 里有 BTree 索引（`rhizodelta_preference_event_at_idx`），主 Cypher 的 `WHERE e.at >= $windowStart` 默认走 index-range scan，**不需要事后再加索引**。

如果 `prefers_aggregation_duration_seconds` p99 持续 > 60 秒：

1. 检查 Neo4j 是否在 GC 抖动（heap、`db.checkpoint`）
2. 用 `EXPLAIN` 看主 Cypher 的 plan，确认 `PreferenceEvent` 上的 `at` 索引仍被使用（如果不是，先排查索引是否被破坏或失效，而不是新加索引）
3. 缩短 `rhizodelta.preference.window-hours`（例如降到 6 小时）以减少单 tick 扫描量
4. 极端情况下：按 `user_id` 模哈希分批，多个 tick 各处理 1/N 用户

每一步都先看指标再动手。
