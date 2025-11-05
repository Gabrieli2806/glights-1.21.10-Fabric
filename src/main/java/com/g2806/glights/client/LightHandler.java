package com.g2806.glights.client;

import com.g2806.glights.GLights;
import com.g2806.glights.client.config.ConfigManager;
import com.logitech.gaming.LogiLED;
import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class LightHandler {
    private static Field CATEGORY_NAME_FIELD;
    private static Method ACTIVE_KEY_METHOD;
    private static boolean ACTIVE_KEY_METHOD_RESOLVED;
    private static Field ACTIVE_KEY_FIELD;
    private static boolean ACTIVE_KEY_FIELD_RESOLVED;

    private final Minecraft client;
    private final ConfigManager config;
    private final Int2IntOpenHashMap keyLastColor = new Int2IntOpenHashMap();
    private final List<Runnable> restartCallbacks = new CopyOnWriteArrayList<>();

    private static final int[] FUNCTION_KEY_KEYSYMS = new int[] {
            GLFW.GLFW_KEY_F1,
            GLFW.GLFW_KEY_F2,
            GLFW.GLFW_KEY_F3,
            GLFW.GLFW_KEY_F4,
            GLFW.GLFW_KEY_F5,
            GLFW.GLFW_KEY_F6,
            GLFW.GLFW_KEY_F7,
            GLFW.GLFW_KEY_F8,
            GLFW.GLFW_KEY_F9,
            GLFW.GLFW_KEY_F10,
            GLFW.GLFW_KEY_F11,
            GLFW.GLFW_KEY_F12
    };

    private boolean active;

    private LightHandler(Minecraft client, ConfigManager config) {
        this.client = client;
        this.config = config;
        this.keyLastColor.defaultReturnValue(0);
    }

    public static Optional<LightHandler> create(Minecraft client, ConfigManager config) {
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

        KeyMapping[] allKeys = client.options.keyMappings;
        Collection<String> categories = new ArrayList<>(allKeys.length + 4);
        for (KeyMapping binding : allKeys) {
            if (binding == null) {
                continue;
            }
            categories.add(resolveCategory(binding));
        }
        categories.add(ConfigManager.CATEGORY_UNKNOWN);
        categories.add(ConfigManager.CATEGORY_DEAD);
        categories.add(ConfigManager.CATEGORY_INVENTORY);
        categories.add(ConfigManager.CATEGORY_INVENTORY_SELECTED);
        categories.add(ConfigManager.CATEGORY_WASD);
        config.ensureDefaults(categories);
        config.saveIfDirty();

        keyLastColor.clear();
        for (KeyMapping binding : allKeys) {
            applyBaseColor(binding);
        }
        applyWasdOverride();
        ensureFunctionKeyFallbacks();
    }

    public void applyBaseColor(KeyMapping binding) {
        if (!active || binding == null) {
            return;
        }

        int scanCode = resolveScanCode(binding);
        int logiKey = resolveLogiKey(binding);
        if (scanCode <= 0 && logiKey < 0) {
            return;
        }

        int color = config.getColorForCategory(resolveCategory(binding));
        setSolidColorOnResolvedKey(logiKey, scanCode, color);
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

    public void setSolidColorOnKey(KeyMapping binding, int color) {
        if (!active || binding == null) {
            return;
        }
        int scanCode = resolveScanCode(binding);
        int logiKey = resolveLogiKey(binding);
        if (scanCode <= 0 && logiKey < 0) {
            return;
        }
        setSolidColorOnResolvedKey(logiKey, scanCode, color);
    }

    public void setSolidColorOnScanCode(int scanCode, int color) {
        setSolidColorOnResolvedKey(-1, scanCode, color);
    }

    public void setSolidColorOnResolvedKey(int logiKey, int scanCode, int color) {
        if (!active) {
            return;
        }
        int[] rgb = splitColor(color);
        if (logiKey >= 0) {
            LogiLED.LogiLedSetLightingForKeyWithKeyName(logiKey, rgb[0], rgb[1], rgb[2]);
        }
        if (scanCode > 0) {
            keyLastColor.put(scanCode, color & 0xFFFFFF);
            LogiLED.LogiLedSetLightingForKeyWithScanCode(scanCode, rgb[0], rgb[1], rgb[2]);
        }
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

    public boolean restart(boolean silent) {
        if (active) {
            initBaseLighting();
            return true;
        }
        if (!startLedSdk(silent)) {
            return false;
        }
        initBaseLighting();
        for (Runnable callback : restartCallbacks) {
            try {
                callback.run();
            } catch (Throwable throwable) {
                GLights.LOGGER.error("Restart callback threw", throwable);
            }
        }
        return true;
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

    public int resolveScanCode(KeyMapping binding) {
        if (binding == null) {
            return -1;
        }
        InputConstants.Key key = resolveActiveKey(binding);
        if (key == null) {
            return -1;
        }
        InputConstants.Type type = key.getType();
        if (type == InputConstants.Type.MOUSE) {
            return -1;
        }
        int code = key.getValue();
        if (code <= 0) {
            return -1;
        }
        if (type == InputConstants.Type.KEYSYM) {
            int scancode = GLFW.glfwGetKeyScancode(code);
            return scancode > 0 ? scancode : code;
        }
        return code;
    }

    public int resolveLogiKey(KeyMapping binding) {
        if (binding == null) {
            return -1;
        }
        InputConstants.Key key = resolveActiveKey(binding);
        return mapKeyToLogiKey(key);
    }

    private static int mapKeyToLogiKey(InputConstants.Key key) {
        if (key == null || key.getType() != InputConstants.Type.KEYSYM) {
            return -1;
        }
        return switch (key.getValue()) {
            case GLFW.GLFW_KEY_ESCAPE -> LogiLED.ESC;
            case GLFW.GLFW_KEY_F1 -> LogiLED.F1;
            case GLFW.GLFW_KEY_F2 -> LogiLED.F2;
            case GLFW.GLFW_KEY_F3 -> LogiLED.F3;
            case GLFW.GLFW_KEY_F4 -> LogiLED.F4;
            case GLFW.GLFW_KEY_F5 -> LogiLED.F5;
            case GLFW.GLFW_KEY_F6 -> LogiLED.F6;
            case GLFW.GLFW_KEY_F7 -> LogiLED.F7;
            case GLFW.GLFW_KEY_F8 -> LogiLED.F8;
            case GLFW.GLFW_KEY_F9 -> LogiLED.F9;
            case GLFW.GLFW_KEY_F10 -> LogiLED.F10;
            case GLFW.GLFW_KEY_F11 -> LogiLED.F11;
            case GLFW.GLFW_KEY_F12 -> LogiLED.F12;
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> LogiLED.TILDE;
            case GLFW.GLFW_KEY_1 -> LogiLED.ONE;
            case GLFW.GLFW_KEY_2 -> LogiLED.TWO;
            case GLFW.GLFW_KEY_3 -> LogiLED.THREE;
            case GLFW.GLFW_KEY_4 -> LogiLED.FOUR;
            case GLFW.GLFW_KEY_5 -> LogiLED.FIVE;
            case GLFW.GLFW_KEY_6 -> LogiLED.SIX;
            case GLFW.GLFW_KEY_7 -> LogiLED.SEVEN;
            case GLFW.GLFW_KEY_8 -> LogiLED.EIGHT;
            case GLFW.GLFW_KEY_9 -> LogiLED.NINE;
            case GLFW.GLFW_KEY_0 -> LogiLED.ZERO;
            case GLFW.GLFW_KEY_MINUS -> LogiLED.MINUS;
            case GLFW.GLFW_KEY_EQUAL -> LogiLED.EQUALS;
            default -> -1;
        };
    }

    private void ensureFunctionKeyFallbacks() {
        if (!active) {
            return;
        }
        int defaultColor = config.getColorForCategory(ConfigManager.CATEGORY_INVENTORY);
        for (int keysym : FUNCTION_KEY_KEYSYMS) {
            int scancode = GLFW.glfwGetKeyScancode(keysym);
            if (scancode > 0 && keyLastColor.containsKey(scancode)) {
                continue;
            }
            InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(keysym);
            int logiKey = mapKeyToLogiKey(key);
            int color = keysym == GLFW.GLFW_KEY_F4 ? 0 : defaultColor;
            setSolidColorOnResolvedKey(logiKey, scancode, color);
        }
    }

    private void applyWasdOverride() {
        if (!active) {
            return;
        }
        int color = config.getWasdColor();
        setSolidColorOnKey(client.options.keyUp, color);
        setSolidColorOnKey(client.options.keyLeft, color);
        setSolidColorOnKey(client.options.keyDown, color);
        setSolidColorOnKey(client.options.keyRight, color);
    }

    // Mojang mappings hide the active key behind reflection; Yarn exposes helper methods.
    private static InputConstants.Key resolveActiveKey(KeyMapping binding) {
        if (!ACTIVE_KEY_METHOD_RESOLVED) {
            ACTIVE_KEY_METHOD_RESOLVED = true;
            ACTIVE_KEY_METHOD = findKeyMethod("getKey");
            if (ACTIVE_KEY_METHOD == null) {
                ACTIVE_KEY_METHOD = findKeyMethod("getBoundKey");
            }
        }

        if (ACTIVE_KEY_METHOD != null) {
            try {
                Object result = ACTIVE_KEY_METHOD.invoke(binding);
                if (result instanceof InputConstants.Key key) {
                    return key;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }

        if (!ACTIVE_KEY_FIELD_RESOLVED) {
            ACTIVE_KEY_FIELD_RESOLVED = true;
            try {
                ACTIVE_KEY_FIELD = KeyMapping.class.getDeclaredField("key");
                ACTIVE_KEY_FIELD.setAccessible(true);
            } catch (ReflectiveOperationException ignored) {
                ACTIVE_KEY_FIELD = null;
            }
        }

        if (ACTIVE_KEY_FIELD != null) {
            try {
                Object value = ACTIVE_KEY_FIELD.get(binding);
                if (value instanceof InputConstants.Key key) {
                    return key;
                }
            } catch (IllegalAccessException ignored) {
            }
        }

        return null;
    }

    private static Method findKeyMethod(String name) {
        try {
            Method method = KeyMapping.class.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
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

    private static String resolveCategory(KeyMapping binding) {
        if (binding == null) {
            return ConfigManager.CATEGORY_UNKNOWN;
        }

        Object category = binding.getCategory();
        if (category == null) {
            return ConfigManager.CATEGORY_UNKNOWN;
        }

        if (CATEGORY_NAME_FIELD == null) {
            try {
                for (Field field : category.getClass().getDeclaredFields()) {
                    if (field.getType() == String.class) {
                        field.setAccessible(true);
                        CATEGORY_NAME_FIELD = field;
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (CATEGORY_NAME_FIELD != null) {
            try {
                Object value = CATEGORY_NAME_FIELD.get(category);
                if (value instanceof String str) {
                    return str;
                }
            } catch (IllegalAccessException ignored) {
            }
        }

        return category.toString();
    }
}
