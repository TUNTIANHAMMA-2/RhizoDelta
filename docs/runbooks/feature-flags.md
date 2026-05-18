# Feature Flags Runbook

> 本文档定义 RhizoDelta 项目的 feature flag 命名约定、当前已注册 flag 一览、切换流程，以及关闭后的副作用。

## Naming Convention

所有 feature flag 必须形如：

```
rhizodelta.feature.<module>.enabled = true | false
```

- `<module>` 为 kebab-case 模块标识，且应与对应 change / capability 名一致
- 默认实现：Spring 原生 `@ConditionalOnProperty(name="rhizodelta.feature.<module>.enabled", havingValue="true", matchIfMissing=true|false)`
- 不引入第三方 feature flag 服务（详见 `observability-foundation` change 的 design.md D2）
- 环境变量等价形式：`RHIZODELTA_FEATURE_<MODULE>_ENABLED`（`<module>` 中的 `-` 替换为 `_` 并大写）

## Currently Registered Flags

下表与 `FeatureFlagRegistry.java` 保持一致。新增 flag 时**两处都要改**。

| Module | Property | Env Var | Default | 受影响 Bean / 行为 | 关闭副作用 |
|---|---|---|---|---|---|
| `observability` | `rhizodelta.feature.observability.enabled` | `RHIZODELTA_FEATURE_OBSERVABILITY_ENABLED` | `true` | `ObservabilityConfig`、`SweeperMetrics`、`MeteredChatLanguageModel` 装配链 | 失去 AI metric，业务功能不变 |
| `sweeper` | `rhizodelta.feature.sweeper.enabled` | `RHIZODELTA_FEATURE_SWEEPER_ENABLED` | `false` | （由 `sweeper-candidate-edge` change 实现） | 漫游智能体不调度，候选边停止生产 |
| `proposal` | `rhizodelta.feature.proposal.enabled` | `RHIZODELTA_FEATURE_PROPOSAL_ENABLED` | `false` | （由 `proposal-governance-full` change 实现） | 提案系统所有 API 返回 503，已存在的 Proposal 不再被驱动状态机 |
| `prefers-aggregation` | `rhizodelta.feature.prefers-aggregation.enabled` | `RHIZODELTA_FEATURE_PREFERS_AGGREGATION_ENABLED` | `false` | `PrefersAggregationJob` 调度入口（每 5 分钟一次） | 调度 tick 仍然触发，但立即递增 `outcome="skipped"` 指标后返回；PreferenceEvent 继续写入但不再聚合到 PREFERS 边，已存在的 PREFERS 边停留在最近一次聚合时的权重，自然衰减需依赖下一次聚合或手动重跑。翻开前请遵循 [`prefers-aggregation.md` §Promotion Checklist](./prefers-aggregation.md#phase-1--2-promotion-checklist) |
| `prefers-feed-ranking` | `rhizodelta.feature.prefers-feed-ranking.enabled` | `RHIZODELTA_FEATURE_PREFERS_FEED_RANKING_ENABLED` | `false` | `FeedService` Cypher 选择分支 | Feed 排序退化为今天的 FOLLOWS-only 行为，PREFERS 边不被读取；切换 flag 不需要重启，下一次 feed 请求即生效。翻开前请遵循 [`prefers-aggregation.md` §Promotion Checklist](./prefers-aggregation.md#phase-1--2-promotion-checklist) |

> `default=false` 的 flag 是显式默认关闭，避免新部署在未配置时无意启用。
> `default=true` 的 flag 是基础设施（如 observability），新部署应该默认启用。

## Toggling Flags

### 本地开发（IDE 启动 backend）

在 IDE 的 Run Configuration → Environment Variables 添加：

```
RHIZODELTA_FEATURE_OBSERVABILITY_ENABLED=false
```

或在 `application-local.yml` 追加：

```yaml
rhizodelta:
  feature:
    observability:
      enabled: false
```

重启应用生效。

### Docker Compose（如果 backend 也容器化）

```bash
# 编辑 .env
vim .env
# 追加 RHIZODELTA_FEATURE_<MODULE>_ENABLED=false

# 重启相应容器
docker compose restart backend
```

### 生产环境

通过环境变量注入。系统编排（systemd / kubernetes / 云函数）应把 `.env` 或等价的 env 配置作为部署参数管理。

切换后可以通过观察启动日志确认：

```
INFO  c.r.i.observability.FeatureFlagLogger - Feature flags: observability=DISABLED(env), sweeper=DISABLED(default), ...
```

`(env)` 表示该值由环境变量提供；`(config)` 表示由 application.yml 提供；`(default)` 表示属性源都没设置，使用 registry 默认值。

## Compounding Effects

某些 flag 关闭会产生级联效果：

- **关闭 observability**：所有依赖 metrics 做"健康判定"的运维流程会失效（例如 sweeper 上线后用 token cost 做熔断）
- **关闭 sweeper 但保留 proposal**：proposal 系统中"候选边复核"类型的 Proposal 不会有新增（依赖 sweeper 产出），但已存在的不受影响
- **关闭 prefers-aggregation 但保留 prefers-feed-ranking**：Feed 排序会读取过期或不存在的 PREFERS 数据；推荐**两者一起关**

## Adding a New Flag

后续 change 引入新模块时：

1. 在 `FeatureFlagRegistry.FLAGS` 末尾追加一条 `new FeatureFlag("<module>", "rhizodelta.feature.<module>.enabled", <default>)`
2. 在 `application.yml` 追加对应默认值
3. 在 `.env.example` 追加注释行
4. 在 Bean 上加 `@ConditionalOnProperty(name = "...", havingValue = "true", matchIfMissing = <default>)`
5. 在本文档的"Currently Registered Flags"表追加一行
6. 写入 change 的 `design.md`：该 flag 关闭后的副作用与依赖关系

## Verification After Toggle

```bash
# backend 启动日志最后会出现一行 Feature flags 状态总览：
docker compose logs backend 2>&1 | grep "Feature flags:"
```

或用 actuator 检查（仅在 `management.endpoints.web.exposure.include` 包含 `env` 时可用）：

```bash
# 不在默认范围内，需要手工开启 env 端点
curl -s http://localhost:8090/actuator/env/rhizodelta.feature.observability.enabled
```

## Related Documents

- [Observability Runbook](./observability.md)
- Change `observability-foundation`：`openspec/changes/observability-foundation/`
- 设计依据：`design.md` 第 D2 / D7 节
