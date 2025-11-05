package com.g2806.glights.client;

import com.g2806.glights.GLights;
import com.g2806.glights.client.config.ConfigManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.Optional;

public final class GLightsClient implements ClientModInitializer {
    public static ConfigManager CONFIG;
    public static LightHandler HANDLER;
    public static EventHandler EVENTS;

    @Override
    @SuppressWarnings("deprecation")
    public void onInitializeClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(GLights.MOD_ID + ".json");

        CONFIG = new ConfigManager(configPath);
        CONFIG.load();

        Optional<LightHandler> handler = LightHandler.create(client, CONFIG);
        if (handler.isEmpty()) {
            CONFIG.saveIfDirty();
            GLights.LOGGER.warn("Logitech LED SDK unavailable; disabling GLights integration.");
            return;
        }

        HANDLER = handler.get();

        EVENTS = new EventHandler(client, HANDLER, CONFIG);
        EVENTS.register();

            ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
                if (HANDLER != null && HANDLER.isActive()) {
                    HANDLER.initBaseLighting();
                }
            });

        SimpleSynchronousResourceReloadListener listener = new SimpleSynchronousResourceReloadListener() {
            @Override
            public void reload(ResourceManager manager) {
                HANDLER.onResourceReload();
            }

            @Override
            public Identifier getFabricId() {
                return Identifier.of(GLights.MOD_ID, "light_handler");
            }
        };

        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(listener);

        GLights.LOGGER.info("GLights client services initialized");
    }
}
