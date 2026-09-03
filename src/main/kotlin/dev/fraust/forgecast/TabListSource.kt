package dev.fraust.forgecast

import net.minecraft.client.Minecraft

/**
 * The single once-per-second reading of the tab list.
 *
 * Before this existed the HUD and the advice check each read and parsed the
 * tab list on their own one-second timers. Two consequences, one of them a
 * real bug:
 *
 *  - **They could disagree.** The timers were independent, so the two paths
 *    sampled different instants. During a warp that produced a warning about a
 *    missing widget while the panel showed correct times from a different
 *    sample. Reading the same result makes that contradiction impossible.
 *  - **Everything was done twice.** Two component-tree walks over ~105 tab
 *    entries and two parses per second, for one answer.
 *
 * Refreshed from the client tick; read from the render thread. Both are the
 * game's main thread, so no synchronisation is needed.
 */
object TabListSource {

	private const val REFRESH_INTERVAL_MS = 1_000L

	private var lastRefreshMs = 0L

	/** The rows as last read. Empty when there is nothing to read. */
	var rows: List<TabRow> = emptyList()
		private set

	/** The parse of [rows], or null when not connected. */
	var snapshot: ForgeSnapshot? = null
		private set

	/** The profile the last reading belonged to, or null if unreadable. */
	var profile: String? = null
		private set

	/** Bumped on every successful refresh, so callers can spot a new reading. */
	var generation: Long = 0L
		private set

	/**
	 * Re-reads at most once per second. Call from the client tick.
	 *
	 * @return true when this call produced a new reading.
	 */
	fun refreshIfDue(client: Minecraft, nowMs: Long = System.currentTimeMillis()): Boolean {
		if (nowMs - lastRefreshMs < REFRESH_INTERVAL_MS) return false
		lastRefreshMs = nowMs

		val connection = client.connection
		if (connection == null) {
			clear()
			return true
		}

		rows = ForgeCast.readTabRows(connection)
		snapshot = ForgeParser.parse(rows)
		profile = ProfileReader.profileOf(rows)
		generation++
		return true
	}

	/** Drops the reading, so nothing stale can be served after a disconnect. */
	fun clear() {
		rows = emptyList()
		snapshot = null
		profile = null
		lastRefreshMs = 0L
	}
}
