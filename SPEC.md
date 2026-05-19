
# Locker PoC — Technical Specification

**Version:** 0.1 (spec only, no implementation)
**Date:** 2026-04-27
**Owner:** locker-poc
**Status:** Draft, locked for implementation

---

## 1. Purpose & scope

Define, at implementation-ready precision, a minimal Java 21 PoC that proves secure communication between:

- a **REST client** calling the single OpenAPI operation `openCompartment`,
- a **REST server** exposing that operation and emitting the resulting business event,
- a **Mosquitto MQTT broker** as transport for events,
- an **MQTT subscriber** that consumes `CompartmentOpenedEventMsg`,
- a **standalone MQTT publisher** for isolated broker/TLS testing.

All communication is encrypted and mutually authenticated using self-signed X.509 certificates (mTLS). All components run as Docker containers, orchestrated by Docker Compose on Unix.

### 1.1 In scope

- One REST operation: `POST /api/{version}/locker/{lockerId}/compartment/{compartmentId}/open`.
- One MQTT event: `CompartmentOpenedEventMsg` on topic `psfusion/business-event/v010/compartment/opened`.
- mTLS for both HTTPS and MQTT.
- Docker-based build and runtime.
- Structured, timestamped logging to STDOUT for every container.

### 1.2 Out of scope

- Any other OpenAPI operation or event type.
- Real locker hardware, persistence, authorization beyond mTLS, rate limiting, retries, dead-letter queues.
- Production PKI, secret management, horizontal scaling, observability (metrics/tracing).
- Kubernetes, Helm, cloud deployment.

### 1.3 Non-functional constraints

- **Language:** Java 21 only.
- **No application frameworks:** no Spring, Quarkus, Micronaut, Jakarta EE, Vert.x, Helidon.
- **Minimal libraries:** only `eclipse.paho.mqttv5.client`, `jackson-databind`, and transitive deps. Everything else uses JDK stdlib.
- **OS target:** Linux containers (tested on Docker Desktop + Linux hosts).

---

## 2. Architecture

### 2.1 Component overview

| # | Container name     | Role                                     | Exposes (in Docker network)     | External port |
| - | ------------------ | ---------------------------------------- | ------------------------------- | ------------- |
| 1 | `cert-init`        | Generates CA + leaf certs on first run   | writes to `certs` volume        | —             |
| 2 | `mosquitto`        | MQTT broker (TLS, mTLS)                  | `mosquitto:8883`                | `8883/tcp`    |
| 3 | `rest-server`      | HTTPS server + MQTT publisher (business event emitter) | `rest-server:8443` | `8443/tcp`    |
| 4 | `rest-client`      | Fires a single `openCompartment` request on start; exits 0 | —                 | —             |
| 5 | `mqtt-subscriber`  | Subscribes to opened topic, logs events  | —                               | —             |
| 6 | `mqtt-publisher`   | Standalone: publishes one test event, exits 0 | —                          | —             |

All containers join a single user-defined bridge network: **`locker-net`**.

### 2.2 Startup order (Docker Compose `depends_on`)

```
cert-init  →  mosquitto
cert-init  →  rest-server  →  (healthcheck) →  rest-client
cert-init  →  mosquitto    →  mqtt-subscriber
cert-init  →  mosquitto    →  mqtt-publisher
```

- `cert-init` uses `restart: "no"` and is expected to exit 0.
- `rest-client` and `mqtt-publisher` are one-shot containers (`restart: "no"`).
- `mosquitto`, `rest-server`, `mqtt-subscriber` are long-running (`restart: unless-stopped`).

### 2.3 End-to-end sequence

See `README.md` sequence diagram. Authoritative steps:

