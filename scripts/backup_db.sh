#!/usr/bin/env bash
set -e

BACKUP_DIR="backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="$BACKUP_DIR/rsdp_$TIMESTAMP.sql"

echo "=== RSDP PostgreSQL 数据库备份 ==="

mkdir -p "$BACKUP_DIR"

docker exec rsdp-postgres pg_dump -U "${POSTGRES_USER:-rsdp}" -d rsdp -Fc > "$BACKUP_FILE"

echo "备份完成：$BACKUP_FILE"
