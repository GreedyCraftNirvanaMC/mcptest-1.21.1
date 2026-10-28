package com.gctn.mcptest.client;

import com.google.gson.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Discovery and runtime validation share the same bounded, deliberately simple schemas. */
final class ToolCatalog {
    private final Map<String, JsonObject> definitions = new LinkedHashMap<>();

    ToolCatalog() {
        add("status", "Inspect connection, world, player, camera and pause state.", true, "", "{}");
        add("inventory", "Inspect player inventory, current container slots, carried stack and item data.", true, "", "{}");
        add("held_item", "Inspect held item, tags, components and full serialized SNBT (Minecraft 1.21 uses components).", true, "", "{\"hand\":{\"type\":\"string\",\"enum\":[\"main\",\"off\"]}}");
        add("inspect_block", "Read a loaded block's properties, tags and client-observed block entity NBT.", true, "x,y,z", position());
        add("nearby_entities", "Inspect client-tracked nearby entities; server-only data may be absent.", true, "", "{\"radius\":{\"type\":\"number\",\"minimum\":0,\"maximum\":128},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":256}}");
        add("inspect_entity", "Inspect a client-tracked entity and client-observed NBT.", true, "entityId", "{\"entityId\":{\"type\":\"integer\"}}");
        add("target", "Inspect the current crosshair hit and reachable target.", true, "", "{}");
        add("screen", "Inspect screen title, GUI-scaled widgets and menu slots. Screenshot pixels have a different scale.", true, "", "{}");
        add("screenshot", "Capture the next rendered frame, including GUI/HUD, as MCP PNG image content. maxWidth defaults to 1280; 0 keeps native resolution.", true, "", "{\"maxWidth\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":3840}}");
        add("key_bindings", "List vanilla and mod key mapping names for press_keys.", true, "", "{}");
        add("press_keys", "Hold key mappings together for 1-200 client ticks, then release. Use names from key_bindings, e.g. key.forward/key.jump. World must be unpaused and no screen open. Attack requires a captured mouse.", false, "keys", "{\"keys\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":16,\"items\":{\"type\":\"string\",\"maxLength\":256}},\"ticks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":200}}");
        add("release_inputs", "Cancel the pending action and release all inputs owned by this bridge.", false, "", "{}");
        add("use_item", "Use the selected item in air with the chosen hand; sustain use for ticks (food, bow, shield), then release. For blocks use interact_block/place_block.", false, "", "{\"hand\":{\"type\":\"string\",\"enum\":[\"main\",\"off\"]},\"ticks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":200}}");
        add("look", "Set camera yaw and pitch, using normal player rotation.", false, "yaw,pitch", "{\"yaw\":{\"type\":\"number\",\"minimum\":-360000,\"maximum\":360000},\"pitch\":{\"type\":\"number\",\"minimum\":-90,\"maximum\":90}}");
        add("look_at", "Aim the camera at a world-space point.", false, "x,y,z", coordinates());
        add("select_slot", "Select a hotbar slot (0-8).", false, "slot", "{\"slot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":8}}");
        add("command", "Submit a command as the connected player with normal permissions. Returns submitted, observations and state; submission is not proof of server success.", false, "command", "{\"command\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":32767}}");
        add("chat", "Send a chat message as the connected player.", false, "message", "{\"message\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":256}}");
        JsonObject interaction = JsonParser.parseString(position()).getAsJsonObject();
        interaction.add("face", JsonParser.parseString("{\"type\":\"string\",\"enum\":[\"up\",\"down\",\"north\",\"south\",\"east\",\"west\"]}"));
        interaction.add("hand", JsonParser.parseString("{\"type\":\"string\",\"enum\":[\"main\",\"off\"]}"));
        add("interact_block", "Right-click a loaded block face through native interaction. Requires real reach and line of sight.", false, "x,y,z,face", interaction.toString());
        add("place_block", "Place the held BlockItem against a visible block face using native placement. Returns prediction plus observed state; server can reject placement.", false, "x,y,z,face", interaction.toString());
        add("break_block", "Mine the current crosshair block with native attack input for at most ticks. Requires captured mouse, reach and no open GUI. Observe block afterward to confirm destruction.", false, "", "{\"ticks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":200}}");
        add("attack_entity", "Attack a visible reachable client-tracked entity using native game mode.", false, "entityId", "{\"entityId\":{\"type\":\"integer\"}}");
        add("interact_entity", "Right-click a visible reachable entity.", false, "entityId", "{\"entityId\":{\"type\":\"integer\"},\"hand\":{\"type\":\"string\",\"enum\":[\"main\",\"off\"]}}");
        add("drop", "Drop one held item or the entire stack.", false, "", "{\"fullStack\":{\"type\":\"boolean\"}}");
        add("inventory_click", "Perform a native inventory/menu click. Obtain current containerId and slot index from inventory; stale IDs are rejected.", false, "containerId,slot", "{\"containerId\":{\"type\":\"integer\",\"minimum\":0},\"slot\":{\"type\":\"integer\",\"minimum\":-999,\"maximum\":65535},\"button\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":40},\"clickType\":{\"type\":\"string\",\"enum\":[\"PICKUP\",\"QUICK_MOVE\",\"SWAP\",\"CLONE\",\"THROW\",\"PICKUP_ALL\"]}}");
        add("container_button", "Submit a native menu button action (e.g. enchanting). Requires current containerId.", false, "containerId,button", "{\"containerId\":{\"type\":\"integer\",\"minimum\":0},\"button\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":255}}");
        add("open_inventory", "Open the player's normal inventory screen.", false, "", "{}");
        add("close_screen", "Close the current screen through its native close handler.", false, "", "{}");
        add("gui_click", "Click and release at GUI-scaled coordinates in the current screen.", false, "x,y", "{\"x\":{\"type\":\"number\",\"minimum\":0},\"y\":{\"type\":\"number\",\"minimum\":0},\"button\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2}}");
        add("gui_scroll", "Scroll a screen at GUI-scaled coordinates.", false, "x,y,vertical", "{\"x\":{\"type\":\"number\",\"minimum\":0},\"y\":{\"type\":\"number\",\"minimum\":0},\"horizontal\":{\"type\":\"number\",\"minimum\":-100,\"maximum\":100},\"vertical\":{\"type\":\"number\",\"minimum\":-100,\"maximum\":100}}");
        add("gui_key", "Press and release a GLFW key in the current screen (Enter=257, Escape=256, Backspace=259, Tab=258). Modifiers are GLFW bits.", false, "key", "{\"key\":{\"type\":\"integer\",\"minimum\":32,\"maximum\":348},\"modifiers\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":63}}");
        add("gui_type", "Type text into the focused screen field using native character events.", false, "text", "{\"text\":{\"type\":\"string\",\"maxLength\":1024}}");
        add("wait_ticks", "Observe after 1-200 client ticks without blocking rendering. Client ticks also run while an integrated world is paused; result includes world time and pause state.", true, "ticks", "{\"ticks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":200}}");
        add("events", "Read a bounded cursor-based log of received chat/system messages and action lifecycle events. Messages are observations, not reliably correlated command acknowledgments.", true, "", "{\"after\":{\"type\":\"integer\",\"minimum\":0},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":256}}");
        add("registry_search", "Search loaded item/block/entity-type registry identifiers by substring, including modded entries. Paginated.", true, "registry", "{\"registry\":{\"type\":\"string\",\"enum\":[\"item\",\"block\",\"entity_type\"]},\"query\":{\"type\":\"string\",\"maxLength\":256},\"offset\":{\"type\":\"integer\",\"minimum\":0},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":256}}");
        add("query_server_nbt", "Query authoritative server NBT through vanilla debug queries. Requires player permission level 2; times out if unsupported. Specify kind and either x/y/z or entityId.", true, "kind", "{\"kind\":{\"type\":\"string\",\"enum\":[\"block\",\"entity\"]},\"x\":{\"type\":\"integer\",\"minimum\":-30000000,\"maximum\":30000000},\"y\":{\"type\":\"integer\",\"minimum\":-2048,\"maximum\":2048},\"z\":{\"type\":\"integer\",\"minimum\":-30000000,\"maximum\":30000000},\"entityId\":{\"type\":\"integer\"}}");
        add("set_pause_on_lost_focus", "Change the current client's pause-on-lost-focus option in memory. Set false before background control; restores original value when the bridge stops.", false, "enabled", "{\"enabled\":{\"type\":\"boolean\"}}");
    }

    private static String position() { return "{\"x\":{\"type\":\"integer\",\"minimum\":-30000000,\"maximum\":30000000},\"y\":{\"type\":\"integer\",\"minimum\":-2048,\"maximum\":2048},\"z\":{\"type\":\"integer\",\"minimum\":-30000000,\"maximum\":30000000}}"; }
    private static String coordinates() { return position().replace("integer", "number"); }
    private void add(String name, String description, boolean readOnly, String required, String properties) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", "minecraft_" + name);
        tool.addProperty("description", description);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", JsonParser.parseString(properties));
        schema.addProperty("additionalProperties", false);
        JsonArray fields = new JsonArray();
        if (!required.isEmpty()) for (String field : required.split(",")) fields.add(field);
        schema.add("required", fields);
        tool.add("inputSchema", schema);
        JsonObject annotations = new JsonObject();
        annotations.addProperty("readOnlyHint", readOnly);
        annotations.addProperty("destructiveHint", !readOnly);
        annotations.addProperty("openWorldHint", true);
        tool.add("annotations", annotations);
        definitions.put("minecraft_" + name, tool);
    }

    JsonArray list() { JsonArray list = new JsonArray(); definitions.values().forEach(t -> list.add(t.deepCopy())); return list; }
    boolean readOnly(String name) { return definitions.get(name).getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean(); }
    void validate(String name, JsonObject args) {
        JsonObject tool = definitions.get(name);
        if (tool == null) throw new IllegalArgumentException("Unknown tool: " + name);
        JsonObject schema = tool.getAsJsonObject("inputSchema");
        for (JsonElement key : schema.getAsJsonArray("required")) if (!args.has(key.getAsString())) throw new IllegalArgumentException("Missing argument: " + key.getAsString());
        JsonObject properties = schema.getAsJsonObject("properties");
        for (var entry : args.entrySet()) {
            if (!properties.has(entry.getKey())) throw new IllegalArgumentException("Unknown argument: " + entry.getKey());
            validateValue(entry.getKey(), entry.getValue(), properties.getAsJsonObject(entry.getKey()));
        }
    }
    private static void validateValue(String field, JsonElement value, JsonObject schema) {
        String type = schema.get("type").getAsString();
        boolean valid = switch (type) {
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number", "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && Double.isFinite(value.getAsDouble()) && (!type.equals("integer") || value.getAsDouble() == Math.rint(value.getAsDouble()));
            case "array" -> value.isJsonArray();
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException(field + " must be " + type);
        if (schema.has("enum") && !schema.getAsJsonArray("enum").contains(value)) throw new IllegalArgumentException("Invalid value for " + field);
        if (type.equals("string")) {
            int length = value.getAsString().length();
            if ((schema.has("minLength") && length < schema.get("minLength").getAsInt()) || (schema.has("maxLength") && length > schema.get("maxLength").getAsInt())) throw new IllegalArgumentException("Invalid length for " + field);
        }
        if (type.equals("number") || type.equals("integer")) {
            if (type.equals("integer")) {
                try { value.getAsBigDecimal().intValueExact(); }
                catch (ArithmeticException ex) { throw new IllegalArgumentException("Out of range or non-integral: " + field); }
            }
            double number = value.getAsDouble();
            if ((schema.has("minimum") && number < schema.get("minimum").getAsDouble()) || (schema.has("maximum") && number > schema.get("maximum").getAsDouble()) || (type.equals("integer") && Math.abs(number) > Integer.MAX_VALUE)) throw new IllegalArgumentException("Out of range: " + field);
        }
        if (type.equals("array")) {
            JsonArray array = value.getAsJsonArray();
            if (array.size() < schema.get("minItems").getAsInt() || array.size() > schema.get("maxItems").getAsInt()) throw new IllegalArgumentException("Invalid size for " + field);
            for (JsonElement item : array) validateValue(field + "[]", item, schema.getAsJsonObject("items"));
        }
    }
}