1. `cert-init` creates CA, leaf certs (RSA-3072), PKCS#12 keystores, and a single truststore containing only the CA.
2. `mosquitto` starts with TLS on **8883**, `require_certificate true`, `use_identity_as_username true`.
3. `rest-server` boots `HttpsServer` on **0.0.0.0:8443**, `SSLContext` configured with own keystore + CA truststore, `needClientAuth=true`. It also connects to `mosquitto:8883` as MQTT client and subscribes only for publish.
4. `mqtt-subscriber` connects to `mosquitto:8883` with mTLS, subscribes to `psfusion/business-event/v010/compartment/opened` at QoS 1.
5. `rest-client` sends `POST https://rest-server:8443/api/v010/locker/{lockerId}/compartment/{compartmentId}/open` with required headers.
6. `rest-server` handler validates, logs, responds **200 OK**, then publishes `CompartmentOpenedEventMsg` on the topic at QoS 1.
7. `mqtt-subscriber` receives, deserializes, logs the event.
8. Optionally: `mqtt-publisher` independently publishes a synthetic `CompartmentOpenedEventMsg` once to prove the broker TLS path is valid without the REST path.

---

## 3. Message contracts

All schema references are pinned to the two YAML files in the repo root.

### 3.1 REST: `openCompartment`

**Source:** `DeliveryMachineBusinessFunction.Open-API-en.yaml`, `operationId: openCompartment` (line ~985).

- **Method + path**
  `POST /api/{version}/locker/{lockerId}/compartment/{compartmentId}/open`
- **Path params**
  - `version` — regex `^v\d{3}$`, hard-coded to `v010` in the PoC.
  - `lockerId` — UUID (`LockerIdTo`). Example: `f47ac10b-58cc-4372-a567-0e02b2c3d479`.
  - `compartmentId` — regex `^compartmentInfo@[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`. Example: `compartmentInfo@d4e5f6a7-b8c9-0123-defa-234567890123`.
- **Required headers**
  - `Client-Version` — regex `^\d+\.\d+\.\d+$`, PoC value `1.2.6`.
  - `Lock-Token` — Base64 string; PoC value `gy12p6N1UC4WJp4H1tFhVqtlzgm7qYp4qVnq5p/PLu4=` (not validated in PoC, just logged).
  - `Idempotency-Key` — UUID; PoC value generated per request.
- **Request body:** none.
- **Success response:** `200 OK`, empty body.
- **Error responses implemented in PoC:**
  - `400` — missing/invalid path param → JSON `ErrorResponseTo { errorCode, message, timestamp }`.
  - `500` — unexpected exception → same shape.
  - (`422`, `501` defined in YAML — **not** implemented in PoC, documented as gap.)

### 3.2 MQTT: `CompartmentOpenedEventMsg`

**Source:** `DeliveryMachineBusinessEvent.Open-API-en.yaml`, message at line ~337, payload schema at line ~695, topic at line ~131.

- **Topic:** `psfusion/business-event/v010/compartment/opened`
- **QoS:** **1** (at-least-once). Retained flag: **false**.
- **Content-Type:** `application/json; charset=utf-8`.
- **Transport:** the PoC serializes **headers + payload as one JSON envelope**, because MQTT v3 has no user properties — MQTT v5 user properties are also populated for convenience.

**Wire JSON (PoC envelope):**

```json
{
  "headers": {
    "apiVersion": "1.0.1",
    "lockerId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "eventType": "CompartmentOpenedEvent",
    "eventCreateUtcDateTime": "2026-04-24T10:30:01Z"
  },
  "payload": {
    "compartmentId": "compartmentInfo@d4e5f6a7-b8c9-0123-defa-234567890123",
    "compartmentOpenUtcDateTime": "2026-04-24T10:30:00Z"
  }
}
```

MQTT v5 user properties (duplicated from headers): `apiVersion`, `lockerId`, `eventType`, `eventCreateUtcDateTime`.

### 3.3 Java DTOs (module `common`)

- `EventHeaders(String apiVersion, UUID lockerId, String eventType, Instant eventCreateUtcDateTime)` — `record`.
- `CompartmentOpenedEventMsg(String compartmentId, Instant compartmentOpenUtcDateTime)` — `record`.
- `EventEnvelope<T>(EventHeaders headers, T payload)` — generic `record`.
- `ErrorResponseTo(String errorCode, String message, Instant timestamp)` — `record`.

