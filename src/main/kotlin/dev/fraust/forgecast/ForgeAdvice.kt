package dev.fraust.forgecast

/** How complete the forge data from the tab list currently is. */
enum class ForgeDataCase {
	/** Section found and all seven slots rendered. Nothing to say. */
	COMPLETE,

	/** Section found but the tab list ran out of rows partway through. */
	TRUNCATED,

	/**
	 * No section at all, and there are unused rows. Room existed and nothing
	 * was drawn, so the widget is most likely switched off.
	 */
	WIDGET_OFF,

	/**
	 * No section at all, and every column is full to the last row. The widget
	 * is probably on but got pushed out entirely.
	 */
	PUSHED_OUT,
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
}

/**
 * Decides when a message should actually be shown.
 *
 * Throttling is by CHANGE, not by clock. A message fires only when the case
 * differs from the one last announced, which gives once-per-case for free and
 * re-arms by itself when the situation is fixed and then breaks again. There
 * is no cooldown to tune and no timer that can drift.
 */
class AdviceThrottle {

	private var lastAnnounced: ForgeDataCase? = null

	/** The case to announce now, or null to stay quiet. */
	fun announce(case: ForgeDataCase): ForgeDataCase? {
		if (case == lastAnnounced) return null
		lastAnnounced = case
		// COMPLETE is recorded so that a later problem re-announces, but it is
		// never itself worth saying out loud.
		return if (case == ForgeDataCase.COMPLETE) null else case
	}

	/** Called when leaving SkyBlock, so returning re-announces honestly. */
	fun reset() {
		lastAnnounced = null
	}
}
