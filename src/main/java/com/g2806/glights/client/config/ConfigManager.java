package com.g2806.glights.client.config;

import com.g2806.glights.GLights;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, String>>() { }.getType();
    private static final Map<String, Integer> DEFAULT_COLORS = ImmutableMap.<String, Integer>builder()
            .put(CATEGORY_UNKNOWN, 0xFF0000)
            .put(CATEGORY_DEAD, 0xFF0000)
            .put(CATEGORY_INVENTORY, 0x00FF00)
            .put(CATEGORY_INVENTORY_SELECTED, 0xFF7F00)
            .put("key.categories.movement", 0x00DCFF)
            .put("key.categories.gameplay", 0xFFFFFF)
            .put("key.categories.creative", 0x8000FF)
            .put("key.categories.multiplayer", 0xFFDC00)
            .put("key.categories.ui", 0x0000FF)
            .put("key.categories.misc", 0x0000FF)
            .build();

    private final Path path;
    private final Map<String, Integer> colors = new HashMap<>();
    private boolean dirty;

    public ConfigManager(Path path) {
        this.path = path;
    }

    public void load() {
        colors.clear();
        colors.putAll(DEFAULT_COLORS);
        dirty = false;

        if (!Files.exists(path)) {
            dirty = true;
            return;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            Map<String, String> raw = GSON.fromJson(reader, TYPE);
            if (raw == null) {
                dirty = true;
                return;
            }

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

        Map<String, String> serialized = new HashMap<>();
        for (Map.Entry<String, Integer> entry : colors.entrySet()) {
            serialized.put(entry.getKey(), String.format(Locale.ROOT, "0x%06X", entry.getValue()));
        }

        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(serialized, TYPE, writer);
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
        return 0x00FF00;
    }

    private static int parseColor(String raw) {
        if (raw.startsWith("#")) {
            return Integer.decode("0x" + raw.substring(1));
        }
        return Integer.decode(raw);
    }
}
