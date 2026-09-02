package dev.fraust.forgecast

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One tab-list row, reduced to just what the parser needs.
 *
 * Deliberately NOT a Minecraft type. The game is one source of these rows; a
 * saved dump file is another. Keeping this plain is what lets the parser be
 * tested without launching Minecraft.
 */
data class TabRow(
	val profileName: String,
	val rawText: String,
)

/** What a single forge slot is currently doing. */
enum class ForgeSlotState {
	/** The server explicitly showed this slot as EMPTY. */
	EMPTY,

	/** Counting down; [ForgeSlot.remaining] holds how long is left. */
	IN_PROGRESS,

	/** Finished but not yet collected. */
	READY,

	/**
	 * We do not know. Either the row never rendered (the tab list truncates
	 * when a column runs out of rows) or its text did not match any shape we
	 * recognise. NEVER treat this as empty.
	 */
	UNKNOWN,
}

/**
 * One forge slot.
 *
 * [observedAt] is reserved for the caching layer that will merge snapshots
 * taken in different places. The parser itself is time-unaware and always
 * leaves it null; whoever calls the parser is responsible for stamping it.
 */
data class ForgeSlot(
	val slot: Int,
	val state: ForgeSlotState,
	val itemName: String? = null,
	val remaining: Duration? = null,
	/** The stripped row this came from, kept so UNKNOWN can be diagnosed. */
	val rawText: String? = null,
	val observedAt: Instant? = null,
)

/** The result of parsing one tab-list snapshot. */
data class ForgeSnapshot(
	/** Always covers slots 1..n with no gaps. Slots that did not render are UNKNOWN. */
	val slots: List<ForgeSlot>,
	/** Profile name of the Forges header row, e.g. "!C-b". Null if not found. */
	val headerProfile: String? = null,
	/** How many slot rows the server actually rendered. */
	val renderedSlots: Int = 0,
	/** Rows inside the section that matched no known shape. */
	val unparsedRows: List<String> = emptyList(),
) {
	val foundSection: Boolean get() = headerProfile != null
}

/**
 * Turns tab-list rows into forge slots.
 *
 * Every rule here came from real captured data, not from documentation.
 */
object ForgeParser {

	/** Hypixel has shown 7 slots. If a higher number ever appears we widen. */
	const val EXPECTED_SLOT_COUNT = 7

	private const val SECTION_SIGN = '§'
	private const val FORGES_HEADER = "Forges:"

	/**
	 * The centred heading every column opens with. It is padding, and it sits
	 * between a wrapped section's header and its continuation, so it must be
	 * skipped rather than read as the end of the section.
	 */
	private const val COLUMN_HEADING = "Info"

	/** Emitted by the dumper for RGB colours that have no legacy code. */
	private val HEX_MARKER = Regex("<#[0-9A-Fa-f]{6}>")

	/** " 3) EMPTY" or " 1) Refined Titanium: 11h" */
	private val SLOT_ROW = Regex("""^(\d+)\)\s*(.*)$""")

	/** A section heading such as "Commissions:" or "Pickaxe Ability:". */
	private val HEADER_ROW = Regex("""^[A-Za-z][A-Za-z \-]*:$""")

	/** One duration piece: 11h, 30m, 26s, 2d. */
	private val DURATION_TOKEN = Regex("""(\d+)([dhms])""", RegexOption.IGNORE_CASE)

	/** Removes section-sign codes and our own hex markers. */
	fun stripFormatting(text: String): String {
		val out = StringBuilder(text.length)
		var i = 0
		while (i < text.length) {
			if (text[i] == SECTION_SIGN && i + 1 < text.length) {
				i += 2
				continue
			}
			out.append(text[i])
			i++
		}
		return HEX_MARKER.replace(out, "")
	}

	/**
	 * Parses a duration, requiring the WHOLE string to be consumed.
	 *
	 * "11h", "1h30m" and "26s" all parse. "11h ago" does not - a partial match
	 * returns null rather than silently losing the rest, so odd text becomes
	 * UNKNOWN instead of a confidently wrong number.
	 */
	fun parseDuration(value: String): Duration? {
		val cleaned = value.filterNot { it.isWhitespace() }
		if (cleaned.isEmpty()) return null

		var total = Duration.ZERO
		var consumed = 0
		var matched = false

		for (match in DURATION_TOKEN.findAll(cleaned)) {
			// Reject gaps: every character must belong to a token.
			if (match.range.first != consumed) return null
			val amount = match.groupValues[1].toLongOrNull() ?: return null
			total += when (match.groupValues[2].lowercase()) {
				"d" -> amount.days
				"h" -> amount.hours
				"m" -> amount.minutes
				"s" -> amount.seconds
				else -> return null
			}
			consumed = match.range.last + 1
			matched = true
		}

		return if (matched && consumed == cleaned.length) total else null
	}


