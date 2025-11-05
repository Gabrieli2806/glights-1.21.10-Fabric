package com.g2806.glights.client;

import com.g2806.glights.client.config.ConfigManager;
import com.logitech.gaming.LogiLED;
import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.Random;

public final class EventHandler {
    private enum SpecialEffect {
        NONE,
    DAMAGE_FLASH,
    LOW_HEALTH,
    UNDERWATER,
    POISON,
    WITHER,
    FROZEN,
    NETHER_PORTAL
    }

    private final Minecraft client;
    private final LightHandler handler;
    private final ConfigManager config;
    private final int[] hotbarScanCodes = new int[9];
    private final int[] hotbarLogiKeys = new int[9];
    private static final int F3_KEYSYM = GLFW.GLFW_KEY_F3;
    private static final int F3_HOLD_THRESHOLD_TICKS = 5;
    private static final int F4_LOGI_KEY = LogiLED.F4;
    private static final int EFFECT_TICK_MASK = (1 << 14) - 1;

    private boolean hotbarInitialized;
    private boolean windowFocused = true;
    private boolean dead;
    private int lastSelectedSlot = -1;
    private SpecialEffect activeEffect = SpecialEffect.NONE;
    private int damageFlashTicks;
    private boolean f3Held;
    private int f3HoldTicks;
    private boolean f4Lit;
    private int effectTicks;
    private int[] effectScanCodes = new int[0];
    private final Random effectRandom = new Random();

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

