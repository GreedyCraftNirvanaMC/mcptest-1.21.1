package com.gctn.mcptest.client;

import com.google.gson.*;
import java.time.Instant;
import java.util.ArrayDeque;

/** Bounded, client-thread-only observations. No command-to-message correlation is assumed. */
final class ObservationLog {
    private final ArrayDeque<JsonObject> entries = new ArrayDeque<>();
    private long sequence;

    long cursor() { return sequence; }
    void add(String kind, String message) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", ++sequence);
        entry.addProperty("time", Instant.now().toString());
        entry.addProperty("kind", kind);
        entry.addProperty("message", message.length() > 8192 ? message.substring(0, 8192) : message);
        entries.addLast(entry);
        while (entries.size() > 256) entries.removeFirst();
    }
    JsonObject read(long after, int limit) {
        JsonObject result = new JsonObject();
        JsonArray found = new JsonArray();
        long next = after;
        for (JsonObject entry : entries) {
            if (entry.get("id").getAsLong() > after && found.size() < limit) {
                found.add(entry.deepCopy());
                next = entry.get("id").getAsLong();
            }
        }
        result.add("entries", found);
        result.addProperty("nextCursor", next);
        result.addProperty("latestCursor", sequence);
        result.addProperty("truncated", !entries.isEmpty() && after < entries.getFirst().get("id").getAsLong() - 1);
        result.addProperty("hasMore", next < sequence);
        return result;
    }
}