Jackson is configured with `JavaTimeModule`, `WRITE_DATES_AS_TIMESTAMPS=false`, `FAIL_ON_UNKNOWN_PROPERTIES=false`.

---

## 4. Security / TLS design

### 4.1 Trust model

- One **self-signed Root CA** (`ca.crt` / `ca.key`) is generated once.
- Every service gets **one leaf certificate**, signed by the CA, with its **DNS SAN equal to its Docker Compose service name** (`mosquitto`, `rest-server`, etc.).
- Every service trusts **only the CA** via a Java truststore containing just `ca.crt`.
- **mTLS is enforced everywhere**: broker requires client certs; HTTPS server requires client certs.

### 4.2 Certificate parameters

| Field            | Value                                                      |
| ---------------- | ---------------------------------------------------------- |
| Key type         | RSA 3072-bit                                               |
| Signature        | SHA-256                                                    |
| CA validity      | 3650 days                                                  |
| Leaf validity    | 825 days                                                   |
| Leaf `CN`        | service name (e.g. `rest-server`)                          |
| Leaf `SAN DNS`   | service name, `localhost`                                  |
| Leaf `SAN IP`    | `127.0.0.1` (for local testing outside Docker)             |
| Leaf key usage   | `digitalSignature, keyEncipherment`                        |
| Leaf EKU         | `serverAuth, clientAuth` (each cert can be both)           |

### 4.3 Key/truststore formats

- **PKCS#12** for all Java services: `{service}-keystore.p12` (password `changeit`, PoC only).
- `truststore.p12` contains only the CA.
- Mosquitto uses **PEM** directly: `ca.crt`, `broker.crt`, `broker.key`.

### 4.4 Cert generation (`scripts/gen-certs.sh`)

Idempotent: if `certs/ca.crt` exists, script exits 0 without regen (override with `FORCE=1`).

Steps:

1. `openssl genrsa -out ca.key 3072`
2. `openssl req -x509 -new -key ca.key -sha256 -days 3650 -out ca.crt -subj "/CN=locker-poc-ca"`
3. For each service `S` in `{broker, rest-server, rest-client}`:
   - `openssl genrsa -out S.key 3072`
   - Build CSR + SAN config file on the fly.
   - `openssl x509 -req ... -CA ca.crt -CAkey ca.key -days 825 -extfile san.cnf`
   - `openssl pkcs12 -export -inkey S.key -in S.crt -certfile ca.crt -out S-keystore.p12 -password pass:changeit -name S`
4. Build `truststore.p12` from `ca.crt` via `keytool -importcert -storetype PKCS12 -alias ca -file ca.crt -keystore truststore.p12 -storepass changeit -noprompt`.

`cert-init` runs this script inside a tiny `alpine/openjdk` image (needs both `openssl` and `keytool`).

### 4.5 TLS in Java (`common/TlsContextFactory`)

A single helper builds `SSLContext` from two env vars:

- `TLS_KEYSTORE_PATH`, `TLS_KEYSTORE_PASSWORD`
- `TLS_TRUSTSTORE_PATH`, `TLS_TRUSTSTORE_PASSWORD`

It loads PKCS#12, initializes `KeyManagerFactory` + `TrustManagerFactory`, returns an `SSLContext` of protocol **TLSv1.3**. Hostname verification uses the default `HttpsURLConnection.getDefaultHostnameVerifier()` for the HTTPS client; server-side enforces `SSLParameters.setNeedClientAuth(true)`.

### 4.6 Mosquitto TLS config (`mosquitto/mosquitto.conf`)

```conf
listener 8883 0.0.0.0
protocol mqtt

cafile      /mosquitto/certs/ca.crt
certfile    /mosquitto/certs/broker.crt
keyfile     /mosquitto/certs/broker.key
tls_version tlsv1.3

require_certificate      true
use_identity_as_username true
allow_anonymous          false

log_dest stdout
log_type error
log_type warning
log_type notice
log_type information
```

ACL is **not** configured in the PoC; `use_identity_as_username true` is documented as the extension point for ACLs.

---

## 5. Container / port matrix

