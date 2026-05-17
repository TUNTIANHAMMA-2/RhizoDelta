#!/usr/bin/env bash
# Claude Code SessionEnd hook：当没有 backend 在跑时停掉依赖容器释放内存。
#
# 判据：`pgrep -f 'spring-boot:run'`
#   - 找到 → 还有 backend 在跑（可能是同会话或别的会话的 dev-up.sh），不动容器
#   - 找不到 → 没人在开发 backend，停容器（数据卷保留）
#
# 多会话安全：每个会话 SessionEnd 都跑同样的判据，幂等。
# 失败静默：hook 任何异常都不阻塞 CLI 退出。
#
# 注册位置：.claude/settings.json 的 hooks.SessionEnd

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# backend 还活着？放过。
if pgrep -f 'spring-boot:run' > /dev/null 2>&1; then
  exit 0
fi

# 没 backend 在跑 → 停所有可能被起过的依赖容器。
# 即便某些容器从未启动，`docker compose stop` 对它们也是 no-op。
docker compose stop neo4j rabbitmq redis prometheus grafana minio > /dev/null 2>&1 || true
exit 0
