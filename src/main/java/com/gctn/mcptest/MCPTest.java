package com.gctn.mcptest;

import com.gctn.mcptest.client.ClientBridge;
import com.gctn.mcptest.mcp.BridgeConfig;
import com.gctn.mcptest.mcp.McpServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;

/** Client-only entry point; dedicated servers never load client classes or open an endpoint. */
@Mod(value = MCPTest.MODID, dist = Dist.CLIENT)
public class MCPTest {
    public static final String MODID = "mcptest";
    public static final Logger LOGGER = LogUtils.getLogger();
    private ClientBridge bridge;
    private McpServer server;

    public MCPTest(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::setup);
        NeoForge.EVENT_BUS.addListener(this::preTick);
        NeoForge.EVENT_BUS.addListener(this::postTick);
        NeoForge.EVENT_BUS.addListener(this::renderFrame);
        NeoForge.EVENT_BUS.addListener(this::chatReceived);
        NeoForge.EVENT_BUS.addListener(this::loggingOut);
        NeoForge.EVENT_BUS.addListener(this::shuttingDown);
    }

    private void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                BridgeConfig config = BridgeConfig.load(FMLPaths.CONFIGDIR.get());
                if (!config.enabled()) { LOGGER.info("MCP Test is disabled in config/mcptest.json"); return; }
                bridge = new ClientBridge(Minecraft.getInstance());
                server = new McpServer(config, bridge);
                server.start();
                LOGGER.info("MCP Test listening on {}:{} at /mcp; bearer token is in config/mcptest.json", config.bindAddress(), config.port());
                if (config.bindAddress().equals("0.0.0.0")) LOGGER.info("LAN clients should connect using this computer's private IPv4 address, not 0.0.0.0");
            } catch (Exception ex) {
                if (server != null) server.close();
                if (bridge != null) bridge.close();
                server = null;
                bridge = null;
                LOGGER.error("MCP Test could not start. Check config/mcptest.json and whether the port is in use.", ex);
            }
        });
    }

    private void preTick(ClientTickEvent.Pre event) { if (bridge != null) bridge.preTick(); }
    private void postTick(ClientTickEvent.Post event) { if (bridge != null) bridge.postTick(); }
    private void renderFrame(RenderFrameEvent.Post event) { if (bridge != null) bridge.renderFrame(); }
    private void chatReceived(ClientChatReceivedEvent event) {
        if (bridge != null) bridge.message(event.isSystem() ? "system_message" : "chat_message", event.getMessage().getString());
    }
    private void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) { if (bridge != null) bridge.disconnect(); }
    private void shuttingDown(GameShuttingDownEvent event) {
        if (bridge != null) bridge.close();
        if (server != null) server.close();
    }
}
