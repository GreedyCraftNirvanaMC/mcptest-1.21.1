package com.gctn.mcptest.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** Read-only observations. The caller schedules every method on the client thread. */
public final class GameInspector {
    private static final int MAX_WIDGETS = 256;
    private final Minecraft minecraft;

    public GameInspector(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    public JsonObject status() {
        JsonObject result = new JsonObject();
        result.addProperty("connected", minecraft.getConnection() != null);
        result.addProperty("inWorld", minecraft.level != null && minecraft.player != null);
        result.addProperty("paused", minecraft.isPaused());
        result.addProperty("windowFocused", minecraft.isWindowActive());
        result.addProperty("mouseGrabbed", minecraft.mouseHandler.isMouseGrabbed());
        result.addProperty("integratedServer", minecraft.hasSingleplayerServer());
        result.add("screen", screen());
        if (minecraft.level != null) {
            JsonObject world = new JsonObject();
            world.addProperty("dimension", minecraft.level.dimension().location().toString());
            world.addProperty("gameTime", minecraft.level.getGameTime());
            world.addProperty("dayTime", minecraft.level.getDayTime());
            world.addProperty("raining", minecraft.level.isRaining());
            world.addProperty("thundering", minecraft.level.isThundering());
            world.addProperty("difficulty", minecraft.level.getDifficulty().name().toLowerCase(Locale.ROOT));
            world.addProperty("minBuildHeight", minecraft.level.getMinBuildHeight());
            world.addProperty("maxBuildHeight", minecraft.level.getMaxBuildHeight());
            result.add("world", world);
        }
        if (minecraft.player != null) {
            var player = minecraft.player;
            JsonObject observed = GameData.entity(player, false);
            observed.add("eyePosition", GameData.position(player.getEyePosition()));
            observed.addProperty("food", player.getFoodData().getFoodLevel());
            observed.addProperty("saturation", player.getFoodData().getSaturationLevel());
            observed.addProperty("air", player.getAirSupply());
            observed.addProperty("experienceLevel", player.experienceLevel);
            observed.addProperty("experienceProgress", player.experienceProgress);
            observed.addProperty("selectedHotbarSlot", player.getInventory().selected);
            observed.addProperty("sprinting", player.isSprinting());
            observed.addProperty("crouching", player.isCrouching());
            observed.addProperty("usingItem", player.isUsingItem());
            observed.addProperty("flying", player.getAbilities().flying);
            observed.addProperty("mayFly", player.getAbilities().mayfly);
            observed.addProperty("blockInteractionRange", player.blockInteractionRange());
            observed.addProperty("entityInteractionRange", player.entityInteractionRange());
            if (minecraft.gameMode != null) {
                observed.addProperty("gameMode", minecraft.gameMode.getPlayerMode().getName());
            }
            result.add("player", observed);
            result.add("target", target());
        }
        result.addProperty("dataSource", "client_observed");
        return result;
    }

    public JsonObject inventory() {
        requireWorld();
        JsonObject result = new JsonObject();
        var inventory = minecraft.player.getInventory();
        result.addProperty("selectedHotbarSlot", inventory.selected);
        JsonArray items = new JsonArray();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            JsonObject slot = new JsonObject();
            slot.addProperty("inventoryIndex", index);
            slot.addProperty("section", index < 9 ? "hotbar" : index < 36 ? "main" : index < 40 ? "armor" : "offhand");
            slot.add("item", GameData.item(minecraft, inventory.getItem(index)));
            items.add(slot);
        }
        result.add("inventorySlots", items);
        result.add("menu", menu(minecraft.player.containerMenu));
        result.addProperty("dataSource", "client_observed");
        return result;
    }

    public JsonObject heldItem(String hand) {
        requireWorld();
        InteractionHand parsed = switch (hand.toLowerCase(Locale.ROOT)) {
            case "main", "main_hand" -> InteractionHand.MAIN_HAND;
            case "off", "off_hand" -> InteractionHand.OFF_HAND;
            default -> throw new IllegalArgumentException("hand must be main or off");
        };
        JsonObject result = new JsonObject();
        result.addProperty("hand", parsed == InteractionHand.MAIN_HAND ? "main" : "off");
        result.add("item", GameData.item(minecraft, minecraft.player.getItemInHand(parsed)));
        result.addProperty("dataSource", "client_observed");
        return result;
    }

