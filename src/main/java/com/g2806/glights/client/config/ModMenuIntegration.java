package com.g2806.glights.client.config;

import com.g2806.glights.client.GLightsClient;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> buildScreen(parent, GLightsClient.CONFIG);
    }

    private Screen buildScreen(Screen parent, ConfigManager config) {
        if (config == null) {
            return parent;
        }

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.glights.title"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        ConfigCategory effects = builder.getOrCreateCategory(Component.translatable("config.glights.category.effects"));
        effects.addEntry(entryBuilder
                .startBooleanToggle(Component.translatable("config.glights.effect.damage"), config.isDamageEffectEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.glights.effect.damage.tooltip"))
                .setSaveConsumer(config::setDamageEffectEnabled)
                .build());
        effects.addEntry(entryBuilder
                .startBooleanToggle(Component.translatable("config.glights.effect.underwater"), config.isUnderwaterEffectEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.glights.effect.underwater.tooltip"))
                .setSaveConsumer(config::setUnderwaterEffectEnabled)
                .build());
        effects.addEntry(entryBuilder
                .startBooleanToggle(Component.translatable("config.glights.effect.poison"), config.isPoisonEffectEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.glights.effect.poison.tooltip"))
                .setSaveConsumer(config::setPoisonEffectEnabled)
                .build());
        effects.addEntry(entryBuilder
                .startBooleanToggle(Component.translatable("config.glights.effect.frozen"), config.isFrozenEffectEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.glights.effect.frozen.tooltip"))
                .setSaveConsumer(config::setFrozenEffectEnabled)
                .build());
    effects.addEntry(entryBuilder
        .startBooleanToggle(Component.translatable("config.glights.effect.nether_portal"), config.isNetherPortalEffectEnabled())
        .setDefaultValue(true)
        .setTooltip(Component.translatable("config.glights.effect.nether_portal.tooltip"))
        .setSaveConsumer(config::setNetherPortalEffectEnabled)
        .build());

        ConfigCategory hotbar = builder.getOrCreateCategory(Component.translatable("config.glights.category.hotbar"));
        hotbar.addEntry(entryBuilder
                .startBooleanToggle(Component.translatable("config.glights.hotbar.highlight_selected"), config.isHighlightSelectedSlot())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.glights.hotbar.highlight_selected.tooltip"))
                .setSaveConsumer(config::setHighlightSelectedSlot)
                .build());
    hotbar.addEntry(entryBuilder
        .startColorField(Component.translatable("config.glights.hotbar.highlight_color"), config.getHighlightColor())
        .setDefaultValue(config.getDefaultHighlightColor())
        .setTooltip(Component.translatable("config.glights.hotbar.highlight_color.tooltip"))
        .setSaveConsumer(color -> config.setHighlightColor(color & 0xFFFFFF))
        .build());

    ConfigCategory keyColors = builder.getOrCreateCategory(Component.translatable("config.glights.category.keys"));
    keyColors.addEntry(entryBuilder
        .startColorField(Component.translatable("config.glights.keys.wasd_color"), config.getWasdColor())
        .setDefaultValue(config.getDefaultWasdColor())
        .setTooltip(Component.translatable("config.glights.keys.wasd_color.tooltip"))
        .setSaveConsumer(color -> config.setWasdColor(color & 0xFFFFFF))
        .build());

        builder.setSavingRunnable(() -> {
            config.saveIfDirty();
            if (GLightsClient.EVENTS != null) {
                GLightsClient.EVENTS.onConfigChanged();
            }
        });

        return builder.build();
    }
}
