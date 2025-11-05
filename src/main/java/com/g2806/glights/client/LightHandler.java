package com.g2806.glights.client;

import com.g2806.glights.GLights;
import com.g2806.glights.client.config.ConfigManager;
import com.logitech.gaming.LogiLED;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class LightHandler {
    private static final Field CATEGORY_TRANSLATION_FIELD;

    static {
        Field field = null;
        try {
            field = KeyBinding.Category.class.getDeclaredField("translationKey");
            field.setAccessible(true);
        } catch (ReflectiveOperationException ignored) {
        }
        CATEGORY_TRANSLATION_FIELD = field;
    }

    private final MinecraftClient client;
    private final ConfigManager config;
    private final Int2IntOpenHashMap keyLastColor = new Int2IntOpenHashMap();
    private final List<Runnable> restartCallbacks = new CopyOnWriteArrayList<>();

    private boolean active;

    private LightHandler(MinecraftClient client, ConfigManager config) {
        this.client = client;
        this.config = config;
        this.keyLastColor.defaultReturnValue(0);
    }

    public static Optional<LightHandler> create(MinecraftClient client, ConfigManager config) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(config, "config");

        LightHandler handler = new LightHandler(client, config);
        if (!handler.startLedSdk(false)) {
            return Optional.empty();
        }

        return Optional.of(handler);
    }

    private boolean startLedSdk(boolean silent) {
        try {
            if (!LogiLED.LogiLedInit()) {
                if (!silent) {
                    GLights.LOGGER.error("Failed to initialise the Logitech LED SDK (LogiLedInit returned false)");
                }
                return false;
            }
        } catch (UnsatisfiedLinkError | NoClassDefFoundError error) {
            if (!silent) {
                GLights.LOGGER.warn("Unable to load Logitech LED SDK. Ensure logiled.jar is on the classpath and the native DLL is present.", error);
            }
            return false;
        }

        LogiLED.LogiLedSetTargetDevice(LogiLED.LOGI_DEVICETYPE_PERKEY_RGB);
        active = true;
        setSolidColor(0x000000);
        return true;
    }

    public boolean isActive() {
        return active;
    }

    public void initBaseLighting() {
        if (!active) {
            return;
        }

        KeyBinding[] allKeys = client.options.allKeys;
        Collection<String> categories = new ArrayList<>(allKeys.length + 4);
        for (KeyBinding binding : allKeys) {
            if (binding == null) {
                continue;
            }
            categories.add(resolveCategory(binding));
        }
        categories.add(ConfigManager.CATEGORY_UNKNOWN);
        categories.add(ConfigManager.CATEGORY_DEAD);
        categories.add(ConfigManager.CATEGORY_INVENTORY);
        categories.add(ConfigManager.CATEGORY_INVENTORY_SELECTED);
        config.ensureDefaults(categories);
        config.saveIfDirty();

        keyLastColor.clear();
        for (KeyBinding binding : allKeys) {
            applyBaseColor(binding);
        }
    }

    public void applyBaseColor(KeyBinding binding) {
        if (!active || binding == null) {
            return;
        }
        int scanCode = resolveScanCode(binding);
        if (scanCode <= 0) {
            return;
        }

        int color = config.getColorForCategory(resolveCategory(binding));
        setSolidColorOnScanCode(scanCode, color);
    }

    public void setSolidColor(int color) {
        if (!active) {
            return;
        }
        int[] rgb = splitColor(color);
        LogiLED.LogiLedSetLighting(rgb[0], rgb[1], rgb[2]);
    }

    public void setFlashingColor(int color, int dutyCycleMs) {
        if (!active) {
            return;
        }
        int[] rgb = splitColor(color);
        LogiLED.LogiLedFlashLighting(rgb[0], rgb[1], rgb[2], LogiLED.LOGI_LED_DURATION_INFINITE, dutyCycleMs);
    }

    public void setPulsingColor(int color, int dutyCycleMs) {
        if (!active) {
            return;
        }
        int[] rgb = splitColor(color);
        LogiLED.LogiLedPulseLighting(rgb[0], rgb[1], rgb[2], LogiLED.LOGI_LED_DURATION_INFINITE, dutyCycleMs);
    }

    public void setSolidColorOnKey(KeyBinding binding, int color) {
        if (binding == null) {
            return;
        }
        setSolidColorOnScanCode(resolveScanCode(binding), color);
    }

    public void setSolidColorOnScanCode(int scanCode, int color) {
        if (!active || scanCode <= 0) {
            return;
        }
        int[] rgb = splitColor(color);
        keyLastColor.put(scanCode, color & 0xFFFFFF);
        LogiLED.LogiLedSetLightingForKeyWithScanCode(scanCode, rgb[0], rgb[1], rgb[2]);
    }

    public void setFlashingColorOnScanCode(int scanCode, int color, int dutyCycleMs) {
        if (!active || scanCode <= 0) {
            return;
        }
        int[] rgb = splitColor(color);
        LogiLED.LogiLedFlashSingleKey(scanCode, rgb[0], rgb[1], rgb[2], dutyCycleMs, dutyCycleMs);
    }

    public void setPulsingColorOnScanCode(int scanCode, int color, int dutyCycleMs) {
        if (!active || scanCode <= 0) {
            return;
        }
        int[] rgb = splitColor(color);
        int[] previous = splitColor(keyLastColor.get(scanCode));
        LogiLED.LogiLedPulseSingleKey(scanCode, previous[0], previous[1], previous[2], rgb[0], rgb[1], rgb[2], dutyCycleMs, true);
    }

    public void stopEffects() {
        if (!active) {
            return;
        }
        LogiLED.LogiLedStopEffects();
    }

    public void saveCurrentLighting() {
        if (!active) {
            return;
        }
        LogiLED.LogiLedSaveCurrentLighting();
    }

    public void restoreLastLighting() {
        if (!active) {
            return;
        }
        LogiLED.LogiLedStopEffects();
        LogiLED.LogiLedRestoreLighting();
        initBaseLighting();
    }

    public void shutdown(boolean silent) {
        if (!active) {
            return;
        }
        if (!silent) {
            GLights.LOGGER.info("Shutting down Logitech LED SDK");
        }
        active = false;
        LogiLED.LogiLedShutdown();
    }

    public void restart(boolean silent) {
        if (active) {
            return;
        }
        if (!startLedSdk(silent)) {
            return;
        }
        initBaseLighting();
        for (Runnable callback : restartCallbacks) {
            try {
                callback.run();
            } catch (Throwable throwable) {
                GLights.LOGGER.error("Restart callback threw", throwable);
            }
        }
    }

    public void addRestartCallback(Runnable callback) {
        restartCallbacks.add(callback);
    }

    public void onResourceReload() {
        if (!active) {
            return;
        }
        shutdown(true);
        restart(true);
    }

    public int resolveScanCode(KeyBinding binding) {
        if (binding == null) {
            return -1;
        }
        InputUtil.Key key = InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey());
        if (key == null) {
            return -1;
        }
        InputUtil.Type type = key.getCategory();
        if (type == InputUtil.Type.MOUSE) {
            return -1;
        }
        int code = key.getCode();
        if (type == InputUtil.Type.KEYSYM) {
            int scancode = GLFW.glfwGetKeyScancode(code);
            return scancode > 0 ? scancode : code;
        }
        return code;
    }

    public Int2IntMap getCurrentColors() {
        return keyLastColor;
    }

    private static int[] splitColor(int hex) {
        int sanitized = hex & 0xFFFFFF;
        int red = (sanitized >> 16) & 0xFF;
        int green = (sanitized >> 8) & 0xFF;
        int blue = sanitized & 0xFF;
        return new int[] {
                Math.round(red / 255.0F * 100.0F),
                Math.round(green / 255.0F * 100.0F),
                Math.round(blue / 255.0F * 100.0F)
        };
    }

    private static String resolveCategory(KeyBinding binding) {
        if (binding == null) {
            return ConfigManager.CATEGORY_UNKNOWN;
        }

        KeyBinding.Category category = binding.getCategory();
        if (CATEGORY_TRANSLATION_FIELD != null) {
            try {
                Object value = CATEGORY_TRANSLATION_FIELD.get(category);
                if (value instanceof String str) {
                    return str;
                }
            } catch (IllegalAccessException ignored) {
            }
        }

        return category.toString();
    }
}
