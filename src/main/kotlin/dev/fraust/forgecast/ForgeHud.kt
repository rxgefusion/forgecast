package dev.fraust.forgecast

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.time.Instant

/**
 * Draws the forge slots as a plain text panel in the corner of the screen.
 *
 * RENDERING ONLY. Parsing is [ForgeParser]'s job and merging is
 * [ForgeMemory]'s; this file decides what the result should look like.
 *
 * Timing: [extractRenderState] is called EVERY FRAME - 60 to 200+ times a
 * second. Re-reading the tab list that often would be pure waste, so the
 * merged result is cached and refreshed at most once per second, measured
 * against the wall clock. Drawing happens every frame; parsing does not.
 *
 * A tick-based counter was the alternative, but ticks stall under lag, so
 * "20 ticks" is not reliably one second. The clock is what once-per-second
 * actually means.
 */
object ForgeHud : HudElement {

	private const val LINE_GAP = 1

	/** Extra indent for slots with nothing to show, so they read differently. */
	private const val TRUNCATED_INDENT = 10

	// Colours are ARGB. Live values are bright; remembered ones are deliberately
	// duller, so the panel reads at a glance without needing to be studied.
	private const val COLOR_HEADER = 0xFFAAAAAA.toInt()
	private const val COLOR_SLOT_NUMBER = 0xFF7F7F7F.toInt()
	private const val COLOR_ITEM = 0xFFFFFFFF.toInt()
	private const val COLOR_TIME = 0xFF55FFFF.toInt()
	private const val COLOR_READY = 0xFF55FF55.toInt()
	private const val COLOR_EMPTY = 0xFF6E6E6E.toInt()
	private const val COLOR_UNRECOGNISED = 0xFFFF5555.toInt()

	private const val COLOR_REMEMBERED_ITEM = 0xFF9A9A9A.toInt()
	private const val COLOR_REMEMBERED_TIME = 0xFF3E9E9E.toInt()
	private const val COLOR_REMEMBERED_READY = 0xFF3E9E5E.toInt()
	private const val COLOR_AGE = 0xFF5A5A5A.toInt()
	private const val COLOR_NOT_VISIBLE = 0xFF4A4A4A.toInt()
	private const val COLOR_LOCKED = 0xFF8A6A6A.toInt()

	private val memory = ForgeMemory()

	/** The reading we last built from, so we rebuild only on a new one. */
	private var lastGeneration = -1L

	/**
	 * The most recent merged view, or null when there is nothing to show.
	 *
	 * Null is the whole point: when the forge section goes away we clear this
	 * rather than keep drawing. Stale numbers that look live are worse than an
	 * empty screen - which is different from a REMEMBERED value, which is
	 * labelled as remembered and shows its age.
	 */
	private var cached: List<MergedSlot>? = null

	override fun extractRenderState(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
		val config = ConfigHolder.current
		if (!config.hudEnabled) {
			// Switched off in settings: drop the view so re-enabling never
			// flashes stale pixels.
			cached = null
			return
		}

		val client = Minecraft.getInstance()

		// Checked every frame because it is cheap, so leaving the server hides
		// the panel immediately rather than up to a second later.
		if (!ForgeCast.isOnHypixel(client)) {
			cached = null
			return
		}

		refreshFromSharedReading()

		val slots = cached ?: return
		draw(graphics, client.font, withoutTrailingLocked(slots))
	}

	/**
	 * Rebuilds the merged view from the shared reading.
	 *
	 * The panel no longer samples the tab list itself. This and the advice check
	 * consume the SAME reading, so the two can never disagree about what the tab
	 * list said - which is exactly what produced a warning about a missing
	 * widget while this panel was showing correct times from a different sample.
	 */
	private fun refreshFromSharedReading() {
		if (TabListSource.generation == lastGeneration) return
		lastGeneration = TabListSource.generation

		val snapshot = TabListSource.snapshot
		// No Forges section here means hide, not freeze.
		if (snapshot == null || !snapshot.foundSection) {
			cached = null
			return
		}

		cached = memory.update(snapshot, TabListSource.profile, Instant.now())
	}

	/**
	 * Drops locked slots from the END of the list only.
	 *
	 * Slots unlock in order, so on an account without all seven the locked ones
	 * are always trailing, and listing them is clutter rather than information.
	 *
	 * Trailing only, and LOCKED only. A locked slot appearing mid-list would
	 * mean an assumption is wrong, so it is shown. And UNKNOWN is never hidden:
	 * if unrecognised rows became invisible, the next thing Hypixel changes
	 * would vanish silently, which is the exact failure this parser exists to
	 * prevent.
	 */
	private fun withoutTrailingLocked(slots: List<MergedSlot>): List<MergedSlot> {
		var end = slots.size
		while (end > 0 && slots[end - 1].state == ForgeSlotState.LOCKED) end--
		return slots.subList(0, end)
	}

