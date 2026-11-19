#!/usr/bin/env bash
set -euo pipefail

# Stage 1 Node Initialization Script for sing-box (VLESS+Reality + Hysteria 2)
mkdir -p ./sing-box ./certs ./logs

echo "=== 1. Generating UUID ==="
UUID=$(docker run --rm ghcr.io/sagernet/sing-box:latest generate uuid)
echo "UUID: $UUID"

echo "=== 2. Generating Reality Keypair ==="
REALITY_KEYS=$(docker run --rm ghcr.io/sagernet/sing-box:latest generate reality-keypair)
echo "$REALITY_KEYS"
PRIVATE_KEY=$(echo "$REALITY_KEYS" | awk '/PrivateKey:/ {print $2}')
PUBLIC_KEY=$(echo "$REALITY_KEYS" | awk '/PublicKey:/ {print $2}')

echo "=== 3. Generating ShortID (8 bytes hex) ==="
SHORT_ID=$(openssl rand -hex 8)
echo "ShortID: $SHORT_ID"

echo "=== 4. Generating Hysteria2 Passwords ==="
HY2_PASS=$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 24)
OBFS_PASS=$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 24)
echo "HY2 User Password: $HY2_PASS"
echo "Salamander Obfs Password: $OBFS_PASS"

echo "=== 5. Generating Self-Signed TLS Certificate for Hysteria 2 ==="
openssl req -x509 -nodes -newkey ec:<(openssl ecparam -name prime256v1) \
  -keyout ./certs/hy2.key \
  -out ./certs/hy2.crt \
  -subj "/CN=bing.com" \
  -days 3650
chmod 600 ./certs/hy2.key

CERT_SHA256=$(openssl x509 -in ./certs/hy2.crt -noout -fingerprint -sha256 | cut -d'=' -f2 | tr -d ':' | tr '[:upper:]' '[:lower:]')

echo "=== 6. Applying credentials to ./sing-box/config.json ==="
sed -i "s|REPLACE_WITH_UUID|$UUID|g" ./sing-box/config.json
sed -i "s|REPLACE_WITH_REALITY_PRIVATE_KEY|$PRIVATE_KEY|g" ./sing-box/config.json
sed -i "s|REPLACE_WITH_SHORT_ID|$SHORT_ID|g" ./sing-box/config.json
sed -i "s|REPLACE_WITH_HY2_USER_PASSWORD|$HY2_PASS|g" ./sing-box/config.json
sed -i "s|REPLACE_WITH_SALAMANDER_OBFS_PASSWORD|$OBFS_PASS|g" ./sing-box/config.json

echo "=== 7. Validating config.json ==="
docker run --rm -v "$(pwd)/sing-box:/etc/sing-box:ro" -v "$(pwd)/certs:/etc/sing-box/certs:ro" \
  ghcr.io/sagernet/sing-box:latest check -C /etc/sing-box

echo ""
echo "=== DONE! Save these client values ==="
echo "PUBLIC_KEY=$PUBLIC_KEY"
echo "UUID=$UUID"
echo "SHORT_ID=$SHORT_ID"
echo "HY2_PASS=$HY2_PASS"
echo "OBFS_PASS=$OBFS_PASS"
echo "HY2_CERT_SHA256=$CERT_SHA256"
