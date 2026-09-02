package com.example.myfirstmod

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

/**
 * Entry point for the mod.
 *
 * Minecraft calls [onInitialize] once, while the game is starting up. This is
 * declared as an `object` (a Kotlin singleton) because Fabric's Kotlin adapter
 * expects a single instance, not a class it has to construct itself.
 */
object MyFirstMod : ModInitializer {

	private val logger = LoggerFactory.getLogger("myfirstmod")

	override fun onInitialize() {
		logger.info("Hello from My First Mod - Kotlin is running inside Minecraft!")
	}
}