	private fun draw(graphics: GuiGraphicsExtractor, font: Font, slots: List<MergedSlot>) {
		val config = ConfigHolder.current
		val step = font.lineHeight + LINE_GAP
		val nowMs = System.currentTimeMillis()

		// Drawn relative to the origin, then moved and scaled as a whole, so the
		// player-chosen position and size need no arithmetic per line.
		graphics.pose().pushMatrix()
		graphics.pose().translate(config.hudX.toFloat(), config.hudY.toFloat())
		graphics.pose().scale(config.hudScale / 100f, config.hudScale / 100f)

		var y = 0

		graphics.text(font, "Forges", 0, y, COLOR_HEADER)
		y += step

		var i = 0
		while (i < slots.size) {
			if (slots[i].source == SlotSource.NONE) {
				// Collapse a run of blanks onto one line, indented further and
				// dimmer so it cannot be mistaken for an empty slot.
				var end = i
				while (end + 1 < slots.size && slots[end + 1].source == SlotSource.NONE) end++
				val label = if (i == end) "${slots[i].slot}" else "${slots[i].slot}-${slots[end].slot}"
				drawSegments(
					graphics, font, TRUNCATED_INDENT, y,
					listOf("$label not visible" to COLOR_NOT_VISIBLE),
				)
				i = end + 1
			} else {
				drawSegments(graphics, font, 0, y, segmentsFor(slots[i], nowMs))
				i++
			}
			y += step
		}

		graphics.pose().popMatrix()
	}

	/** Each line is drawn piece by piece so every piece gets its own colour. */
	private fun drawSegments(
		graphics: GuiGraphicsExtractor,
		font: Font,
		x: Int,
		y: Int,
		segments: List<Pair<String, Int>>,
	) {
		var cursor = x
		for ((text, color) in segments) {
			graphics.text(font, text, cursor, y, color)
			cursor += font.width(text)
		}
	}

	private fun segmentsFor(slot: MergedSlot, nowMs: Long): List<Pair<String, Int>> {
		val number = "${slot.slot}) " to COLOR_SLOT_NUMBER

		if (slot.source == SlotSource.REMEMBERED) {
			// Three signals mark a remembered value: the "~", the dimmer colours,
			// and the age in brackets. None of them is load-bearing alone.
			val age = slot.observedAt?.let { " (${ageText(it, nowMs)})" to COLOR_AGE }
			return when (slot.state) {
				ForgeSlotState.IN_PROGRESS -> listOfNotNull(
					number,
					"${slot.itemName ?: "?"} " to COLOR_REMEMBERED_ITEM,
					"~${slot.remaining?.toString() ?: "?"}" to COLOR_REMEMBERED_TIME,
					age,
				)

				ForgeSlotState.READY -> listOfNotNull(
					number,
					"${slot.itemName ?: "?"} " to COLOR_REMEMBERED_ITEM,
					"~READY" to COLOR_REMEMBERED_READY,
					age,
				)

				// A catch-all "else" here used to render a remembered LOCKED slot
				// as "~empty", which is a different and wrong claim.
				ForgeSlotState.LOCKED -> listOfNotNull(number, "~locked" to COLOR_LOCKED, age)

				else -> listOfNotNull(number, "~empty" to COLOR_NOT_VISIBLE, age)
			}
		}

		return when (slot.state) {
			ForgeSlotState.IN_PROGRESS -> listOf(
				number,
				"${slot.itemName ?: "?"} " to COLOR_ITEM,
				(slot.remaining?.toString() ?: "?") to COLOR_TIME,
			)

			ForgeSlotState.READY -> listOf(
				number,
				"${slot.itemName ?: "?"} " to COLOR_ITEM,
				"READY" to COLOR_READY,
			)

			ForgeSlotState.EMPTY -> listOf(number, "empty" to COLOR_EMPTY)

			// Only reached for a locked slot that is NOT at the end of the list.
			// Trailing ones are dropped before drawing; one appearing mid-list
			// would be an anomaly worth seeing.
			ForgeSlotState.LOCKED -> listOf(number, "locked" to COLOR_LOCKED)

			// Rendered but unrecognised. Shown so it can be reported.
			ForgeSlotState.UNKNOWN -> listOf(number, "unrecognised" to COLOR_UNRECOGNISED)
		}
	}

	/** Compact "how long ago was this last actually seen". */
	private fun ageText(observedAt: Instant, nowMs: Long): String {
		val seconds = ((nowMs - observedAt.toEpochMilli()) / 1000).coerceAtLeast(0)
		return when {
			seconds < 60 -> "${seconds}s ago"
			seconds < 3600 -> "${seconds / 60}m ago"
			else -> "${seconds / 3600}h ago"
		}
	}
}
