package com.gctn.mcptest.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Locale;
import java.util.Objects;

/** Native, immediate client actions. Call only on the Minecraft client thread. */
public final class GameActions {
    private final Minecraft mc;

    public GameActions(Minecraft mc) {
        this.mc = Objects.requireNonNull(mc);
    }

    public JsonObject command(String command) {
        requireWorld();
        String normalized = Objects.requireNonNull(command, "command").strip();
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        checkText(normalized, 32767, "command");
        mc.player.connection.sendCommand(normalized);
        JsonObject result = submitted("command");
        result.addProperty("command", normalized);
        result.addProperty("serverSuccessKnown", false);
        result.addProperty("note", "Submitted through the normal client command path; inspect subsequent messages and game state for the server outcome.");
        return result;
    }

    public JsonObject chat(String message) {
        requireWorld();
        checkText(message, 256, "message");
        mc.player.connection.sendChat(message);
        JsonObject result = submitted("chat");
        result.addProperty("message", message);
        result.addProperty("serverSuccessKnown", false);
        return result;
    }

    public JsonObject look(float yaw, float pitch) {
        requireGameplay();
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90 || pitch > 90) {
            throw new IllegalArgumentException("yaw must be finite and pitch must be between -90 and 90.");
        }
        float wrappedYaw = Mth.wrapDegrees(yaw);
        mc.player.setYRot(wrappedYaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = wrappedYaw;
        mc.player.xRotO = pitch;
        mc.player.setYHeadRot(wrappedYaw);
        JsonObject result = submitted("look");
        result.addProperty("yaw", wrappedYaw);
        result.addProperty("pitch", pitch);
        return result;
    }

