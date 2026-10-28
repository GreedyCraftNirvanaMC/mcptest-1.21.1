package com.gctn.mcptest.client;

import com.gctn.mcptest.MCPTest;
import com.gctn.mcptest.mcp.McpTools;
import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.ToggleKeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ClientHooks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** All game access is marshalled onto the client thread. HTTP workers never wait on that thread. */
public final class ClientBridge implements McpTools, AutoCloseable {
    private final Minecraft mc;
    private final GameInspector inspector;
    private final GameActions actions;
    private final ToolCatalog catalog = new ToolCatalog();
    private final ObservationLog log = new ObservationLog();
    private final Set<KeyMapping> held = new LinkedHashSet<>();
    private final ArrayDeque<Capture> captures = new ArrayDeque<>();
    private final AtomicInteger queued = new AtomicInteger();
    private Pending pending;
    private long clientTick;
    private boolean closed;
    private Boolean originalPauseOnLostFocus;
    private Boolean originalToggleCrouch;
    private Boolean originalToggleSprint;

    public ClientBridge(Minecraft mc) {
        this.mc = mc;
        inspector = new GameInspector(mc);
        actions = new GameActions(mc);
    }

    @Override public JsonArray listTools() { return catalog.list(); }

    @Override public CompletableFuture<JsonObject> call(String name, JsonObject arguments) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        if (queued.incrementAndGet() > 32) {
            queued.decrementAndGet();
            future.complete(error("BUSY", "Too many queued client requests."));
            return future;
        }
        future.whenComplete((value, exception) -> {
            queued.decrementAndGet();
            if (future.isCancelled()) mc.execute(() -> {
                if (pending != null && pending.future == future) cancelPending("CANCELLED", "Request cancelled; inputs released.");
                captures.removeIf(capture -> capture.future == future);
            });
        });
        mc.execute(() -> {
            if (future.isDone()) return; // Timed-out queued requests must never execute later.
            try {
                if (closed) throw new IllegalStateException("The MCP bridge is closed.");
                catalog.validate(name, arguments);
                if (pending != null && !catalog.readOnly(name) && !name.equals("minecraft_release_inputs")) {
                    future.complete(error("BUSY", "Another action is running. Wait, cancel it, or call minecraft_release_inputs."));
                    return;
                }
                dispatch(name.substring("minecraft_".length()), arguments, future);
            } catch (Exception exception) {
                if (pending == null) releaseKeys();
                future.complete(failure(exception));
            }
        });
        return future;
    }

    private void dispatch(String tool, JsonObject a, CompletableFuture<JsonObject> future) throws Exception {
        JsonObject data;
        switch (tool) {
            case "status" -> data = status();
            case "inventory" -> data = inspector.inventory();
            case "held_item" -> data = inspector.heldItem(string(a, "hand", "main"));
            case "inspect_block" -> data = inspector.block(pos(a));
            case "nearby_entities" -> data = inspector.entities(number(a, "radius", 32), integer(a, "limit", 64));
            case "inspect_entity" -> data = inspector.entity(integer(a, "entityId", 0));
            case "target" -> { if (mc.player != null) mc.gameRenderer.pick(1); data = inspector.target(); }
            case "screen" -> data = inspector.screen();
            case "events" -> data = log.read(integer(a, "after", 0), integer(a, "limit", 64));
            case "registry_search" -> data = registry(a);
            case "key_bindings" -> data = keyBindings();
            case "screenshot" -> {
                if (captures.size() >= 4) throw new IllegalStateException("Screenshot queue is full.");
                captures.add(new Capture(future, integer(a, "maxWidth", 1280)));
                return;
            }
            case "release_inputs" -> {
                cancelPending("CANCELLED", "Cancelled by minecraft_release_inputs.");
                releaseKeys();
                data = new JsonObject(); data.addProperty("released", true);
            }
            case "wait_ticks" -> { begin(tool, future, new JsonObject(), integer(a, "ticks", 1), null, null); return; }
            case "press_keys" -> {
                requirePlayable();
                JsonObject before = status();
                List<KeyMapping> requested = new ArrayList<>();
                for (JsonElement key : a.getAsJsonArray("keys")) requested.add(findKey(key.getAsString()));
                if (requested.contains(mc.options.keyAttack) && !mc.mouseHandler.isMouseGrabbed()) throw new IllegalStateException("Native attack input requires the mouse to be captured by the game.");
                holdKeys(requested, true);
                data = new JsonObject(); data.add("keys", a.get("keys").deepCopy());
                begin(tool, future, data, integer(a, "ticks", 1), null, before);
                return;
            }
            case "use_item" -> {
                requirePlayable();
                if (mc.options.keyUse.isDown() || mc.player.isUsingItem() || mc.player.isHandsBusy()) throw new IllegalStateException("Another item-use input is already active.");
                if (!mc.player.getItemInHand(hand(a)).isItemEnabled(mc.level.enabledFeatures())) throw new IllegalStateException("The held item's required feature flags are disabled.");
                JsonObject before = status();
                var input = ClientHooks.onClickInput(1, mc.options.keyUse, hand(a));
                if (input.isCanceled()) throw new IllegalStateException("A client mod canceled the use interaction.");
                try {
                    var result = mc.gameMode.useItem(mc.player, hand(a));
                    if (result.shouldSwing() && input.shouldSwingHand()) mc.player.swing(hand(a));
                    boolean sustained = mc.player.isUsingItem();
                    if (sustained) holdKeys(List.of(mc.options.keyUse), false);
                    data = new JsonObject(); data.addProperty("nativeResult", result.name());
                    data.addProperty("sustainedUse", sustained);
                    begin(tool, future, data, sustained ? integer(a, "ticks", 32) : 1, null, before);
                    pending.sustainedUse = sustained;
                    pending.useHand = hand(a);
                } catch (Exception ex) { releaseKeys(); throw ex; }
                return;
            }
            case "break_block" -> {
                requirePlayable();
                if (!mc.mouseHandler.isMouseGrabbed()) throw new IllegalStateException("Mining through native attack input requires the mouse to be captured by the game.");
                mc.gameRenderer.pick(1);
                if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) throw new IllegalStateException("Aim the crosshair at a reachable block first.");
                BlockPos target = hit.getBlockPos();
                JsonObject before = status();
                data = new JsonObject(); data.add("blockBefore", inspector.block(target));
                holdKeys(List.of(mc.options.keyAttack), true);
                begin(tool, future, data, integer(a, "ticks", 40), target, before);
                pending.originalBlock = mc.level.getBlockState(target);
                return;
            }
            case "query_server_nbt" -> { queryNbt(a, future); return; }
            default -> {
                // Keep a state snapshot before submitting a native action and observe it after a few ticks.
                JsonObject before = status();
                long cursor = log.cursor();
                BlockPos observeBlock = null;
                data = switch (tool) {
                    case "look" -> actions.look((float) number(a, "yaw", 0), (float) number(a, "pitch", 0));
                    case "look_at" -> actions.lookAt(number(a, "x", 0), number(a, "y", 0), number(a, "z", 0));
                    case "select_slot" -> actions.selectSlot(integer(a, "slot", 0));
                    case "command" -> actions.command(string(a, "command", ""));
                    case "chat" -> actions.chat(string(a, "message", ""));
                    case "interact_block", "place_block" -> actions.interactBlock(pos(a), Direction.valueOf(string(a, "face", "up").toUpperCase(Locale.ROOT)), string(a, "hand", "main"), tool.equals("place_block"));
                    case "attack_entity" -> actions.attackEntity(integer(a, "entityId", 0));
                    case "interact_entity" -> actions.interactEntity(integer(a, "entityId", 0), string(a, "hand", "main"));
                    case "drop" -> actions.drop(bool(a, "fullStack", false));
                    case "inventory_click" -> actions.inventoryClick(integer(a, "containerId", 0), integer(a, "slot", 0), integer(a, "button", 0), string(a, "clickType", "PICKUP"));
                    case "container_button" -> actions.containerButton(integer(a, "containerId", 0), integer(a, "button", 0));
                    case "open_inventory" -> actions.openInventory();
                    case "close_screen" -> actions.closeScreen();
                    case "gui_click" -> actions.guiClick(number(a, "x", 0), number(a, "y", 0), integer(a, "button", 0));
                    case "gui_scroll" -> actions.guiScroll(number(a, "x", 0), number(a, "y", 0), number(a, "horizontal", 0), number(a, "vertical", 0));
                    case "gui_key" -> actions.guiKey(integer(a, "key", 0), integer(a, "modifiers", 0));
                    case "gui_type" -> actions.guiType(string(a, "text", ""));
                    case "set_pause_on_lost_focus" -> {
                        if (originalPauseOnLostFocus == null) originalPauseOnLostFocus = mc.options.pauseOnLostFocus;
                        mc.options.pauseOnLostFocus = bool(a, "enabled", true);
                        JsonObject setting = new JsonObject(); setting.addProperty("pauseOnLostFocus", mc.options.pauseOnLostFocus); yield setting;
                    }
                    default -> throw new IllegalArgumentException("Unknown tool: " + tool);
                };
                if (tool.equals("place_block") || tool.equals("interact_block")) observeBlock = pos(a);
                begin(tool, future, data, tool.equals("command") ? 5 : 2, observeBlock, before);
                pending.cursor = cursor;
                return;
            }
        }
        future.complete(success(data));
    }

    private void begin(String name, CompletableFuture<JsonObject> future, JsonObject submitted, int ticks, BlockPos block, JsonObject before) {
        if (pending != null) throw new IllegalStateException("Another timed action is running.");
        pending = new Pending(name, future, submitted, before == null ? status() : before, clientTick, clientTick + ticks, log.cursor(), mc.level, mc.screen, block);
        log.add("action_started", name);
    }

    public void preTick() {
        if (pending == null) return;
        if (pending.future.isCancelled()) { cancelPending("CANCELLED", "Request cancelled."); return; }
        if (pending.level != mc.level) { cancelPending("WORLD_CHANGED", "World changed during the operation."); return; }
        if (!held.isEmpty() && (mc.screen != pending.screen || mc.isPaused() || mc.player == null || !mc.player.isAlive())) {
            cancelPending("INPUT_CONTEXT_CHANGED", "Screen, pause or player state changed; inputs released.");
            return;
        }
        if (inputCompleted()) { finishPending(); return; }
        if (pending.originalBlock != null) {
            mc.gameRenderer.pick(1);
            if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pending.block)) {
                cancelPending("TARGET_CHANGED", "The crosshair moved away from the original mining target; attack released.");
                return;
            }
        }
        // Owned vanilla toggle bindings temporarily use hold mode.
        held.forEach(mapping -> { if (!mapping.isDown()) mapping.setDown(true); });
    }

    public void postTick() {
        clientTick++;
        if (pending == null) return;
        if (pending.future.isDone()) { cancelPending("CANCELLED", "Request ended."); return; }
        if (inputCompleted()) { finishPending(); return; }
        if (clientTick >= pending.endTick) {
            if (pending.query) cancelPending("NBT_QUERY_TIMEOUT", "The server did not answer the vanilla NBT query. Check permissions and server support.");
            else finishPending();
        }
    }

    private boolean inputCompleted() {
        return pending != null && ((pending.sustainedUse && (mc.player == null || !mc.player.isUsingItem() || mc.player.getUsedItemHand() != pending.useHand))
                || (pending.originalBlock != null && mc.level == pending.level && !mc.level.getBlockState(pending.block).equals(pending.originalBlock)));
    }

    private void finishPending() {
        Pending action = pending;
        pending = null;
        releaseKeys();
        try {
            JsonObject result = action.submitted;
            result.addProperty("operation", action.name);
            result.addProperty("completion", "observed");
            result.addProperty("elapsedClientTicks", clientTick - action.startTick);
            result.addProperty("serverConfirmed", false);
            result.add("before", action.before);
            result.add("after", status());
            if (action.block != null && mc.level == action.level) {
                result.add("blockAfter", inspector.block(action.block));
                if (action.name.equals("place_block")) {
                    JsonObject adjacent = new JsonObject();
                    for (Direction direction : Direction.values()) {
                        BlockPos neighbor = action.block.relative(direction);
                        if (mc.level.getChunkSource().hasChunk(neighbor.getX() >> 4, neighbor.getZ() >> 4) && !mc.level.isOutsideBuildHeight(neighbor)) adjacent.add(direction.getName(), inspector.block(neighbor));
                    }
                    result.add("adjacentBlocksAfter", adjacent);
                }
            }
            log.add("action_completed", action.name);
            result.add("observations", log.read(action.cursor, 64));
            action.future.complete(success(result));
        } catch (Exception exception) { action.future.complete(failure(exception)); }
    }

    private void cancelPending(String code, String message) {
        Pending action = pending;
        pending = null;
        releaseKeys();
        if (action != null) {
            log.add("action_cancelled", action.name + ": " + message);
            JsonObject result = error(code, message);
            action.future.complete(result);
        }
    }

    private void holdKeys(Collection<KeyMapping> requested, boolean click) {
        for (KeyMapping mapping : requested) {
            if (mapping.isDown()) throw new IllegalStateException("Key is already held by another input source: " + mapping.getName());
            if (mapping.isUnbound()) throw new IllegalStateException("Bind this key in Minecraft's controls first: " + mapping.getName());
            if (!mapping.isConflictContextAndModifierActive()) throw new IllegalStateException("The key's context or physical modifier is not active: " + mapping.getName());
            if (mapping instanceof ToggleKeyMapping && mapping != mc.options.keyShift && mapping != mc.options.keySprint) throw new IllegalStateException("Custom toggle key mappings do not support bounded holds: " + mapping.getName());
        }
        for (KeyMapping mapping : new LinkedHashSet<>(requested)) {
            held.add(mapping);
            if (mapping == mc.options.keyShift) {
                originalToggleCrouch = mc.options.toggleCrouch().get();
                mc.options.toggleCrouch().set(false);
            }
            if (mapping == mc.options.keySprint) {
                originalToggleSprint = mc.options.toggleSprint().get();
                mc.options.toggleSprint().set(false);
            }
            mapping.setDown(true);
            if (click) KeyMapping.click(mapping.getKey());
        }
    }

    private void releaseKeys() {
        if (held.contains(mc.options.keyUse) && mc.player != null && mc.gameMode != null && mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
        if (held.contains(mc.options.keyAttack) && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        for (KeyMapping mapping : held) {
            mapping.setDown(false);
            while (mapping.consumeClick()) { /* Drain only keys owned by this bridge. */ }
        }
        held.clear();
        if (originalToggleCrouch != null) { mc.options.toggleCrouch().set(originalToggleCrouch); originalToggleCrouch = null; }
        if (originalToggleSprint != null) { mc.options.toggleSprint().set(originalToggleSprint); originalToggleSprint = null; }
    }

    private void requirePlayable() {
        if (mc.level == null || mc.player == null || mc.gameMode == null) throw new IllegalStateException("Join a world first.");
        if (mc.isPaused() || mc.screen != null || mc.getOverlay() != null || !mc.player.isAlive()) throw new IllegalStateException("Player must be alive, the world unpaused, and all GUI screens/loading overlays closed.");
    }

    private KeyMapping findKey(String name) {
        return Arrays.stream(mc.options.keyMappings).filter(key -> key.getName().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown key mapping: " + name + ". Use minecraft_key_bindings."));
    }

    private JsonObject keyBindings() {
        JsonArray keys = new JsonArray();
        for (KeyMapping key : mc.options.keyMappings) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", key.getName()); entry.addProperty("category", key.getCategory());
            entry.addProperty("binding", key.getKey().getName()); entry.addProperty("down", key.isDown());
            entry.addProperty("unbound", key.isUnbound());
            entry.addProperty("contextAndModifierActive", key.isConflictContextAndModifierActive());
            entry.addProperty("modifier", key.getKeyModifier().name());
            keys.add(entry);
        }
        JsonObject result = new JsonObject(); result.add("keys", keys); result.addProperty("note", "Native clicks can activate other mappings sharing the same physical binding. Unbound and inactive mappings are rejected."); return result;
    }

    private JsonObject status() {
        JsonObject result = inspector.status();
        result.addProperty("clientTick", clientTick);
        result.addProperty("eventCursor", log.cursor());
        result.addProperty("pauseOnLostFocus", mc.options.pauseOnLostFocus);
        result.addProperty("mouseCaptured", mc.mouseHandler.isMouseGrabbed());
        result.addProperty("activeOperation", pending == null ? "" : pending.name);
        JsonArray keys = new JsonArray(); held.forEach(key -> keys.add(key.getName())); result.add("agentHeldKeys", keys);
        return result;
    }

    private JsonObject registry(JsonObject a) {
        var registry = switch (string(a, "registry", "item")) {
            case "item" -> BuiltInRegistries.ITEM;
            case "block" -> BuiltInRegistries.BLOCK;
            case "entity_type" -> BuiltInRegistries.ENTITY_TYPE;
            default -> throw new IllegalArgumentException("Unknown registry.");
        };
        String query = string(a, "query", "").toLowerCase(Locale.ROOT);
        var matches = registry.keySet().stream().map(Object::toString).filter(id -> id.toLowerCase(Locale.ROOT).contains(query)).sorted().toList();
        int offset = integer(a, "offset", 0), limit = integer(a, "limit", 64);
        JsonArray entries = new JsonArray(); matches.stream().skip(offset).limit(limit).forEach(entries::add);
        JsonObject result = new JsonObject(); result.add("entries", entries); result.addProperty("total", matches.size()); result.addProperty("nextOffset", Math.min(matches.size(), (long) offset + entries.size())); return result;
    }

    private void queryNbt(JsonObject a, CompletableFuture<JsonObject> future) {
        if (pending != null) throw new IllegalStateException("Another timed action or NBT query is running.");
        if (mc.player == null || mc.level == null || mc.getConnection() == null) throw new IllegalStateException("Join a world first.");
        if (!mc.player.hasPermissions(2)) throw new IllegalStateException("Vanilla server NBT queries require permission level 2.");
        String kind = string(a, "kind", "block");
        BlockPos position = null;
        int entityId = 0;
        if (kind.equals("block")) { position = pos(a); inspector.block(position); }
        else { if (!a.has("entityId")) throw new IllegalArgumentException("entityId is required."); entityId = a.get("entityId").getAsInt(); inspector.entity(entityId); }
        begin("query_server_nbt", future, new JsonObject(), 100, null, null);
        pending.query = true;
        Consumer<CompoundTag> callback = tag -> {
            if (pending == null || pending.future != future || future.isDone()) return;
            if (pending.level != mc.level) { cancelPending("WORLD_CHANGED", "World changed during NBT query."); return; }
            pending = null;
            JsonObject result = new JsonObject();
            result.addProperty("source", "server_debug_query"); result.addProperty("serverConfirmed", true); result.addProperty("kind", kind);
            result.addProperty("hasData", tag != null);
            if (tag != null) result.addProperty("snbt", tag.toString());
            log.add("action_completed", "query_server_nbt");
            future.complete(success(result));
        };
        try {
            if (kind.equals("block")) mc.getConnection().getDebugQueryHandler().queryBlockEntityTag(position, callback);
            else mc.getConnection().getDebugQueryHandler().queryEntityTag(entityId, callback);
        } catch (Exception ex) { cancelPending("NBT_QUERY_FAILED", ex.getMessage()); }
    }

    /** Called after frame rendering, with the GL context current. */
    public void renderFrame() {
        Capture capture = captures.poll();
        if (capture == null || capture.future.isDone()) return;
        if ((long) mc.getMainRenderTarget().width * mc.getMainRenderTarget().height > 33_177_600) {
            capture.future.complete(error("SCREENSHOT_TOO_LARGE", "Framebuffer exceeds the 8K pixel budget."));
            return;
        }
        try (NativeImage original = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int width = original.getWidth(), height = original.getHeight();
            if ((long) width * height > 33_177_600) throw new IllegalStateException("Framebuffer exceeds the 8K pixel budget.");
            byte[] png;
            int outputWidth = width, outputHeight = height;
            if (capture.maxWidth > 0 && width > capture.maxWidth) {
                outputWidth = capture.maxWidth;
                outputHeight = Math.max(1, (int) ((long) height * outputWidth / width));
                try (NativeImage resized = new NativeImage(original.format(), outputWidth, outputHeight, false)) {
                    original.resizeSubRectTo(0, 0, width, height, resized);
                    png = resized.asByteArray();
                }
            } else png = original.asByteArray();
            if (png.length > 16 * 1024 * 1024) throw new IllegalStateException("Encoded screenshot exceeds 16 MiB. Request a smaller maxWidth.");
            JsonObject data = new JsonObject();
            data.addProperty("width", outputWidth); data.addProperty("height", outputHeight);
            data.addProperty("framebufferWidth", width); data.addProperty("framebufferHeight", height);
            data.addProperty("guiWidth", mc.getWindow().getGuiScaledWidth()); data.addProperty("guiHeight", mc.getWindow().getGuiScaledHeight());
            data.addProperty("clientTick", clientTick); data.addProperty("mimeType", "image/png");
            JsonObject result = success(data);
            JsonObject image = new JsonObject(); image.addProperty("type", "image"); image.addProperty("mimeType", "image/png"); image.addProperty("data", Base64.getEncoder().encodeToString(png));
            result.getAsJsonArray("content").add(image);
            capture.future.complete(result);
        } catch (Exception ex) { capture.future.complete(failure(ex)); }
    }

    public void message(String kind, String message) { log.add(kind, message); }
    public void disconnect() { cancelPending("DISCONNECTED", "Disconnected from the world; inputs released."); }
    @Override public void close() {
        closed = true;
        cancelPending("SHUTDOWN", "MCP bridge stopped.");
        captures.forEach(capture -> capture.future.complete(error("SHUTDOWN", "MCP bridge stopped.")));
        captures.clear();
        if (originalPauseOnLostFocus != null) mc.options.pauseOnLostFocus = originalPauseOnLostFocus;
    }

    private static JsonObject success(JsonObject data) {
        data.addProperty("ok", true);
        return envelope(data, false);
    }
    private static JsonObject error(String code, String message) {
        JsonObject data = new JsonObject(); data.addProperty("ok", false); data.addProperty("code", code); data.addProperty("message", message == null ? code : message); return envelope(data, true);
    }
    private static JsonObject failure(Exception exception) {
        if (!(exception instanceof IllegalArgumentException) && !(exception instanceof IllegalStateException)) MCPTest.LOGGER.warn("MCP client operation failed", exception);
        return error(exception instanceof IllegalArgumentException ? "INVALID_ARGUMENT" : exception instanceof IllegalStateException ? "PRECONDITION_FAILED" : "CLIENT_ERROR", exception.getMessage());
    }
    private static JsonObject envelope(JsonObject data, boolean isError) {
        JsonObject result = new JsonObject(); JsonArray content = new JsonArray(); JsonObject text = new JsonObject(); text.addProperty("type", "text"); text.addProperty("text", data.toString()); content.add(text); result.add("content", content); result.add("structuredContent", data); result.addProperty("isError", isError); return result;
    }
    private static String string(JsonObject a, String key, String fallback) { return a.has(key) ? a.get(key).getAsString() : fallback; }
    private static int integer(JsonObject a, String key, int fallback) { return a.has(key) ? a.get(key).getAsInt() : fallback; }
    private static double number(JsonObject a, String key, double fallback) { return a.has(key) ? a.get(key).getAsDouble() : fallback; }
    private static boolean bool(JsonObject a, String key, boolean fallback) { return a.has(key) ? a.get(key).getAsBoolean() : fallback; }
    private static BlockPos pos(JsonObject a) { for (String axis : List.of("x", "y", "z")) if (!a.has(axis)) throw new IllegalArgumentException(axis + " is required."); return new BlockPos(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt()); }
    private static InteractionHand hand(JsonObject a) { return string(a, "hand", "main").equals("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND; }
    private record Capture(CompletableFuture<JsonObject> future, int maxWidth) {}
    private static final class Pending {
        final String name; final CompletableFuture<JsonObject> future; final JsonObject submitted; final JsonObject before;
        final long startTick; final long endTick; long cursor; final ClientLevel level; final Screen screen; final BlockPos block; boolean query; boolean sustainedUse; InteractionHand useHand; BlockState originalBlock;
        Pending(String name, CompletableFuture<JsonObject> future, JsonObject submitted, JsonObject before, long startTick, long endTick, long cursor, ClientLevel level, Screen screen, BlockPos block) {
            this.name = name; this.future = future; this.submitted = submitted; this.before = before; this.startTick = startTick; this.endTick = endTick; this.cursor = cursor; this.level = level; this.screen = screen; this.block = block;
        }
    }
}
