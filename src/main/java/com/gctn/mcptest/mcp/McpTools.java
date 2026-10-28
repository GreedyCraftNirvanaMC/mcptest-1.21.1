package com.gctn.mcptest.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** The transport never reads Minecraft state; implementations dispatch onto the client thread. */
public interface McpTools {
    JsonArray listTools();

    /** Returns a complete MCP CallToolResult. Cancellation must also cancel queued game work. */
    CompletableFuture<JsonObject> call(String name, JsonObject arguments);
}
