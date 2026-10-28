package com.gctn.mcptest.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Serializers for data observed by the client. Call on the client thread. */
public final class GameData {
    private GameData() {}

    public static JsonObject position(Vec3 position) {
        JsonObject result = new JsonObject();
        result.addProperty("x", position.x);
        result.addProperty("y", position.y);
        result.addProperty("z", position.z);
        return result;
    }

    public static JsonObject blockPosition(BlockPos position) {
        JsonObject result = new JsonObject();
        result.addProperty("x", position.getX());
        result.addProperty("y", position.getY());
        result.addProperty("z", position.getZ());
        return result;
    }

    public static JsonObject bounds(AABB box) {
        JsonObject result = new JsonObject();
        result.add("min", position(new Vec3(box.minX, box.minY, box.minZ)));
        result.add("max", position(new Vec3(box.maxX, box.maxY, box.maxZ)));
        return result;
    }

    public static JsonObject item(Minecraft minecraft, ItemStack stack) {
        JsonObject result = new JsonObject();
        result.addProperty("empty", stack.isEmpty());
        result.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        result.addProperty("count", stack.getCount());
        if (stack.isEmpty()) {
            result.addProperty("snbt", "{}");
            return result;
        }
        result.addProperty("name", stack.getHoverName().getString());
        result.addProperty("maxStackSize", stack.getMaxStackSize());
        result.addProperty("damage", stack.getDamageValue());
        result.addProperty("maxDamage", stack.getMaxDamage());
        JsonArray tags = new JsonArray();
        stack.getTags().map(tag -> tag.location().toString()).sorted().forEach(tags::add);
        result.add("tags", tags);
        JsonArray componentTypes = new JsonArray();
        stack.getComponents().keySet().stream()
                .map(type -> String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)))
                .sorted().forEach(componentTypes::add);
        result.add("componentTypes", componentTypes);
        if (minecraft.level != null) {
            try {
                result.addProperty("snbt", stack.saveOptional(minecraft.level.registryAccess()).toString());
            } catch (RuntimeException exception) {
                result.addProperty("nbtError", error(exception));
            }
            try {
                var encoded = DataComponentMap.CODEC.encodeStart(
                        minecraft.level.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                        stack.getComponents());
                encoded.result().ifPresent(value -> result.add("components", value));
                encoded.error().ifPresent(error -> result.addProperty("componentsError", error.message()));
                result.addProperty("componentsIncludeDefaults", true);
                result.addProperty("transientComponentsSerialized", false);
            } catch (RuntimeException exception) {
                result.addProperty("componentsError", error(exception));
            }
        }
        return result;
    }

    public static JsonObject entity(Entity entity, boolean includeNbt) {
        JsonObject result = new JsonObject();
        result.addProperty("entityId", entity.getId());
        result.addProperty("uuid", entity.getUUID().toString());
        result.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        result.addProperty("name", entity.getName().getString());
        result.add("position", position(entity.position()));
        result.add("velocity", position(entity.getDeltaMovement()));
        result.addProperty("yaw", entity.getYRot());
        result.addProperty("pitch", entity.getXRot());
        result.addProperty("alive", entity.isAlive());
        result.addProperty("onGround", entity.onGround());
        result.addProperty("invisible", entity.isInvisible());
        result.add("boundingBox", bounds(entity.getBoundingBox()));
        JsonArray tags = new JsonArray();
        entity.getTags().stream().sorted().forEach(tags::add);
        result.add("scoreboardTags", tags);
        if (entity instanceof LivingEntity living) {
            result.addProperty("health", living.getHealth());
            result.addProperty("maxHealth", living.getMaxHealth());
            result.addProperty("armor", living.getArmorValue());
        }
        if (includeNbt) {
            try {
                result.addProperty("snbt", entity.saveWithoutId(new CompoundTag()).toString());
            } catch (RuntimeException exception) {
                result.addProperty("nbtError", error(exception));
            }
            result.addProperty("nbtSource", "client_observed");
            result.addProperty("nbtMayBeIncomplete", true);
        }
        return result;
    }

    static String error(RuntimeException exception) {
        return exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage());
    }
}