    public JsonObject block(BlockPos position) {
        requireWorld();
        if (minecraft.level.isOutsideBuildHeight(position)) {
            throw new IllegalArgumentException("Position is outside the world's build height");
        }
        // ClientLevel.hasChunk() is always true; check the actual client chunk cache.
        if (!minecraft.level.getChunkSource().hasChunk(position.getX() >> 4, position.getZ() >> 4)) {
            throw new IllegalArgumentException("The block's chunk is not loaded on the client");
        }
        BlockState state = minecraft.level.getBlockState(position);
        JsonObject result = new JsonObject();
        result.add("position", GameData.blockPosition(position));
        result.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        result.addProperty("air", state.isAir());
        JsonObject properties = new JsonObject();
        state.getProperties().stream().sorted(Comparator.comparing(Property::getName))
                .forEach(property -> properties.addProperty(property.getName(), propertyValue(state, property)));
        result.add("properties", properties);
        JsonArray tags = new JsonArray();
        state.getTags().map(tag -> tag.location().toString()).sorted().forEach(tags::add);
        result.add("tags", tags);
        result.addProperty("fluid", BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString());
        result.addProperty("destroySpeed", state.getDestroySpeed(minecraft.level, position));
        result.addProperty("withinReach", minecraft.player.canInteractWithBlock(position, 0.0));
        result.addProperty("hasBlockEntity", state.hasBlockEntity());
        BlockEntity blockEntity = minecraft.level.getBlockEntity(position);
        if (blockEntity != null) {
            result.addProperty("blockEntityType", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString());
            try {
                result.addProperty("snbt", blockEntity.saveWithFullMetadata(minecraft.level.registryAccess()).toString());
            } catch (RuntimeException exception) {
                result.addProperty("nbtError", GameData.error(exception));
            }
        } else {
            result.add("snbt", JsonNull.INSTANCE);
        }
        result.addProperty("nbtSource", "client_observed");
        result.addProperty("nbtMayBeIncomplete", true);
        return result;
    }