        if (!config.isModEnabled()) {
            return;
        }

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
            if (!config.isModEnabled()) {
                windowFocused = true;
                return;
            }
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
        effectTicks = 0;
        effectScanCodes = new int[0];
        if (activeEffect != SpecialEffect.NONE) {
            applySpecialEffect(activeEffect, false);
        } else {
            handler.initBaseLighting();
            resetHotbarHighlight();
        }
    }

    private static boolean isPlayerDead(LocalPlayer player) {
        return player.isDeadOrDying() || player.getHealth() <= 0.0F || player.isRemoved();
    }

    private void updateSpecialEffects(LocalPlayer player) {
        boolean poisonActive = config.isPoisonEffectEnabled() && player.hasEffect(MobEffects.POISON);
        boolean witherActive = config.isWitherEffectEnabled() && player.hasEffect(MobEffects.WITHER);
        boolean lowHealthActive = config.isLowHealthBlinkEnabled() && isLowHealth(player);

        if (config.isDamageEffectEnabled() && !poisonActive && !witherActive && !lowHealthActive && player.hurtTime > 0) {
            damageFlashTicks = 12;
        } else if (!config.isDamageEffectEnabled() || poisonActive || witherActive || lowHealthActive) {
            damageFlashTicks = 0;
        } else if (damageFlashTicks > 0) {
            damageFlashTicks--;
        }

        SpecialEffect desired = determineDesiredEffect(player, poisonActive, witherActive, lowHealthActive);
        if (desired != activeEffect) {
            applySpecialEffect(desired, true);
        }
        if (activeEffect != SpecialEffect.NONE) {
            tickActiveEffect();
        }
    }

    private SpecialEffect determineDesiredEffect(LocalPlayer player, boolean poisonActive, boolean witherActive, boolean lowHealthActive) {
        if (config.isDamageEffectEnabled() && damageFlashTicks > 0) {
            return SpecialEffect.DAMAGE_FLASH;
        }
        if (lowHealthActive) {
            return SpecialEffect.LOW_HEALTH;
        }
        if (config.isNetherPortalEffectEnabled() && isWaitingForNether(player)) {
            return SpecialEffect.NETHER_PORTAL;
        }
        if (witherActive) {
            return SpecialEffect.WITHER;
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
        effectTicks = 0;
        effectScanCodes = new int[0];

        activeEffect = effect;

        if (effect == SpecialEffect.NONE) {
            if (restoreBaseAfterNone) {
                handler.initBaseLighting();
                resetHotbarHighlight();
            }
        } else {
            captureEffectScanCodes();
            tickActiveEffect();
        }
    }

    private void tickActiveEffect() {
        if (!handler.isActive() || activeEffect == SpecialEffect.NONE) {
            return;
        }

        effectTicks = (effectTicks + 1) & EFFECT_TICK_MASK;
        if (effectScanCodes.length == 0) {
            captureEffectScanCodes();
        }

        int[] scanCodes = effectScanCodes;
        switch (activeEffect) {
            case DAMAGE_FLASH -> runDamageRipple(scanCodes);
            case LOW_HEALTH -> runLowHealthBlink();
            case UNDERWATER -> runUnderwaterWave(scanCodes);
            case POISON -> runPoisonStarlight(scanCodes);
            case WITHER -> runWitherEcho(scanCodes);
            case FROZEN -> runFrozenBreathing();
            case NETHER_PORTAL -> runNetherColorWave(scanCodes);
            default -> {
            }
        }
    }

    private void captureEffectScanCodes() {
        Int2IntMap colors = handler.getCurrentColors();
        if (colors == null || colors.isEmpty()) {
            effectScanCodes = new int[0];
            return;
        }
        IntSet set = colors.keySet();
        effectScanCodes = set.toIntArray();
        Arrays.sort(effectScanCodes);
    }

    private void runDamageRipple(int[] scanCodes) {
        float decay = clamp01(damageFlashTicks / 12.0f);
        int base = blendColors(0x1A0000, 0x360000, decay);
        int accent = blendColors(0xFF2A00, 0xFF5A00, decay);
        handler.setSolidColor(blendColors(base, accent, 0.35f + 0.25f * decay));
        if (scanCodes.length == 0) {
            return;
        }
        int bandCount = 6;
        int waveIndex = effectTicks % bandCount;
        for (int i = 0; i < scanCodes.length; i++) {
            int offset = (i + waveIndex) % bandCount;
            float strength;
            if (offset == 0) {
                strength = 1.0f;
            } else if (offset == 1 || offset == bandCount - 1) {
                strength = 0.65f;
            } else {
                strength = 0.0f;
            }
            if (strength > 0.0f) {
                strength *= decay;
                int color = blendColors(base, accent, strength);
                handler.setSolidColorOnScanCode(scanCodes[i], color);
            }
        }
    }

    private void runUnderwaterWave(int[] scanCodes) {
        float swell = 0.5f + 0.5f * (float) Math.sin(effectTicks * 0.05f);
        int base = blendColors(0x00162C, 0x003A66, swell);
        handler.setSolidColor(base);
        if (scanCodes.length == 0) {
            return;
        }
        for (int i = 0; i < scanCodes.length; i++) {
            float offset = effectTicks * 0.12f - i * 0.18f;
            float wave = 0.5f + 0.5f * (float) Math.sin(offset);
            int color = blendColors(0x003253, 0x00B2FF, wave);
            handler.setSolidColorOnScanCode(scanCodes[i], color);
        }
    }

    private void runPoisonStarlight(int[] scanCodes) {
        handler.setSolidColor(blendColors(0x001904, 0x003A0B, 0.6f));
        if (scanCodes.length == 0) {
            return;
        }
        int starCount = Math.max(4, scanCodes.length / 12);
        for (int i = 0; i < starCount; i++) {
            int index = effectRandom.nextInt(scanCodes.length);
            float sparkle = effectRandom.nextFloat();
            int color = blendColors(0x047A1F, 0x7CFF8A, sparkle);
            handler.setSolidColorOnScanCode(scanCodes[index], color);
        }
    }

    private void runLowHealthBlink() {
        int phase = (effectTicks / 3) & 1;
        int color = phase == 0 ? 0xFF0000 : 0x000000;
        handler.setSolidColor(color);
    }

    private void runWitherEcho(int[] scanCodes) {
        float swell = 0.5f + 0.5f * (float) Math.sin(effectTicks * 0.045f + 0.6f);
        int base = blendColors(0x050007, 0x160022, swell);
        handler.setSolidColor(base);
        if (scanCodes.length == 0) {
            return;
        }
        int echoCount = Math.max(4, scanCodes.length / 16);
        for (int i = 0; i < echoCount; i++) {
            int index = (effectTicks / 4 + i * 19) % scanCodes.length;
            float age = ((effectTicks + i * 13) % 48) / 48.0f;
            float pulse = clamp01(1.0f - age);
            pulse *= pulse;
            int color = blendColors(0x26003A, 0xB400FF, pulse);
            handler.setSolidColorOnScanCode(scanCodes[index], color);
        }
        if (effectTicks % 12 == 0) {
            int flickers = Math.min(3, scanCodes.length);
            int accent = blendColors(0x30004A, 0xE000FF, 0.85f);
            for (int i = 0; i < flickers; i++) {
                int index = effectRandom.nextInt(scanCodes.length);
                handler.setSolidColorOnScanCode(scanCodes[index], accent);
            }
        }
    }

    private void runFrozenBreathing() {
        float wave = 0.5f + 0.5f * (float) Math.sin(effectTicks * 0.08f);
        int color = blendColors(0x152D45, 0xC9F4FF, wave);
        handler.setSolidColor(color);
    }

    private void runNetherColorWave(int[] scanCodes) {
        float hueBase = 0.78f + 0.04f * (float) Math.sin(effectTicks * 0.05f);
        int base = hsvToRgb(hueBase, 0.85f, 0.35f);
        handler.setSolidColor(base);
        if (scanCodes.length == 0) {
            return;
        }
        for (int i = 0; i < scanCodes.length; i++) {
            float wave = 0.5f + 0.5f * (float) Math.sin(effectTicks * 0.17f - i * 0.25f);
            float hue = 0.74f + 0.1f * wave;
            float brightness = 0.6f + 0.3f * wave;
            int color = hsvToRgb(hue, 0.95f, brightness);
            handler.setSolidColorOnScanCode(scanCodes[i], color);
        }
    }

    private static boolean isLowHealth(LocalPlayer player) {
        if (player == null) {
            return false;
        }
        if (player.isCreative() || player.isSpectator()) {
            return false;
        }
        if (player.isDeadOrDying() || player.getHealth() <= 0.0F) {
            return false;
        }
        return player.getHealth() + player.getAbsorptionAmount() <= 4.0F;
    }

    private static int blendColors(int from, int to, float ratio) {
        float t = clamp01(ratio);
        int fr = (from >> 16) & 0xFF;
        int fg = (from >> 8) & 0xFF;
        int fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF;
        int tg = (to >> 8) & 0xFF;
        int tb = to & 0xFF;

        int r = Math.round(fr + (tr - fr) * t);
        int g = Math.round(fg + (tg - fg) * t);
        int b = Math.round(fb + (tb - fb) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static int hsvToRgb(float hue, float saturation, float value) {
        float h = wrapHue(hue) * 6.0f;
        int sector = (int) Math.floor(h);
        float fraction = h - sector;
        float p = value * (1.0f - saturation);
        float q = value * (1.0f - saturation * fraction);
        float t = value * (1.0f - saturation * (1.0f - fraction));

        float rf;
        float gf;
        float bf;
        switch (sector) {
            case 0 -> {
                rf = value;
                gf = t;
                bf = p;
            }
            case 1 -> {
                rf = q;
                gf = value;
                bf = p;
            }
            case 2 -> {
                rf = p;
                gf = value;
                bf = t;
            }
            case 3 -> {
                rf = p;
                gf = q;
                bf = value;
            }
            case 4 -> {
                rf = t;
                gf = p;
                bf = value;
            }
            default -> {
                rf = value;
                gf = p;
                bf = q;
            }
        }

        int r = Math.round(clamp01(rf) * 255.0f);
        int g = Math.round(clamp01(gf) * 255.0f);
        int b = Math.round(clamp01(bf) * 255.0f);
        return (r << 16) | (g << 8) | b;
    }

    private static float clamp01(float value) {
        if (value <= 0.0f) {
            return 0.0f;
        }
        if (value >= 1.0f) {
            return 1.0f;
        }
        return value;
    }

    private static float wrapHue(float hue) {
        float wrapped = hue % 1.0f;
        return wrapped < 0.0f ? wrapped + 1.0f : wrapped;
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
        activeEffect = SpecialEffect.NONE;
        effectTicks = 0;
        effectScanCodes = new int[0];
    }

    private void updateFunctionKeyLighting() {
        if (!handler.isActive()) {
            return;
        }
        boolean currentlyHeld = InputConstants.isKeyDown(client.getWindow(), F3_KEYSYM);
        if (currentlyHeld) {
            if (!f3Held) {
                f3Held = true;
                f3HoldTicks = 0;
            }
            if (f3HoldTicks < F3_HOLD_THRESHOLD_TICKS) {
                f3HoldTicks++;
            }
            if (f3HoldTicks >= F3_HOLD_THRESHOLD_TICKS) {
                int color = config.getColorForCategory(ConfigManager.CATEGORY_INVENTORY);
                handler.setSolidColorOnResolvedKey(F4_LOGI_KEY, -1, color);
                f4Lit = true;
            }
        } else {
            if (f4Lit) {
                handler.setSolidColorOnResolvedKey(F4_LOGI_KEY, -1, 0);
                f4Lit = false;
            }
            f3Held = false;
            f3HoldTicks = 0;
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
        resetFunctionKeyLighting();

        if (!config.isModEnabled()) {
            clearSpecialEffects(false);
            if (handler.isActive()) {
                handler.stopEffects();
                handler.shutdown(true);
            }
            return;
        }

        if (!handler.isActive()) {
            handler.restart(true);
        }

        clearSpecialEffects(true);
    }

    private boolean isWaitingForNether(LocalPlayer player) {
        if (player == null || player.level() == null) {
            return false;
        }
        return player.level().getBlockState(player.blockPosition()).is(Blocks.NETHER_PORTAL);
    }

    private void resetFunctionKeyLighting() {
        f3Held = false;
        f3HoldTicks = 0;
        f4Lit = false;
        if (handler.isActive()) {
            handler.setSolidColorOnResolvedKey(F4_LOGI_KEY, -1, 0);
        }
    }
}
