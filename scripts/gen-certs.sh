#!/usr/bin/env bash
# gen-certs.sh — Generate self-signed CA + leaf certs for locker-poc (mTLS)
# Idempotent: skips if certs/ca.crt exists unless FORCE=1.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${CERT_OUT_DIR:-$SCRIPT_DIR/../certs}"
PASSWORD="changeit"

SERVICES=(mosquitto rest-server rest-client)

if [ -f "$OUT/ca.crt" ] && [ "${FORCE:-0}" != "1" ]; then
  echo "[gen-certs] Certificates already exist in $OUT — skipping (set FORCE=1 to regenerate)."
  exit 0
fi

mkdir -p "$OUT"
echo "[gen-certs] Generating certificates in $OUT"

# ── 1. Root CA ──────────────────────────────────────────────────────────────
openssl genrsa -out "$OUT/ca.key" 3072 2>/dev/null
openssl req -x509 -new -key "$OUT/ca.key" -sha256 -days 3650 \
  -out "$OUT/ca.crt" -subj "/CN=locker-poc-ca"
echo "[gen-certs] CA created: $OUT/ca.crt"

# ── 2. Leaf certs per service ───────────────────────────────────────────────
for SVC in "${SERVICES[@]}"; do
  echo "[gen-certs] Generating cert for: $SVC"

  # Key
  openssl genrsa -out "$OUT/$SVC.key" 3072 2>/dev/null

  # SAN config
  cat > "$OUT/$SVC-san.cnf" <<EOF
[req]
distinguished_name = req_dn
req_extensions     = v3_req
prompt             = no

[req_dn]
CN = $SVC

[v3_req]
subjectAltName = @alt_names
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth, clientAuth

[alt_names]
DNS.1 = $SVC
DNS.2 = localhost
IP.1  = 127.0.0.1
EOF

  # CSR
  openssl req -new -key "$OUT/$SVC.key" -out "$OUT/$SVC.csr" \
    -config "$OUT/$SVC-san.cnf"

  # Sign with CA
  openssl x509 -req -in "$OUT/$SVC.csr" \
    -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" -CAcreateserial \
    -days 825 -sha256 \
    -extfile "$OUT/$SVC-san.cnf" -extensions v3_req \
    -out "$OUT/$SVC.crt" 2>/dev/null

  # PKCS#12 keystore (for Java)
  openssl pkcs12 -export \
    -inkey "$OUT/$SVC.key" -in "$OUT/$SVC.crt" -certfile "$OUT/ca.crt" \
    -out "$OUT/$SVC-keystore.p12" -password "pass:$PASSWORD" -name "$SVC"

  # Cleanup CSR + SAN config
  rm -f "$OUT/$SVC.csr" "$OUT/$SVC-san.cnf"
done

# ── 3. Truststore (CA only) ────────────────────────────────────────────────
rm -f "$OUT/truststore.p12"
keytool -importcert -storetype PKCS12 \
  -alias ca -file "$OUT/ca.crt" \
  -keystore "$OUT/truststore.p12" -storepass "$PASSWORD" -noprompt

echo "[gen-certs] Done. Files in $OUT:"
ls -la "$OUT"

# Make all files readable (Mosquitto runs as non-root)
chmod 644 "$OUT"/*.key "$OUT"/*.crt "$OUT"/*.p12 2>/dev/null || true