    public JsonObject entities(double radius, int limit) {
        requireWorld();
        if (!Double.isFinite(radius) || radius < 0 || radius > 128 || limit < 1 || limit > 256) {
            throw new IllegalArgumentException("radius must be 0..128 and limit must be 1..256");
        }
        List<Entity> nearby = new ArrayList<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity.distanceToSqr(minecraft.player) <= radius * radius) nearby.add(entity);
        }
        nearby.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(minecraft.player)));
        JsonObject result = new JsonObject();
        result.addProperty("radius", radius);
        result.addProperty("totalInRadius", nearby.size());
        result.addProperty("truncated", nearby.size() > limit);
        JsonArray entities = new JsonArray();
        for (Entity entity : nearby.subList(0, Math.min(limit, nearby.size()))) {
            JsonObject observed = GameData.entity(entity, false);
            observed.addProperty("distance", entity.distanceTo(minecraft.player));
            entities.add(observed);
        }
        result.add("entities", entities);
        result.addProperty("dataSource", "client_observed");
        return result;
    }

    public JsonObject entity(int entityId) {
        requireWorld();
        Entity entity = minecraft.level.getEntity(entityId);
        if (entity == null) throw new IllegalArgumentException("No client-loaded entity with ID " + entityId);
        JsonObject result = GameData.entity(entity, true);
        result.addProperty("distance", entity.distanceTo(minecraft.player));
        result.addProperty("withinReach", minecraft.player.canInteractWithEntity(entity, 0.0));
        return result;
    }

    public JsonObject screen() {
        JsonObject result = new JsonObject();
        result.addProperty("open", minecraft.screen != null);
        result.addProperty("guiWidth", minecraft.getWindow().getGuiScaledWidth());
        result.addProperty("guiHeight", minecraft.getWindow().getGuiScaledHeight());
        result.addProperty("framebufferWidth", minecraft.getWindow().getWidth());
        result.addProperty("framebufferHeight", minecraft.getWindow().getHeight());
        result.addProperty("guiScale", minecraft.getWindow().getGuiScale());
        result.addProperty("coordinateSpace", "gui_scaled_pixels");
        if (minecraft.screen != null) {
            result.addProperty("class", minecraft.screen.getClass().getName());
            result.addProperty("title", minecraft.screen.getTitle().getString());
            result.addProperty("pausesGame", minecraft.screen.isPauseScreen());
            JsonArray widgets = new JsonArray();
            collectWidgets(minecraft.screen.children(), "", widgets, 0);
            result.add("widgets", widgets);
            result.addProperty("widgetLimit", MAX_WIDGETS);
            result.addProperty("widgetsMayBeTruncated", widgets.size() == MAX_WIDGETS);
            if (minecraft.screen instanceof AbstractContainerScreen<?> container) {
                result.addProperty("containerLeft", container.getGuiLeft());
                result.addProperty("containerTop", container.getGuiTop());
                result.addProperty("containerWidth", container.getXSize());
                result.addProperty("containerHeight", container.getYSize());
                result.add("menu", menu(container.getMenu()));
            }
        }
        return result;
    }

    public JsonObject target() {
        JsonObject result = new JsonObject();
        HitResult hit = minecraft.hitResult;
        if (hit == null) {
            result.addProperty("type", "none");
            return result;
        }
        result.addProperty("type", hit.getType().name().toLowerCase(Locale.ROOT));
        result.add("location", GameData.position(hit.getLocation()));
        if (minecraft.player != null) {
            result.addProperty("distance", minecraft.player.getEyePosition().distanceTo(hit.getLocation()));
        }
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            result.add("blockPosition", GameData.blockPosition(blockHit.getBlockPos()));
            result.addProperty("face", blockHit.getDirection().getName());
            result.addProperty("inside", blockHit.isInside());
        } else if (hit instanceof EntityHitResult entityHit) {
            result.add("entity", GameData.entity(entityHit.getEntity(), false));
        }
        return result;
    }

    private JsonObject menu(AbstractContainerMenu menu) {
        JsonObject result = new JsonObject();
        result.addProperty("containerId", menu.containerId);
        result.addProperty("stateId", menu.getStateId());
        result.addProperty("class", menu.getClass().getName());
        try {
            result.addProperty("type", BuiltInRegistries.MENU.getKey(menu.getType()).toString());
        } catch (UnsupportedOperationException exception) {
            result.add("type", JsonNull.INSTANCE);
        }
        result.add("carried", GameData.item(minecraft, menu.getCarried()));
        JsonArray slots = new JsonArray();
        for (Slot slot : menu.slots) {
            JsonObject observed = new JsonObject();
            observed.addProperty("slotId", slot.index);
            observed.addProperty("containerSlot", slot.getContainerSlot());
            observed.addProperty("x", slot.x);
            observed.addProperty("y", slot.y);
            if (minecraft.screen instanceof AbstractContainerScreen<?> container && container.getMenu() == menu) {
                observed.addProperty("guiX", container.getGuiLeft() + slot.x);
                observed.addProperty("guiY", container.getGuiTop() + slot.y);
            }
            observed.addProperty("active", slot.isActive());
            observed.addProperty("maxStackSize", slot.getMaxStackSize());
            observed.add("item", GameData.item(minecraft, slot.getItem()));
            slots.add(observed);
        }
        result.add("slots", slots);
        return result;
    }

    private void collectWidgets(List<? extends GuiEventListener> children, String path, JsonArray output, int depth) {
        if (depth > 8) return;
        for (int index = 0; index < children.size() && output.size() < MAX_WIDGETS; index++) {
            GuiEventListener child = children.get(index);
            String childPath = path.isEmpty() ? Integer.toString(index) : path + "." + index;
            if (child instanceof AbstractWidget widget) {
                JsonObject observed = new JsonObject();
                observed.addProperty("path", childPath);
                observed.addProperty("class", child.getClass().getName());
                observed.addProperty("message", widget.getMessage().getString());
                observed.addProperty("x", widget.getX());
                observed.addProperty("y", widget.getY());
                observed.addProperty("width", widget.getWidth());
                observed.addProperty("height", widget.getHeight());
                observed.addProperty("active", widget.active);
                observed.addProperty("visible", widget.visible);
                observed.addProperty("focused", widget.isFocused());
                if (widget instanceof EditBox editBox) observed.addProperty("value", editBox.getValue());
                output.add(observed);
            }
            if (child instanceof ContainerEventHandler container) {
                collectWidgets(container.children(), childPath, output, depth + 1);
            }
        }
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private void requireWorld() {
        if (minecraft.player == null || minecraft.level == null) {
            throw new IllegalStateException("Join a world before inspecting game data");
        }
    }
}