| Container        | Image base                 | Ports (host → container) | Volumes                                   | Key env vars                                                        |
| ---------------- | -------------------------- | ------------------------ | ----------------------------------------- | ------------------------------------------------------------------- |
| `cert-init`      | `eclipse-temurin:21-jdk-alpine` (+ `openssl`) | —                        | `./certs:/out`, `./scripts:/scripts:ro`   | `FORCE`                                                             |
| `mosquitto`      | `eclipse-mosquitto:2`      | `8883:8883`              | `./certs:/mosquitto/certs:ro`, `./mosquitto/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro` | —                                                                   |
| `rest-server`    | `eclipse-temurin:21-jre`   | `8443:8443`              | `./certs:/certs:ro`                       | `TLS_*`, `MQTT_BROKER_URI=ssl://mosquitto:8883`, `HTTPS_BIND=0.0.0.0:8443`, `LOCKER_ID`, `LOG_LEVEL` |
| `rest-client`    | `eclipse-temurin:21-jre`   | —                        | `./certs:/certs:ro`                       | `TLS_*`, `SERVER_URL=https://rest-server:8443`, `LOCKER_ID`, `COMPARTMENT_ID`, `LOG_LEVEL` |
| `mqtt-publisher` | `eclipse-temurin:21-jre`   | —                        | `./certs:/certs:ro`                       | `TLS_*` using `rest-client-keystore.p12`, `MQTT_BROKER_URI=ssl://mosquitto:8883`, `LOG_LEVEL`        |
| `mqtt-subscriber`| `eclipse-temurin:21-jre`   | —                        | `./certs:/certs:ro`                       | `TLS_*` using `rest-client-keystore.p12`, `MQTT_BROKER_URI=ssl://mosquitto:8883`, `LOG_LEVEL`        |

`TLS_*` expands to `TLS_KEYSTORE_PATH`, `TLS_KEYSTORE_PASSWORD`, `TLS_TRUSTSTORE_PATH`, `TLS_TRUSTSTORE_PASSWORD`. Each service points to its own `{service}-keystore.p12`.

---

## 6. Build

### 6.1 Maven layout

- Parent aggregator `pom.xml` at repo root, packaging `pom`, modules:
  - `common`
  - `rest-server`
  - `rest-client`
  - `mqtt-publisher`
  - `mqtt-subscriber`
  - `integration-test`
- Java 21 (`maven.compiler.release=21`).
- Each executable module uses `maven-shade-plugin` to produce a single runnable JAR `app.jar` with `Main-Class` set.

### 6.2 Dependencies (pinned in `dependencyManagement`)

| GroupId / artifactId                              | Version    | Used by                                |
| ------------------------------------------------- | ---------- | -------------------------------------- |
| `org.eclipse.paho:org.eclipse.paho.mqttv5.client` | `1.2.5`    | rest-server, mqtt-publisher, mqtt-subscriber |
| `com.fasterxml.jackson.core:jackson-databind`     | `2.17.2`   | all executable modules + common        |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | `2.17.2` | all executable modules + common        |

No other runtime dependencies. No SLF4J (JUL is the single logger).

### 6.3 Dockerfiles

- `cert-init` keeps its own Dockerfile because it has a separate certificate-generation purpose.
- All Java service images use the shared root-level `Dockerfile.java-service`.
- The shared Dockerfile is parameterized by build args:
  - `MODULE` - required; one of `rest-server`, `rest-client`, `mqtt-publisher`, `mqtt-subscriber`, `integration-test`.
  - `INSTALL_CURL` - optional, default `false`; set to `true` only for `rest-server` so its Docker healthcheck can call `/health`.
- **Multi-stage**:
  - stage 1 `maven:3.9-eclipse-temurin-21` copies `common/`, installs it with `mvn -f common/pom.xml install -DskipTests -q`, then copies `${MODULE}/` and runs `mvn -f ${MODULE}/pom.xml package -DskipTests -q`.
  - stage 2 `eclipse-temurin:21-jre` conditionally installs `curl` when `INSTALL_CURL=true`, copies `/src/${MODULE}/target/app.jar` to `/app/app.jar`, and sets `ENTRYPOINT ["java","-jar","/app/app.jar"]`.
