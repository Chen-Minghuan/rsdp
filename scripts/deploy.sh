#!/usr/bin/env bash
# RSDP 主项目服务器发布脚本（参考 rsdp-office scripts/deploy.sh）
# 用法：bash scripts/deploy.sh {check|build|deploy}
#   check  预检：工具链、.env 权限与必填项、Compose 配置、目录权限
#   build  构建全部镜像（含 server/web 测试阶段）
#   deploy 备份数据库后按 IMAGE_TAG 发布；Flyway 迁移随 server 启动执行
set -euo pipefail
umask 027

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export DEPLOY_ROOT=${DEPLOY_ROOT:-/opt/rsdp}
export ENV_FILE=${ENV_FILE:-$DEPLOY_ROOT/.env}
export IMAGE_TAG=${IMAGE_TAG:-$(git -C "$repo_root" rev-parse --short=12 HEAD)}

compose() {
    docker compose --project-name rsdp --env-file "$ENV_FILE" -f "$1" "${@:2}"
}

check() {
    for tool in docker curl git flock python3 gzip; do
        command -v "$tool" >/dev/null || { echo "缺少命令：$tool" >&2; exit 1; }
    done
    docker info >/dev/null
    docker compose version
    test -r "$ENV_FILE" || { echo "无法读取服务器配置：$ENV_FILE" >&2; exit 1; }
    python3 - "$ENV_FILE" "$repo_root/deploy/compose.deploy.yaml" <<'PY'
import stat, subprocess, sys
from pathlib import Path
p = Path(sys.argv[1])
if stat.S_IMODE(p.stat().st_mode) & 0o007:
    sys.exit('.env 不应允许其他用户访问；请设置为 0640 或 0600。')
values = {}
for line in p.read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        values[key.strip()] = value.strip().strip('\"\'')
required = ('POSTGRES_PASSWORD', 'REDIS_PASSWORD', 'MINIO_ROOT_USER', 'MINIO_ROOT_PASSWORD',
            'DASHSCOPE_API_KEY', 'RSDP_ENCRYPTION_KEY', 'RSDP_JWT_SECRET',
            'RSDP_ORDER_INVITE_SECRET', 'RSDP_ALLOWED_ORIGINS')
for key in required:
    if not values.get(key):
        sys.exit('服务器 .env 缺少 ' + key)
for key in ('RSDP_ENCRYPTION_KEY', 'RSDP_JWT_SECRET', 'RSDP_ORDER_INVITE_SECRET'):
    if len(values[key]) < 32:
        sys.exit(key + ' 至少需要 32 个字符。')
if not values.get('RSDP_INITIAL_ADMIN_PASSWORD'):
    # sys_user 非空（已有管理员）时允许不配置初始密码；库不可达或为空则必须配置
    result = subprocess.run([
        'docker', 'compose', '--project-name', 'rsdp', '--env-file', str(p),
        '-f', sys.argv[2], 'exec', '-T', 'postgres', 'sh', '-c',
        'psql -At -U "$POSTGRES_USER" -d rsdp -c "SELECT count(*) FROM sys_user" 2>/dev/null'
    ], capture_output=True, text=True)
    if result.returncode or not result.stdout.strip().isdigit() or int(result.stdout.strip()) == 0:
        sys.exit('未能核验已有用户；首次部署必须配置 RSDP_INITIAL_ADMIN_PASSWORD。')
print('服务器 .env 必填项及权限检查通过（不输出配置值）。')
PY
    compose "$repo_root/deploy/compose.deploy.yaml" config --quiet
    for dir in release backups; do
        test -w "$DEPLOY_ROOT/$dir" || { echo "无写入权限：$DEPLOY_ROOT/$dir" >&2; exit 1; }
    done
}

case "${1:-check}" in
    check)
        check
        ;;
    build)
        check
        compose "$repo_root/deploy/compose.deploy.yaml" pull --policy missing postgres redis minio
        compose "$repo_root/deploy/compose.deploy.yaml" build
        ;;
    deploy)
        check
        for image in "rsdp-server:$IMAGE_TAG" "rsdp-web:$IMAGE_TAG" "rsdp-cad-parser:$IMAGE_TAG" \
                     "pgvector/pgvector:pg16" "redis:7-alpine" "bitnamilegacy/minio:2025.2.28"; do
            docker image inspect "$image" >/dev/null
        done
        exec 9>"$DEPLOY_ROOT/release/deploy.lock"
        flock -n 9 || { echo '已有部署进行中。' >&2; exit 1; }
        release="$DEPLOY_ROOT/release/$IMAGE_TAG"
        mkdir -p "$release"
        # 仅保存非敏感发布文件，独立于 Jenkins 工作区
        cp "$repo_root/deploy/compose.deploy.yaml" "$release/compose.deploy.yaml"
        mkdir -p "$release/db/migration"
        cp "$repo_root/server/src/main/resources/db/migration/"V*.sql "$release/db/migration/"
        stamp=$(date -u +%Y%m%dT%H%M%SZ)
        # 无 down/-v、无自动回滚；Flyway 结构迁移由 server 启动期执行
        compose "$release/compose.deploy.yaml" up -d --no-build --pull never --wait --wait-timeout 180 postgres redis minio cad-parser
        # 已有数据先备份；首次空库也保留备份
        backup="$DEPLOY_ROOT/backups/$stamp-$IMAGE_TAG.sql.gz"
        compose "$release/compose.deploy.yaml" exec -T postgres sh -c \
            'pg_dump -U "$POSTGRES_USER" -d rsdp' | gzip > "$backup"
        gzip -t "$backup"
        test -s "$backup"
        echo "数据库备份：$backup"
        compose "$release/compose.deploy.yaml" up -d --no-build --pull never --wait --wait-timeout 420
        server_port=$(compose "$release/compose.deploy.yaml" port server 8081)
        web_port=$(compose "$release/compose.deploy.yaml" port web 80)
        curl --fail --silent --output /dev/null "http://$web_port/"
        curl --fail --silent --output /dev/null "http://$server_port/api/v1/public/products"
        # RSDP 未自定义认证入口点，匿名访问受保护接口由 Spring Security 返回 403（office 为 401）
        code=$(curl --silent --output /dev/null --write-out '%{http_code}' "http://$server_port/api/v1/auth/me")
        test "$code" = 401 -o "$code" = 403 || { echo "匿名认证检查失败：HTTP $code" >&2; exit 1; }
        printf '%s\n' "$IMAGE_TAG" > "$DEPLOY_ROOT/release/current-image-tag"
        compose "$release/compose.deploy.yaml" ps
        echo "已部署 $IMAGE_TAG；本机入口 http://$web_port，公网经 HTTPS 反向代理。"
        ;;
    *)
        echo 'Usage: bash scripts/deploy.sh {check|build|deploy}' >&2
        exit 2
        ;;
esac
