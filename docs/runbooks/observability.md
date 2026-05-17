# Observability Runbook

> 本文档面向 RhizoDelta 单实例 Docker Compose 部署。覆盖：访问、验证、按 flag 关闭、看板解读、容量阈值。

## Access

启动监控栈：

```bash
# 第一次部署前，先在 .env 里设置 GRAFANA_ADMIN_PASSWORD（不要用默认 changeme）
docker compose up -d prometheus grafana

# 查看容器健康
docker compose ps prometheus grafana
```

访问入口（均仅绑定 `127.0.0.1`，不暴露公网）：

| 服务 | 地址 | 凭据 |
|---|---|---|
| Prometheus 原始查询 | http://127.0.0.1:9090 | 无 |
| Prometheus targets 状态 | http://127.0.0.1:9090/targets | 无 |
| Grafana 仪表盘 | http://127.0.0.1:3000 | admin / `${GRAFANA_ADMIN_PASSWORD}` |

## Verification

确认指标全链路通畅的 3 个验证点：

### 1. backend 自身在暴露指标

```bash
curl -s http://localhost:8090/actuator/prometheus | grep -E "^(jvm_|ai_llm_|sweeper_)" | head -10
```

预期：
- `jvm_memory_used_bytes{...}` 等基础指标必现
- `sweeper_candidates_total{stage="embedding|llm|merged"} 0` 必现（即使没有 sweeper 也是预注册的零值时序）
- `ai_llm_*` 在第一次 LLM 调用后出现

### 2. Prometheus 在抓取 backend

打开 http://127.0.0.1:9090/targets，找 `rhizodelta-backend` job，State 应为 `UP`。

排障：
- State `DOWN` + Last Error 显示 connection refused → backend 没启动 / 端口不对
- State `DOWN` + Last Error 显示 i/o timeout → `extra_hosts: host.docker.internal:host-gateway` 没生效，检查 docker-compose.yml

### 3. Grafana 看板有数据

登录 Grafana → 左侧菜单 Dashboards → 默认目录里有 "AI Cost & Latency" → 触发一次发帖（走 AI 编排），刷新页面应看到 4 个 panel 中至少 "AI Token Rate by Model" 和 "AI Latency P50/P95/P99 by Purpose" 出现折线。

## Disable Observability

需要立即关闭可观测性时（例：怀疑 metric 装饰器影响业务、Prometheus 占盘过多）：

### Level 1（最快，约 3 分钟）：关 backend 侧埋点

```bash
# 编辑 .env 追加（或修改）：
echo "RHIZODELTA_FEATURE_OBSERVABILITY_ENABLED=false" >> .env

# 重启 backend（或在 IDE 重启）
# 重启后 backend 不再装配 MeteredChatLanguageModel，业务等同于本 change 之前
```

效果：
- backend 仍暴露 `/actuator/prometheus`，但不再有 `ai_llm_*` / `sweeper_*` 时序
- JVM 指标 / HTTP 指标继续可见（Spring Boot 自带，未关）
- Prometheus / Grafana 容器无需重启，看板里 ai.llm 系列折线会平稳归零

### Level 2（中等，约 10 分钟）：关停监控容器

```bash
docker compose stop prometheus grafana
```

效果：
- backend 仍在埋点（如果 Level 1 没做），但无人采集
- 不释放 backend 资源，仅释放 prometheus / grafana 内存
- 历史数据保留在 `prometheus_data` volume 中，重启后可恢复

### Level 3（终极，30 分钟）：git revert 整个 change

```bash
# 找到 change 的 squash commit / PR
git log --oneline | grep observability
# 用 git revert 反转所有相关 commit
```

完全恢复 change 之前的行为。

## Panel Interpretation

| Panel | 含义 | 健康基线 | 异常信号 |
|---|---|---|---|
| **AI Token Rate by Model** | 每个 model 的 input+output token/秒 | 跟随发帖量起伏 | 突增 5x 以上 → 可能有循环调用 |
| **AI Latency P50/P95/P99 by Purpose** | 4 个 purpose（SUMMARY/ROUTING/QUALITY/EMBEDDING）的延迟分位 | P50 < 2s, P95 < 8s | P99 持续 > 30s → DashScope 限流或网络问题 |
| **AI Error Rate by Exception** | 按异常类名分组的错误速率 | 0 或偶发 | 持续 > 0.1/s → 立刻看 backend 日志，对照 exception tag |
| **Sweeper Candidates by Stage** | 漫游候选边速率，分 embedding / llm / merged 三阶段 | 当前为 0（sweeper 未上线）| 上线后 embedding > llm > merged，递减幅度反映过滤强度 |

## Capacity Triggers

容量基线（依据 design.md D5）：500 用户中等活跃 × 5 次/天 × 15d 保留期 ≈ 1.5 GiB；
本期分配 2 GiB 余量，**预警阈值 5 GiB**。

定期检查：

```bash
# 查看 prometheus 数据卷大小
docker compose exec prometheus du -sh /prometheus

# 或从宿主机看 docker volume
docker volume inspect rhizodelta_prometheus_data --format '{{.Mountpoint}}' \
  | xargs -I{} du -sh {}
```

触发动作：

| 卷大小 | 状态 | 动作 |
|---|---|---|
| < 2 GiB | 健康 | 无需动作 |
| 2 ~ 5 GiB | 注意 | 检查是否有意外的 cardinality 爆炸：`curl -s localhost:9090/api/v1/status/tsdb \| jq '.data.seriesCountByMetricName'` |
| > 5 GiB | 预警 | 立刻评估保留期收缩到 7d，或检查是否有未受控的 metric tag |
| > 10 GiB | 紧急 | Level 2 关停 prometheus → 备份 volume → 缩短保留期重启 |

## Related Documents

- [Feature Flags Runbook](./feature-flags.md)
- Change `observability-foundation`：`openspec/changes/observability-foundation/`
- 容量基线计算依据：`design.md` 第 D5 节