- Build context is the repo root so `common` and the selected module are visible.

### 6.4 `docker-compose.yml`

- Defines the 6 services from §5.
- One named network `locker-net`.
- One bind mount `./certs` shared across cert-init + all services.
- Java services build with `dockerfile: Dockerfile.java-service` and a service-specific `MODULE` build arg. `rest-server` also passes `INSTALL_CURL=true`.
- **Every container has a healthcheck** — see §6.5.
- `depends_on` uses `condition: service_healthy` (not just `service_started`) wherever possible, so e.g. `rest-client` waits until `rest-server` is actually serving TLS.

### 6.5 Healthchecks (mandatory for every container)

Each Docker Compose service defines a `healthcheck` block. All use `interval: 5s`, `timeout: 3s`, `retries: 10`, `start_period: 5s` unless noted.

| Service           | Check strategy                                                                                                                                   | Notes                                                                                     |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------- |
| `cert-init`       | No `healthcheck` block; `exit 0` is the "health" signal. Downstream services use `depends_on: { cert-init: { condition: service_completed_successfully } }`. | One-shot container — Compose treats successful exit as completion.                         |
| `mosquitto`       | `CMD-SHELL mosquitto_sub -h localhost -p 8883 --cafile /mosquitto/certs/ca.crt --cert /mosquitto/certs/broker.crt --key /mosquitto/certs/broker.key -t '$$SYS/broker/uptime' -C 1 -W 2` | Uses the built-in `$SYS` topic; succeeds only when TLS + client auth work end-to-end.      |
| `rest-server`     | `CMD-SHELL curl -fsS --cacert /certs/ca.crt --cert /certs/rest-client.crt --key /certs/rest-client.key https://localhost:8443/health`            | Server exposes **`GET /health`** returning `200 {"status":"UP"}` (mTLS required, see §7.1).|
| `rest-client`     | `CMD test -f /tmp/rest-client.done`                                                                                                              | One-shot: main writes `/tmp/rest-client.done` after successful `openCompartment` call. Healthcheck reports healthy just before `System.exit(0)`, which lets the integration test assert completion. `start_period: 2s`, `retries: 30`. |
| `mqtt-publisher`  | `CMD test -f /tmp/mqtt-publisher.done`                                                                                                           | Same pattern as `rest-client`.                                                             |
| `mqtt-subscriber` | `CMD test -f /tmp/mqtt-subscriber.ready`                                                                                                         | Long-running: subscriber writes `/tmp/mqtt-subscriber.ready` immediately after MQTT `SUBSCRIBE` ACK, so downstream publishers know it is listening. |

**Java-side contract:** `common` provides `HealthFile` helpers:

- `HealthFile.markReady(String name)` — writes `/tmp/<name>.ready`.
- `HealthFile.markDone(String name)` — writes `/tmp/<name>.done`.

Each executable calls the appropriate helper at the documented lifecycle point.

---

## 7. Runtime configuration

All services read config from environment variables only. No config files baked into images except certs and `mosquitto.conf`.

### 7.1 REST server endpoints

The `rest-server` container exposes exactly two HTTPS endpoints (both mTLS-protected):

| Method | Path                                                                                            | Purpose                                                                |
| ------ | ----------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| `POST` | `/api/{version}/locker/{lockerId}/compartment/{compartmentId}/open`                             | `openCompartment` — the one business operation (§3.1).                 |
| `GET`  | `/health`                                                                                       | Liveness probe for the Docker healthcheck and integration test. Returns `200 {"status":"UP"}` (content-type `application/json`). Still requires a valid client certificate — there is **no** anonymous surface. |

### 7.2 Environment variables

Common env:

- `LOG_LEVEL` — one of `FINE|INFO|WARNING|SEVERE`, default `INFO`.
- `TLS_KEYSTORE_PATH`, `TLS_KEYSTORE_PASSWORD`, `TLS_TRUSTSTORE_PATH`, `TLS_TRUSTSTORE_PASSWORD` — as §4.5.

