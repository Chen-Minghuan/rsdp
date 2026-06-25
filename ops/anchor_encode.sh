#!/usr/bin/env bash
set -e

# 锚点图批量编码脚本
# 用法：./ops/anchor_encode.sh <图片目录>

DIR=${1:-"./anchors"}
API=${2:-"http://localhost:8081/api/v1/products/entry"}

echo "=== 锚点图批量编码 ==="
echo "目录：$DIR"
echo "API：$API"

for img in "$DIR"/*.{jpg,jpeg,png,webp}; do
  [ -f "$img" ] || continue
  echo "处理：$img"
  # TODO: 调用批量编码接口
  # curl -X POST "$API" -F "image=@$img" ...
done

echo "=== 锚点图编码完成 ==="
