package dev.fraust.forgecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [ForgeParser].
 *
 * Two kinds of case, kept deliberately separate:
 *
 *  - REAL CAPTURES, loaded from src/test/resources/dumps. Every one of these
 *    is something the server actually sent. They are the evidence.
 *
 *  - HAND-BUILT ROWS, constructed inline further down. These exercise shapes
 *    we have never captured (a section truncated to nothing, an unrecognised
 *    value). They are visibly test inputs, not pretend captures - which is why
 *    they live in code and not in the fixtures folder.
 */
class ForgeParserTest {

	// ---------------------------------------------------------------- helpers

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
			if (parts.size >= 3) {
				rows += TabRow(parts[2], if (parts.size >= 4) parts[3] else "")
			}
		}
		return rows
	}

	private fun row(profile: String, text: String) = TabRow(profile, text)

	// -------------------------------------------------- real capture fixtures

	private val B = ForgeSlotState.IN_PROGRESS
	private val E = ForgeSlotState.EMPTY
	private val R = ForgeSlotState.READY
	private val U = ForgeSlotState.UNKNOWN

	private data class Expected(
		val file: String,
		val where: String,
		val header: String,
		val rendered: Int,
		val states: List<ForgeSlotState>,
	)

	private val expectations = listOf(
		Expected(
			"dump-20260902-020918-000-FINISHED-STATE.txt", "Dwarven Mines", "!C-b", 7,
			listOf(B, B, R, E, E, E, E),
		),
		Expected(
			"dump-20260902-021808-688.txt", "Dwarven Mines", "!C-b", 7,
			listOf(B, B, E, B, E, E, E),
		),
		Expected(
			"dump-20260902-021816-163.txt", "Dwarven Mines", "!C-b", 7,
			listOf(B, B, E, B, E, E, E),
		),
		Expected(
			"dump-20260902-021823-286.txt", "Dwarven Mines", "!C-b", 7,
			listOf(B, B, E, B, E, E, E),
		),
		Expected(
			"dump-20260902-021829-725.txt", "Dwarven Mines", "!C-b", 7,
			listOf(B, B, E, B, E, E, E),
		),
		Expected(
			"dump-20260902-021835-871.txt", "Dwarven Mines", "!C-b", 7,
			listOf(B, B, E, R, E, E, E),
		),
		Expected(
			"dump-20260902-021842-048.txt", "Dwarven Mines", "!C-b", 7,
			listOf(B, B, E, R, E, E, E),
		),
		// The Private Island truncates: 5 slots render, 6 and 7 never appear.
		Expected(
			"dump-20260902-022501-663.txt", "Private Island", "!D-o", 5,
			listOf(B, B, E, R, E, U, U),
		),
		// Wrapping enabled. The section fits in one column here.
		Expected(
			"dump-20260902-044123-097.txt", "Private Island", "!C-b", 7,
			listOf(B, B, E, R, E, E, E),
		),
		// Wrapping enabled and the section spans B into C. This is the layout
		// that read zero slots before the boundary rule was fixed.
		Expected(
			"dump-20260902-044129-623.txt", "Private Island wrapped", "!B-t", 7,
			listOf(B, B, E, R, E, E, E),
		),
	)

	@TestFactory
	fun `every real capture parses to its known result`(): List<DynamicTest> =
		expectations.map { expected ->
			DynamicTest.dynamicTest("${expected.where}: ${expected.file}") {
				val snapshot = ForgeParser.parse(loadFixture(expected.file))

				assertEquals(
					expected.header, snapshot.headerProfile,
					"Forges header row moved - the parser must find it by text, not position",
				)
				assertEquals(
					expected.rendered, snapshot.renderedSlots,
					"Number of slot rows the server rendered changed",
				)
				assertEquals(
					ForgeParser.EXPECTED_SLOT_COUNT, snapshot.slots.size,
					"A snapshot must always report every slot, rendered or not",
				)
				assertEquals(
					expected.states, snapshot.slots.map { it.state },
					"Slot states changed for ${expected.file}",
				)
				assertTrue(
					snapshot.unparsedRows.isEmpty(),
					"Unparsed rows appeared: ${snapshot.unparsedRows}",
				)
			}
		}

	@Test
	fun `a section that wraps into the next column is read in full`() {
		// Real capture with Wrapping enabled on the Forge widget. The header
		// lands on the last row of column B and all seven slots live in column
		// C, separated by that column's "Info" heading.
		val snapshot = ForgeParser.parse(loadFixture("dump-20260902-044129-623.txt"))

		assertEquals("!B-t", snapshot.headerProfile, "header sits on the last row of column B")
		assertEquals(7, snapshot.renderedSlots, "all seven slots continue into column C")
		assertEquals(
			listOf(B, B, E, R, E, E, E), snapshot.slots.map { it.state },
			"the wrapped section must parse exactly like an unwrapped one",
		)
		assertEquals("Refined Titanium", snapshot.slots[0].itemName)
		assertEquals(9.hours, snapshot.slots[0].remaining)
		assertEquals("Bejeweled Handle", snapshot.slots[3].itemName)
		assertTrue(snapshot.unparsedRows.isEmpty(), "junk leaked in: ${snapshot.unparsedRows}")
	}

	@Test
	fun `a wrapped section stops before the next heading`() {
		// The row after slot 7 is blank, then "Pet:". Neither may be collected,
		// and the pet rows below must not become slots.
		val snapshot = ForgeParser.parse(loadFixture("dump-20260902-044129-623.txt"))
		assertEquals(7, snapshot.renderedSlots)
		assertEquals(
			ForgeParser.EXPECTED_SLOT_COUNT, snapshot.slots.size,
			"nothing beyond the seven real slots may be invented",
		)
	}

	@Test
	fun `pet training rows using a colon are never mistaken for slots`() {
		// "1: [Lvl 95] Rift Ferret" is one character away from a slot row.
		val rows = listOf(
			row("!C-b", "§9§lForges:"),
			row("!C-c", " 1) §7EMPTY"),
			row("!C-d", "§e§lPet Training:"),
			row("!C-e", " 1: §7[Lvl 95] §5Rift Ferret §b3M"),
			row("!C-f", " 2: §7[Lvl 60] §5Golden Dragon"),
		)
		val snapshot = ForgeParser.parse(rows)

		assertEquals(1, snapshot.renderedSlots, "only the real slot row counts")
		assertEquals(ForgeSlotState.EMPTY, snapshot.slots[0].state)
		assertEquals(
			ForgeSlotState.UNKNOWN, snapshot.slots[1].state,
			"the pet row must not become slot 2",
		)
	}

	@Test
	fun `slots that never rendered are unknown, never empty`() {
		val snapshot = ForgeParser.parse(loadFixture("dump-20260902-022501-663.txt"))

		// This is the distinction the whole design hangs on.
		assertEquals(ForgeSlotState.UNKNOWN, snapshot.slots[5].state, "slot 6")
		assertEquals(ForgeSlotState.UNKNOWN, snapshot.slots[6].state, "slot 7")

		// A slot that did not render carries no raw text; an unrecognised one would.
		assertNull(snapshot.slots[5].rawText, "a slot that never rendered has no source row")

		// And slot 5 really was reported empty, so UNKNOWN is not just "past the end".
		assertEquals(ForgeSlotState.EMPTY, snapshot.slots[4].state, "slot 5")
	}

	@Test
	fun `the countdown series parses to the exact times captured`() {
		val series = listOf(
			"dump-20260902-021808-688.txt" to 26.seconds,
			"dump-20260902-021816-163.txt" to 17.seconds,
			"dump-20260902-021823-286.txt" to 10.seconds,
			"dump-20260902-021829-725.txt" to 4.seconds,
		)
		for ((file, remaining) in series) {
			val snapshot = ForgeParser.parse(loadFixture(file))
			val slot4 = snapshot.slots[3]
			assertEquals(ForgeSlotState.IN_PROGRESS, slot4.state, file)
			assertEquals("Bejeweled Handle", slot4.itemName, file)
			assertEquals(remaining, slot4.remaining, file)
		}
	}

	@Test
	fun `long running slots keep their item name and hours`() {
		val snapshot = ForgeParser.parse(loadFixture("dump-20260902-021808-688.txt"))
		assertEquals("Refined Titanium", snapshot.slots[0].itemName)
		assertEquals(11.hours, snapshot.slots[0].remaining)
		assertEquals("Refined Titanium", snapshot.slots[1].itemName)
		assertEquals(10.hours, snapshot.slots[1].remaining)
	}

	@Test
	fun `a ready slot has no remaining time`() {
		val snapshot = ForgeParser.parse(loadFixture("dump-20260902-021842-048.txt"))
		val slot4 = snapshot.slots[3]
		assertEquals(ForgeSlotState.READY, slot4.state)
		assertEquals("Bejeweled Handle", slot4.itemName)
		assertNull(slot4.remaining, "READY means no countdown remains")
	}

	@Test
	fun `the parser never trusts iteration order`() {
		// Reversing the input must not change the result. The game returns rows
		// in scrambled order, so sorting is load-bearing, not cosmetic.
		val rows = loadFixture("dump-20260902-021808-688.txt")
		val forwards = ForgeParser.parse(rows)
		val backwards = ForgeParser.parse(rows.reversed())
		assertEquals(forwards.slots.map { it.state }, backwards.slots.map { it.state })
		assertEquals(forwards.headerProfile, backwards.headerProfile)
	}

	// ------------------------------------------------ hand-built row fixtures
	//
	// Shapes we have never captured. Built inline so they can never be mistaken
	// for real evidence.

	@Test
	fun `a section truncated to two slots reports the rest as unknown`() {
		// The shape observed once in the Hub, where the header landed at !D-r
		// and only two rows remained in the column.
		val rows = listOf(
			row("!D-q", ""),
			row("!D-r", "§9§lForges:"),
			row("!D-s", " 1) §6Refined Titanium§7: §b11h"),
			row("!D-t", " 2) §6Refined Titanium§7: §b10h"),
		)
		val snapshot = ForgeParser.parse(rows)

		assertEquals("!D-r", snapshot.headerProfile)
		assertEquals(2, snapshot.renderedSlots)
		assertEquals(ForgeSlotState.IN_PROGRESS, snapshot.slots[0].state)
		assertEquals(ForgeSlotState.IN_PROGRESS, snapshot.slots[1].state)
		for (i in 2..6) {
			assertEquals(
				ForgeSlotState.UNKNOWN, snapshot.slots[i].state,
				"slot ${i + 1} did not render and must not be reported as empty",
			)
		}
	}

	@Test
	fun `a missing section yields no header and all slots unknown`() {
		// This is the case that will trigger the widget warning later. It has
		// never been captured, so it can only be constructed.
		val rows = listOf(
			row("!C-a", "               §3§lInfo"),
			row("!C-b", "§b§lArea: §7Hub"),
			row("!C-c", " Server: §8mini1A"),
		)
		val snapshot = ForgeParser.parse(rows)

		assertNull(snapshot.headerProfile)
		assertFalse(snapshot.foundSection)
		assertEquals(0, snapshot.renderedSlots)
		assertEquals(ForgeParser.EXPECTED_SLOT_COUNT, snapshot.slots.size)
		assertTrue(snapshot.slots.all { it.state == ForgeSlotState.UNKNOWN })
	}

	@Test
	fun `the section stops at the next header and does not swallow it`() {
		val rows = listOf(
			row("!C-b", "§9§lForges:"),
			row("!C-c", " 1) §7EMPTY"),
			row("!C-d", "§9§lCommissions:"),
			row("!C-e", " §fTitanium Miner: §c6.7%"),
		)
		val snapshot = ForgeParser.parse(rows)

		assertEquals(1, snapshot.renderedSlots, "only the row before Commissions belongs to the section")
		assertEquals(ForgeSlotState.EMPTY, snapshot.slots[0].state)
		assertTrue(snapshot.unparsedRows.isEmpty(), "the Commissions rows must not leak in")
	}

	@Test
	fun `the section continues across a column boundary`() {
		// This test used to assert the OPPOSITE - that a column change ended the
		// section. Real captures with Wrapping enabled disproved that: Hypixel
		// deliberately continues the slots into the next column. The rows are
		// unchanged; only the expectation is corrected.
		val rows = listOf(
			row("!C-s", "§9§lForges:"),
			row("!C-t", " 1) §6Refined Titanium§7: §b11h"),
			row("!D-a", " 2) §6Refined Titanium§7: §b10h"),
		)
		val snapshot = ForgeParser.parse(rows)

		assertEquals(2, snapshot.renderedSlots, "a column change must not end the section")
		assertEquals(ForgeSlotState.IN_PROGRESS, snapshot.slots[1].state)
		assertEquals(10.hours, snapshot.slots[1].remaining)
	}

	@Test
	fun `column padding between a wrapped header and its slots is skipped`() {
		// The gap a wrapped section actually has: the header ends one column,
		// and the next opens with a blank row and its own "Info" heading.
		val rows = listOf(
			row("!B-t", "§9§lForges:"),
			row("!C-a", "               §3§lInfo"),
			row("!C-b", " 1) §6Refined Titanium§7: §b9h"),
			row("!C-c", " 2) §7EMPTY"),
			row("!C-d", ""),
			row("!C-e", "§e§lPet:"),
			row("!C-f", " §7[Lvl 100] §5Scatha"),
		)
		val snapshot = ForgeParser.parse(rows)

		assertEquals(2, snapshot.renderedSlots, "the Info heading is padding, not a terminator")
		assertEquals(ForgeSlotState.IN_PROGRESS, snapshot.slots[0].state)
		assertEquals(ForgeSlotState.EMPTY, snapshot.slots[1].state)
		assertEquals(
			ForgeSlotState.UNKNOWN, snapshot.slots[2].state,
			"the pet rows below Pet: must not be collected",
		)
	}

	@Test
	fun `no more than seven slots are ever collected`() {
		// A malformed or hostile widget must not be able to grow the forge.
		val rows = buildList {
			add(row("!C-b", "§9§lForges:"))
			for (n in 1..12) add(row("!C-${'c' + n - 1}", " $n) §7EMPTY"))
		}
		val snapshot = ForgeParser.parse(rows)

		assertEquals(ForgeParser.EXPECTED_SLOT_COUNT, snapshot.renderedSlots)
		assertEquals(ForgeParser.EXPECTED_SLOT_COUNT, snapshot.slots.size)
	}

	@Test
	fun `an unrecognised value becomes unknown and keeps its text`() {
		// Hypixel can add states. The parser must not guess.
		val rows = listOf(
			row("!C-b", "§9§lForges:"),
			row("!C-c", " 1) §6Refined Titanium§7: §dPaused"),
		)
		val snapshot = ForgeParser.parse(rows)
		val slot1 = snapshot.slots[0]

		assertEquals(ForgeSlotState.UNKNOWN, slot1.state)
		assertEquals("Refined Titanium", slot1.itemName, "the item is still readable")
		assertNotNull(slot1.rawText, "the raw text must survive so the state can be diagnosed")
		assertTrue(slot1.rawText!!.contains("Paused"))
	}

	// ------------------------------------------------------ duration grammar

	@Test
	fun `durations parse generically across units`() {
		assertEquals(11.hours, ForgeParser.parseDuration("11h"))
		assertEquals(26.seconds, ForgeParser.parseDuration("26s"))
		assertEquals(45.minutes, ForgeParser.parseDuration("45m"))
		assertEquals(1.hours + 30.minutes, ForgeParser.parseDuration("1h30m"))
		assertEquals(Duration.ZERO, ForgeParser.parseDuration("0s"))
	}

	@Test
	fun `partial or non-durations are rejected rather than half read`() {
		// "11h ago" becoming 11h would be a confidently wrong answer, which is
		// worse than admitting we do not know.
		assertNull(ForgeParser.parseDuration("11h ago"))
		assertNull(ForgeParser.parseDuration("Ready!"))
		assertNull(ForgeParser.parseDuration("10"))
		assertNull(ForgeParser.parseDuration("abc"))
		assertNull(ForgeParser.parseDuration(""))
	}

	@Test
	fun `formatting codes are stripped without eating real text`() {
		assertEquals(
			" 1) Refined Titanium: 11h",
			ForgeParser.stripFormatting(" 1) §6Refined Titanium§7: §b11h"),
		)
		assertEquals("Forges:", ForgeParser.stripFormatting("§9§lForges:"))
	}
}
