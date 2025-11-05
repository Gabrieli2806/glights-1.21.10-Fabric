package com.g2806.glights.client;

import com.g2806.glights.GLights;
import com.g2806.glights.client.config.ConfigManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.nio.file.Path;
import java.util.Optional;

public final class GLightsClient implements ClientModInitializer {
    public static ConfigManager CONFIG;
    public static LightHandler HANDLER;
    public static EventHandler EVENTS;

    @Override
    @SuppressWarnings("deprecation")
    public void onInitializeClient() {
        Minecraft client = Minecraft.getInstance();
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

        if (!CONFIG.isModEnabled() && HANDLER.isActive()) {
            HANDLER.shutdown(true);
        }

        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            if (HANDLER != null && CONFIG.isModEnabled() && HANDLER.isActive()) {
                HANDLER.initBaseLighting();
            }
        });

        SimpleSynchronousResourceReloadListener listener = new SimpleSynchronousResourceReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                HANDLER.onResourceReload();
            }

            @Override
            public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath(GLights.MOD_ID, "light_handler");
            }
        };

        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(listener);

        CONFIG.saveIfDirty();
        GLights.LOGGER.info("GLights client services initialized");
    }
}
