package dev.fraust.forgecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [GuiForgeParser].
 *
 * The fixture is a real capture of the Forge screen, TRIMMED to slots 10-16
 * before being committed - the raw dump contained the whole player inventory,
 * and this folder is public.
 */
class GuiForgeParserTest {

	private val captured: Instant = Instant.parse("2026-09-02T05:04:49.327Z")

	/** Reads a trimmed GUI dump into the parser's input shape. */
	private fun loadGuiFixture(name: String): List<GuiSlotRow> {
		val stream = javaClass.getResourceAsStream("/dumps/$name")
			?: error("Fixture missing from the test classpath: /dumps/$name")

		val names = mutableMapOf<Int, Pair<Int, String>>()
		val tips = mutableMapOf<Int, MutableList<String>>()
		var inBody = false

		stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
			if (!inBody) {
				if (line.trim() == "--") inBody = true
				return@forEachLine
			}
			val parts = line.split('\t')
			when {
				parts.size >= 4 && parts[0] == "slot" ->
					parts[1].toIntOrNull()?.let { names[it] = (parts[2].toIntOrNull() ?: 0) to parts[3] }

				parts.size >= 3 && parts[0] == "tip" ->
					parts[1].toIntOrNull()?.let { tips.getOrPut(it) { mutableListOf() } += parts[2] }
			}
		}

		return names.map { (index, nameAndCount) ->
			GuiSlotRow(index, nameAndCount.first, nameAndCount.second, tips[index].orEmpty())
		}.sortedBy { it.index }
	}

	private fun parseFixture() =
		GuiForgeParser.parse(loadGuiFixture("gui-the-forge-trimmed.txt"), captured)

	private fun row(index: Int, name: String, vararg tips: String) =
		GuiSlotRow(index, 1, name, tips.toList())

	// ------------------------------------------------- the real capture

	@Test
	fun `the real Forge capture parses all seven slots`() {
		val snapshot = parseFixture()

		assertEquals(
			listOf(
				ForgeSlotState.IN_PROGRESS,
				ForgeSlotState.IN_PROGRESS,
				ForgeSlotState.EMPTY,
				ForgeSlotState.READY,
				ForgeSlotState.EMPTY,
				ForgeSlotState.EMPTY,
				ForgeSlotState.EMPTY,
			),
			snapshot.slots.map { it.state },
		)
		assertTrue(snapshot.problems.isEmpty(), "unexpected problems: ${snapshot.problems}")
	}

	@Test
	fun `running slots keep second level precision`() {
		val snapshot = parseFixture()

		// The tab list rounded these to "9h" and "7h". The GUI does not.
		assertEquals("Refined Titanium", snapshot.slots[0].itemName)
		assertEquals(8.hours + 46.minutes + 2.seconds, snapshot.slots[0].remaining)

		assertEquals("Refined Titanium", snapshot.slots[1].itemName)
		assertEquals(7.hours + 35.minutes + 36.seconds, snapshot.slots[1].remaining)
	}

	@Test
	fun `every parsed slot is stamped with the capture time`() {
		val snapshot = parseFixture()

		// An exact finish time is observedAt + remaining. Stamping every slot is
		// what makes that arithmetic possible without storing a second field.
		assertEquals(captured, snapshot.slots[0].observedAt)
		assertEquals(
			captured.plusSeconds((8 * 3600 + 46 * 60 + 2).toLong()),
			snapshot.slots[0].observedAt!!.plusMillis(snapshot.slots[0].remaining!!.inWholeMilliseconds),
			"absolute finish must be exact, not an estimate",
		)
	}

	@Test
	fun `a completed slot is ready with no remaining time`() {
		val snapshot = parseFixture()
		val slot4 = snapshot.slots[3]

		assertEquals(ForgeSlotState.READY, slot4.state)
		assertEquals("Bejeweled Handle", slot4.itemName)
		assertNull(slot4.remaining, "Completed! means nothing is left to count")
	}

	@Test
	fun `empty slots carry no item name`() {
		val snapshot = parseFixture()
		for (index in listOf(2, 4, 5, 6)) {
			assertEquals(ForgeSlotState.EMPTY, snapshot.slots[index].state, "slot ${index + 1}")
			assertNull(snapshot.slots[index].itemName, "slot ${index + 1}")
		}
	}

	// ------------------------------------- slot identity, not grid position

	@Test
	fun `an empty slot's own number is used to verify its position`() {
		// "Slot #3" in GUI slot 12 agrees with 12 - 9 = 3.
		val snapshot = GuiForgeParser.parse(
			listOf(row(12, "§aSlot #3", "§aSlot #3", "§eClick to select a process!")),
			captured,
		)
		assertEquals(ForgeSlotState.EMPTY, snapshot.slots[2].state)
		assertTrue(snapshot.problems.isEmpty())
	}

	@Test
	fun `a slot whose own number disagrees with its position is not trusted`() {
		// If Hypixel ever moves the grid, believing the position would silently
		// report the wrong forge slot. Better to refuse.
		val snapshot = GuiForgeParser.parse(
			listOf(row(12, "§aSlot #5", "§aSlot #5", "§eClick to select a process!")),
			captured,
		)
		assertEquals(
			ForgeSlotState.UNKNOWN, snapshot.slots[2].state,
			"position said 3, the slot said 5 - that must not be guessed at",
		)
		assertTrue(
			snapshot.problems.any { it.contains("3") && it.contains("5") },
			"the mismatch must be reported: ${snapshot.problems}",
		)
	}

	// ------------------------------------------------- unknown shapes

	@Test
	fun `an unreadable time becomes unknown and keeps its text`() {
		val snapshot = GuiForgeParser.parse(
			listOf(row(10, "§6Refined Titanium", "§7Time Remaining: §aSoonish")),
			captured,
		)
		val slot1 = snapshot.slots[0]

		assertEquals(ForgeSlotState.UNKNOWN, slot1.state)
		assertNotNull(slot1.rawText)
		assertTrue(slot1.rawText!!.contains("Soonish"), "raw text kept: ${slot1.rawText}")
	}

	@Test
	fun `a slot with no recognisable tooltip is unknown`() {
		val snapshot = GuiForgeParser.parse(
			listOf(row(10, "§6Mystery Item", "§7Something entirely new")),
			captured,
		)
		assertEquals(ForgeSlotState.UNKNOWN, snapshot.slots[0].state)
	}

	@Test
	fun `slots absent from the capture are unknown, never empty`() {
		val snapshot = GuiForgeParser.parse(emptyList(), captured)

		assertEquals(ForgeParser.EXPECTED_SLOT_COUNT, snapshot.slots.size)
		assertTrue(snapshot.slots.all { it.state == ForgeSlotState.UNKNOWN })
		assertTrue(snapshot.slots.all { it.rawText == null }, "absent is not the same as unreadable")
	}

	@Test
	fun `durations reuse the tab parser's grammar`() {
		// Not a second implementation that could drift: the same function.
		val snapshot = GuiForgeParser.parse(
			listOf(
				row(10, "§6A", "§7Time Remaining: §a2d 3h"),
				row(11, "§6B", "§7Time Remaining: §a45m"),
			),
			captured,
		)
		assertEquals(ForgeParser.parseDuration("2d3h"), snapshot.slots[0].remaining)
		assertEquals(45.minutes, snapshot.slots[1].remaining)
	}
}
