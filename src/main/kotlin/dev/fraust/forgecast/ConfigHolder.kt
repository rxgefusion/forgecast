package dev.fraust.forgecast

import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The live settings, and the one place that touches the config file.
 *
 * Kept apart from [ForgeCastConfig] and [ConfigStore] so those stay free of
 * Fabric types and can be tested without a game.
 */
object ConfigHolder {

	private val logger = LoggerFactory.getLogger("forgecast")

	private val file: File by lazy {
		FabricLoader.getInstance().configDir.resolve("forgecast.properties").toFile()
	}

	var current: ForgeCastConfig = ForgeCastConfig()
		private set

	/** Called once at startup. Never throws: worst case the defaults stand. */
	fun load() {
		current = ConfigStore.load(file)
		applyTo()
		logger.info("Settings loaded from {}", file.absolutePath)
	}

	/**
	 * Changes settings and writes them out.
	 *
	 * The in-memory value updates even when the write fails, so a read-only
	 * disk costs persistence rather than making the menu appear broken.
	 */
	fun update(change: (ForgeCastConfig) -> ForgeCastConfig) {
		current = change(current)
		applyTo()
		if (!ConfigStore.save(file, current)) {
			logger.warn("Could not write settings to {} - they apply now but will not survive a restart", file)
		}
	}

	/** Pushes settings into the places that read them outside the config object. */
	private fun applyTo() {
		DevTools.optedIn = current.devToolsEnabled
	}
}
