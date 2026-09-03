package dev.fraust.forgecast

/** How complete the forge data from the tab list currently is. */
enum class ForgeDataCase {
	/** Section found and all seven slots rendered. Nothing to say. */
	COMPLETE,

	/** Section found but the tab list ran out of rows partway through. */
	TRUNCATED,

	/**
	 * No widget rows at all. The tab-list widget system is switched off, not
	 * merely crowded - a different problem with a different fix.
	 *
	 * Announced at most ONCE per session. Unlike the others this is a standing
	 * configuration choice rather than something that comes and goes, so
	 * repeating it would just be nagging.
	 */
	WIDGETS_OFF_ENTIRELY,

	/**
	 * Widget rows exist but no Forges section, and there are unused rows. Room
	 * existed and nothing was drawn, so the forge widget is off for this island.
	 */
	WIDGET_OFF,

	/**
	 * Widget rows exist, no Forges section, and every column is full to the last
	 * row. The widget is probably on but got pushed out entirely.
	 */
	PUSHED_OUT,
}

/**
 * Decides whether the tab list is showing SkyBlock at all.
 *
 * Two independent signals, because either can be missing:
 *
 *  - The widget rows carry a "Profile:" line. Reliable, but it IS a widget row,
 *    so it vanishes exactly when the widgets are switched off - which is the
 *    case we most want to warn about.
 *  - The sidebar scoreboard is titled SKYBLOCK. Independent of the widget
 *    system entirely, so it still answers when the widgets are gone.
 *
 * Deliberately conservative: if neither signal is present we say no. A missed
 * warning costs one message; a false positive spams every lobby.
 */
object SkyBlockDetector {

	private const val SCOREBOARD_MARKER = "SKYBLOCK"

	/**
	 * @param scoreboardTitle the sidebar objective's display name with
	 *   formatting already stripped, or null when there is no sidebar.
	 */
	fun isSkyBlock(rows: List<TabRow>, scoreboardTitle: String?): Boolean {
		if (ProfileReader.profileOf(rows) != null) return true

		val title = scoreboardTitle ?: return false
		// Hypixel decorates the title, so match loosely on the word rather than
		// demanding an exact string.
		return title.uppercase().replace(" ", "").contains(SCOREBOARD_MARKER)
	}
}

/**
 * Works out whether the forge data is complete, and what the player could do
 * about it if not.
 *
 * Pure: no Minecraft types, so it is tested from captured dumps.
 */
object ForgeAdvice {

	/**
	 * Menu paths shown to the player.
	 *
	 * "/widget" is confirmed - it is the command used in-game to turn Wrapping
	 * on. The sub-item labels are best-effort and deliberately live here as
	 * single constants so they can be corrected in one place.
	 */
	const val PATH_ENABLE = "/widget  >  Forge Widget (on)"
	const val PATH_WRAPPING = "/widget  >  Forge Widget  >  Wrapping (on)"

	/** Rows in a tab-list column, "a" through "t". */
	private const val ROWS_PER_COLUMN = 20

	fun classify(rows: List<TabRow>, snapshot: ForgeSnapshot): ForgeDataCase {
		if (snapshot.foundSection) {
			return if (snapshot.renderedSlots >= ForgeParser.EXPECTED_SLOT_COUNT) {
				ForgeDataCase.COMPLETE
			} else {
				ForgeDataCase.TRUNCATED
			}
		}

		// No widget rows at all is a different problem from a crowded tab list.
		// Reporting "pushed out" here would send the player looking for a
		// crowding problem that does not exist.
		if (rows.none { it.profileName.startsWith("!") }) return ForgeDataCase.WIDGETS_OFF_ENTIRELY

		// No section. Was there room for one?
		return if (spareRows(rows) > 0) ForgeDataCase.WIDGET_OFF else ForgeDataCase.PUSHED_OUT
	}

	/**
	 * How many unused rows sit at the bottom of the columns.
	 *
	 * Counted from the end of each column, because a blank row in the middle is
	 * a deliberate separator rather than free space.
	 */
	fun spareRows(rows: List<TabRow>): Int {
		val widgets = rows.filter { it.profileName.startsWith("!") && it.profileName.length >= 4 }
		if (widgets.isEmpty()) return 0

		return widgets
			.groupBy { it.profileName[1] }
			.values
			.sumOf { column ->
				val sorted = column.sortedBy { it.profileName }
				val lastUsed = sorted.indexOfLast {
					ForgeParser.stripFormatting(it.rawText).trim().isNotEmpty()
				}
				// Unused rows the server sent, plus rows it never sent at all.
				(sorted.size - 1 - lastUsed) + (ROWS_PER_COLUMN - sorted.size)
			}
	}

