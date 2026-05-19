package com.example.locker.server;

import com.example.locker.common.LogConfig;
import com.example.locker.common.tls.TlsContextFactory;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class RestServerMain {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(RestServerMain.class.getName());

    public static void main(String[] args) throws Exception {
        LogConfig.init();

        String bind = System.getenv().getOrDefault("HTTPS_BIND", "0.0.0.0:8443");
        String[] parts = bind.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        String brokerUri = System.getenv().getOrDefault("MQTT_BROKER_URI", "ssl://mosquitto:8883");
        String mqttClientId = System.getenv().getOrDefault("MQTT_CLIENT_ID", "rest-server");
        String mqttTopic = System.getenv().getOrDefault("MQTT_TOPIC_OPENED",
                "psfusion/business-event/v010/compartment/opened");
        String lockerVersion = System.getenv().getOrDefault("LOCKER_VERSION", "1.0.0");

        SSLContext sslContext = TlsContextFactory.create();

        MqttEventEmitter emitter = new MqttEventEmitter(brokerUri, mqttClientId, mqttTopic, sslContext);

        HttpsServer server = HttpsServer.create(new InetSocketAddress(host, port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters sslParams = sslContext.getDefaultSSLParameters();
                sslParams.setNeedClientAuth(true);
                params.setSSLParameters(sslParams);
            }
        });

        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.createContext("/api/locker/", new LockerVersionHandler(lockerVersion));
        server.createContext("/api/", new OpenCompartmentHandler(emitter));

        server.setExecutor(null);
        server.start();

        LOG.info("Starting HttpsServer on " + bind + " with mTLS");
    }
}
