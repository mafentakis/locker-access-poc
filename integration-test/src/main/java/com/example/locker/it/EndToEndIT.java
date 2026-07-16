package com.example.locker.it;

import com.example.locker.common.JsonMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EndToEndIT {

    private static final String REST_BASE = System.getenv().getOrDefault("REST_SERVER_URL", "https://rest-server:8443");
    private static final String MQTT_BROKER = System.getenv().getOrDefault("MQTT_BROKER_URI", "ssl://mosquitto:8883");
    private static final String LOCKER_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
    private static final String COMPARTMENT_ID = "compartmentInfo@d4e5f6a7-b8c9-0123-defa-234567890123";
    private static final String TOPIC = "psfusion/business-event/v010/compartment/opened";
    private static final String DNS_ALT_NAME_PROPERTY = "rest.server.dns.alt.name";

    private static HttpClient httpClient;

    @BeforeAll
    static void setup() throws Exception {
        SSLContext ssl = CertLoader.clientSslContext();
        httpClient = HttpClient.newBuilder().sslContext(ssl).build();
    }

    @Test
    @Order(1)
    void restServerHealthEndpointReturns200() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(REST_BASE + "/health"))
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"status\":\"UP\""));
    }

    @Test
    @Order(2)
    void openCompartmentReturns200AndEmitsEvent() throws Exception {
        try (MqttTestClient mqtt = new MqttTestClient(CertLoader.clientSslContext(), MQTT_BROKER, TOPIC)) {
            String url = REST_BASE + "/api/v010/locker/" + LOCKER_ID + "/compartment/" + COMPARTMENT_ID + "/open";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .header("Client-Version", "1.2.6")
                    .header("Lock-Token", "gy12p6N1UC4WJp4H1tFhVqtlzgm7qYp4qVnq5p/PLu4=")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());

            String payload = mqtt.poll(10);
            assertNotNull(payload, "Expected MQTT event within 10s");

            JsonNode node = JsonMapper.instance().readTree(payload);
            assertEquals("CompartmentOpenedEvent", node.path("headers").path("eventType").asText());
            assertEquals(COMPARTMENT_ID, node.path("payload").path("compartmentId").asText());
        }
    }

    @Test
    @Order(3)
    void invalidCompartmentIdReturns400() throws Exception {
        String url = REST_BASE + "/api/v010/locker/" + LOCKER_ID + "/compartment/bogus/open";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Client-Version", "1.2.6")
                .header("Lock-Token", "dummy")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp.statusCode());

        JsonNode node = JsonMapper.instance().readTree(resp.body());
        assertEquals("INVALID_COMPARTMENT_ID", node.path("errorCode").asText());
    }

    @Test
    @Order(4)
    void missingLockTokenHeaderReturns400() throws Exception {
        String url = REST_BASE + "/api/v010/locker/" + LOCKER_ID + "/compartment/" + COMPARTMENT_ID + "/open";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Client-Version", "1.2.6")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp.statusCode());

        JsonNode node = JsonMapper.instance().readTree(resp.body());
        assertEquals("MISSING_HEADER", node.path("errorCode").asText());
        assertTrue(node.path("message").asText().contains("Lock-Token"));
    }

    @Test
    @Order(5)
    void httpsWithoutClientCertIsRejected() {
        assertThrows(Exception.class, () -> {
            SSLContext trustOnly = CertLoader.trustOnlySslContext();
            HttpClient noClientCert = HttpClient.newBuilder().sslContext(trustOnly).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(REST_BASE + "/health"))
                    .GET().build();
            noClientCert.send(req, HttpResponse.BodyHandlers.ofString());
        }, "Connection without client cert should be rejected");
    }

    @Test
    @Order(6)
    void mqttWithoutClientCertIsRejected() {
        assertThrows(Exception.class, () -> {
            SSLContext trustOnly = CertLoader.trustOnlySslContext();
            new MqttTestClient(trustOnly, MQTT_BROKER, TOPIC);
        }, "MQTT connection without client cert should be rejected");
    }

    /**
     * DNS-workaround POC: {@code <uuid>.fusion.dhl.com} is not resolvable by
     * Docker's embedded DNS (it only knows compose service names). We resolve
     * rest-server's real container IP and add a temporary /etc/hosts entry
     * mapping the alt name to it, proving TLS hostname verification against
     * the certificate's SAN succeeds without any real DNS record.
     */
    @Test
    @Order(7)
    void httpsWithDnsWorkaroundHostnameSucceeds() throws Exception {
        String altFqdn = System.getProperty(DNS_ALT_NAME_PROPERTY);
        assertNotNull(altFqdn, "Required system property not set: " + DNS_ALT_NAME_PROPERTY);
        assertFalse(altFqdn.isBlank(), "System property " + DNS_ALT_NAME_PROPERTY + " must not be blank");

        String restServerIp = InetAddress.getByName("rest-server").getHostAddress();

        Path hostsFile = Path.of("/etc/hosts");
        String hostsEntry = restServerIp + " " + altFqdn;
        Files.writeString(hostsFile, hostsEntry + System.lineSeparator(), StandardOpenOption.APPEND);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + altFqdn + ":8443/health"))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("\"status\":\"UP\""));
        } finally {
            List<String> remaining = Files.readAllLines(hostsFile).stream()
                    .filter(line -> !line.contains(altFqdn))
                    .toList();
            Files.write(hostsFile, remaining);
        }
    }
}

