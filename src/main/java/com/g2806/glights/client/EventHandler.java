package com.g2806.glights.client;

import com.g2806.glights.client.config.ConfigManager;
import com.logitech.gaming.LogiLED;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import org.lwjgl.glfw.GLFW;

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
    private final int[] hotbarLogiKeys = new int[9];
    private static final int F3_KEYSYM = GLFW.GLFW_KEY_F3;
    private static final int F4_LOGI_KEY = LogiLED.F4;

    private boolean hotbarInitialized;
    private boolean windowFocused = true;
    private boolean dead;
    private int lastSelectedSlot = -1;
    private SpecialEffect activeEffect = SpecialEffect.NONE;
    private int damageFlashTicks;
    private boolean f3Held;

    public EventHandler(Minecraft client, LightHandler handler, ConfigManager config) {
        this.client = client;
        this.handler = handler;
        this.config = config;
        Arrays.fill(hotbarScanCodes, -1);
        Arrays.fill(hotbarLogiKeys, -1);

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

        updateFunctionKeyLighting();
    }

    private void handleFocus() {
        boolean focused = client.isWindowActive();
        if (windowFocused && !focused) {
            windowFocused = false;
            resetFunctionKeyLighting();
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
            return;
        }
        KeyMapping[] bindings = client.options.keyHotbarSlots;
        for (int i = 0; i < bindings.length && i < hotbarScanCodes.length; i++) {
            hotbarScanCodes[i] = handler.resolveScanCode(bindings[i]);
            hotbarLogiKeys[i] = handler.resolveLogiKey(bindings[i]);
        }
        hotbarInitialized = true;
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
                int previousLogiKey = hotbarLogiKeys[lastSelectedSlot];
                if (previousCode > 0 || previousLogiKey >= 0) {
                    handler.setSolidColorOnResolvedKey(previousLogiKey, previousCode, config.getColorForCategory(ConfigManager.CATEGORY_INVENTORY));
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
                int previousLogiKey = hotbarLogiKeys[lastSelectedSlot];
                if (previousCode > 0 || previousLogiKey >= 0) {
                    handler.setSolidColorOnResolvedKey(previousLogiKey, previousCode, config.getColorForCategory(ConfigManager.CATEGORY_INVENTORY));
                }
            }

            lastSelectedSlot = slot;
            int code = hotbarScanCodes[slot];
            int logiKey = hotbarLogiKeys[slot];
            if (code > 0 || logiKey >= 0) {
                handler.setSolidColorOnResolvedKey(logiKey, code, config.getHighlightColor());
            }
        }
    }

    private void onJoin(ClientPacketListener handler, PacketSender sender, Minecraft client) {
        dead = false;
        lastSelectedSlot = -1;
        hotbarInitialized = false;
        Arrays.fill(hotbarScanCodes, -1);
        Arrays.fill(hotbarLogiKeys, -1);
        resetFunctionKeyLighting();
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
        Arrays.fill(hotbarScanCodes, -1);
        Arrays.fill(hotbarLogiKeys, -1);
        resetFunctionKeyLighting();
    }

    private void onHandlerRestart() {
        hotbarInitialized = false;
        lastSelectedSlot = -1;
        Arrays.fill(hotbarScanCodes, -1);
        Arrays.fill(hotbarLogiKeys, -1);
        resetFunctionKeyLighting();
        if (activeEffect != SpecialEffect.NONE) {
            applySpecialEffect(activeEffect, false);
        }
    }

    private static boolean isPlayerDead(LocalPlayer player) {
        return player.isDeadOrDying() || player.getHealth() <= 0.0F || player.isRemoved();
    }

    private void updateSpecialEffects(LocalPlayer player) {
        boolean poisonActive = config.isPoisonEffectEnabled() && player.hasEffect(MobEffects.POISON);

        if (config.isDamageEffectEnabled() && !poisonActive && player.hurtTime > 0) {
            damageFlashTicks = 12;
        } else if (!config.isDamageEffectEnabled() || poisonActive) {
            damageFlashTicks = 0;
        } else if (damageFlashTicks > 0) {
            damageFlashTicks--;
        }

        SpecialEffect desired = determineDesiredEffect(player, poisonActive);
        if (desired != activeEffect) {
            applySpecialEffect(desired, true);
        }
    }

    private SpecialEffect determineDesiredEffect(LocalPlayer player, boolean poisonActive) {
        if (config.isDamageEffectEnabled() && damageFlashTicks > 0) {
            return SpecialEffect.DAMAGE_FLASH;
        }
        if (poisonActive) {
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
            resetHotbarHighlight();
        }
    }

    private void updateFunctionKeyLighting() {
        if (!handler.isActive()) {
            return;
        }
        boolean currentlyHeld = InputConstants.isKeyDown(client.getWindow(), F3_KEYSYM);
        if (currentlyHeld == f3Held) {
            return;
        }
        f3Held = currentlyHeld;
    int color = currentlyHeld ? config.getColorForCategory(ConfigManager.CATEGORY_INVENTORY) : 0;
    handler.setSolidColorOnResolvedKey(F4_LOGI_KEY, -1, color);
    }

    private void resetHotbarHighlight() {
        if (!config.isHighlightSelectedSlot()) {
            return;
        }
        if (lastSelectedSlot < 0 || lastSelectedSlot >= hotbarScanCodes.length) {
            return;
        }
        int code = hotbarScanCodes[lastSelectedSlot];
        int logiKey = hotbarLogiKeys[lastSelectedSlot];
        if (code > 0 || logiKey >= 0) {
            handler.setSolidColorOnResolvedKey(logiKey, code, config.getHighlightColor());
        }
    }

    public void onConfigChanged() {
        hotbarInitialized = false;
        lastSelectedSlot = -1;
        Arrays.fill(hotbarScanCodes, -1);
        Arrays.fill(hotbarLogiKeys, -1);
        clearSpecialEffects(true);
        resetFunctionKeyLighting();
    }

    private void resetFunctionKeyLighting() {
        f3Held = false;
        if (handler.isActive()) {
            handler.setSolidColorOnResolvedKey(F4_LOGI_KEY, -1, 0);
        }
    }
}