Service-specific:

- `rest-server`: `HTTPS_BIND`, `MQTT_BROKER_URI`, `MQTT_CLIENT_ID=rest-server`, `MQTT_TOPIC_OPENED=psfusion/business-event/v010/compartment/opened`, `LOCKER_ID`.
- `rest-client`: `SERVER_URL`, `LOCKER_ID`, `COMPARTMENT_ID`, `API_VERSION=v010`, `CLIENT_VERSION=1.2.6`.
- `mqtt-publisher`: `MQTT_BROKER_URI`, `MQTT_CLIENT_ID=mqtt-publisher`, `MQTT_TOPIC_OPENED=...`, `LOCKER_ID`, `COMPARTMENT_ID`; reuses `rest-client-keystore.p12`.
- `mqtt-subscriber`: `MQTT_BROKER_URI`, `MQTT_CLIENT_ID=mqtt-subscriber`, `MQTT_TOPIC_OPENED=...`; reuses `rest-client-keystore.p12`.

---

## 8. Logging

- **Framework:** `java.util.logging`.
- **Destination:** STDOUT (so `docker compose logs` captures everything).
- **Format:** `yyyy-MM-dd'T'HH:mm:ss.SSSXXX  LEVEL  [logger]  message`. Implemented via a custom `SimpleFormatter` pattern set in `logging.properties` loaded from classpath.
- **Mandatory log lines** (required for acceptance):
  - `rest-server`: `"Starting HttpsServer on {bind} with mTLS"`, `"Received openCompartment lockerId={} compartmentId={} idempotencyKey={} lockToken=*** clientVersion={}"`, `"Published CompartmentOpenedEventMsg to topic={}"`.
  - `rest-client`: `"Sending openCompartment to {url}"`, `"Response status={} body={}"`.
  - `mqtt-subscriber`: `"Connected to {broker} with mTLS"`, `"Received event topic={} payload={}"`.
  - `mqtt-publisher`: `"Published test CompartmentOpenedEventMsg to topic={}"`.

Secrets (`Lock-Token`, passwords) are **never** logged verbatim — replaced with `***`.

---

## 9. Error handling

- REST server: any uncaught exception → `500` with `ErrorResponseTo { errorCode: "INTERNAL_ERROR", message: <short>, timestamp }`; stack trace to log.
- Invalid `compartmentId` regex → `400` `INVALID_COMPARTMENT_ID`.
- Invalid `lockerId` UUID → `400` `INVALID_LOCKER_ID`.
- Missing required header → `400` `MISSING_HEADER` with the header name in `message`.
- MQTT publish failure inside the handler: logged at `SEVERE`. The HTTP response is still `200` (the command succeeded); event emission is best-effort in the PoC. This is explicitly called out as a PoC simplification.
- MQTT clients reconnect automatically via Paho's `MqttConnectionOptions.setAutomaticReconnect(true)`.

---

## 10. Integration test

### 10.1 Goal

Automated, reproducible end-to-end verification that the full stack (certs + broker + server + subscriber + publishers + client) actually works together, exercised as an external black-box consumer over the published ports and using the generated certificates.

### 10.2 Approach

- **Language / runtime:** Java 21.
- **Test framework:** JUnit Jupiter 5 (test-scope only). This is explicitly allowed because it does not execute at runtime of the PoC services. **No** Spring, no Testcontainers (keeps the stack identical to dev).
- **Driver:** a new Maven module `integration-test` (packaging `jar`, but runs via `mvn -pl integration-test verify`). Not included in the shade-jar pipeline and not in any Docker image.
- **Orchestration:** the test class starts and stops the stack via `docker compose` subprocesses (`ProcessBuilder`). It does not redefine the stack.

### 10.3 Module layout

```text
integration-test/
├── pom.xml
└── src/test/java/com/example/locker/it/
    ├── DockerComposeStack.java        # JUnit 5 extension: up/down + waitForHealthy
    ├── CertLoader.java                # loads ca.crt + rest-client.p12 from ./certs
    ├── MqttTestClient.java            # Paho MQTTv5 subscriber, mTLS, collects messages
    └── EndToEndIT.java                # the actual tests
```

