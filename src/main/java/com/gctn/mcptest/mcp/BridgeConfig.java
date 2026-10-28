package com.gctn.mcptest.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;

/** Local connection settings. The token is a secret and must never be logged. */
public record BridgeConfig(boolean enabled, int port, String bearerToken, int requestTimeoutSeconds) {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public BridgeConfig {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1024 and 65535");
        }
        if (bearerToken == null || !bearerToken.matches("[A-Za-z0-9_-]{32,256}")) {
            throw new IllegalArgumentException("bearerToken must contain 32–256 URL-safe letters, digits, '_' or '-'");
        }
        if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 120) {
            throw new IllegalArgumentException("requestTimeoutSeconds must be between 1 and 120");
        }
    }

    public static BridgeConfig load(Path gameConfigDir) throws IOException {
        Files.createDirectories(gameConfigDir);
        Path path = gameConfigDir.resolve("mcptest.json");
        JsonObject json = new JsonObject();
        if (Files.exists(path)) {
            if (Files.size(path) > 65536) {
                throw new IOException("mcptest.json exceeds 64 KiB");
            }
            try {
                json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IOException("mcptest.json must be a JSON object");
            }
        }
        boolean missing = !json.has("enabled") || !json.has("port")
                || !json.has("bearerToken") || !json.has("requestTimeoutSeconds");
        BridgeConfig config;
        try {
            config = new BridgeConfig(bool(json, "enabled", true), integer(json, "port", 8765),
                    json.has("bearerToken") ? string(json.get("bearerToken")) : newToken(),
                    integer(json, "requestTimeoutSeconds", 30));
        } catch (RuntimeException e) {
            // Do not include the input object: it contains the bearer token.
            throw new IOException("Invalid mcptest.json: " + e.getMessage());
        }
        if (missing) {
            Path temporary = Files.createTempFile(gameConfigDir, "mcptest-", ".tmp");
            try {
                Files.writeString(temporary, JSON.toJson(config) + System.lineSeparator(), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return config;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("bearerToken must be a string");
        }
        return value.getAsString();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    @Override
    public String toString() {
        return "BridgeConfig[enabled=" + enabled + ", port=" + port
                + ", bearerToken=<redacted>, requestTimeoutSeconds=" + requestTimeoutSeconds + "]";
    }
}
