package dev.fraust.forgecast

import java.io.File

/**
 * Everything the player can change.
 *
 * Defaults are deliberately minimal: nothing draws on screen until it is asked
 * for. The plan's settings section is explicit that the loudest complaint about
 * SkyBlock mods is arriving with everything switched on.
 *
 * The one thing on by default is [adviceEnabled], because it only ever speaks
 * when the mod cannot see the forge, and only once per situation. A silent
 * failure is worse than one quiet line.
 */
data class ForgeCastConfig(
	val hudEnabled: Boolean = false,
	val hudX: Int = 4,
	val hudY: Int = 4,
	/** Percent, so the file never contains a locale-dependent decimal point. */
	val hudScale: Int = 100,
	val adviceEnabled: Boolean = true,
	/**
	 * A chat line when a slot finishes.
	 *
	 * On by default, unlike the panel. It draws nothing, and it speaks only on a
	 * confirmed change - which for a forge is a few times a day at most.
	 */
	val completionAlertEnabled: Boolean = true,
	/**
	 * A chat line when a GUI-derived finish time passes out of sight.
	 *
	 * On by default. This is the one thing the mod knows that nothing reading
	 * only the tab list can know, and it is worded as expectation rather than
	 * fact - so the honest failure is a line saying "should be ready" about
	 * something already collected, not a false claim.
	 */
	val forecastAlertEnabled: Boolean = true,
	/** A chime alongside it. Off: a sound nobody asked for is an intrusion. */
	val completionSoundEnabled: Boolean = false,
) {
	companion object {
		const val MIN_SCALE = 50
		const val MAX_SCALE = 300
	}

	/** Clamped copy. Applied on load so a hand-edited file cannot break drawing. */
	fun sanitised(screenWidth: Int = Int.MAX_VALUE, screenHeight: Int = Int.MAX_VALUE): ForgeCastConfig =
		copy(
			hudX = hudX.coerceIn(0, (screenWidth - 1).coerceAtLeast(0)),
			hudY = hudY.coerceIn(0, (screenHeight - 1).coerceAtLeast(0)),
			hudScale = hudScale.coerceIn(MIN_SCALE, MAX_SCALE),
		)
}

/**
 * Turns a config into text and back.
 *
 * Hand-rolled on purpose: a config library is a dependency whose behaviour we
 * would have to trust, and the format here is six keys.
 *
 * [decode] NEVER throws. A missing key, a misspelled key, a value that is not a
 * number, a truncated file, or outright binary rubbish all fall back to the
 * default for that one field. A corrupt config costs the player their settings,
 * never their game.
 */
object ConfigCodec {

	const val CURRENT_VERSION = 1

	private const val KEY_VERSION = "version"
	private const val KEY_HUD_ENABLED = "hud.enabled"
	private const val KEY_HUD_X = "hud.x"
	private const val KEY_HUD_Y = "hud.y"
	private const val KEY_HUD_SCALE = "hud.scale"
	private const val KEY_ADVICE = "advice.enabled"
	private const val KEY_ALERT = "alert.enabled"
	private const val KEY_ALERT_FORECAST = "alert.forecast"
	private const val KEY_ALERT_SOUND = "alert.sound"

	fun encode(config: ForgeCastConfig): String = buildString {
		appendLine("# ForgeCast settings. Edited by the game; hand edits are read back on next start.")
		appendLine("$KEY_VERSION=$CURRENT_VERSION")
		appendLine("$KEY_HUD_ENABLED=${config.hudEnabled}")
		appendLine("$KEY_HUD_X=${config.hudX}")
		appendLine("$KEY_HUD_Y=${config.hudY}")
		appendLine("$KEY_HUD_SCALE=${config.hudScale}")
		appendLine("$KEY_ADVICE=${config.adviceEnabled}")
		appendLine("$KEY_ALERT=${config.completionAlertEnabled}")
		appendLine("$KEY_ALERT_FORECAST=${config.forecastAlertEnabled}")
		appendLine("$KEY_ALERT_SOUND=${config.completionSoundEnabled}")
	}

	fun decode(text: String): ForgeCastConfig {
		val values = mutableMapOf<String, String>()
		for (rawLine in text.lineSequence()) {
			val line = rawLine.trim()
			if (line.isEmpty() || line.startsWith("#")) continue
			val separator = line.indexOf('=')
			if (separator <= 0) continue
			values[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
		}

		val defaults = ForgeCastConfig()
		return ForgeCastConfig(
			hudEnabled = values.bool(KEY_HUD_ENABLED, defaults.hudEnabled),
			hudX = values.int(KEY_HUD_X, defaults.hudX),
			hudY = values.int(KEY_HUD_Y, defaults.hudY),
			hudScale = values.int(KEY_HUD_SCALE, defaults.hudScale),
			adviceEnabled = values.bool(KEY_ADVICE, defaults.adviceEnabled),
			completionAlertEnabled = values.bool(KEY_ALERT, defaults.completionAlertEnabled),
			forecastAlertEnabled = values.bool(KEY_ALERT_FORECAST, defaults.forecastAlertEnabled),
			completionSoundEnabled = values.bool(KEY_ALERT_SOUND, defaults.completionSoundEnabled),
		).sanitised()
	}

	/** Anything that is not exactly true or false keeps the default. */
	private fun Map<String, String>.bool(key: String, fallback: Boolean): Boolean =
		when (this[key]?.lowercase()) {
			"true" -> true
			"false" -> false
			else -> fallback
		}

	private fun Map<String, String>.int(key: String, fallback: Int): Int =
		this[key]?.toIntOrNull() ?: fallback
}

/**
 * Reads and writes the config file.
 *
 * Saving writes to a temporary file and then replaces the real one, so a crash
 * mid-write leaves the previous settings intact rather than a half-written file
 * that would read as defaults.
 */
object ConfigStore {

	fun load(file: File): ForgeCastConfig {
		return try {
			if (!file.exists()) ForgeCastConfig() else ConfigCodec.decode(file.readText(Charsets.UTF_8))
		} catch (error: Exception) {
			// Unreadable for any reason - permissions, a directory where a file
			// should be, malformed bytes. Defaults are always better than a crash.
			ForgeCastConfig()
		}
	}

	/** Returns true when the settings reached disk. */
	fun save(file: File, config: ForgeCastConfig): Boolean = try {
		// Refuse rather than clear the way. File.delete() succeeds on an empty
		// directory, so without this check a directory sitting at the config
		// path would be silently removed to make room.
		if (file.isDirectory) return false

		file.parentFile?.mkdirs()
		val temp = File(file.parentFile, "${file.name}.tmp")
		temp.writeText(ConfigCodec.encode(config), Charsets.UTF_8)
		if (file.exists()) file.delete()
		val moved = temp.renameTo(file)
		if (!moved) {
			// Fall back to a direct write rather than losing the change.
			file.writeText(ConfigCodec.encode(config), Charsets.UTF_8)
			temp.delete()
		}
		true
	} catch (error: Exception) {
		false
	}
}
