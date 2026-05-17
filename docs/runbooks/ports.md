# Port Convention Runbook

> RhizoDelta 单实例本地/开发部署的端口约定。任何对默认端口的修改都必须更新本文件、`.env.example`、以及下文"修改清单"中列出的所有联动配置。

## 默认端口表

| 服务 | 默认端口 | 暴露形式 | 覆盖方式 | 备注 |
|---|---|---|---|---|
| Backend HTTP（API + Actuator + Prometheus metrics） | `8080` | 宿主机直连（IDE 或 `mvn spring-boot:run`） | `SERVER_PORT` 环境变量 → `application.yml` 中 `server.port: ${SERVER_PORT:8080}` | API、`/actuator/health`、`/actuator/prometheus` 都走这个端口。**不要**为 metrics 单独开 management port。 |
| Frontend Vite dev server | `5173` | `localhost:5173`（Vite 默认） | `vite --port <n>` 或 `frontend/vite.config.ts` 中 `server.port` | `/api` 前缀由 Vite 代理转发到 backend。 |
| Neo4j HTTP（Browser） | `7474` | `127.0.0.1:7474` | `NEO4J_HTTP_PORT`（`.env`） | docker-compose 容器内部固定 7474。 |
| Neo4j Bolt | `7687` | `127.0.0.1:7687` | `NEO4J_BOLT_PORT`（`.env`） + `NEO4J_URI` | 容器内部固定 7687。 |
| RabbitMQ AMQP | `5672` | `127.0.0.1:5672` | `RABBITMQ_PORT`（`.env`） | 容器内部固定 5672。 |
| RabbitMQ Management UI | `15672` | `127.0.0.1:15672` | `RABBITMQ_MANAGEMENT_PORT`（`.env`） | 容器内部固定 15672。 |
| Redis | `6379` | `127.0.0.1:6379` | `REDIS_PORT`（`.env`） | 容器内部固定 6379。 |
| MinIO API（可选） | `9000` | `127.0.0.1:9000` | `MINIO_API_PORT`（`.env`） | 仅在 `rhizodelta.minio.enabled=true` 时启动。 |
| MinIO Console（可选） | `9001` | `127.0.0.1:9001` | `MINIO_CONSOLE_PORT`（`.env`） | — |
| Prometheus | `9090` | `127.0.0.1:9090` | `PROMETHEUS_PORT`（`.env`） | 容器通过 `host.docker.internal:8080` 抓 backend。 |
| Grafana | `3000` | `127.0.0.1:3000` | `GRAFANA_PORT`（`.env`） | — |

约定：

- **所有 docker-compose 服务都仅绑定到 `127.0.0.1`**，不允许公网暴露；如需远程访问请通过 SSH 隧道或反向代理。
- **Backend 永远走单一端口 8080**：API、Actuator、Prometheus 指标共用。这样 `/api/...` 与 `/actuator/...` 不会发生跨端口的鉴权/CORS 复杂化。
- **Vite 永远代理到 `http://localhost:8080`**：`frontend/vite.config.ts` 中硬编码的代理目标必须与 backend 实际端口一致。
- **环境变量名空间**：宿主机端口用 `<SERVICE>_PORT` 形式（`SERVER_PORT`、`NEO4J_HTTP_PORT` 等）；不要随意发明新的 `XXX_HOST_PORT`、`XXX_LISTEN_PORT` 风格变量。

## 冲突排查

启动前快速确认端口空闲：

```bash
# 任一返回非空 → 已被占用
ss -lntp | awk '$4 ~ /:(8080|5173|7474|7687|5672|15672|6379|9000|9001|9090|3000)$/'
```

如果默认端口被占用，按"覆盖方式"列改环境变量。**禁止只改一处**——以 backend 为例，从 8080 换到 8090 需要同步：

1. `.env`：`SERVER_PORT=8090`
2. `prometheus/prometheus.yml`：`targets: ['host.docker.internal:8090']`
3. `frontend/vite.config.ts`：`proxy['/api'].target = 'http://localhost:8090'`
4. 所有 runbook / 使用手册中 `localhost:8080/...` 的 curl 示例（搜索：`rg -n '8080' Doc/ docs/ README.md frontend/README.md`）

修改完毕务必跑一遍校验：

```bash
# backend
curl -fsS http://localhost:${SERVER_PORT:-8080}/actuator/health
curl -fsS http://localhost:${SERVER_PORT:-8080}/actuator/prometheus | head -3

# prometheus 抓取状态（应看到 rhizodelta-backend State=UP）
open http://127.0.0.1:${PROMETHEUS_PORT:-9090}/targets
```

## 历史教训

- **2026-05-17**：曾有一次 ad-hoc 把 `prometheus/prometheus.yml` 抓取目标从 8080 改为 8081，但 backend、`application.yml`、Vite 代理、所有 runbook 都仍是 8080，导致 Prometheus targets 全部 `DOWN`。结论：**端口修改是横切关注点，只改单一文件 ≈ 引入静默 bug**。本文件就是为了避免重复这种事。

## 相关文档

- [Observability Runbook](./observability.md)：Prometheus / Grafana 验证步骤
- [Feature Flags Runbook](./feature-flags.md)
- `docker-compose.yml`、`.env.example`：容器端口绑定的事实来源
- `frontend/vite.config.ts`：前端代理目标的事实来源
