package dev.cowgirlfraust.forgecast

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

/**
 * Entry point for the mod.
 *
 * Minecraft calls [onInitialize] once, while the game is starting up. This is
 * declared as an `object` (a Kotlin singleton) because Fabric's Kotlin adapter
 * expects a single instance, not a class it has to construct itself.
 *
 * The name passed to the logger is what appears in brackets in the game log,
 * so this mod's messages show up as "(forgecast)".
 */
object ForgeCast : ModInitializer {

	private val logger = LoggerFactory.getLogger("forgecast")

	override fun onInitialize() {
		logger.info("Hello from ForgeCast - Kotlin is running inside Minecraft!")
	}
}