Naming: file suffix `IT` so Maven Failsafe picks it up; unit tests (none in this PoC) would use `Test`.

### 10.4 JUnit extension: `DockerComposeStack`

Implements `BeforeAllCallback` + `AfterAllCallback`:

1. `beforeAll`:
   - Run `./scripts/gen-certs.sh` (idempotent).
   - Run `docker compose up -d --build` from repo root.
   - Poll `docker compose ps --format json` until **every** service is either `running (healthy)` or (for one-shot containers `cert-init`, `rest-client`, `mqtt-publisher`) `exited 0`. Fail after 120 s.
   - Additionally assert `mqtt-subscriber` is `healthy` (i.e. `/tmp/mqtt-subscriber.ready` present) so test can safely publish.
2. `afterAll`:
   - Dump logs (`docker compose logs --no-color > target/compose-logs.txt`) for post-mortem.
   - `docker compose down -v` (remove volumes; keeps `./certs` because it's a bind mount — that is intentional for debuggability).

The extension is a JVM-level singleton (tests share the stack). A system property `-Dit.keepStack=true` skips `afterAll` teardown for local iteration.

### 10.5 Test cases (`EndToEndIT`)

All tests use `HttpClient` with an `SSLContext` built from `certs/rest-client-keystore.p12` + `certs/truststore.p12`, and a Paho `MqttTestClient` built from `certs/rest-client-keystore.p12`.

| # | Test                                                       | Assertion                                                                                                                                                                |
| - | ---------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1 | `restServerHealthEndpointReturns200`                       | `GET https://localhost:8443/health` with client cert → HTTP 200, body contains `"status":"UP"`.                                                                          |
| 2 | `openCompartmentReturns200AndEmitsEvent`                   | Subscribe to `psfusion/business-event/v010/compartment/opened`. Send `POST .../compartment/{id}/open` with valid headers → HTTP 200. Assert MQTT message received within 5 s; JSON envelope has `headers.eventType=="CompartmentOpenedEvent"` and `payload.compartmentId` equals the one requested. |
| 3 | `invalidCompartmentIdReturns400`                           | `POST` with `compartmentId=bogus` → HTTP 400, body `errorCode=="INVALID_COMPARTMENT_ID"`.                                                                                |
| 4 | `missingLockTokenHeaderReturns400`                         | `POST` without `Lock-Token` header → HTTP 400, `errorCode=="MISSING_HEADER"`, `message` contains `Lock-Token`.                                                           |
| 5 | `httpsWithoutClientCertIsRejected`                         | Build an `SSLContext` with truststore only (no keystore), `HttpClient` request → `SSLHandshakeException` (bad_certificate). Validates server-side `needClientAuth`.       |
| 6 | `mqttWithoutClientCertIsRejected`                          | Open a raw TLS socket to `localhost:8883` with truststore only, attempt MQTT CONNECT → handshake fails or connection closed. Validates broker `require_certificate`.      |
| 7 | `standaloneMqttPublisherEventWasObservedBySubscriber`      | Read `target/compose-logs.txt` after teardown of a dedicated run OR query `docker logs mqtt-subscriber` and assert at least **two** `Received event` lines (one from REST, one from standalone publisher), confirming acceptance criterion §11.5. |

### 10.6 Runbook

```bash
# Full integration test (builds + starts stack + runs tests + tears down)
mvn -pl integration-test -am verify

# Keep the stack up for debugging:
mvn -pl integration-test verify -Dit.keepStack=true

# Re-run tests against an already-running stack:
mvn -pl integration-test test -Dit.skipStack=true
```

### 10.7 CI consideration (out of scope, documented for later)

The `integration-test` module is safe to run in any environment where Docker + Docker Compose v2 are available. It should not be part of the default `mvn package` phase; it is bound to the `verify` phase and triggered by the presence of the `integration-test` module on the reactor.

---

## 11. Acceptance criteria

The PoC is considered complete when **all** of the following hold on a fresh checkout on a Unix host with Docker:

1. `./scripts/gen-certs.sh` runs from an empty state and produces, in `./certs/`: `ca.crt`, `ca.key`, and for each of `{broker, rest-server, rest-client}`: `<svc>.crt`, `<svc>.key`, `<svc>-keystore.p12`, plus a single `truststore.p12`. The `mqtt-publisher` and `mqtt-subscriber` services reuse `rest-client-keystore.p12`.
2. `docker compose build` succeeds with no warnings about missing files; all Java service images build from `Dockerfile.java-service`.
3. `docker compose up` brings up `cert-init` (exits 0), then `mosquitto`, `rest-server`, `mqtt-subscriber` reach **healthy** state per §6.5, then `rest-client` and `mqtt-publisher` run once, mark themselves done, and exit 0.
4. `docker compose ps` shows every long-running service as `healthy` and every one-shot service as `exited (0)`.
5. `docker compose logs rest-server` contains exactly one `Received openCompartment ...` line and one `Published CompartmentOpenedEventMsg ...` line per client run.
6. `docker compose logs mqtt-subscriber` contains **at least two** `Received event ...` lines in a full run (one from the REST-triggered publish, one from the standalone publisher), each showing a valid JSON envelope.
7. `docker compose logs rest-client` shows `Response status=200`.
8. Attempting to connect to `mosquitto:8883` without a client certificate (e.g. `mosquitto_sub` without `--cert`) is rejected.
9. Attempting `curl https://localhost:8443/...` without `--cacert` or without `--cert/--key` is rejected by the server.
10. All logs use the timestamped format defined in §8.
11. No framework dependencies (Spring, Quarkus, etc.) appear in any production `pom.xml` (`integration-test` is allowed to depend on JUnit 5 in `test` scope only).
12. `mvn -pl integration-test -am verify` passes all tests defined in §10.5 on a clean checkout.

---

## 12. Known PoC simplifications (explicit gaps)

- Self-signed CA committed via generation script, not a real PKI.
- Keystore passwords hard-coded to `changeit`.
- `Lock-Token` and `Idempotency-Key` are not validated, only logged.
- No response body on success (YAML spec also has none for 200).
- Only `400` and `500` error paths implemented (not `422` / `501`).
- No idempotency store; the server processes every request fresh.
- MQTT publish failures do not affect the HTTP status.
- Only an end-to-end integration test (§10) is shipped; no unit tests and no contract tests against the OpenAPI YAMLs.
- No CI pipeline.

---

## 13. Implementation order (when we switch from spec → code)

1. `scripts/gen-certs.sh` + `cert-init` Dockerfile → verify certs appear in `./certs`.
2. `mosquitto` container + `mosquitto.conf` + healthcheck → verify `docker compose ps` reports `healthy`, and `mosquitto_sub` works with generated client cert.
3. `common` module (DTOs, `TlsContextFactory`, `HealthFile`, `logging.properties`).
4. `mqtt-subscriber` (writes `/tmp/mqtt-subscriber.ready` after SUBSCRIBE ACK) → confirm TLS connect, subscribe, and healthcheck pass.
5. `mqtt-publisher` (writes `/tmp/mqtt-publisher.done` on success) → confirm subscriber logs a received envelope.
6. `rest-server` HTTPS handler + `GET /health` + healthcheck → confirm mTLS with `curl --cert`, healthcheck passes.
7. `rest-server` MQTT publish wired into `openCompartment` handler → confirm subscriber logs the REST-triggered event.
8. `rest-client` (writes `/tmp/rest-client.done` on 200 response) → confirm full end-to-end via `docker compose up`.
9. `docker-compose.yml` final wiring: shared Java service Dockerfile build args, `depends_on` conditions (`service_healthy`, `service_completed_successfully`), all 6 healthcheck blocks per §6.5.
10. `integration-test` module (§10) → `mvn -pl integration-test -am verify` passes on a clean host.
11. README quick-start verification on a clean host.

