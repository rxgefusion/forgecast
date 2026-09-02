package dev.fraust.forgecast

import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement

/**
 * Draws the forge slots as a plain text panel in the corner of the screen.
 *
 * RENDERING ONLY. This reads through [ForgeParser] and the same live wiring
 * the commands use; it does not parse anything itself.
 *
 * Timing: [extractRenderState] is called EVERY FRAME - 60 to 200+ times a
 * second. Re-reading the tab list that often would be pure waste, so the
 * parsed snapshot is cached and refreshed at most once per second, measured
 * against the wall clock. Drawing happens every frame; parsing does not.
 *
 * A tick-based counter was the alternative, but ticks stall under lag, so
 * "20 ticks" is not reliably one second. The clock is what once-per-second
 * actually means.
 */
object ForgeHud : HudElement {

	/** Flipped by /forgecast toggle. */
	var enabled: Boolean = false
		private set

	private const val REFRESH_INTERVAL_MS = 1_000L

	private const val MARGIN = 4
	private const val LINE_GAP = 1

	/** Extra indent for slots that did not render, so they read differently. */
	private const val TRUNCATED_INDENT = 10

	// Colours are ARGB. Deliberately few: just enough to separate the states.
	private const val COLOR_HEADER = 0xFFAAAAAA.toInt()
	private const val COLOR_SLOT_NUMBER = 0xFF7F7F7F.toInt()
	private const val COLOR_ITEM = 0xFFFFFFFF.toInt()
	private const val COLOR_TIME = 0xFF55FFFF.toInt()
	private const val COLOR_READY = 0xFF55FF55.toInt()
	private const val COLOR_EMPTY = 0xFF6E6E6E.toInt()
	private const val COLOR_NOT_VISIBLE = 0xFF4A4A4A.toInt()
	private const val COLOR_UNRECOGNISED = 0xFFFF5555.toInt()

	private var lastRefreshMs = 0L

	/**
	 * The most recent usable snapshot, or null when there is nothing to show.
	 *
	 * Null is the whole point: when the forge section goes away we clear this
	 * rather than keep drawing the last values. Stale numbers that look live
	 * are worse than an empty screen.
	 */
	private var cached: ForgeSnapshot? = null

	fun toggle(): Boolean {
		enabled = !enabled
		if (!enabled) {
			// Drop everything so re-enabling never flashes old values.
			cached = null
			lastRefreshMs = 0L
		}
		return enabled
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
		if (!enabled) return

		val client = Minecraft.getInstance()

		// Checked every frame because it is cheap, so leaving the server hides
		// the panel immediately rather than up to a second later.
		if (!onHypixel(client)) {
			cached = null
			return
		}

		refreshIfDue(client)

		val snapshot = cached ?: return
		draw(graphics, client.font, snapshot)
	}

	/** True only while connected to a Hypixel address. */
	private fun onHypixel(client: Minecraft): Boolean {
		if (client.connection == null) return false
		val address = client.currentServer?.ip ?: return false
		return address.lowercase().contains("hypixel")
	}

	private fun refreshIfDue(client: Minecraft) {
		val now = System.currentTimeMillis()
		if (now - lastRefreshMs < REFRESH_INTERVAL_MS) return
		lastRefreshMs = now

		val connection = client.connection
		if (connection == null) {
			cached = null
			return
		}

		// Reuses the exact live wiring the commands use, so the panel can never
		// disagree with /forgecast status.
		val snapshot = ForgeParser.parse(ForgeCast.readTabRows(connection))

		// No Forges section here means hide, not freeze.
		cached = if (snapshot.foundSection) snapshot else null
	}

	private fun didNotRender(slot: ForgeSlot): Boolean =
		slot.state == ForgeSlotState.UNKNOWN && slot.rawText == null

	private fun draw(graphics: GuiGraphicsExtractor, font: Font, snapshot: ForgeSnapshot) {
		val step = font.lineHeight + LINE_GAP
		var y = MARGIN

		graphics.text(font, "Forges", MARGIN, y, COLOR_HEADER)
		y += step

		val slots = snapshot.slots
		var i = 0
		while (i < slots.size) {
			if (didNotRender(slots[i])) {
				// Collapse a run of missing slots onto one line, indented further
				// and dimmer so it cannot be mistaken for an empty slot.
				var end = i
				while (end + 1 < slots.size && didNotRender(slots[end + 1])) end++
				val label = if (i == end) "${slots[i].slot}" else "${slots[i].slot}-${slots[end].slot}"
				drawSegments(
					graphics, font, MARGIN + TRUNCATED_INDENT, y,
					listOf("$label not visible" to COLOR_NOT_VISIBLE),
				)
				i = end + 1
			} else {
				drawSegments(graphics, font, MARGIN, y, segmentsFor(slots[i]))
				i++
			}
			y += step
		}
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

	private fun segmentsFor(slot: ForgeSlot): List<Pair<String, Int>> {
		val number = "${slot.slot}) " to COLOR_SLOT_NUMBER
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

			// Rendered but unrecognised. Shown so it can be reported.
			ForgeSlotState.UNKNOWN -> listOf(number, "unrecognised" to COLOR_UNRECOGNISED)
		}
	}
}
