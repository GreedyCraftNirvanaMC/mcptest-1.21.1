package com.gctn.mcptest.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** MCP Streamable HTTP with JSON responses; no SSE stream or additional runtime dependency. */
public final class McpServer implements AutoCloseable {
    public static final String PROTOCOL_VERSION = "2025-11-25";
    private static final Set<String> SUPPORTED_VERSIONS = Set.of(PROTOCOL_VERSION, "2025-06-18");
    private static final int MAX_BODY_BYTES = 262144;
    private static final int MAX_SESSIONS = 16;
    private static final long SESSION_IDLE_NANOS = Duration.ofMinutes(30).toNanos();
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private final BridgeConfig config;
    private final McpTools tools;
    private final JsonArray catalog;
    private final Set<String> toolNames;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Semaphore connections = new Semaphore(32);
    private final Semaphore calls = new Semaphore(8);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mcptest-mcp-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private volatile HttpServer server;
    private volatile boolean closed;

    public McpServer(BridgeConfig config, McpTools tools) {
        this.config = config;
        this.tools = tools;
        this.catalog = tools.listTools().deepCopy();
        this.toolNames = catalog.asList().stream().map(e -> e.getAsJsonObject().get("name").getAsString())
                .collect(Collectors.toUnmodifiableSet());
    }

