#!/usr/bin/env bash
# gen-certs.sh - Generate self-signed CA + leaf certs for locker-poc (mTLS)
# Preserves existing PEM files unless FORCE=1, but always rebuilds PKCS#12 stores.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${CERT_OUT_DIR:-$SCRIPT_DIR/../certs}"
PASSWORD="changeit"
SERVICES=(mosquitto rest-server rest-client)
FORCE_REGEN="${FORCE:-0}"

mkdir -p "$OUT"
echo "[gen-certs] Generating certificates in $OUT"

if [ "$FORCE_REGEN" = "1" ] || [ ! -f "$OUT/ca.crt" ] || [ ! -f "$OUT/ca.key" ]; then
  rm -f "$OUT/ca.crt" "$OUT/ca.key" "$OUT/ca.srl"
  openssl genrsa -out "$OUT/ca.key" 3072 2>/dev/null
  openssl req -x509 -new -key "$OUT/ca.key" -sha256 -days 3650 \
    -out "$OUT/ca.crt" -subj "/CN=locker-poc-ca"
  echo "[gen-certs] CA created: $OUT/ca.crt"
else
  echo "[gen-certs] Reusing existing CA: $OUT/ca.crt"
fi

for SVC in "${SERVICES[@]}"; do
  KEY="$OUT/$SVC.key"
  CRT="$OUT/$SVC.crt"
  SAN="$OUT/$SVC-san.cnf"
  CSR="$OUT/$SVC.csr"

  if [ "$FORCE_REGEN" != "1" ] && [ -f "$KEY" ] && [ -f "$CRT" ]; then
    echo "[gen-certs] Reusing existing PEM cert set for: $SVC"
    continue
  fi

  echo "[gen-certs] Generating PEM cert for: $SVC"
  rm -f "$KEY" "$CRT" "$CSR" "$SAN"

  openssl genrsa -out "$KEY" 3072 2>/dev/null

  cat > "$SAN" <<EOF
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

  openssl req -new -key "$KEY" -out "$CSR" -config "$SAN"

  openssl x509 -req -in "$CSR" \
    -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" -CAcreateserial \
    -days 825 -sha256 \
    -extfile "$SAN" -extensions v3_req \
    -out "$CRT" 2>/dev/null

  rm -f "$CSR" "$SAN"

  echo "[gen-certs] Generated PEM cert set for: $SVC"
done

for SVC in "${SERVICES[@]}"; do
  KEY="$OUT/$SVC.key"
  CRT="$OUT/$SVC.crt"
  P12="$OUT/$SVC-keystore.p12"

  rm -f "$P12"
  openssl pkcs12 -export \
    -inkey "$KEY" -in "$CRT" -certfile "$OUT/ca.crt" \
    -out "$P12" -password "pass:$PASSWORD" -name "$SVC"
  echo "[gen-certs] Rebuilt keystore: $P12"
done

rm -f "$OUT/truststore.p12"
keytool -importcert -storetype PKCS12 \
  -alias ca -file "$OUT/ca.crt" \
  -keystore "$OUT/truststore.p12" -storepass "$PASSWORD" -noprompt
echo "[gen-certs] Rebuilt truststore: $OUT/truststore.p12"

echo "[gen-certs] Done. Files in $OUT:"
ls -la "$OUT"

chmod 644 "$OUT"/*.key "$OUT"/*.crt "$OUT"/*.p12 2>/dev/null || true