    public JsonObject lookAt(double x, double y, double z) {
        requireGameplay();
        finite(x, "x");
        finite(y, "y");
        finite(z, "z");
        Vec3 delta = new Vec3(x, y, z).subtract(mc.player.getEyePosition());
        if (delta.lengthSqr() < 1.0E-12) {
            throw new IllegalArgumentException("The look target must differ from the player's eye position.");
        }
        return look((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90),
                (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
    }

    public JsonObject selectSlot(int slot) {
        requireGameplay();
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("Hotbar slot must be between 0 and 8.");
        mc.player.getInventory().selected = slot;
        JsonObject result = submitted("select_slot");
        result.addProperty("selectedSlot", slot);
        result.addProperty("note", "The native game-mode tick synchronizes the held slot with the server.");
        return result;
    }

    public JsonObject drop(boolean fullStack) {
        requireGameplay();
        if (mc.player.isSpectator()) throw new IllegalStateException("Spectators cannot drop items.");
        if (mc.player.getMainHandItem().isEmpty()) throw new IllegalStateException("The selected hand is empty.");
        boolean removedLocally = mc.player.drop(fullStack);
        if (removedLocally) mc.player.swing(InteractionHand.MAIN_HAND);
        JsonObject result = submitted("drop");
        result.addProperty("fullStack", fullStack);
        result.addProperty("removedLocally", removedLocally);
        return result;
    }

    public JsonObject inventoryClick(int containerId, int slot, int button, String clickType) {
        requireContainer(containerId);
        if (mc.screen instanceof CreativeModeInventoryScreen) {
            throw new IllegalStateException("The creative inventory uses special slot synchronization; interact with its GUI instead.");
        }
        ClickType type;
        try {
            type = ClickType.valueOf(Objects.requireNonNull(clickType, "clickType").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported inventory click type.");
        }
        if (type == ClickType.QUICK_CRAFT) {
            throw new IllegalArgumentException("QUICK_CRAFT is a multi-step drag protocol; use GUI interactions or ordinary slot clicks instead.");
        }
        boolean outside = slot == -999 && type == ClickType.PICKUP;
        if (!outside && (slot < 0 || slot >= mc.player.containerMenu.slots.size())) {
            throw new IllegalArgumentException("Slot is outside the current container; -999 is allowed only for PICKUP.");
        }
        boolean validButton = switch (type) {
            case PICKUP, QUICK_MOVE, THROW, PICKUP_ALL -> button == 0 || button == 1;
            case SWAP -> button >= 0 && button <= 8 || button == 40;
            case CLONE -> button == 2;
            default -> false;
        };
        if (!validButton) throw new IllegalArgumentException("Invalid button for the selected inventory click type.");
        if (type == ClickType.CLONE && !mc.player.getAbilities().instabuild) {
            throw new IllegalStateException("CLONE requires creative mode.");
        }
        if (!outside && !mc.player.containerMenu.getSlot(slot).isActive()) {
            throw new IllegalStateException("The requested slot is not active.");
        }
        mc.gameMode.handleInventoryMouseClick(containerId, slot, button, type, mc.player);
        JsonObject result = submitted("inventory_click");
        result.addProperty("containerId", containerId);
        result.addProperty("slot", slot);
        result.addProperty("button", button);
        result.addProperty("clickType", type.name());
        return result;
    }

    public JsonObject containerButton(int containerId, int button) {
        requireContainer(containerId);
        if (button < 0 || button > 255) throw new IllegalArgumentException("Container button must be between 0 and 255.");
        mc.gameMode.handleInventoryButtonClick(containerId, button);
        JsonObject result = submitted("container_button");
        result.addProperty("containerId", containerId);
        result.addProperty("button", button);
        result.addProperty("note", "The server determines whether this menu supports the requested button.");
        return result;
    }

    public JsonObject interactBlock(BlockPos pos, Direction face, String hand, boolean requireBlockItem) {
        requireGameplay();
        InteractionHand interactionHand = hand(hand);
        if (pos == null || face == null) throw new IllegalArgumentException("A block position and face are required.");
        if (!mc.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                || mc.level.isOutsideBuildHeight(pos)) {
            throw new IllegalStateException("The target block is outside loaded client chunks or build height.");
        }
        if (!mc.level.getWorldBorder().isWithinBounds(pos)) throw new IllegalStateException("The target is outside the world border.");
        if (!mc.player.canInteractWithBlock(pos, 0)) throw new IllegalStateException("The target block is beyond interaction reach.");
        if (mc.player.isHandsBusy()) throw new IllegalStateException("The player's hands are busy.");
        if (mc.gameMode.isDestroying()) throw new IllegalStateException("Finish or cancel block mining before using an item.");
        if (requireBlockItem && mc.player.isSpectator()) throw new IllegalStateException("Spectators cannot place blocks.");
        if (requireBlockItem && !(mc.player.getItemInHand(interactionHand).getItem() instanceof BlockItem)) {
            throw new IllegalStateException("The selected hand must hold a block item for placement.");
        }
        if (!mc.player.getItemInHand(interactionHand).isItemEnabled(mc.level.enabledFeatures())) {
            throw new IllegalStateException("The held item's required feature flags are disabled.");
        }
        BlockHitResult hit = visibleBlockFace(pos, face);
        var input = ClientHooks.onClickInput(1, mc.options.keyUse, interactionHand);
        if (input.isCanceled()) {
            if (input.shouldSwingHand()) mc.player.swing(interactionHand);
            throw new IllegalStateException("A client mod canceled the use interaction.");
        }
        InteractionResult nativeResult = mc.gameMode.useItemOn(mc.player, interactionHand, hit);
        if (nativeResult.shouldSwing() && input.shouldSwingHand()) mc.player.swing(interactionHand);
        JsonObject result = interactionResult(requireBlockItem ? "place_block" : "interact_block", nativeResult);
        result.addProperty("x", pos.getX());
        result.addProperty("y", pos.getY());
        result.addProperty("z", pos.getZ());
        result.addProperty("face", face.getName());
        result.addProperty("hand", interactionHand.name());
        result.addProperty("note", "The native interaction may activate the block, place an item, or be rejected. Reinspect the affected blocks after server synchronization.");
        return result;
    }

    public JsonObject attackEntity(int id) {
        EntityHitResult hit = entityHit(id);
        if (mc.player.isSpectator()) throw new IllegalStateException("Spectators cannot attack entities.");
        if (!mc.player.getMainHandItem().isItemEnabled(mc.level.enabledFeatures())) {
            throw new IllegalStateException("The held item's required feature flags are disabled.");
        }
        var input = ClientHooks.onClickInput(0, mc.options.keyAttack, InteractionHand.MAIN_HAND);
        if (input.isCanceled()) {
            if (input.shouldSwingHand()) mc.player.swing(InteractionHand.MAIN_HAND);
            throw new IllegalStateException("A client mod canceled the attack interaction.");
        }
        mc.gameMode.attack(mc.player, hit.getEntity());
        if (input.shouldSwingHand()) mc.player.swing(InteractionHand.MAIN_HAND);
        JsonObject result = submitted("attack_entity");
        result.addProperty("entityId", id);
        result.addProperty("serverSuccessKnown", false);
        return result;
    }

    public JsonObject interactEntity(int id, String hand) {
        EntityHitResult hit = entityHit(id);
        InteractionHand interactionHand = hand(hand);
        if (!mc.player.getItemInHand(interactionHand).isItemEnabled(mc.level.enabledFeatures())) {
            throw new IllegalStateException("The held item's required feature flags are disabled.");
        }
        var input = ClientHooks.onClickInput(1, mc.options.keyUse, interactionHand);
        if (input.isCanceled()) {
            if (input.shouldSwingHand()) mc.player.swing(interactionHand);
            throw new IllegalStateException("A client mod canceled the use interaction.");
        }
        InteractionResult nativeResult = mc.gameMode.interactAt(mc.player, hit.getEntity(), hit, interactionHand);
        if (!nativeResult.consumesAction()) nativeResult = mc.gameMode.interact(mc.player, hit.getEntity(), interactionHand);
        if (nativeResult.shouldSwing() && input.shouldSwingHand()) mc.player.swing(interactionHand);
        JsonObject result = interactionResult("interact_entity", nativeResult);
        result.addProperty("entityId", id);
        result.addProperty("hand", interactionHand.name());
        return result;
    }

    public JsonObject guiClick(double x, double y, int button) {
        Screen screen = requireScreen();
        checkCoordinates(screen, x, y);
        if (button < 0 || button > 2) throw new IllegalArgumentException("Mouse button must be 0, 1, or 2.");
        screen.afterMouseAction();
        boolean pressed = ClientHooks.onScreenMouseClickedPre(screen, x, y, button);
        if (!pressed) pressed = ClientHooks.onScreenMouseClickedPost(screen, x, y, button, screen.mouseClicked(x, y, button));
        // Release the original screen even if the click opens another one; do not click through it.
        boolean released = ClientHooks.onScreenMouseReleasedPre(screen, x, y, button);
        if (!released) released = ClientHooks.onScreenMouseReleasedPost(screen, x, y, button, screen.mouseReleased(x, y, button));
        JsonObject result = submitted("gui_click");
        result.addProperty("pressHandled", pressed);
        result.addProperty("releaseHandled", released);
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("button", button);
        result.addProperty("coordinateSpace", "gui_scaled");
        return result;
    }

    public JsonObject guiScroll(double x, double y, double horizontal, double vertical) {
        Screen screen = requireScreen();
        checkCoordinates(screen, x, y);
        finite(horizontal, "horizontal");
        finite(vertical, "vertical");
        if (Math.abs(horizontal) > 100 || Math.abs(vertical) > 100) throw new IllegalArgumentException("Scroll amounts must be between -100 and 100.");
        // The convenience hook derives coordinates from the physical mouse. Use the same native
        // events with the explicitly requested coordinates instead.
        boolean handled = NeoForge.EVENT_BUS.post(new ScreenEvent.MouseScrolled.Pre(screen, x, y, horizontal, vertical)).isCanceled();
        if (!handled) {
            handled = screen.mouseScrolled(x, y, horizontal, vertical);
            if (!handled) NeoForge.EVENT_BUS.post(new ScreenEvent.MouseScrolled.Post(screen, x, y, horizontal, vertical));
        }
        screen.afterMouseAction();
        JsonObject result = submitted("gui_scroll");
        result.addProperty("handled", handled);
        return result;
    }

    public JsonObject guiKey(int key, int modifiers) {
        Screen screen = requireScreen();
        if (key < 32 || key > 348) throw new IllegalArgumentException("key must be a GLFW keyboard key code between 32 and 348.");
        if (modifiers < 0 || modifiers > 63) throw new IllegalArgumentException("modifiers must be a GLFW modifier bitmask between 0 and 63.");
        screen.afterKeyboardAction();
        boolean pressed = ClientHooks.onScreenKeyPressedPre(screen, key, 0, modifiers);
        if (!pressed) pressed = screen.keyPressed(key, 0, modifiers);
        if (!pressed) pressed = ClientHooks.onScreenKeyPressedPost(screen, key, 0, modifiers);
        boolean released = ClientHooks.onScreenKeyReleasedPre(screen, key, 0, modifiers);
        if (!released) released = screen.keyReleased(key, 0, modifiers);
        if (!released) released = ClientHooks.onScreenKeyReleasedPost(screen, key, 0, modifiers);
        JsonObject result = submitted("gui_key");
        result.addProperty("pressHandled", pressed);
        result.addProperty("releaseHandled", released);
        result.addProperty("note", "Modifier bits are passed to the screen callback; vanilla shortcuts that poll physical keyboard state require those keys to be physically held.");
        return result;
    }

    public JsonObject guiType(String text) {
        Screen screen = requireScreen();
        if (text == null || text.length() > 1024) throw new IllegalArgumentException("text must contain at most 1024 characters.");
        if (text.chars().anyMatch(c -> c < 32 || c == 127 || c == 167)) {
            throw new IllegalArgumentException("text must not contain control characters or formatting section signs; use gui_key for Enter or Tab.");
        }
        int accepted = 0;
        for (char character : text.toCharArray()) {
            if (mc.screen != screen) break;
            if (ClientHooks.onScreenCharTypedPre(screen, character, 0)) continue;
            if (screen.charTyped(character, 0)) accepted++;
            else ClientHooks.onScreenCharTypedPost(screen, character, 0);
        }
        screen.afterKeyboardAction();
        JsonObject result = submitted("gui_type");
        result.addProperty("charactersOffered", text.length());
        result.addProperty("charactersHandled", accepted);
        result.addProperty("note", "A handled character does not prove the widget retained it; inspect the screen or capture a screenshot.");
        return result;
    }

    public JsonObject closeScreen() {
        Screen screen = requireScreen();
        screen.onClose();
        JsonObject result = submitted("close_screen");
        result.addProperty("screenChanged", mc.screen != screen);
        return result;
    }

    public JsonObject openInventory() {
        requireGameplay();
        if (mc.gameMode.isServerControlledInventory()) {
            mc.player.sendOpenInventory();
        } else {
            mc.setScreen(new InventoryScreen(mc.player));
        }
        return submitted("open_inventory");
    }

    private EntityHitResult entityHit(int id) {
        requireGameplay();
        if (mc.player.isHandsBusy()) throw new IllegalStateException("The player's hands are busy.");
        Entity entity = mc.level.getEntity(id);
        if (entity == null || entity == mc.player || entity.isRemoved() || !entity.isPickable()) {
            throw new IllegalStateException("The entity is not a loaded, pickable target.");
        }
        if (!mc.level.getWorldBorder().isWithinBounds(entity.blockPosition())) throw new IllegalStateException("The entity is outside the world border.");
        if (!mc.player.canInteractWithEntity(entity, 0)) throw new IllegalStateException("The entity is beyond interaction reach.");
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = entity.getBoundingBox().getCenter();
        double reachSquared = Math.pow(mc.player.entityInteractionRange(), 2);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(mc.player, eye, end,
                new AABB(eye, end).inflate(1), candidate -> !candidate.isSpectator() && candidate.isPickable(), reachSquared);
        if (hit == null || hit.getEntity() != entity) throw new IllegalStateException("The entity is occluded by another entity or cannot be targeted.");
        BlockHitResult block = mc.level.clip(new ClipContext(eye, hit.getLocation(), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (block.getType() == HitResult.Type.BLOCK && eye.distanceToSqr(block.getLocation()) <= eye.distanceToSqr(hit.getLocation())) {
            throw new IllegalStateException("The entity is occluded by a block.");
        }
        return hit;
    }

    private BlockHitResult visibleBlockFace(BlockPos pos, Direction face) {
        Vec3 eye = mc.player.getEyePosition();
        double reachSquared = Math.pow(mc.player.blockInteractionRange(), 2);
        BlockHitResult closest = null;
        double closestDistance = Double.MAX_VALUE;
        // Use native outline boxes, so slabs and other partial blocks are not treated as full cubes.
        for (AABB box : mc.level.getBlockState(pos).getShape(mc.level, pos, CollisionContext.of(mc.player)).toAabbs()) {
            Vec3 center = box.getCenter();
            Vec3 facePoint = switch (face) {
                case DOWN -> new Vec3(center.x, box.minY, center.z);
                case UP -> new Vec3(center.x, box.maxY, center.z);
                case NORTH -> new Vec3(center.x, center.y, box.minZ);
                case SOUTH -> new Vec3(center.x, center.y, box.maxZ);
                case WEST -> new Vec3(box.minX, center.y, center.z);
                case EAST -> new Vec3(box.maxX, center.y, center.z);
            };
            facePoint = facePoint.add(pos.getX(), pos.getY(), pos.getZ());
            Vec3 end = facePoint.add(facePoint.subtract(eye).normalize().scale(0.001));
            BlockHitResult candidate = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            double distance = eye.distanceToSqr(candidate.getLocation());
            if (candidate.getType() == HitResult.Type.BLOCK && candidate.getBlockPos().equals(pos)
                    && candidate.getDirection() == face && distance <= reachSquared && distance < closestDistance) {
                closest = candidate;
                closestDistance = distance;
            }
        }
        if (closest == null) {
            throw new IllegalStateException("No reachable visible surface was found on the requested block face. Choose another face or move the player.");
        }
        return closest;
    }

    private void requireWorld() {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            throw new IllegalStateException("Join a world before using this action.");
        }
    }

    private void requireGameplay() {
        requireWorld();
        if (!mc.player.isAlive()) throw new IllegalStateException("The player must be alive.");
        if (mc.screen != null) throw new IllegalStateException("Close the current screen before using this gameplay action.");
        if (mc.isPaused()) throw new IllegalStateException("Resume the game before using this action.");
    }

    private void requireContainer(int containerId) {
        requireWorld();
        if (!(mc.screen instanceof AbstractContainerScreen<?>)) throw new IllegalStateException("Open an inventory or container screen first.");
        if (containerId != mc.player.containerMenu.containerId) throw new IllegalStateException("The container ID is stale; inspect the current inventory first.");
    }

    private Screen requireScreen() {
        if (mc.screen == null) throw new IllegalStateException("No GUI screen is open.");
        if (mc.getOverlay() != null) throw new IllegalStateException("Wait for the loading overlay to finish before interacting with the screen.");
        return mc.screen;
    }

    private static InteractionHand hand(String hand) {
        if (hand == null) throw new IllegalArgumentException("hand must be main or off.");
        return switch (hand.toLowerCase(Locale.ROOT)) {
            case "main", "main_hand" -> InteractionHand.MAIN_HAND;
            case "off", "off_hand" -> InteractionHand.OFF_HAND;
            default -> throw new IllegalArgumentException("hand must be main or off.");
        };
    }

    private static void checkCoordinates(Screen screen, double x, double y) {
        finite(x, "x");
        finite(y, "y");
        if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) {
            throw new IllegalArgumentException("Coordinates must be inside the current GUI-scaled screen dimensions.");
        }
    }

    private static void finite(double number, String name) {
        if (!Double.isFinite(number)) throw new IllegalArgumentException(name + " must be finite.");
    }

    private static void checkText(String text, int maximum, String name) {
        if (text == null || text.isBlank() || text.length() > maximum) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maximum + " characters.");
        }
        if (text.chars().anyMatch(c -> c < 32 || c == 127 || c == 167)) {
            throw new IllegalArgumentException(name + " must not contain control characters or formatting section signs.");
        }
    }

    private static JsonObject submitted(String action) {
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        result.addProperty("status", "submitted");
        result.addProperty("source", "client");
        return result;
    }

    private static JsonObject interactionResult(String action, InteractionResult nativeResult) {
        JsonObject result = submitted(action);
        result.addProperty("nativeResult", nativeResult.name());
        result.addProperty("consumesAction", nativeResult.consumesAction());
        result.addProperty("serverSuccessKnown", false);
        return result;
    }
}
