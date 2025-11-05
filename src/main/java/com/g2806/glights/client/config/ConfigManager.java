package com.g2806.glights.client.config;

import com.g2806.glights.GLights;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ConfigManager {
    public static final String CATEGORY_UNKNOWN = "key.categories.unknown";
    public static final String CATEGORY_DEAD = "key.categories.dead";
    public static final String CATEGORY_INVENTORY = "key.categories.inventory";
    public static final String CATEGORY_INVENTORY_SELECTED = "key.categories.inventory.selected";
    public static final String CATEGORY_WASD = "key.glights.special.wasd";
    public static final String CATEGORY_BACKGROUND = "key.glights.special.background";

    private static final int DEFAULT_UNKNOWN_COLOR = 0xFF0000;
    private static final int DEFAULT_DEAD_COLOR = 0xFF0000;
    private static final int DEFAULT_INVENTORY_COLOR = 0x00FF00;
    private static final int DEFAULT_HIGHLIGHT_COLOR = 0xFF7F00;
    private static final int DEFAULT_WASD_COLOR = 0xFF8C00;
    private static final int DEFAULT_BACKGROUND_COLOR = 0x1A1A1A;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, String>>() { }.getType();
    private static final Map<String, Integer> DEFAULT_COLORS = ImmutableMap.<String, Integer>builder()
            .put(CATEGORY_UNKNOWN, DEFAULT_UNKNOWN_COLOR)
            .put(CATEGORY_DEAD, DEFAULT_DEAD_COLOR)
            .put(CATEGORY_INVENTORY, DEFAULT_INVENTORY_COLOR)
            .put(CATEGORY_INVENTORY_SELECTED, DEFAULT_HIGHLIGHT_COLOR)
            .put(CATEGORY_WASD, DEFAULT_WASD_COLOR)
            .put(CATEGORY_BACKGROUND, DEFAULT_BACKGROUND_COLOR)
            .put("key.categories.movement", 0x00DCFF)
            .put("key.categories.gameplay", 0xFFFFFF)
            .put("key.categories.creative", 0x8000FF)
            .put("key.categories.multiplayer", 0xFFDC00)
            .put("key.categories.ui", 0x0000FF)
            .put("key.categories.misc", 0x0000FF)
            .build();

    private final Path path;
    private final Map<String, Integer> colors = new HashMap<>();
    private final Settings settings = new Settings();
    private boolean dirty;

    private static final class Settings {
        boolean damageEffect = true;
        boolean underwaterEffect = true;
        boolean poisonEffect = true;
        boolean frozenEffect = true;
        boolean netherPortalEffect = true;
        boolean witherEffect = true;
        boolean lowHealthBlink = true;
        boolean backgroundFillEnabled = false;
        boolean mouseLightingEnabled = true;
        boolean highlightSelectedSlot = true;

        void reset() {
            damageEffect = true;
            underwaterEffect = true;
            poisonEffect = true;
            frozenEffect = true;
            netherPortalEffect = true;
            witherEffect = true;
            lowHealthBlink = true;
            backgroundFillEnabled = false;
            mouseLightingEnabled = true;
            highlightSelectedSlot = true;
        }
    }

    public ConfigManager(Path path) {
        this.path = path;
    }

    public void load() {
        colors.clear();
        colors.putAll(DEFAULT_COLORS);
        settings.reset();
        dirty = false;

        if (!Files.exists(path)) {
            dirty = true;
            return;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || element.isJsonNull()) {
                dirty = true;
                return;
            }

            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                boolean migratedFormat = false;
                if (object.has("colors") && object.get("colors").isJsonObject()) {
                    migratedFormat = true;
                    readColors(object.getAsJsonObject("colors"));
                } else {
                    // Legacy flat map format
                    readLegacyColors(object);
                }

                if (object.has("settings") && object.get("settings").isJsonObject()) {
                    migratedFormat = true;
                    readSettings(object.getAsJsonObject("settings"));
                }

                if (!migratedFormat) {
                    // Pure legacy format, ensure we rewrite using the new structure.
                    dirty = true;
                }
            } else {
                // Unexpected type, treat as legacy map
                Map<String, String> raw = GSON.fromJson(element, TYPE);
                if (raw != null) {
                    readLegacyColors(raw);
                }
                dirty = true;
            }
        } catch (IOException e) {
            GLights.LOGGER.error("Failed to read GLights config from {}", path, e);
            dirty = true;
        }
    }

    public int getColorForCategory(String category) {
        Objects.requireNonNull(category, "category");
        return colors.computeIfAbsent(category, this::defaultColorFor);
    }

    public void setColorForCategory(String category, int color) {
        Objects.requireNonNull(category, "category");
        int normalized = color & 0xFFFFFF;
        Integer current = colors.get(category);
        if (current != null && current == normalized) {
            return;
        }
        colors.put(category, normalized);
        dirty = true;
    }

    public Map<String, Integer> snapshot() {
        return Map.copyOf(colors);
    }

    public void saveIfDirty() {
        if (!dirty) {
            return;
        }

        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            GLights.LOGGER.error("Failed to create config directory for {}", path, e);
            return;
        }

        JsonObject root = new JsonObject();
        JsonObject colorObject = new JsonObject();
        for (Map.Entry<String, Integer> entry : colors.entrySet()) {
            colorObject.addProperty(entry.getKey(), String.format(Locale.ROOT, "0x%06X", entry.getValue()));
        }
        root.add("colors", colorObject);

        JsonObject settingsObject = new JsonObject();
        settingsObject.addProperty("damageEffect", settings.damageEffect);
        settingsObject.addProperty("underwaterEffect", settings.underwaterEffect);
        settingsObject.addProperty("poisonEffect", settings.poisonEffect);
        settingsObject.addProperty("frozenEffect", settings.frozenEffect);
        settingsObject.addProperty("netherPortalEffect", settings.netherPortalEffect);
    settingsObject.addProperty("witherEffect", settings.witherEffect);
    settingsObject.addProperty("lowHealthBlink", settings.lowHealthBlink);
        settingsObject.addProperty("backgroundFillEnabled", settings.backgroundFillEnabled);
    settingsObject.addProperty("mouseLightingEnabled", settings.mouseLightingEnabled);
        settingsObject.addProperty("highlightSelectedSlot", settings.highlightSelectedSlot);
        root.add("settings", settingsObject);

        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(root, writer);
            dirty = false;
        } catch (IOException e) {
            GLights.LOGGER.error("Failed to write GLights config to {}", path, e);
        }
    }

    public void ensureDefaults(Iterable<String> categories) {
        for (String category : categories) {
            if (category == null) {
                continue;
            }
            getColorForCategory(category);
        }
    }

    private int defaultColorFor(String category) {
        Integer preset = DEFAULT_COLORS.get(category);
        if (preset != null) {
            return preset;
        }
        dirty = true;
        return DEFAULT_INVENTORY_COLOR;
    }

    private static int parseColor(String raw) {
        if (raw.startsWith("#")) {
            return Integer.decode("0x" + raw.substring(1));
        }
        return Integer.decode(raw);
    }

    private void readColors(JsonObject colorsObject) {
        for (Map.Entry<String, JsonElement> entry : colorsObject.entrySet()) {
            String category = entry.getKey();
            JsonElement valueElement = entry.getValue();
            if (category == null || valueElement == null || !valueElement.isJsonPrimitive()) {
                continue;
            }
            try {
                int color = parseColor(valueElement.getAsString().trim());
                colors.put(category, color & 0xFFFFFF);
            } catch (NumberFormatException exception) {
                GLights.LOGGER.warn("Ignoring malformed color '{}' for category '{}'", valueElement, category);
                dirty = true;
            }
        }
    }

    private void readLegacyColors(JsonObject legacyObject) {
        Map<String, String> raw = GSON.fromJson(legacyObject, TYPE);
        if (raw != null) {
            readLegacyColors(raw);
        }
    }

    private void readLegacyColors(Map<String, String> raw) {
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String category = entry.getKey();
            String value = entry.getValue();
            if (category == null || value == null) {
                continue;
            }
            try {
                int color = parseColor(value.trim());
                colors.put(category, color & 0xFFFFFF);
            } catch (NumberFormatException e) {
                GLights.LOGGER.warn("Ignoring malformed color '{}' for category '{}'", value, category);
                dirty = true;
            }
        }
    }

    private void readSettings(JsonObject settingsObject) {
        settings.damageEffect = getBoolean(settingsObject, "damageEffect", settings.damageEffect);
        settings.underwaterEffect = getBoolean(settingsObject, "underwaterEffect", settings.underwaterEffect);
        settings.poisonEffect = getBoolean(settingsObject, "poisonEffect", settings.poisonEffect);
        settings.frozenEffect = getBoolean(settingsObject, "frozenEffect", settings.frozenEffect);
        settings.netherPortalEffect = getBoolean(settingsObject, "netherPortalEffect", settings.netherPortalEffect);
        settings.witherEffect = getBoolean(settingsObject, "witherEffect", settings.witherEffect);
        settings.lowHealthBlink = getBoolean(settingsObject, "lowHealthBlink", settings.lowHealthBlink);
        settings.backgroundFillEnabled = getBoolean(settingsObject, "backgroundFillEnabled", settings.backgroundFillEnabled);
        settings.mouseLightingEnabled = getBoolean(settingsObject, "mouseLightingEnabled", settings.mouseLightingEnabled);
        settings.highlightSelectedSlot = getBoolean(settingsObject, "highlightSelectedSlot", settings.highlightSelectedSlot);
    }

    private static boolean getBoolean(JsonObject object, String key, boolean defaultValue) {
        if (object == null || !object.has(key)) {
            return defaultValue;
        }
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsBoolean();
        } catch (ClassCastException | IllegalStateException e) {
            return defaultValue;
        }
    }

    public boolean isDamageEffectEnabled() {
        return settings.damageEffect;
    }

    public void setDamageEffectEnabled(boolean enabled) {
        if (settings.damageEffect != enabled) {
            settings.damageEffect = enabled;
            dirty = true;
        }
    }

    public boolean isUnderwaterEffectEnabled() {
        return settings.underwaterEffect;
    }

    public void setUnderwaterEffectEnabled(boolean enabled) {
        if (settings.underwaterEffect != enabled) {
            settings.underwaterEffect = enabled;
            dirty = true;
        }
    }

    public boolean isPoisonEffectEnabled() {
        return settings.poisonEffect;
    }

    public void setPoisonEffectEnabled(boolean enabled) {
        if (settings.poisonEffect != enabled) {
            settings.poisonEffect = enabled;
            dirty = true;
        }
    }

    public boolean isFrozenEffectEnabled() {
        return settings.frozenEffect;
    }

    public void setFrozenEffectEnabled(boolean enabled) {
        if (settings.frozenEffect != enabled) {
            settings.frozenEffect = enabled;
            dirty = true;
        }
    }

    public boolean isNetherPortalEffectEnabled() {
        return settings.netherPortalEffect;
    }

    public void setNetherPortalEffectEnabled(boolean enabled) {
        if (settings.netherPortalEffect != enabled) {
            settings.netherPortalEffect = enabled;
            dirty = true;
        }
    }

    public boolean isWitherEffectEnabled() {
        return settings.witherEffect;
    }

    public void setWitherEffectEnabled(boolean enabled) {
        if (settings.witherEffect != enabled) {
            settings.witherEffect = enabled;
            dirty = true;
        }
    }

    public boolean isHighlightSelectedSlot() {
        return settings.highlightSelectedSlot;
    }

    public void setHighlightSelectedSlot(boolean enabled) {
        if (settings.highlightSelectedSlot != enabled) {
            settings.highlightSelectedSlot = enabled;
            dirty = true;
        }
    }

    public int getHighlightColor() {
        return getColorForCategory(CATEGORY_INVENTORY_SELECTED);
    }

    public void setHighlightColor(int color) {
        setColorForCategory(CATEGORY_INVENTORY_SELECTED, color);
    }

    public int getDefaultHighlightColor() {
        return DEFAULT_HIGHLIGHT_COLOR;
    }

    public int getWasdColor() {
        return getColorForCategory(CATEGORY_WASD);
    }

    public void setWasdColor(int color) {
        setColorForCategory(CATEGORY_WASD, color);
    }

    public int getDefaultWasdColor() {
        return DEFAULT_WASD_COLOR;
    }

    public boolean isBackgroundFillEnabled() {
        return settings.backgroundFillEnabled;
    }

    public void setBackgroundFillEnabled(boolean enabled) {
        if (settings.backgroundFillEnabled != enabled) {
            settings.backgroundFillEnabled = enabled;
            dirty = true;
        }
    }

    public int getBackgroundFillColor() {
        return getColorForCategory(CATEGORY_BACKGROUND);
    }

    public void setBackgroundFillColor(int color) {
        setColorForCategory(CATEGORY_BACKGROUND, color);
    }

    public int getDefaultBackgroundFillColor() {
        return DEFAULT_BACKGROUND_COLOR;
    }

    public boolean isMouseLightingEnabled() {
        return settings.mouseLightingEnabled;
    }

    public void setMouseLightingEnabled(boolean enabled) {
        if (settings.mouseLightingEnabled != enabled) {
            settings.mouseLightingEnabled = enabled;
            dirty = true;
        }
    }

    public boolean isLowHealthBlinkEnabled() {
        return settings.lowHealthBlink;
    }

    public void setLowHealthBlinkEnabled(boolean enabled) {
        if (settings.lowHealthBlink != enabled) {
            settings.lowHealthBlink = enabled;
            dirty = true;
        }
    }
}
