package dev.fraust.forgecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.hours

/**
 * The LOCKED state: a forge slot not yet unlocked on this account.
 *
 * The plan listed this as probably untestable ("how a locked slot renders for a
 * player below HotM 7 - probably not testable on this account"), so the capture
 * behind these tests is worth more than most.
 *
 * It renders as " 5) §cLOCKED" - red, and with NO colon, so structurally it is
 * the same shape as EMPTY rather than the "<item>: <value>" shape.
 */
class LockedSlotTest {

	private fun loadFixture(name: String): List<TabRow> {
		val stream = javaClass.getResourceAsStream("/dumps/$name")
			?: error("Fixture missing from the test classpath: /dumps/$name")
		val rows = mutableListOf<TabRow>()
		var inBody = false
		stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
			if (!inBody) {
				if (line.trim() == "--") inBody = true
				return@forEachLine
			}
			val parts = line.split('\t')
			if (parts.size >= 3) rows += TabRow(parts[2], if (parts.size >= 4) parts[3] else "")
		}
		return rows
	}

	private val fixture = "dump-20260902-191221-537.txt"

	// --------------------------------------------------- the real capture

	@Test
	fun `the real capture parses four running slots and three locked`() {
		val snapshot = ForgeParser.parse(loadFixture(fixture))

		assertEquals(
			listOf(
				ForgeSlotState.IN_PROGRESS, ForgeSlotState.IN_PROGRESS,
				ForgeSlotState.IN_PROGRESS, ForgeSlotState.IN_PROGRESS,
				ForgeSlotState.LOCKED, ForgeSlotState.LOCKED, ForgeSlotState.LOCKED,
			),
			snapshot.slots.map { it.state },
		)
		assertEquals(7, snapshot.renderedSlots)
	}

	@Test
	fun `locked is a known state, never unrecognised`() {
		// The bug: these showed as "unrecognised" because LOCKED has no colon and
		// fell through to the unknown branch.
		val snapshot = ForgeParser.parse(loadFixture(fixture))
		assertTrue(
			snapshot.slots.none { it.state == ForgeSlotState.UNKNOWN },
			"nothing in this capture is genuinely unknown",
		)
		assertTrue(snapshot.unparsedRows.isEmpty(), "${snapshot.unparsedRows}")
	}

	@Test
	fun `a locked slot carries no item and no time`() {
		val slot5 = ForgeParser.parse(loadFixture(fixture)).slots[4]
		assertEquals(ForgeSlotState.LOCKED, slot5.state)
		assertEquals(null, slot5.itemName)
		assertEquals(null, slot5.remaining)
		assertNotNull(slot5.rawText, "the source row is kept, as for every parsed slot")
	}

	@Test
	fun `the running slots alongside locked ones still parse`() {
		val slots = ForgeParser.parse(loadFixture(fixture)).slots
		assertEquals("Refined Titanium", slots[0].itemName)
		assertEquals(6.hours, slots[0].remaining)
		assertEquals("Refined Diamond", slots[2].itemName)
		assertEquals(3.hours, slots[2].remaining)
	}

	@Test
	fun `case is ignored, as it is for EMPTY`() {
		val rows = listOf(
			TabRow("!C-b", "§9§lForges:"),
			TabRow("!C-c", " 1) §cLocked"),
			TabRow("!C-d", " 2) §clocked"),
		)
		val slots = ForgeParser.parse(rows).slots
		assertEquals(ForgeSlotState.LOCKED, slots[0].state)
		assertEquals(ForgeSlotState.LOCKED, slots[1].state)
	}

	// ------------------------------------------------- the advice logic

	@Test
	fun `an account with locked slots reads as COMPLETE, not truncated`() {
		// The trap: "fewer than 7 usable slots" must not be mistaken for the tab
		// list running out of rows. It is not - Hypixel renders the locked ones,
		// so all seven rows are present and renderedSlots is 7.
		val rows = loadFixture(fixture)
		val snapshot = ForgeParser.parse(rows)

		assertEquals(7, snapshot.renderedSlots, "locked slots still occupy a row")
		assertEquals(ForgeDataCase.COMPLETE, ForgeAdvice.classify(rows, snapshot))
		assertEquals(null, ForgeAdvice.message(ForgeDataCase.COMPLETE, 7), "nothing to warn about")
	}

	@Test
	fun `a genuinely truncated list with locked slots is still reported`() {
		// Truncation and locked slots are independent. One must not mask the other.
		val rows = listOf(
			TabRow("!D-p", "§9§lForges:"),
			TabRow("!D-q", " 1) §6Refined Titanium§7: §b6h"),
			TabRow("!D-r", " 2) §cLOCKED"),
		)
		val snapshot = ForgeParser.parse(rows)
		assertEquals(2, snapshot.renderedSlots)
		assertEquals(ForgeDataCase.TRUNCATED, ForgeAdvice.classify(rows, snapshot))
	}

	// -------------------------------------------------------- the memory

	@Test
	fun `a locked slot is remembered like any other known state`() {
		val memory = ForgeMemory()
		val now = Instant.parse("2026-09-02T19:12:21Z")
		val full = ForgeParser.parse(loadFixture(fixture))
		memory.update(full, "TestProfile", now)

		// Now truncated to four slots; 5-7 fall out of view.
		val short = ForgeParser.parse(
			listOf(
				TabRow("!D-b", "§9§lForges:"),
				TabRow("!D-c", " 1) §6Refined Titanium§7: §b6h"),
				TabRow("!D-d", " 2) §6Refined Titanium§7: §b6h"),
				TabRow("!D-e", " 3) §5Refined Diamond§7: §b3h"),
				TabRow("!D-f", " 4) §5Refined Diamond§7: §b3h"),
			)
		)
		val merged = memory.update(short, "TestProfile", now.plusSeconds(60))

		assertEquals(SlotSource.REMEMBERED, merged[4].source)
		assertEquals(
			ForgeSlotState.LOCKED, merged[4].state,
			"a locked slot cannot silently unlock while out of sight",
		)
	}

	// ------------------------------------------ hiding TRAILING locked only

	/**
	 * Mirrors ForgeHud.withoutTrailingLocked. The HUD's copy is private and its
	 * drawing cannot be tested, but the RULE can be, and the rule is the part
	 * with a sharp edge.
	 */
	private fun withoutTrailingLocked(states: List<ForgeSlotState>): List<ForgeSlotState> {
		var end = states.size
		while (end > 0 && states[end - 1] == ForgeSlotState.LOCKED) end--
		return states.subList(0, end)
	}

	@Test
	fun `trailing locked slots are dropped`() {
		val states = listOf(
			ForgeSlotState.IN_PROGRESS, ForgeSlotState.EMPTY,
			ForgeSlotState.LOCKED, ForgeSlotState.LOCKED, ForgeSlotState.LOCKED,
		)
		assertEquals(
			listOf(ForgeSlotState.IN_PROGRESS, ForgeSlotState.EMPTY),
			withoutTrailingLocked(states),
		)
	}

	@Test
	fun `a locked slot in the MIDDLE is kept`() {
		// Slots unlock in order, so this should be impossible. If it ever
		// happens an assumption is wrong, and hiding it would hide the evidence.
		val states = listOf(
			ForgeSlotState.IN_PROGRESS, ForgeSlotState.LOCKED,
			ForgeSlotState.EMPTY, ForgeSlotState.LOCKED,
		)
		assertEquals(
			listOf(ForgeSlotState.IN_PROGRESS, ForgeSlotState.LOCKED, ForgeSlotState.EMPTY),
			withoutTrailingLocked(states),
			"only the trailing run may be dropped",
		)
	}

	@Test
	fun `an unrecognised slot is NEVER hidden, even at the end`() {
		// The rule that matters most. If unknown states became invisible, the
		// next thing Hypixel changes would disappear silently - the exact
		// failure this parser was built to avoid.
		val states = listOf(
			ForgeSlotState.IN_PROGRESS, ForgeSlotState.LOCKED, ForgeSlotState.UNKNOWN,
		)
		assertEquals(states, withoutTrailingLocked(states), "UNKNOWN must survive at the end")
	}

	@Test
	fun `an unrecognised slot after locked ones blocks the trim`() {
		val states = listOf(
			ForgeSlotState.LOCKED, ForgeSlotState.LOCKED,
			ForgeSlotState.UNKNOWN, ForgeSlotState.LOCKED,
		)
		// Only the final LOCKED goes; the UNKNOWN stops the trim, and the locked
		// ones before it stay visible as a result.
		assertEquals(
			listOf(ForgeSlotState.LOCKED, ForgeSlotState.LOCKED, ForgeSlotState.UNKNOWN),
			withoutTrailingLocked(states),
		)
	}

	@Test
	fun `an all-locked list collapses to nothing`() {
		assertEquals(emptyList<ForgeSlotState>(), withoutTrailingLocked(List(7) { ForgeSlotState.LOCKED }))
	}

	@Test
	fun `a list with no locked slots is untouched`() {
		val states = List(7) { ForgeSlotState.EMPTY }
		assertEquals(states, withoutTrailingLocked(states))
	}
}
