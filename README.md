# locker-poc

Minimal **Java 21** Proof-of-Concept demonstrating secure, framework-free communication between a REST client, a REST server, and an MQTT publish/subscribe pair — all running as Docker containers orchestrated via Docker Compose, secured with self-signed mTLS certificates.

## Goals

- Show an end-to-end, secure flow:
  **REST Client → (HTTPS/mTLS) → REST Server → (MQTT/TLS) → MQTT Broker → MQTT Subscriber**
- Implement **only the `openCompartment` operation** from `DeliveryMachineBusinessFunction.Open-API-en.yaml`.
- Emit **only the `CompartmentOpenedEventMsg`** from `DeliveryMachineBusinessEvent.Open-API-en.yaml`.
- Use **pure JDK 21** (`HttpsServer`, `HttpClient`, `SSLContext`) plus the smallest possible set of libraries: **Eclipse Paho MQTTv5** + **Jackson**.
- **No application frameworks** (no Spring, no Quarkus, no Micronaut, no Jakarta EE).
- Everything buildable and runnable with `docker compose up` on Unix.

## Non-goals

- Not production-grade. Self-signed certs, hard-coded IDs, dummy business logic.
- No persistence, no auth beyond mTLS, no real locker hardware integration.
- No full OpenAPI surface — exactly **one** REST operation and **one** MQTT event.

---

## Stack (locked choices)

| Concern            | Choice                                                     |
| ------------------ | ---------------------------------------------------------- |
| Language / runtime | Java 21 (Temurin JRE in runtime images)                    |
| Build              | Maven multi-module                                         |
| HTTPS server       | JDK `com.sun.net.httpserver.HttpsServer`                   |
| HTTPS client       | JDK `java.net.http.HttpClient`                             |
| MQTT client        | Eclipse Paho **MQTTv5** (`org.eclipse.paho.mqttv5.client`) |
| MQTT broker        | Eclipse Mosquitto 2.x                                      |
| JSON               | Jackson `jackson-databind`                                 |
| Logging            | `java.util.logging` (JUL) + `logging.properties`           |
| TLS trust model    | **mTLS everywhere**, one self-signed CA signs all certs    |
| Cert generation    | Docker Compose `cert-init` runs `scripts/gen-certs.sh`     |
| Orchestration      | Docker Compose (Unix)                                      |

---

## Repository layout

```text
locker-poc/
├── common/             # shared DTOs and TLS helpers
├── integration-test/   # JUnit 5 end-to-end tests around docker compose
├── mosquitto/          # TLS-enabled Mosquitto image and config
├── mqtt-publisher/     # standalone MQTT test publisher
├── mqtt-subscriber/    # MQTT event subscriber
├── parent-pom/         # shared Maven dependency and plugin versions
├── rest-client/        # one-shot HTTPS client for openCompartment
├── rest-server/        # HTTPS server and MQTT event emitter
├── scripts/            # certificate generation helpers
├── docker-compose.yml
├── pom.xml             # aggregator
├── README.md
└── SPEC.md
```

Generated certificates are written by the `cert-init` container to `certs/`, which is git-ignored. The `mqtt-publisher` container reuses the `rest-server` certificate identity, and the `mqtt-subscriber` container reuses the `rest-client` certificate identity for MQTT client authentication.

---

## Quick start

```bash
# 1. Build all Java modules and Docker images
docker compose build

# 2. Start the stack. cert-init generates certificates inside Docker first.
docker compose up

# 3. Observe logs
docker compose logs -f rest-server mqtt-subscriber

# 4. Run the end-to-end integration test (spins the stack up/down itself)
mvn -pl integration-test -am verify
```

### Healthchecks

Every long-running container (`mosquitto`, `rest-server`, `mqtt-subscriber`) exposes a Docker `HEALTHCHECK` and every one-shot container (`rest-client`, `mqtt-publisher`) reports completion via a sentinel file. See **SPEC §6.5** for the exact commands. `depends_on` uses `condition: service_healthy` / `service_completed_successfully` so the compose graph is race-free.

### Integration test

Module `integration-test/` (JUnit 5, test scope only — no framework in production code) boots the full stack via `docker compose`, waits for all healthchecks, runs black-box checks over HTTPS + MQTT using the generated certificates, then tears the stack down. See **SPEC §10**.

---

## DNS workaround POC (`dns-workaround` branch)

Proof of concept demonstrating that TLS hostname validation succeeds when a
DNS name present in the server certificate's `subjectAltName` is resolved
locally via `/etc/hosts`, instead of via real DNS.

- `scripts/gen-certs.sh` adds a third SAN entry to the `rest-server`
  certificate on each (re)generation: `DNS.3 = <random-uuid>.fusion.dhl.com`.
  The UUID is generated fresh per run (`/proc/sys/kernel/random/uuid`) and
  written to `certs/rest-server-dns-alt.txt`.
- The `rest-server` container is only reachable inside the `locker-net`
  Docker bridge network under its Compose service name (`rest-server`),
  resolved by the embedded Docker DNS server (`127.0.0.11:53`). Docker DNS has
  no entry for `...fusion.dhl.com` — it only knows service names declared in
  `docker-compose.yml`.
- `docker-entrypoint.sh` (used by every service built from
  `Dockerfile.java-service`) reads `certs/rest-server-dns-alt.txt`, if
  present, and starts the JVM with
  `-Drest.server.dns.alt.name=<uuid>.fusion.dhl.com`. The integration test
  never touches the certs directory directly — it only reads the system
  property.
- Before making its HTTPS call, the integration test resolves the real,
  dynamically-assigned IP of `rest-server` (`InetAddress.getByName`) and
  appends it to `/etc/hosts` in the `integration-test` container under the
  alias from the system property. `/etc/hosts` lookups take priority over
  Docker DNS, so the client resolves the alias to `rest-server`'s current IP
  without a real DNS record. The entry is removed again after the test.
- This proves that hostname verification against the SAN succeeds purely
  because the name is present in `/etc/hosts` — no real DNS record for
  `fusion.dhl.com` is required for the POC to pass.

---

## Security disclaimer

This repository is a **proof of concept**. Certificates are self-signed, private keys are stored in a local volume, IDs are hard-coded, and there is no secret management. **Do not reuse any of this in production.**
