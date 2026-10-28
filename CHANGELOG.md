# Changelog

## Unreleased

- Add opt-in IPv4 LAN access through `bindAddress`, retaining localhost-only defaults and bearer authentication.
- Validate LAN peers and destination Host/Origin headers, and add `--host` to the Python diagnostic client.

## 1.0.0

- Add an authenticated, loopback-only MCP Streamable HTTP endpoint for the Minecraft 1.21.1 NeoForge client.
- Add client/world, item component and SNBT, block-state, entity, inventory, GUI, registry, and event inspection.
- Add native gameplay and menu controls, bounded key holds, cancellation, and input cleanup.
- Return screenshots as MCP PNG image content with structured operation results.
- Add permission-checked vanilla server NBT queries and an optional standard-library Python diagnostic client.
