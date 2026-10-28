# MCP tool reference

All tools use the `minecraft_` prefix. `tools/list` is the authoritative machine-readable catalog. Unknown arguments, wrong JSON types, and values outside each schema's bounds are rejected. Game access runs on the client thread, with screenshot capture after rendering.

## Transport and results

The endpoint is `http://127.0.0.1:8765/mcp` by default. Send `Authorization: Bearer <token>`, `Content-Type: application/json`, and `Accept: application/json, text/event-stream` on POST requests.

1. Send a JSON-RPC `initialize` request with a protocol version, `capabilities: {}`, and `clientInfo` containing `name` and `version`.
2. Store the response's `Mcp-Session-Id` HTTP header and negotiated `protocolVersion`.
3. Include `Mcp-Session-Id` and `Mcp-Protocol-Version` on subsequent requests, then send the `notifications/initialized` notification.
4. Call `tools/list` or `tools/call`. Send HTTP `DELETE` with the session headers when finished.

Supported revisions are `2025-11-25` and `2025-06-18`. Responses use JSON; GET/SSE, JSON-RPC batches, and task-augmented calls are not supported. Sessions expire after 30 minutes without requests. An expired session requires initialization again.

Example call after initialization:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "minecraft_inspect_block",
    "arguments": { "x": 0, "y": 64, "z": 0 }
  }
}
```

Tool results contain `content` blocks, `isError`, and `structuredContent`. Text content also carries the JSON result. Screenshots additionally contain a base64 PNG `image` block. Inspect `isError` even when the HTTP status is 200. Transport/JSON-RPC errors and game-operation errors are separate failure layers.

Action results describe submission or local observations. Verify an action through later inspection or a screenshot; server rejection can arrive after a local prediction. The event log records observed messages and lifecycle events, without guaranteed correlation between a command and a message.

To cancel an outstanding call, send a notification in the same session with its original request ID:

```json
{
  "jsonrpc": "2.0",
  "method": "notifications/cancelled",
  "params": { "requestId": 2, "reason": "Stop the current action" }
}
```

Cancellation stops remaining queued/timed work and releases bridge-owned input; it cannot undo completed gameplay changes. HTTP connection closure is not cancellation. Session deletion cancels that session's pending calls. `minecraft_release_inputs` explicitly stops active controller input.

## Inspection

Required fields appear without brackets. Square brackets indicate optional fields, not JSON syntax.

| Tool | Arguments | Returned information |
| --- | --- | --- |
| `minecraft_status` | `{}` | Connection, pause/focus state, dimensions/time, player position/rotation, health/food, game mode, held-use state, selected hotbar slot, screen and crosshair target. |
| `minecraft_inventory` | `{}` | Inventory indices, item stacks, current menu `containerId`/`stateId`, clickable `slotId` values, and carried stack. |
| `minecraft_held_item` | `[hand]` | `main` or `off`; item registry ID, count, name, durability, tags, SNBT, component types and persistent component values. |
| `minecraft_inspect_block` | `x, y, z` | Loaded block ID, state properties, registry tags, fluid type, reach information, and client-observed block-entity SNBT. Unloaded chunks and invalid build heights are rejected. |
| `minecraft_nearby_entities` | `[radius, limit]` | Nearest client-tracked entities, positions, distances, health where applicable, and truncation information. Radius 0–128; limit 1–256. |
| `minecraft_inspect_entity` | `entityId` | Client entity details, scoreboard tags, bounding box, reach information and client-observed SNBT. |
| `minecraft_target` | `{}` | Current crosshair hit type, world position, block face or entity, and distance. |
| `minecraft_screen` | `{}` | Screen title/class, GUI/framebuffer dimensions, widget descriptions and coordinates, and menu slots where available. |
| `minecraft_screenshot` | `[maxWidth]` | Next rendered frame as PNG image content plus metadata. Width cap 0–3840; default 1280; 0 preserves native size. Framebuffer limit: 33,177,600 pixels (7680×4320); PNG limit: 16 MiB. |
| `minecraft_key_bindings` | `{}` | Registered vanilla and mod key-mapping names usable with `press_keys`. |
| `minecraft_registry_search` | `registry, [query, offset, limit]` | Paginated registry ID matches. Registry is `item`, `block`, or `entity_type`; substring query up to 256 characters, offset ≥0, limit 1–256. |
| `minecraft_events` | `[after, limit]` | Bounded log of received chat/system messages and action lifecycle observations. Supply the returned cursor on subsequent reads; limit 1–256. |
| `minecraft_query_server_nbt` | `kind`, plus `x,y,z` or `entityId` | Vanilla server debug NBT query. `kind` is `block` or `entity`; permission level 2 is required. Queries are serialized and time out if no response arrives. |

For item data, `snbt` contains the full persistent stack representation, while `components` includes persistent defaults as well as overrides. `componentTypes` also identifies transient component types, whose values the persistence codec omits. Empty stacks have `empty: true` and `{}` SNBT. Serialization errors from a modded component are reported in `nbtError` or `componentsError` where possible.

Client block/entity NBT includes `nbtSource: "client_observed"` and `nbtMayBeIncomplete: true`. Do not interpret absent fields as proof that the server lacks them. Server debug-query NBT follows vanilla serialization and may omit identifying metadata; keep the queried position/entity ID with the result.

## Gameplay control

Gameplay mutations use one controller at a time. Long-running input or query operations may report busy when another incompatible operation is pending. Inspect state before retrying a timeout, because an already applied action cannot be rolled back.

| Tool | Arguments | Behavior |
| --- | --- | --- |
| `minecraft_press_keys` | `keys, [ticks]` | Hold 1–16 mapping names together for 1–200 client ticks. Example: `{"keys":["key.forward","key.jump"],"ticks":10}`. Requires an unpaused world and no open screen; attack requires captured mouse. |
| `minecraft_release_inputs` | `{}` | Cancel pending controller action and release bridge-owned inputs. |
| `minecraft_use_item` | `[hand, ticks]` | Use selected item in air; sustain food/bow/shield use for 1–200 ticks, then release. Hand is `main` or `off`. |
| `minecraft_look` | `yaw, pitch` | Set normal player rotation. Yaw −360000…360000 degrees; pitch −90…90 degrees. |
| `minecraft_look_at` | `x, y, z` | Aim at a world-space point; coordinates may be fractional. |
| `minecraft_select_slot` | `slot` | Select hotbar slot 0–8. |
| `minecraft_command` | `command` | Submit a 1–32767-character command as the connected player using ordinary permissions. Inspect subsequent state/messages for success. |
| `minecraft_chat` | `message` | Send a 1–256-character chat message as the player. |
| `minecraft_interact_block` | `x,y,z,face, [hand]` | Native right-click of a reachable, visible loaded block face. |
| `minecraft_place_block` | `x,y,z,face, [hand]` | Use the held block item against a reachable, visible support face. Coordinates identify the clicked block, not necessarily the resulting placement position. Inspect the world afterward. |
| `minecraft_break_block` | `[ticks]` | Mine the current crosshair block using native attack for at most 1–200 ticks. Requires captured mouse, reach, and no screen. A hold ending does not prove destruction. |
| `minecraft_attack_entity` | `entityId` | Native attack against a reachable, visible client-tracked entity. |
| `minecraft_interact_entity` | `entityId, [hand]` | Native right-click interaction against a reachable, visible entity. |
| `minecraft_drop` | `[fullStack]` | Drop one selected item or its full stack. |
| `minecraft_wait_ticks` | `ticks` | Wait 1–200 client ticks while rendering continues, then observe state. Client ticks can advance while integrated-world time is paused. |
| `minecraft_set_pause_on_lost_focus` | `enabled` | Temporarily change the client's focus-loss pause option; original value restores when the bridge stops. This does not close an existing pause screen. |

Block faces are `up`, `down`, `north`, `south`, `east`, or `west`. Integer block coordinates are bounded to X/Z ±30,000,000 and Y ±2048 by the schemas; the world's actual build-height limits also apply. Placement and use follow native game/mod behavior, including server permission checks and possible rejection.

`use_item` makes one direct item-use attempt; it holds use only while that chosen hand is actively using the item, stopping early when use finishes. `break_block` stops when the original block changes, and cancels if the crosshair leaves the original target. `press_keys` can intentionally perform repeated native use/attack input.

Key holds reject unbound mappings, inactive key contexts/modifiers, and keys already held by another input source. Vanilla crouch/sprint temporarily use hold mode and restore their toggle preferences afterward. Custom toggle mappings are rejected. Native clicks can activate other mappings that share the same physical binding; inspect `key_bindings` before using mod key mappings.

## Menus and GUI

| Tool | Arguments | Behavior |
| --- | --- | --- |
| `minecraft_inventory_click` | `containerId, slot, [button, clickType]` | Native menu click. Read fresh IDs from `inventory`; player inventory indices and menu slot IDs are different. Types: `PICKUP`, `QUICK_MOVE`, `SWAP`, `CLONE`, `THROW`, `PICKUP_ALL`. Supported button values depend on click type. `-999` means outside the menu where supported. |
| `minecraft_container_button` | `containerId, button` | Submit a native menu button action, such as an enchanting choice. Button 0–255; meaning depends on the menu. |
| `minecraft_open_inventory` | `{}` | Open the normal player inventory. |
| `minecraft_close_screen` | `{}` | Close through the current screen's native handler. |
| `minecraft_gui_click` | `x, y, [button]` | Click and release in GUI-scaled coordinates. Buttons 0=left, 1=right, 2=middle. |
| `minecraft_gui_scroll` | `x, y, vertical, [horizontal]` | Scroll at GUI-scaled coordinates; deltas −100…100. |
| `minecraft_gui_key` | `key, [modifiers]` | Press and release a GLFW key in the current screen. Enter=257, Escape=256, Backspace=259, Tab=258. Modifier mask: Shift=1, Control=2, Alt=4, Super=8, Caps Lock=16, Num Lock=32. |
| `minecraft_gui_type` | `text` | Send native character events to the focused field; at most 1024 characters. Focus a field first. |

Widget coordinates and `guiX`/`guiY` slot positions are GUI-scaled pixels. A slot is normally 16×16 pixels; its returned GUI position is the upper-left corner. Use its center when clicking visually. `slotId` with `inventory_click` avoids coordinate conversion.

Screenshots may be resized. Convert screenshot coordinates using the returned image dimensions and `screen.guiWidth`/`screen.guiHeight`, or use inspected GUI coordinates directly. Widgets can change after every action, and custom modded screens need not expose every control through the standard widget hierarchy. GUI character/key methods do not emulate an operating-system keyboard or guarantee that custom code polling physical modifier keys observes supplied modifiers.
