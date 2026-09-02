package dev.fraust.forgecast

import java.time.Instant

/**
 * One slot of an open container screen, reduced to what the parser needs.
 *
 * Deliberately not a Minecraft type, so the parser can be exercised from a
 * saved GUI dump without launching the game.
 */
data class GuiSlotRow(
	val index: Int,
	val count: Int,
	val rawName: String,
	val tooltip: List<String>,
)

/**
 * The result of reading forge slots out of an open Forge screen.
 *
 * [slots] always covers forge slots 1..7. A slot missing from the capture is
 * UNKNOWN with no raw text; a slot that was present but unreadable is UNKNOWN
 * *with* its text, so the two can be told apart.
 */
data class GuiForgeSnapshot(
	val slots: List<ForgeSlot>,
	val capturedAt: Instant,
	/** Anything suspicious, such as a slot whose own number disagreed. */
	val problems: List<String> = emptyList(),
)

/**
 * Reads forge slots from the Forge GUI.
 *
 * Why this exists alongside [ForgeParser]: the tab list rounds times to a
 * single unit ("9h"), while the GUI gives "8h 46m 2s". Two captures 5.894s
 * apart were observed to tick 6s, so the GUI figure is exact rather than an
 * estimate, and nothing here builds in the approximation the tab-list path
 * needs.
 *
 * Returns the same [ForgeSlot] type the tab parser returns, so both feed one
 * model. No change to that type was required: an exact finish time is
 * `observedAt + remaining`, and every slot here is stamped with the capture
 * time.
 */
object GuiForgeParser {

	/** GUI slot 10 holds forge slot 1, through GUI slot 16 for forge slot 7. */
	const val FIRST_FORGE_GUI_SLOT = 10

	private const val COMPLETED = "Completed!"

	private val TIME_REMAINING = Regex("""^Time Remaining:\s*(.+)$""")
	private val CURRENTLY_MAKING = Regex("""^Currently making:\s*(.+)$""")

	/** An empty slot is named after itself: "Slot #3". */
	private val SLOT_NAME = Regex("""^Slot\s*#(\d+)$""")

	fun parse(rows: List<GuiSlotRow>, capturedAt: Instant): GuiForgeSnapshot {
		val byIndex = rows.associateBy { it.index }
		val problems = mutableListOf<String>()

		val slots = (1..ForgeParser.EXPECTED_SLOT_COUNT).map { forgeSlot ->
			val row = byIndex[FIRST_FORGE_GUI_SLOT + forgeSlot - 1]
				// Absent from the capture. Unknown, and deliberately no raw text.
				?: return@map ForgeSlot(forgeSlot, ForgeSlotState.UNKNOWN)
			readSlot(forgeSlot, row, capturedAt, problems)
		}

		return GuiForgeSnapshot(slots, capturedAt, problems)
	}

	private fun readSlot(
		forgeSlot: Int,
		row: GuiSlotRow,
		capturedAt: Instant,
		problems: MutableList<String>,
	): ForgeSlot {
		val name = ForgeParser.stripFormatting(row.rawName).trim()
		val tooltip = row.tooltip.map { ForgeParser.stripFormatting(it).trim() }

		// An empty slot states its own number. Trust that over grid position:
		// if Hypixel ever moves the grid, believing the position would report
		// the wrong forge slot without any sign that anything was wrong.
		SLOT_NAME.find(name)?.let { match ->
			val declared = match.groupValues[1].toIntOrNull()
			if (declared != forgeSlot) {
				problems += "GUI slot ${row.index} should be forge slot $forgeSlot " +
					"but calls itself slot $declared"
				return ForgeSlot(forgeSlot, ForgeSlotState.UNKNOWN, rawText = name)
			}
			return ForgeSlot(forgeSlot, ForgeSlotState.EMPTY, observedAt = capturedAt)
		}

		val itemName = tooltip.firstNotNullOfOrNull { CURRENTLY_MAKING.find(it)?.groupValues?.get(1) }
			?.trim()
			?: name

		val timeValue = tooltip.firstNotNullOfOrNull { TIME_REMAINING.find(it)?.groupValues?.get(1) }
			?.trim()
			// Occupied, but nothing we recognise. Keep the name so it can be
			// reported rather than silently dropped.
			?: return ForgeSlot(forgeSlot, ForgeSlotState.UNKNOWN, itemName = itemName, rawText = name)

		if (timeValue.equals(COMPLETED, ignoreCase = true)) {
			return ForgeSlot(
				forgeSlot, ForgeSlotState.READY,
				itemName = itemName, observedAt = capturedAt,
			)
		}

		// Same duration grammar as the tab parser - the same function, not a
		// second implementation that could drift away from it.
		val remaining = ForgeParser.parseDuration(timeValue)
			?: return ForgeSlot(
				forgeSlot, ForgeSlotState.UNKNOWN,
				itemName = itemName, rawText = "Time Remaining: $timeValue",
			)

		return ForgeSlot(
			forgeSlot, ForgeSlotState.IN_PROGRESS,
			itemName = itemName,
			remaining = remaining,
			// Stamped so an exact finish time is observedAt + remaining.
			observedAt = capturedAt,
		)
	}
}
