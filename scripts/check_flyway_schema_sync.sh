#!/bin/bash
# schema/ 重放 与 Flyway V1+ 增量 双路径结构一致性校验（发布校验）
#
# 路径 A：database/schema/*.sql 按序重放（开发态全貌，含必需种子）
# 路径 B：Flyway 迁移目录 V1__baseline.sql + 已有 V2+ 增量脚本按序执行
# 两条路径在两个空库上执行后做 schema-only dump diff，必须零差异。
# 任何结构变更若只改了一处，本脚本会失败——这是"唯一状态源"的强制闸。
set -euo pipefail

cd "$(dirname "$0")/.."
IMAGE="pgvector/pgvector:pg16"
CONTAINER="rsdp-sync-check-$RANDOM"
trap 'docker rm -f "$CONTAINER" >/dev/null 2>&1 || true' EXIT

dump_schema() { # $1=库名 $2=输出文件
  docker exec -e PGPASSWORD=rsdp "$CONTAINER" pg_dump -h localhost -U rsdp -d "$1" --schema-only -O -x \
    | grep -v -E '^(SET |SELECT pg_catalog\.set_config|\\restrict|\\unrestrict|--)' | grep -v '^$' > "$2"
}

echo "[1/4] 启动临时 PG 实例（${IMAGE}）..."
docker run -d --name "$CONTAINER" -e POSTGRES_USER=rsdp -e POSTGRES_PASSWORD=rsdp -e POSTGRES_DB=rsdp "$IMAGE" >/dev/null
for i in $(seq 1 30); do
  docker exec -e PGPASSWORD=rsdp "$CONTAINER" psql -h localhost -U rsdp -d rsdp -tAc "SELECT 1" >/dev/null 2>&1 && break
  sleep 1
done

echo "[2/4] 路径 A：database/schema/*.sql 按序重放 -> 库 rsdp ..."
docker exec -i -e PGPASSWORD=rsdp "$CONTAINER" psql -h localhost -U rsdp -d rsdp -v ON_ERROR_STOP=1 -q \
  < <(cat database/schema/*.sql)
dump_schema rsdp /tmp/rsdp_sync_a.sql

echo "[3/4] 路径 B：Flyway 迁移脚本按序执行 -> 库 rsdp_b ..."
docker exec -e PGPASSWORD=rsdp "$CONTAINER" psql -h localhost -U rsdp -d postgres -c "CREATE DATABASE rsdp_b;" >/dev/null
MIG_DIR="server/src/main/resources/db/migration"
shopt -s nullglob
vfiles=( $(ls "$MIG_DIR"/V*.sql | sort -V) )
if [ ${#vfiles[@]} -eq 0 ]; then
  echo "❌ 未找到任何 V*.sql 迁移脚本"; exit 1
fi
for f in "${vfiles[@]}"; do
  echo "  applying $(basename "$f")"
  docker exec -i -e PGPASSWORD=rsdp "$CONTAINER" psql -h localhost -U rsdp -d rsdp_b -v ON_ERROR_STOP=1 -q < "$f"
done
dump_schema rsdp_b /tmp/rsdp_sync_b.sql

echo "[4/4] 结构 diff ..."
if diff /tmp/rsdp_sync_a.sql /tmp/rsdp_sync_b.sql > /tmp/rsdp_sync_diff.txt; then
  echo "✅ schema/ 重放与 Flyway V1+ 执行结果零差异"
else
  echo "❌ 检测到结构漂移，必须修复（同步修改 schema/ 与 V 增量脚本，使两路径一致）："
  head -40 /tmp/rsdp_sync_diff.txt
  exit 1
fi
