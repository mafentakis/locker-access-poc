FROM eclipse-temurin:21-jdk

RUN apt-get update && apt-get install -y --no-install-recommends openssl && rm -rf /var/lib/apt/lists/*

COPY scripts/gen-certs.sh /scripts/gen-certs.sh
RUN sed -i 's/\r$//' /scripts/gen-certs.sh && chmod +x /scripts/gen-certs.sh

ENV CERT_OUT_DIR=/out

ENTRYPOINT ["/bin/bash", "/scripts/gen-certs.sh"]
