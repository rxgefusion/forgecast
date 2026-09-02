package dev.fraust.forgecast

import net.fabricmc.loader.api.FabricLoader

/**
 * Gate for the capture tools used to work out Hypixel's formats.
 *
 * These exist to gather data, not to play with. They write files, and the
 * screen capture walks every slot of every open container and builds each
 * tooltip - the most expensive thing this mod does. None of it belongs in a
 * build someone else runs.
 *
 * Two independent conditions, both required:
 *
 *  1. [inDevelopmentEnvironment] - true only when launched from Gradle. A jar
 *     installed in a normal mods folder always reports false, so the commands
 *     are never even registered there. This is not a setting anyone can flip.
 *
 *  2. [optedIn] - off by default even in development. Set from config, or from
 *     -Dforgecast.devtools=true for a single launch.
 *
 * The result is that the tools are off unless explicitly asked for, and
 * unreachable in a release build regardless of what any config file says.
 */
object DevTools {

	/**
	 * Whether the game was launched from the development environment.
	 *
	 * Read once: it cannot change while the game runs, and this is checked on
	 * a tick.
	 */
	val inDevelopmentEnvironment: Boolean = FabricLoader.getInstance().isDevelopmentEnvironment

	/**
	 * Whether the player has asked for the tools this session.
	 *
	 * Defaults to false. Config sets this during startup; the system property
	 * is a convenience for a one-off launch.
	 */
	var optedIn: Boolean = System.getProperty("forgecast.devtools")?.equals("true", ignoreCase = true) == true

	/** Whether the capture tools may do anything at all. */
	val available: Boolean get() = inDevelopmentEnvironment && optedIn

	/** Human-readable reason the tools are unavailable, or null when they are. */
	fun unavailableReason(): String? = when {
		!inDevelopmentEnvironment ->
			"the capture tools only exist in a development build"
		!optedIn ->
			"the capture tools are switched off - enable them in /forgecast"
		else -> null
	}
}