	/**
	 * The message for a case, or null when there is nothing worth saying.
	 *
	 * [renderedSlots] is only used by [ForgeDataCase.TRUNCATED].
	 */
	fun message(case: ForgeDataCase, renderedSlots: Int): List<String>? = when (case) {
		ForgeDataCase.COMPLETE -> null

		ForgeDataCase.TRUNCATED -> listOf(
			"Only $renderedSlots of ${ForgeParser.EXPECTED_SLOT_COUNT} forge slots fit in the tab list here.",
			"Turn Wrapping on to see the rest:",
			PATH_WRAPPING,
		)

		ForgeDataCase.WIDGETS_OFF_ENTIRELY -> listOf(
			"The tab-list widgets are switched off, so the forge cannot be read.",
			"Switch them on:",
			PATH_ENABLE,
		)

		ForgeDataCase.WIDGET_OFF -> listOf(
			"No Forge widget in the tab list here, and there is room for one.",
			"Switch it on:",
			PATH_ENABLE,
		)

		ForgeDataCase.PUSHED_OUT -> listOf(
			"The Forge widget has been pushed out - every tab-list column is full.",
			"Turn Wrapping on, or switch off a widget you do not need here:",
			PATH_WRAPPING,
		)
	}

	/** One short line for the settings screen. Null when there is no problem. */
	fun summary(case: ForgeDataCase, renderedSlots: Int): String? = when (case) {
		ForgeDataCase.COMPLETE -> null
		ForgeDataCase.TRUNCATED ->
			"Forge data incomplete - only $renderedSlots of ${ForgeParser.EXPECTED_SLOT_COUNT} slots visible"
		ForgeDataCase.WIDGETS_OFF_ENTIRELY -> "Forge data unavailable - tab-list widgets are off"
		ForgeDataCase.WIDGET_OFF -> "Forge data unavailable - the Forge widget is off here"
		ForgeDataCase.PUSHED_OUT -> "Forge data unavailable - the tab list is full"
	}

	/** The one-line fix for a case, for the settings screen. */
	fun fixPath(case: ForgeDataCase): String? = when (case) {
		ForgeDataCase.COMPLETE -> null
		ForgeDataCase.TRUNCATED, ForgeDataCase.PUSHED_OUT -> PATH_WRAPPING
		ForgeDataCase.WIDGETS_OFF_ENTIRELY, ForgeDataCase.WIDGET_OFF -> PATH_ENABLE
	}
}

/**
 * Decides when a message should actually be shown.
 *
 * Throttling is by CHANGE, not by clock. A message fires only when the case
 * differs from the one last announced, which gives once-per-case for free and
 * re-arms by itself when the situation is fixed and then breaks again.
 *
 * [ONE_SHOT_CASES] are the exception: a standing configuration choice rather
 * than a passing condition, so they are said once per session and then never
 * again, even after a [reset].
 */
class AdviceThrottle {

	private companion object {
		val ONE_SHOT_CASES = setOf(ForgeDataCase.WIDGETS_OFF_ENTIRELY)
	}

	private var lastAnnounced: ForgeDataCase? = null
	private val alreadySaidThisSession = mutableSetOf<ForgeDataCase>()

	/** The case to announce now, or null to stay quiet. */
	fun announce(case: ForgeDataCase): ForgeDataCase? {
		if (case in ONE_SHOT_CASES) {
			// add() returns false when it was already there.
			if (!alreadySaidThisSession.add(case)) return null
			lastAnnounced = case
			return case
		}

		if (case == lastAnnounced) return null
		lastAnnounced = case
		// COMPLETE is recorded so that a later problem re-announces, but it is
		// never itself worth saying out loud.
		return if (case == ForgeDataCase.COMPLETE) null else case
	}

	/**
	 * Called when leaving SkyBlock, so returning re-announces honestly.
	 *
	 * Deliberately does NOT clear the one-shot record: "once per session" has
	 * to survive walking through a lobby.
	 */
	fun reset() {
		lastAnnounced = null
	}
}
