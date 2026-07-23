#!/usr/bin/env bash
# 生成 nginx 自签名 SSL 证书（开发/内网部署用）。
#
# 背景：deploy/nginx/nginx.conf 强制 HTTPS 并加载 /etc/nginx/ssl/server.crt|key，
# 证书缺失时 nginx 容器启动即失败（emerg: cannot load certificate）。
# 本脚本生成自签名证书到 deploy/nginx/ssl/，供 docker-compose 挂载使用。
#
# 用法：
#   scripts/gen-dev-cert.sh [域名或IP，默认 localhost]
#
# 生产环境请替换为正式 CA 证书，覆盖 deploy/nginx/ssl/server.crt 与 server.key 即可。

set -euo pipefail

DOMAIN="${1:-localhost}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SSL_DIR="$SCRIPT_DIR/../deploy/nginx/ssl"

mkdir -p "$SSL_DIR"

if [[ -f "$SSL_DIR/server.crt" && -f "$SSL_DIR/server.key" ]]; then
  echo "证书已存在：$SSL_DIR/server.crt"
  echo "如需重新生成，请先删除 server.crt 与 server.key。"
  exit 0
fi

if ! command -v openssl >/dev/null 2>&1; then
  echo "错误：未找到 openssl，请先安装（Windows 可使用 Git Bash 自带的 openssl）。"
  exit 1
fi

# SAN 同时覆盖域名与常见本地地址；IP 入参按 IP SAN 处理
if [[ "$DOMAIN" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  SAN_ENTRIES="DNS.1 = localhost
IP.1 = 127.0.0.1
IP.2 = ${DOMAIN}"
else
  SAN_ENTRIES="DNS.1 = ${DOMAIN}
DNS.2 = localhost
IP.1 = 127.0.0.1"
fi

# 使用临时 openssl 配置文件（避免 -subj 参数在 Git Bash/MSYS 下被路径转换破坏）
TMP_CONF="$(mktemp)"
trap 'rm -f "$TMP_CONF"' EXIT

cat > "$TMP_CONF" <<EOF
[ req ]
default_bits       = 2048
default_md         = sha256
distinguished_name = dn
x509_extensions    = v3_req
prompt             = no

[ dn ]
CN = ${DOMAIN}
O  = RSDP Dev
OU = Self-Signed

[ v3_req ]
basicConstraints = CA:FALSE
keyUsage         = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName   = @alt_names

[ alt_names ]
${SAN_ENTRIES}
EOF

openssl req -x509 -nodes -new -days 825 \
  -keyout "$SSL_DIR/server.key" \
  -out "$SSL_DIR/server.crt" \
  -config "$TMP_CONF"

chmod 600 "$SSL_DIR/server.key"

echo "自签名证书已生成："
echo "  证书: $SSL_DIR/server.crt"
echo "  私钥: $SSL_DIR/server.key"
echo ""
echo "注意：自签名证书浏览器会提示不受信任，仅用于开发/内网环境。"
echo "生产环境请用正式 CA 证书覆盖上述两个文件后重启 nginx：cd deploy && docker compose restart nginx"