	fun parse(rows: List<TabRow>): ForgeSnapshot {
		// 1. Widget rows only. Real players never carry widget text.
		// 2. Sort - the game hands rows back in scrambled order.
		val widgets = rows
			.filter { it.profileName.startsWith("!") }
			.sortedBy { it.profileName }

		// 3. Locate the header by its text, never by a hardcoded position:
		//    it moves between islands.
		val headerIndex = widgets.indexOfFirst {
			stripFormatting(it.rawText).trim() == FORGES_HEADER
		}
		if (headerIndex < 0) {
			return ForgeSnapshot(slots = fillMissing(emptyMap(), EXPECTED_SLOT_COUNT))
		}

		val header = widgets[headerIndex]

		val found = LinkedHashMap<Int, ForgeSlot>()
		val unparsed = mutableListOf<String>()

		// 4. Read forward until the section ends.
		//
		// This deliberately crosses column boundaries. With Wrapping enabled on
		// the Forge widget, Hypixel puts the header on the last row of one
		// column and continues the slots at the top of the next, separated by
		// that column's own heading. An earlier version stopped at the boundary
		// and read zero slots from such a layout.
		for (i in headerIndex + 1 until widgets.size) {
			// Never invent more slots than the forge has.
			if (found.size >= EXPECTED_SLOT_COUNT) break

			val text = stripFormatting(widgets[i].rawText).trim()

			// Column padding: blank filler rows and the heading a column opens
			// with. These sit between a wrapped section and its continuation,
			// so they are skipped rather than treated as the end.
			if (text.isEmpty() || text == COLUMN_HEADING) continue

			// A different section begins here.
			if (HEADER_ROW.matches(text)) break

			val slot = parseSlotRow(text)
			if (slot == null) {
				// Not padding, not a heading, not a slot: we have left the
				// section. Recorded so it is possible to see why we stopped.
				unparsed += text
				break
			}
			found[slot.slot] = slot
		}

		// 8. Slots that did not render are UNKNOWN, not EMPTY.
		val highest = found.keys.maxOrNull() ?: 0
		val total = maxOf(EXPECTED_SLOT_COUNT, highest)

		return ForgeSnapshot(
			slots = fillMissing(found, total),
			headerProfile = header.profileName,
			renderedSlots = found.size,
			unparsedRows = unparsed,
		)
	}

	/** 5 + 6. One row into one slot. */
	private fun parseSlotRow(text: String): ForgeSlot? {
		val match = SLOT_ROW.find(text) ?: return null
		val number = match.groupValues[1].toIntOrNull() ?: return null
		val body = match.groupValues[2].trim()

		// Shape A: no colon.
		if (body.equals("EMPTY", ignoreCase = true)) {
			return ForgeSlot(number, ForgeSlotState.EMPTY, rawText = text)
		}

		// Shape B: "<item>: <value>". Split on the LAST separator in case an
		// item name ever contains one.
		val separator = body.lastIndexOf(": ")
		if (separator < 0) {
			return ForgeSlot(number, ForgeSlotState.UNKNOWN, rawText = text)
		}

		val item = body.substring(0, separator).trim()
		val value = body.substring(separator + 2).trim()

		if (value.equals("Ready!", ignoreCase = true)) {
			return ForgeSlot(number, ForgeSlotState.READY, itemName = item, rawText = text)
		}

		val remaining = parseDuration(value)
		return if (remaining != null) {
			ForgeSlot(number, ForgeSlotState.IN_PROGRESS, itemName = item, remaining = remaining, rawText = text)
		} else {
			// A shape we know, holding a value we do not. Hypixel can add states.
			ForgeSlot(number, ForgeSlotState.UNKNOWN, itemName = item, rawText = text)
		}
	}

	private fun fillMissing(found: Map<Int, ForgeSlot>, total: Int): List<ForgeSlot> =
		(1..total).map { n ->
			found[n] ?: ForgeSlot(n, ForgeSlotState.UNKNOWN, rawText = null)
		}
}