    public synchronized void start() throws IOException {
        if (closed || server != null) throw new IllegalStateException("MCP server has already started or closed");
        if (!config.enabled()) return;
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.port()), 16);
        http.createContext("/mcp", this::handle);
        http.setExecutor(workers);
        server = http;
        http.start();
        deadlines.scheduleAtFixedRate(this::expireSessions, 1, 1, TimeUnit.MINUTES);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!connections.tryAcquire()) {
            try (exchange) {
                respond(exchange, 503, error(null, -32000, "Too many connections; retry later"));
            }
            return;
        }
        try {
            if (closed) {
                respond(exchange, 503, error(null, -32000, "MCP server is shutting down"));
                return;
            }
            if (!exchange.getRequestURI().getPath().equals("/mcp") || exchange.getRequestURI().getRawQuery() != null) {
                respond(exchange, 404, error(null, -32600, "Unknown endpoint"));
                return;
            }
            if (!validLocalRequest(exchange)) {
                respond(exchange, 403, error(null, -32600, "Only local requests with a trusted Host and Origin are allowed"));
                return;
            }
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"mcptest\"");
                respond(exchange, 401, error(null, -32600, "Missing or invalid bearer token"));
                return;
            }
            switch (exchange.getRequestMethod()) {
                case "POST" -> post(exchange);
                case "DELETE" -> delete(exchange);
                default -> {
                    if (exchange.getRequestHeaders().containsKey("Mcp-Session-Id")) {
                        if (requireSession(exchange) == null) return;
                    } else {
                        String version = exchange.getRequestHeaders().getFirst("Mcp-Protocol-Version");
                        if (version != null && !SUPPORTED_VERSIONS.contains(version)) {
                            respond(exchange, 400, error(null, -32600, "Unsupported Mcp-Protocol-Version"));
                            return;
                        }
                    }
                    exchange.getResponseHeaders().set("Allow", "POST, DELETE");
                    respond(exchange, 405, error(null, -32600, "Use POST; this server does not offer an SSE stream"));
                }
            }
        } catch (IOException e) {
            // A dropped HTTP connection is not an MCP cancellation. Pending calls have their own deadlines.
        } catch (RuntimeException e) {
            // No configuration, bearer token, or arbitrary exception text is sent to the client here.
            try {
                respond(exchange, 500, error(null, -32603, "Internal MCP transport error"));
            } catch (IOException ignored) {
                // The exchange may already be closed.
            }
        } finally {
            exchange.close();
            connections.release();
        }
    }

    private void post(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            respond(exchange, 415, error(null, -32600, "Content-Type must be application/json"));
            return;
        }
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        if (accept == null || !accept.contains("application/json") || !accept.contains("text/event-stream")) {
            respond(exchange, 406, error(null, -32600, "Accept must include application/json and text/event-stream"));
            return;
        }
        byte[] body;
        ScheduledFuture<?> bodyDeadline = deadlines.schedule(exchange::close, 10, TimeUnit.SECONDS);
        try {
            body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        } finally {
            bodyDeadline.cancel(false);
        }
        if (body.length > MAX_BODY_BYTES) {
            respond(exchange, 413, error(null, -32600, "Request body exceeds 256 KiB"));
            return;
        }
        JsonElement parsed;
        try {
            parsed = parseStrict(body);
        } catch (IOException | RuntimeException e) {
            respond(exchange, 400, error(null, -32700, "Invalid JSON"));
            return;
        }
        if (!parsed.isJsonObject()) {
            respond(exchange, 400, error(null, -32600, "Expected a single JSON-RPC object; batches are not supported"));
            return;
        }
        JsonObject request = parsed.getAsJsonObject();
        JsonElement id = request.get("id");
        if (!isString(request.get("jsonrpc")) || !"2.0".equals(request.get("jsonrpc").getAsString())
                || (request.has("id") && !validId(id))) {
            respond(exchange, 400, error(null, -32600, "Invalid JSON-RPC envelope or request ID"));
            return;
        }
        // This server sends no requests, but the transport accepts valid client response envelopes.
        if (!request.has("method") && validId(id) && (request.has("result") ^ request.has("error"))) {
            Session session = requireSession(exchange);
            if (session != null) respond(exchange, 202, null);
            return;
        }
        if (!isString(request.get("method")) || request.has("result") || request.has("error")) {
            respond(exchange, 400, error(id, -32600, "Invalid JSON-RPC request"));
            return;
        }
        String method = request.get("method").getAsString();
        if (request.has("params") && !request.get("params").isJsonObject()) {
            respond(exchange, 400, error(id, -32602, "params must be an object"));
            return;
        }
        JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        if (method.equals("initialize")) {
            if (id == null) {
                respond(exchange, 400, error(null, -32600, "initialize must be a request"));
            } else {
                initialize(exchange, id, params);
            }
            return;
        }
        Session session = requireSession(exchange);
        if (session == null) return;
        if (id == null) {
            if (method.equals("notifications/initialized")) session.initialized = true;
            if (method.equals("notifications/cancelled") && validId(params.get("requestId"))) {
                CompletableFuture<JsonObject> pending = session.pending.get(idKey(params.get("requestId")));
                if (pending != null) pending.cancel(false);
            }
            respond(exchange, 202, null);
            return;
        }
        if (method.equals("ping")) {
            respond(exchange, 200, result(id, new JsonObject()));
            return;
        }
        if (!session.initialized) {
            respond(exchange, 200, error(id, -32002, "Send notifications/initialized before using tools"));
            return;
        }
        switch (method) {
            case "tools/list" -> {
                if (params.has("cursor")) {
                    respond(exchange, 200, error(id, -32602, "This tool catalog has no pagination cursor"));
                    return;
                }
                JsonObject response = new JsonObject();
                response.add("tools", catalog.deepCopy());
                respond(exchange, 200, result(id, response));
            }
            case "tools/call" -> call(exchange, session, id, params);
            default -> respond(exchange, 200, error(id, -32601, "Method not found"));
        }
    }

    private void initialize(HttpExchange exchange, JsonElement id, JsonObject params) throws IOException {
        if (exchange.getRequestHeaders().containsKey("Mcp-Session-Id")) {
            respond(exchange, 400, error(id, -32600, "Initialize a new connection without Mcp-Session-Id"));
            return;
        }
        if (!isString(params.get("protocolVersion")) || !params.has("capabilities")
                || !params.get("capabilities").isJsonObject() || !params.has("clientInfo")
                || !params.get("clientInfo").isJsonObject()
                || !isString(params.getAsJsonObject("clientInfo").get("name"))
                || !isString(params.getAsJsonObject("clientInfo").get("version"))) {
            respond(exchange, 200, error(id, -32602, "initialize requires protocolVersion, capabilities and clientInfo"));
            return;
        }
        String requested = params.get("protocolVersion").getAsString();
        String version = SUPPORTED_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSION;
        String sessionId = UUID.randomUUID().toString();
        synchronized (sessions) {
            if (closed) {
                respond(exchange, 503, error(id, -32000, "MCP server is shutting down"));
                return;
            }
            expireSessions();
            if (sessions.size() >= MAX_SESSIONS) {
                respond(exchange, 503, error(id, -32000, "Session limit reached; close an existing session"));
                return;
            }
            sessions.put(sessionId, new Session(version));
        }
        JsonObject response = new JsonObject();
        response.addProperty("protocolVersion", version);
        JsonObject capabilities = new JsonObject();
        JsonObject toolsCapability = new JsonObject();
        toolsCapability.addProperty("listChanged", false);
        capabilities.add("tools", toolsCapability);
        response.add("capabilities", capabilities);
        JsonObject info = new JsonObject();
        info.addProperty("name", "mcptest");
        info.addProperty("version", "1.0.0");
        response.add("serverInfo", info);
        response.addProperty("instructions", "Inspect game state before acting. Tools control the real Minecraft client. "
                + "A submitted action is not proof of server acceptance; inspect resulting state and messages. "
                + "Use minecraft_release_inputs to stop input. Minecraft must remain running and responsive.");
        exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
        respond(exchange, 200, result(id, response));
    }

    private void call(HttpExchange exchange, Session session, JsonElement id, JsonObject params) throws IOException {
        if (!isString(params.get("name")) || !toolNames.contains(params.get("name").getAsString())) {
            respond(exchange, 200, error(id, -32602, "Unknown or missing tool name"));
            return;
        }
        if ((params.has("arguments") && !params.get("arguments").isJsonObject()) || params.has("task")) {
            respond(exchange, 200, error(id, -32602, "arguments must be an object; task-augmented execution is not supported"));
            return;
        }
        if (!calls.tryAcquire()) {
            respond(exchange, 200, result(id, toolError("busy", "Too many active tools; retry after an operation completes")));
            return;
        }
        String key = idKey(id);
        CompletableFuture<JsonObject> pending = new CompletableFuture<>();
        synchronized (session) {
            if (closed || session.terminated) {
                calls.release();
                respond(exchange, 404, error(id, -32001, "Session has ended"));
                return;
            }
            if (session.pending.putIfAbsent(key, pending) != null) {
                calls.release();
                respond(exchange, 200, error(id, -32600, "Request ID is already in use in this session"));
                return;
            }
        }
        try {
            JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
            CompletableFuture<JsonObject> execution;
            synchronized (session) {
                // Cancellation or DELETE may arrive between registration and dispatch.
                if (pending.isCancelled() || session.terminated) throw new CancellationException();
                execution = tools.call(params.get("name").getAsString(), arguments);
            }
            pending.whenComplete((value, failure) -> {
                if (pending.isCancelled()) execution.cancel(false);
            });
            execution.whenComplete((value, failure) -> {
                if (failure == null) pending.complete(value);
                else if (execution.isCancelled()) pending.cancel(false);
                else pending.completeExceptionally(failure);
            });
            JsonObject response = pending.get(config.requestTimeoutSeconds(), TimeUnit.SECONDS).deepCopy();
            respond(exchange, 200, result(id, response));
        } catch (TimeoutException e) {
            pending.cancel(false);
            JsonObject response = toolError("timeout", "Timed out waiting for Minecraft. Queued work was cancelled; "
                    + "actions already applied cannot be rolled back. Inspect state before retrying.");
            respond(exchange, 200, result(id, response));
        } catch (CancellationException e) {
            // Close the cancelled request without delivering a JSON-RPC response, per MCP cancellation semantics.
            respond(exchange, 204, null);
        } catch (InterruptedException e) {
            pending.cancel(false);
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RuntimeException e) {
            pending.cancel(false);
            JsonObject response = toolError("execution_error", "The game operation failed. Consult the Minecraft log and inspect state before retrying.");
            respond(exchange, 200, result(id, response));
        } finally {
            session.pending.remove(key, pending);
            calls.release();
        }
    }

    private void delete(HttpExchange exchange) throws IOException {
        Session session = requireSession(exchange);
        if (session == null) return;
        String id = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (sessions.remove(id, session)) session.cancelAll();
        respond(exchange, 204, null);
    }

    private Session requireSession(HttpExchange exchange) throws IOException {
        String id = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (id == null) {
            respond(exchange, 400, error(null, -32600, "Mcp-Session-Id is required after initialization"));
            return null;
        }
        Session session = sessions.get(id);
        if (session == null || session.terminated || System.nanoTime() - session.lastSeen > SESSION_IDLE_NANOS) {
            if (session != null && sessions.remove(id, session)) session.cancelAll();
            respond(exchange, 404, error(null, -32001, "Session not found or expired; initialize a new session"));
            return null;
        }
        String version = exchange.getRequestHeaders().getFirst("Mcp-Protocol-Version");
        if (version != null && (!SUPPORTED_VERSIONS.contains(version) || !version.equals(session.version))) {
            respond(exchange, 400, error(null, -32600, "Mcp-Protocol-Version does not match the negotiated version"));
            return null;
        }
        session.lastSeen = System.nanoTime();
        return session;
    }

    private boolean authorized(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                && MessageDigest.isEqual(config.bearerToken().getBytes(StandardCharsets.UTF_8),
                authorization.substring(7).getBytes(StandardCharsets.UTF_8));
    }

    private boolean validLocalRequest(HttpExchange exchange) {
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) return false;
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (!("127.0.0.1:" + config.port()).equals(host)
                && !("localhost:" + config.port()).equalsIgnoreCase(host)) return false;
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true; // Native MCP clients normally send no Origin.
        try {
            URI uri = URI.create(origin);
            return "http".equalsIgnoreCase(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()))
                    && uri.getPort() == config.port() && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static JsonElement parseStrict(byte[] bytes) throws IOException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Malformed UTF-8");
        }
        // Bound recursion before Gson traverses an untrusted document.
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > 64) throw new IOException("JSON nesting exceeds 64");
            } else if (c == '}' || c == ']') depth--;
        }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement value = JSON.getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT || value == null) throw new IOException("Invalid JSON document");
            return value;
        }
    }

    private void expireSessions() {
        long now = System.nanoTime();
        sessions.forEach((id, session) -> {
            if (now - session.lastSeen > SESSION_IDLE_NANOS && sessions.remove(id, session)) session.cancelAll();
        });
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static boolean validId(JsonElement id) {
        if (!(id instanceof JsonPrimitive primitive)) return false;
        if (primitive.isString()) return true;
        if (!primitive.isNumber()) return false;
        try {
            return primitive.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException | ArithmeticException e) {
            return false;
        }
    }

    private static String idKey(JsonElement id) {
        JsonPrimitive value = id.getAsJsonPrimitive();
        return value.isString() ? "s:" + value.getAsString() : "n:" + value.getAsBigDecimal().stripTrailingZeros();
    }

    private static JsonObject result(JsonElement id, JsonObject value) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0");
        envelope.add("id", id);
        envelope.add("result", value);
        return envelope;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0");
        envelope.add("id", id == null ? JsonNull.INSTANCE : id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        envelope.add("error", error);
        return envelope;
    }

    private static JsonObject toolError(String status, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("status", status);
        data.addProperty("message", message);
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", data.toString());
        JsonArray content = new JsonArray();
        content.add(text);
        JsonObject response = new JsonObject();
        response.add("content", content);
        response.add("structuredContent", data);
        response.addProperty("isError", true);
        return response;
    }

    private static void respond(HttpExchange exchange, int status, JsonObject body) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        synchronized (sessions) {
            sessions.values().forEach(Session::cancelAll);
            sessions.clear();
        }
        if (server != null) server.stop(0);
        deadlines.shutdownNow();
        workers.shutdownNow();
    }

    private static final class Session {
        final String version;
        final ConcurrentHashMap<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
        volatile long lastSeen = System.nanoTime();
        volatile boolean initialized;
        volatile boolean terminated;

        Session(String version) {
            this.version = version;
        }

        synchronized void cancelAll() {
            terminated = true;
            pending.values().forEach(future -> future.cancel(false));
        }
    }
}
