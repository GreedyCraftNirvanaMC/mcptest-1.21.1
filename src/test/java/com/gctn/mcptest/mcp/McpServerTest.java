package com.gctn.mcptest.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Standalone, dependency-free transport/security integration checks; no game or graphics needed. */
public final class McpServerTest {
    private static final String TOKEN = "test-only-0123456789abcdefghijklmnopqrstuvwxyz";
    private static final String VERSION = McpServer.PROTOCOL_VERSION;
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        configChecks();
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        FakeTools tools = new FakeTools();
        BridgeConfig config = new BridgeConfig(true, port, TOKEN, 1);
        try (McpServer server = new McpServer(config, tools);
                HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(3)).build()) {
            server.start();
            Fixture test = new Fixture(client, port);
            check(test.send("{}", null, VERSION, null, null, "POST").statusCode() == 401, "token required");
            check(test.send("{}", null, VERSION, "https://evil.example", TOKEN, "POST").statusCode() == 403, "hostile Origin rejected");
            check(test.send("{}", null, VERSION, "null", TOKEN, "POST").statusCode() == 403, "opaque Origin rejected");
            check(test.send("{}", null, VERSION, "http://localhost:" + (port + 1), TOKEN, "POST").statusCode() == 403, "wrong Origin port rejected");
            check(test.send(null, null, VERSION, null, TOKEN, "GET").statusCode() == 405, "optional SSE is explicitly unsupported");
            check(badHost(port).startsWith("HTTP/1.1 403"), "DNS rebinding Host rejected");

            HttpResponse<String> init = test.send(initialize(VERSION), null, VERSION, "http://localhost:" + port, TOKEN, "POST");
            check(init.statusCode() == 200, "initialize succeeds");
            String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
            check(object(init).getAsJsonObject("result").get("protocolVersion").getAsString().equals(VERSION), "version negotiated");
            check(test.rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", null).statusCode() == 400, "session required");
            check(errorCode(test.rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session)) == -32002, "initialized notification required");
            check(test.rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session).statusCode() == 202, "initialized accepted");
            check(test.rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", session).statusCode() == 200, "ping succeeds");
            JsonObject list = object(test.rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}", session));
            check(list.getAsJsonObject("result").getAsJsonArray("tools").size() == 3, "tool catalog exposed");
            JsonObject echo = object(test.rpc(call(4, "echo", "{\"message\":\"中文\"}"), session)).getAsJsonObject("result");
            check(!echo.get("isError").getAsBoolean(), "successful tool result");
            check(echo.getAsJsonObject("structuredContent").get("message").getAsString().equals("中文"), "UTF-8 structured content retained");
            check(echo.getAsJsonArray("content").size() == 1, "text fallback retained");
            check(errorCode(test.rpc(call(5, "unknown", "{}"), session)) == -32602, "unknown tool is protocol error");
            check(errorCode(test.rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"missing\"}", session)) == -32601, "unknown method");
            check(errorCode(test.rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":[]}", session)) == -32602, "invalid params");
            check(errorCode(test.rpc("{unquoted: true}", session)) == -32700, "strict JSON syntax");
            check(errorCode(test.rpc("{} {}", session)) == -32700, "trailing JSON rejected");
            check(errorCode(test.rpc("[{}]", session)) == -32600, "JSON-RPC batches rejected");
            HttpResponse<String> badId = test.rpc("{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}", session);
            check(object(badId).has("id") && object(badId).get("id").isJsonNull(), "invalid request uses explicit null id");
            check(errorCode(test.rpc("{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"ping\"}", session)) == -32600, "fractional request ID rejected");
            check(test.rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/unknown\"}", session).statusCode() == 202, "unknown notification accepted without response body");
            check(test.send("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"ping\"}", session, "unsupported", null, TOKEN, "POST").statusCode() == 400, "unsupported version header rejected");
            check(test.rpc("x".repeat(262145), session).statusCode() == 413, "request body bounded");
            check(errorCode(test.rpc("[".repeat(65) + "0" + "]".repeat(65), session)) == -32700, "JSON nesting bounded");

            CompletableFuture<HttpResponse<String>> held = test.async(call(20, "hold", "{}"), session);
            CompletableFuture<JsonObject> heldExecution = tools.held.poll(3, TimeUnit.SECONDS);
            check(heldExecution != null, "held call started");
            check(errorCode(test.rpc(call(20, "echo", "{}"), session)) == -32600, "duplicate in-flight request ID rejected");
            test.rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":20}}", session);
            check(held.get(3, TimeUnit.SECONDS).statusCode() == 204, "cancelled request emits no RPC response");
            check(heldExecution.isCancelled(), "cancellation reaches game future");

            HttpResponse<String> timedOut = test.rpc(call(21, "hold", "{}"), session);
            CompletableFuture<JsonObject> timedExecution = tools.held.poll(3, TimeUnit.SECONDS);
            check(object(timedOut).getAsJsonObject("result").get("isError").getAsBoolean(), "timeout is tool error");
            check(object(timedOut).getAsJsonObject("result").getAsJsonObject("structuredContent").get("status").getAsString().equals("timeout"), "timeout has machine-readable status");
            check(timedExecution != null && timedExecution.isCancelled(), "deadline cancels pending work");
            check(object(test.rpc(call(22, "fail", "{}"), session)).getAsJsonObject("result").get("isError").getAsBoolean(), "execution failure is tool error");

            String old = test.send(initialize("2025-06-18"), null, null, null, TOKEN, "POST")
                    .headers().firstValue("Mcp-Session-Id").orElseThrow();
            test.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", old, "2025-06-18", null, TOKEN, "POST");
            JsonObject oldResult = object(test.send(call(23, "echo", "{}"), old, "2025-06-18", null, TOKEN, "POST")).getAsJsonObject("result");
            check(oldResult.has("structuredContent") && oldResult.has("content"), "June protocol receives compatible content");
            check(test.send(null, old, "2025-06-18", null, TOKEN, "DELETE").statusCode() == 204, "June session deleted");

            CompletableFuture<HttpResponse<String>> deleting = test.async(call(24, "hold", "{}"), session);
            CompletableFuture<JsonObject> deletedExecution = tools.held.poll(3, TimeUnit.SECONDS);
            check(deletedExecution != null, "call active before DELETE");
            check(test.send(null, session, VERSION, null, TOKEN, "DELETE").statusCode() == 204, "DELETE closes session");
            check(deleting.get(3, TimeUnit.SECONDS).statusCode() == 204 && deletedExecution.isCancelled(), "DELETE cancels active work");
            check(test.rpc("{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"ping\"}", session).statusCode() == 404, "deleted session cannot be reused");
            check(test.send(null, session, VERSION, null, TOKEN, "GET").statusCode() == 404, "deleted session rejected on GET");
            check(test.send(null, null, "unsupported", null, TOKEN, "GET").statusCode() == 400, "invalid version rejected on GET");

            List<String> full = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                HttpResponse<String> response = test.send(initialize(i == 0 ? "2025-03-26" : "unsupported"), null, null, null, TOKEN, "POST");
                check(object(response).getAsJsonObject("result").get("protocolVersion").getAsString().equals(VERSION), "unsupported proposal negotiates supported version");
                full.add(response.headers().firstValue("Mcp-Session-Id").orElseThrow());
            }
            check(test.send(initialize(VERSION), null, null, null, TOKEN, "POST").statusCode() == 503, "session count bounded");
            for (String sessionId : full) test.send(null, sessionId, VERSION, null, TOKEN, "DELETE");

            String shutdown = test.send(initialize(VERSION), null, VERSION, null, TOKEN, "POST").headers().firstValue("Mcp-Session-Id").orElseThrow();
            test.rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", shutdown);
            CompletableFuture<HttpResponse<String>> closing = test.async(call(26, "hold", "{}"), shutdown);
            CompletableFuture<JsonObject> closingExecution = tools.held.poll(3, TimeUnit.SECONDS);
            check(closingExecution != null, "call active before shutdown");
            server.close();
            check(closingExecution.isCancelled(), "server shutdown cancels game work");
            try { closing.get(3, TimeUnit.SECONDS); } catch (Exception expected) { /* Connection closes during shutdown. */ }
        }
        System.out.println("MCP transport checks passed: " + checks);
    }

    private static void configChecks() throws IOException {
        Path base = Path.of(System.getProperty("mcptest.test.temp", "build/tmp"));
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "mcptest-config-");
        try {
            BridgeConfig first = BridgeConfig.load(directory);
            check(first.port() == 8765 && first.enabled(), "config defaults");
            check(first.bearerToken().length() >= 32, "secure generated token length");
            check(BridgeConfig.load(directory).bearerToken().equals(first.bearerToken()), "token persists");
            check(!first.toString().contains(first.bearerToken()), "config string redacts token");
            Files.writeString(directory.resolve("mcptest.json"), "{\"port\":1234.5}");
            boolean rejected = false;
            try { BridgeConfig.load(directory); } catch (IOException expected) { rejected = true; }
            check(rejected, "fractional config port rejected");
        } finally {
            Files.deleteIfExists(directory.resolve("mcptest.json"));
            Files.deleteIfExists(directory);
        }
    }

    private static String badHost(int port) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            String request = "POST /mcp HTTP/1.1\r\nHost: evil.example:" + port + "\r\nConnection: close\r\nContent-Length: 2\r\n\r\n{}";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String initialize(String version) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" + version
                + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"transport-test\",\"version\":\"1\"}}}";
    }

    private static String call(int id, String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}";
    }

    private static JsonObject object(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static int errorCode(HttpResponse<String> response) {
        return object(response).getAsJsonObject("error").get("code").getAsInt();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private record Fixture(HttpClient client, int port) {
        HttpResponse<String> rpc(String body, String session) throws IOException, InterruptedException {
            return send(body, session, VERSION, null, TOKEN, "POST");
        }

        CompletableFuture<HttpResponse<String>> async(String body, String session) {
            return client.sendAsync(request(body, session, VERSION, null, TOKEN, "POST"), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        HttpResponse<String> send(String body, String session, String version, String origin, String token, String method) throws IOException, InterruptedException {
            return client.send(request(body, session, version, origin, token, method), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        HttpRequest request(String body, String session, String version, String origin, String token, String method) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream");
            if (session != null) builder.header("Mcp-Session-Id", session);
            if (version != null) builder.header("Mcp-Protocol-Version", version);
            if (origin != null) builder.header("Origin", origin);
            if (token != null) builder.header("Authorization", "Bearer " + token);
            return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        }
    }

    private static final class FakeTools implements McpTools {
        final LinkedBlockingQueue<CompletableFuture<JsonObject>> held = new LinkedBlockingQueue<>();

        @Override
        public JsonArray listTools() {
            JsonArray tools = new JsonArray();
            for (String name : List.of("echo", "hold", "fail")) {
                JsonObject tool = new JsonObject();
                tool.addProperty("name", name);
                tool.add("inputSchema", JsonParser.parseString("{\"type\":\"object\"}"));
                tools.add(tool);
            }
            return tools;
        }

        @Override
        public CompletableFuture<JsonObject> call(String name, JsonObject arguments) {
            if (name.equals("hold")) {
                CompletableFuture<JsonObject> future = new CompletableFuture<>();
                held.add(future);
                return future;
            }
            if (name.equals("fail")) return CompletableFuture.failedFuture(new IllegalStateException("test failure"));
            JsonObject response = new JsonObject();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", arguments.toString());
            JsonArray content = new JsonArray();
            content.add(text);
            response.add("content", content);
            response.add("structuredContent", arguments);
            response.addProperty("isError", false);
            return CompletableFuture.completedFuture(response);
        }
    }
}
