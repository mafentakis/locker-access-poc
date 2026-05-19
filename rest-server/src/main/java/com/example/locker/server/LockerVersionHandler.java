package com.example.locker.server;

import com.example.locker.common.JsonMapper;
import com.example.locker.common.dto.ErrorResponseTo;
import com.example.locker.common.dto.LockerVersionTo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LockerVersionHandler implements HttpHandler {

    private static final Pattern PATH_PATTERN = Pattern.compile("/api/locker/([^/]+)/version");

    private final String version;

    public LockerVersionHandler(String version) {
        this.version = version;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        Matcher matcher = PATH_PATTERN.matcher(path);
        if (!matcher.matches()) {
            sendError(exchange, 400, "INVALID_PATH", "Path does not match expected pattern: " + path);
            return;
        }

        String lockerId = matcher.group(1);
        try {
            UUID.fromString(lockerId);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "INVALID_LOCKER_ID",
                    "Path parameter 'lockerId' is not a valid UUID.");
            return;
        }

        String clientVersion = exchange.getRequestHeaders().getFirst("Client-Version");
        if (clientVersion == null || clientVersion.isBlank()) {
            sendError(exchange, 400, "MISSING_HEADER",
                    "Required header 'Client-Version' is missing.");
            return;
        }

        LockerVersionTo response = new LockerVersionTo(lockerId, version);
        byte[] body = JsonMapper.instance().writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        ErrorResponseTo error = new ErrorResponseTo(code, message, Instant.now());
        byte[] body = JsonMapper.instance().writeValueAsBytes(error);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
