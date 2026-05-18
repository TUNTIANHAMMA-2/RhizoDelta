#!/usr/bin/env bash
# Claude Code SessionEnd hook：当没有 backend 在跑时停掉依赖容器释放内存。
#
# 判据：`ps -e -o comm,args` 过滤"进程名为 java 或 mvn 且 cmdline 含 spring-boot:run"。
#   - 用 ps + awk 而非 `pgrep -f`：避免被任何 shell 命令行里碰巧提到
#     'spring-boot:run' 字符串的进程（比如 Claude Code 自己跑的 bash 工具命令）
#     误命中导致"该停时没停"。
#   - 命中 → 还有 backend 在跑（同会话或别会话的 dev-up.sh），放过不动容器
#   - 不命中 → 没人在开发 backend，停容器（数据卷保留）
#
# 多会话安全：每个会话 SessionEnd 都跑同样的判据，幂等。
# 失败静默：hook 任何异常都不阻塞 CLI 退出。
# 事件日志：写 /tmp/rhizodelta-hook.log（供 hermes 等外部监控消费）。
#
# 注册位置：.claude/settings.json 的 hooks.SessionEnd

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# 统一事件日志（供外部监控如 hermes 消费）。/tmp 重启清空 = 监控的 "new boot" 边界。
LOG=/tmp/rhizodelta-hook.log
TS="$(date -Is)"

MVN_COUNT=$(ps -e -o comm,args 2>/dev/null | awk '$1 ~ /^(java|mvn)$/ && /spring-boot:run/' | wc -l)

# backend 还活着？放过。
if [[ "$MVN_COUNT" -gt 0 ]]; then
  echo "$TS event=session-end mvn_pids=$MVN_COUNT action=skip" >> "$LOG"
  exit 0
fi

# 没 backend 在跑 → 停所有可能被起过的依赖容器。
# 即便某些容器从未启动，`docker compose stop` 对它们也是 no-op。
echo "$TS event=session-end mvn_pids=0 action=stop services=neo4j,rabbitmq,redis,prometheus,grafana,minio" >> "$LOG"
docker compose stop neo4j rabbitmq redis prometheus grafana minio > /dev/null 2>&1 || true
exit 0
