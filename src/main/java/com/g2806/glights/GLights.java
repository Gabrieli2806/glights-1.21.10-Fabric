package com.g2806.glights;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GLights implements ModInitializer {
	public static final String MOD_ID = "glights";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("GLights common hooks loaded");
	}
}