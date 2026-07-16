#!/bin/sh
# docker-entrypoint.sh - launches app.jar, injecting extra -D system
# properties discovered from the mounted certs directory.
#
# rest-server-dns-alt.txt (written by scripts/gen-certs.sh) holds the random
# DNS-workaround SAN name for rest-server's certificate. If present, it is
# passed to the JVM as -Drest.server.dns.alt.name=<value>; modules that don't
# use the property simply ignore it.
set -eu

CERTS_DIR="${CERTS_DIR:-/certs}"
DNS_ALT_FILE="$CERTS_DIR/rest-server-dns-alt.txt"

JAVA_OPTS=""
if [ -f "$DNS_ALT_FILE" ]; then
  DNS_ALT_NAME="$(cat "$DNS_ALT_FILE")"
  JAVA_OPTS="-Drest.server.dns.alt.name=$DNS_ALT_NAME"
fi

exec java $JAVA_OPTS -jar /app/app.jar
