#!/usr/bin/env bash
# RSDP 本地演示数据一键导入脚本
# 从本地测试案例图目录复制样例图片，并写入数据库开发/演示种子数据（database/seed_dev_data.sql）
#
# 用法：
#   scripts/seed-demo-data.sh
#   DEMO_IMAGE_SOURCE=/path/to/images scripts/seed-demo-data.sh

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/deploy/.env"

if [ ! -f "$ENV_FILE" ]; then
    echo "错误：找不到环境变量文件 $ENV_FILE"
    echo "请先复制 deploy/.env.example 为 deploy/.env 并填写密钥"
    exit 1
fi

# 读取 .env 文件中的变量（兼容 Windows CRLF）
set -a
# shellcheck source=/dev/null
source <(sed 's/\r$//' "$ENV_FILE")
set +a

# 图片源目录必须通过 DEMO_IMAGE_SOURCE 环境变量指定（无默认值，避免他机硬编码路径）
if [ -z "${DEMO_IMAGE_SOURCE:-}" ]; then
    echo "错误：未设置 DEMO_IMAGE_SOURCE 环境变量"
    echo "用法：DEMO_IMAGE_SOURCE=/path/to/测试案例图/案例图 scripts/seed-demo-data.sh"
    echo "说明：图片源目录为本机测试案例图目录，需包含 中古风/奶油风/侘寂风 等风格子文件夹"
    exit 1
fi
SOURCE_DIR="$DEMO_IMAGE_SOURCE"
DEST_DIR="$PROJECT_ROOT/server/data/uploads/images"
SQL_FILE="$PROJECT_ROOT/database/seed_dev_data.sql"

if [ ! -d "$SOURCE_DIR" ]; then
    echo "错误：找不到图片源目录 $SOURCE_DIR"
    echo "可通过 DEMO_IMAGE_SOURCE 环境变量指定其他路径"
    exit 1
fi

mkdir -p "$DEST_DIR"

# 风格编码 -> 图片源子目录名（兼容 macOS 自带 bash 3.2：不使用 declare -A 关联数组）
STYLE_PAIRS="MC:中古风
WJ:侘寂风
CR:奶油风
MD:孟菲斯·多巴胺
IO:工业风LOFT
IL:意式极简轻奢
ZS:新中式·宋式
FN:法式复古南洋
HH:混搭风
MB:现代极简·包豪斯"

copy_first_image() {
    local src_folder="$1"
    local dest_name="$2"
    local src_file

    src_file=$(find "$src_folder" -maxdepth 1 -type f \( \
        -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.webp' \) | sort | head -1)

    if [ -z "$src_file" ]; then
        echo "警告：$src_folder 下未找到图片文件"
        return 1
    fi

    local ext
    ext="${src_file##*.}"
    ext=$(echo "$ext" | tr '[:upper:]' '[:lower:]')
    # 演示脚本统一按 png 处理（源文件均为 PNG），如为其他格式则保留原扩展名
    cp -f "$src_file" "$DEST_DIR/${dest_name}.${ext}"
    echo "已复制：$src_file -> $DEST_DIR/${dest_name}.${ext}"
}

echo "=========================================="
echo " RSDP 本地演示数据导入"
echo "=========================================="
echo "图片源目录: $SOURCE_DIR"
echo "图片目标目录: $DEST_DIR"
echo ""

echo "[1/3] 复制风格样例图片..."
echo "$STYLE_PAIRS" | while IFS=: read -r code folder; do
    [ -z "$code" ] && continue
    # bash 3.2 不支持 ${code,,}，用 tr 转小写
    dest_code=$(echo "$code" | tr '[:upper:]' '[:lower:]')
    copy_first_image "$SOURCE_DIR/$folder" "demo-${dest_code}-001" || true
done

echo ""
echo "[2/3] 复制工厂展示图片..."
copy_first_image "$SOURCE_DIR/中古风" "demo-test-01" || true
copy_first_image "$SOURCE_DIR/奶油风" "demo-test-02" || true

echo ""
echo "[3/3] 写入数据库开发/演示种子数据（database/seed_dev_data.sql）..."
# 优先使用 Docker 内 psql，避免依赖本地 PostgreSQL 客户端
if docker ps --format '{{.Names}}' | grep -qx 'rsdp-postgres'; then
    docker exec -i rsdp-postgres psql -U "${POSTGRES_USER:-rsdp}" -d rsdp -f - < "$SQL_FILE"
else
    echo "警告：未检测到 rsdp-postgres 容器，尝试使用本地 psql..."
    export PGPASSWORD="${POSTGRES_PASSWORD:-rsdp}"
    psql -h localhost -p 5432 -U "${POSTGRES_USER:-rsdp}" -d rsdp -f "$SQL_FILE"
fi

echo ""
echo "=========================================="
echo " 演示数据导入完成"
echo "=========================================="
echo "新增/补充："
echo "  - 开发测试账号：admin/editor/designer/factory/user（弱口令 rsdp-dev-2026!，仅开发用）"
echo "  - 工厂：F001、F002，并补全 TEST 工厂资料"
echo "  - RSPU：10 条（覆盖 10 种风格）"
echo "  - 变体：10 条、图片：10 张、RSKU：12 条"
echo ""
echo "提示："
echo "  1. 后端正在运行时会自动读取 server/data/uploads/images/ 下的新图片"
echo "  2. 刷新前端页面即可在「产品库」「工厂管理」看到演示数据"
