package com.g2806.glights.client;

import com.g2806.glights.client.config.ConfigManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;

import java.util.Arrays;

public final class EventHandler {
    private enum SpecialEffect {
        NONE,
        DAMAGE_FLASH,
        UNDERWATER,
        POISON,
        FROZEN
    }

    private final Minecraft client;
    private final LightHandler handler;
    private final ConfigManager config;
    private final int[] hotbarScanCodes = new int[9];

    private boolean hotbarInitialized;
    private boolean hotbarPrimed;
    private boolean windowFocused = true;
    private boolean dead;
    private int lastSelectedSlot = -1;
    private SpecialEffect activeEffect = SpecialEffect.NONE;
    private int damageFlashTicks;

    public EventHandler(Minecraft client, LightHandler handler, ConfigManager config) {
        this.client = client;
        this.handler = handler;
        this.config = config;
        Arrays.fill(hotbarScanCodes, -1);
        hotbarPrimed = false;

        handler.addRestartCallback(this::onHandlerRestart);
    }

    public void register() {
        ClientTickEvents.START_CLIENT_TICK.register(this::onClientTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, clientInstance) -> onJoin(handler, sender, clientInstance));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, clientInstance) -> onDisconnect(handler, clientInstance));
    }

    private void onClientTick(Minecraft ignored) {
        handleFocus();

        if (!handler.isActive()) {
            return;
        }

        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        ensureHotbarCodes();
        handleDeathState(player);
        if (!dead) {
            updateSpecialEffects(player);
        } else {
            clearSpecialEffects(false);
        }

        if (activeEffect == SpecialEffect.NONE) {
            handleSelectedSlot(player);
        }
    }

    private void handleFocus() {
        boolean focused = client.isWindowActive();
        if (windowFocused && !focused) {
            windowFocused = false;
            handler.shutdown(true);
        } else if (!windowFocused && focused) {
            if (handler.restart(true)) {
                windowFocused = true;
                if (dead) {
                    handler.setSolidColor(config.getColorForCategory(ConfigManager.CATEGORY_DEAD));
                }
            }
        }
    }

    private void ensureHotbarCodes() {
        if (hotbarInitialized) {
            if (config.isHotbarAlwaysVisible() && !hotbarPrimed) {
                refreshHotbarBaseColors();
            }
            return;
        }
        KeyMapping[] bindings = client.options.keyHotbarSlots;
        for (int i = 0; i < bindings.length && i < hotbarScanCodes.length; i++) {
            hotbarScanCodes[i] = handler.resolveScanCode(bindings[i]);
        }
        hotbarInitialized = true;
        if (config.isHotbarAlwaysVisible()) {
            refreshHotbarBaseColors();
        }
    }

    private void handleDeathState(LocalPlayer player) {
        boolean currentlyDead = isPlayerDead(player);
        if (!dead && currentlyDead) {
            dead = true;
            clearSpecialEffects(false);
            handler.saveCurrentLighting();
            handler.setSolidColor(config.getColorForCategory(ConfigManager.CATEGORY_DEAD));
        } else if (dead && !currentlyDead) {
            dead = false;
            handler.restoreLastLighting();
            lastSelectedSlot = -1;
        }
    }

    private void handleSelectedSlot(LocalPlayer player) {
        if (!config.isHighlightSelectedSlot()) {
            if (lastSelectedSlot >= 0) {
                int previousCode = hotbarScanCodes[lastSelectedSlot];
                if (previousCode > 0) {
                    handler.setSolidColorOnScanCode(previousCode, config.getColorForCategory(ConfigManager.CATEGORY_INVENTORY));
                }
                lastSelectedSlot = -1;
            }
            return;
        }

        int slot = player.getInventory().getSelectedSlot();
        if (slot < 0 || slot >= hotbarScanCodes.length) {
            return;
        }

        if (lastSelectedSlot != slot) {
            if (lastSelectedSlot >= 0) {
                int previousCode = hotbarScanCodes[lastSelectedSlot];
                if (previousCode > 0) {
                    handler.setSolidColorOnScanCode(previousCode, config.getColorForCategory(ConfigManager.CATEGORY_INVENTORY));
                }
            }

            lastSelectedSlot = slot;
            int code = hotbarScanCodes[slot];
            if (code > 0) {
                handler.setSolidColorOnScanCode(code, config.getColorForCategory(ConfigManager.CATEGORY_INVENTORY_SELECTED));
            }
        }
    }

    private void onJoin(ClientPacketListener handler, PacketSender sender, Minecraft client) {
        dead = false;
        lastSelectedSlot = -1;
        hotbarInitialized = false;
        hotbarPrimed = false;
        Arrays.fill(hotbarScanCodes, -1);
        clearSpecialEffects(false);
        if (this.handler.isActive()) {
            this.handler.initBaseLighting();
        }
    }

    private void onDisconnect(ClientPacketListener handler, Minecraft client) {
        if (dead) {
            dead = false;
            this.handler.restoreLastLighting();
        }
        lastSelectedSlot = -1;
        hotbarInitialized = false;
        hotbarPrimed = false;
        Arrays.fill(hotbarScanCodes, -1);
    }

    private void onHandlerRestart() {
        hotbarInitialized = false;
        hotbarPrimed = false;
        lastSelectedSlot = -1;
        Arrays.fill(hotbarScanCodes, -1);
        if (activeEffect != SpecialEffect.NONE) {
            applySpecialEffect(activeEffect, false);
        }
    }

    private static boolean isPlayerDead(LocalPlayer player) {
        return player.isDeadOrDying() || player.getHealth() <= 0.0F || player.isRemoved();
    }

    private void updateSpecialEffects(LocalPlayer player) {
        if (config.isDamageEffectEnabled() && player.hurtTime > 0) {
            damageFlashTicks = 12;
        } else if (damageFlashTicks > 0 && config.isDamageEffectEnabled()) {
            damageFlashTicks--;
        } else if (!config.isDamageEffectEnabled()) {
            damageFlashTicks = 0;
        }

        SpecialEffect desired = determineDesiredEffect(player);
        if (desired != activeEffect) {
            applySpecialEffect(desired, true);
        }
    }

    private SpecialEffect determineDesiredEffect(LocalPlayer player) {
        if (config.isDamageEffectEnabled() && damageFlashTicks > 0) {
            return SpecialEffect.DAMAGE_FLASH;
        }
        if (config.isPoisonEffectEnabled() && player.hasEffect(MobEffects.POISON)) {
            return SpecialEffect.POISON;
        }
        if (config.isFrozenEffectEnabled() && player.getTicksFrozen() > 0) {
            return SpecialEffect.FROZEN;
        }
        if (config.isUnderwaterEffectEnabled() && player.isUnderWater()) {
            return SpecialEffect.UNDERWATER;
        }
        return SpecialEffect.NONE;
    }

    private void applySpecialEffect(SpecialEffect effect, boolean restoreBaseAfterNone) {
        if (!handler.isActive()) {
            activeEffect = effect;
            return;
        }

        handler.stopEffects();

        hotbarPrimed = false;
        switch (effect) {
            case DAMAGE_FLASH:
                handler.setFlashingColor(0xFF0000, 200);
                break;
            case UNDERWATER:
                handler.setPulsingColor(0x003A9D, 1200);
                break;
            case POISON:
                handler.setPulsingColor(0x1BCB5D, 900);
                break;
            case FROZEN:
                handler.setPulsingColor(0x76D4F5, 1500);
                break;
            case NONE:
                if (restoreBaseAfterNone) {
                    handler.initBaseLighting();
                    refreshHotbarBaseColors();
                    resetHotbarHighlight();
                }
                break;
        }

        activeEffect = effect;
    }

    private void clearSpecialEffects(boolean restoreBase) {
        damageFlashTicks = 0;
        if (activeEffect != SpecialEffect.NONE) {
            applySpecialEffect(SpecialEffect.NONE, restoreBase);
        } else if (restoreBase) {
            handler.stopEffects();
            handler.initBaseLighting();
            refreshHotbarBaseColors();
            resetHotbarHighlight();
        }
    }

    private void resetHotbarHighlight() {
        if (!config.isHighlightSelectedSlot()) {
            return;
        }
        if (lastSelectedSlot < 0 || lastSelectedSlot >= hotbarScanCodes.length) {
            return;
        }
        int code = hotbarScanCodes[lastSelectedSlot];
        if (code > 0) {
            handler.setSolidColorOnScanCode(code, config.getColorForCategory(ConfigManager.CATEGORY_INVENTORY_SELECTED));
        }
    }

    private void refreshHotbarBaseColors() {
        if (!config.isHotbarAlwaysVisible() || !handler.isActive()) {
            hotbarPrimed = false;
            return;
        }
        int baseColor = config.getColorForCategory(ConfigManager.CATEGORY_INVENTORY);
        for (int code : hotbarScanCodes) {
            if (code > 0) {
                handler.setSolidColorOnScanCode(code, baseColor);
            }
        }
        hotbarPrimed = true;
    }

    public void onConfigChanged() {
        hotbarInitialized = false;
        hotbarPrimed = false;
        lastSelectedSlot = -1;
        Arrays.fill(hotbarScanCodes, -1);
        clearSpecialEffects(true);
    }
}
