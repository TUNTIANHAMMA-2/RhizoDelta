#!/usr/bin/env bash
# 启动 RhizoDelta 开发回路：拉起依赖容器 → 前台跑 backend → 退出时自动停容器释放内存。
#
# 用法：
#   ./scripts/dev-up.sh                              # 仅启动核心依赖（neo4j / rabbitmq / redis）
#   WITH_OBSERVABILITY=true ./scripts/dev-up.sh      # 额外启动 prometheus / grafana
#   WITH_MINIO=true ./scripts/dev-up.sh              # 额外启动 minio（头像上传）
#
# 退出时（Ctrl+C 或 mvn 自然结束）执行 `docker compose stop`：
#   - 容器停掉，内存释放
#   - 数据卷（neo4j_data / grafana_data / minio_data ...）保留，下次启动几秒回来

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SERVICES=(neo4j rabbitmq redis)
[[ "${WITH_OBSERVABILITY:-false}" == "true" ]] && SERVICES+=(prometheus grafana)
[[ "${WITH_MINIO:-false}" == "true" ]] && SERVICES+=(minio)

cleanup() {
  trap - EXIT INT TERM
  echo
  echo "[dev-up] backend exited → docker compose stop ${SERVICES[*]}"
  docker compose stop "${SERVICES[@]}"
  echo "[dev-up] containers stopped (data volumes preserved)."
}
trap cleanup EXIT INT TERM

if [[ -z "${DASHSCOPE_API_KEY:-}" ]]; then
  echo "[dev-up] WARNING: DASHSCOPE_API_KEY is not set; backend will refuse to start (see CLAUDE.md §4.2)."
fi

echo "[dev-up] starting docker services: ${SERVICES[*]}"
docker compose up -d "${SERVICES[@]}"

echo "[dev-up] starting backend with mvn spring-boot:run — press Ctrl+C to stop everything"
mvn spring-boot:run
