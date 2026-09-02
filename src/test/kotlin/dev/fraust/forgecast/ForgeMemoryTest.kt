package dev.fraust.forgecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [ForgeMemory] and [ProfileReader].
 *
 * Rows are built inline rather than loaded from the captured dumps, because
 * these tests are about the merge rules rather than about parsing. The real
 * captures already cover parsing in [ForgeParserTest].
 */
class ForgeMemoryTest {

	private val t0: Instant = Instant.parse("2026-09-02T02:00:00Z")

	private fun at(offsetMinutes: Long): Instant = t0.plusSeconds(offsetMinutes * 60)

	/**
	 * Builds a tab list with a profile row and a Forges section holding the
	 * given slot rows. Rows land in column C starting at !C-b, so a short list
	 * truncates exactly the way the real widget does.
	 */
	private fun rows(profile: String, vararg slotRows: String): List<TabRow> {
		val out = mutableListOf(
			TabRow("!B-g", "§e§lProfile: §a$profile"),
			TabRow("!C-b", "§9§lForges:"),
		)
		slotRows.forEachIndexed { index, text ->
			// !C-c, !C-d, ...
			out += TabRow("!C-${'c' + index}", text)
		}
		return out
	}

	private fun snapshotOf(profile: String, vararg slotRows: String) =
		ForgeParser.parse(rows(profile, *slotRows))

	private fun busy(slot: Int, item: String, time: String) = " $slot) §6$item§7: §b$time"
	private fun ready(slot: Int, item: String) = " $slot) §9$item§7: §aReady!"
	private fun empty(slot: Int) = " $slot) §7EMPTY"

	// ------------------------------------------------------- profile reading

	@Test
	fun `profile is read by text not position`() {
		val shifted = listOf(
			TabRow("!D-q", "§e§lProfile: §aMango"),
			TabRow("!C-b", "§9§lForges:"),
		)
		assertEquals("Mango", ProfileReader.profileOf(shifted))
	}

	@Test
	fun `a missing profile row reads as null`() {
		val none = listOf(TabRow("!C-b", "§9§lForges:"))
		assertNull(ProfileReader.profileOf(none))
	}

	// ----------------------------------------------------- the merge rules

	@Test
	fun `a slot visible now overwrites what was remembered`() {
		val memory = ForgeMemory()

		// Seen with 7 slots: slot 4 is running.
		memory.update(
			snapshotOf(
				"Pear",
				busy(1, "Refined Titanium", "11h"), empty(2), empty(3),
				busy(4, "Bejeweled Handle", "2h"), empty(5), empty(6), empty(7),
			),
			"Pear", t0,
		)

		// Later, still visible, but now it is Ready. Live must win.
		val merged = memory.update(
			snapshotOf(
				"Pear",
				busy(1, "Refined Titanium", "10h"), empty(2), empty(3),
				ready(4, "Bejeweled Handle"), empty(5), empty(6), empty(7),
			),
			"Pear", at(60),
		)

		val slot4 = merged[3]
		assertEquals(SlotSource.LIVE, slot4.source, "a visible slot is never served from memory")
		assertEquals(ForgeSlotState.READY, slot4.state)
		assertNull(slot4.remaining)
	}

	@Test
	fun `a slot no longer visible keeps its remembered value`() {
		val memory = ForgeMemory()

		memory.update(
			snapshotOf(
				"Pear",
				empty(1), empty(2), empty(3), empty(4), empty(5),
				busy(6, "Refined Diamond", "3h"), empty(7),
			),
			"Pear", t0,
		)

		// Now only five slots render - 6 and 7 truncate away.
		val merged = memory.update(
			snapshotOf("Pear", empty(1), empty(2), empty(3), empty(4), empty(5)),
			"Pear", at(60),
		)

		val slot6 = merged[5]
		assertEquals(SlotSource.REMEMBERED, slot6.source)
		assertEquals(ForgeSlotState.IN_PROGRESS, slot6.state)
		assertEquals("Refined Diamond", slot6.itemName)
		// Counted down from the absolute finish time, not decremented.
		assertEquals(2.hours, slot6.remaining)
		assertEquals(t0, slot6.observedAt, "the age shown must be when it was last seen")

		// Slot 7 was empty when last seen, so it is remembered as empty.
		assertEquals(SlotSource.REMEMBERED, merged[6].source)
		assertEquals(ForgeSlotState.EMPTY, merged[6].state)
	}

	@Test
	fun `a remembered slot counts down from an absolute finish time`() {
		val memory = ForgeMemory()
		memory.update(
			snapshotOf(
				"Pear", empty(1), empty(2), empty(3), empty(4), empty(5),
				busy(6, "Refined Diamond", "3h"), empty(7),
			),
			"Pear", t0,
		)

		val short = snapshotOf("Pear", empty(1), empty(2), empty(3), empty(4), empty(5))

		// Reading at different times gives different remainders from ONE stored
		// instant. Nothing is decremented per refresh, so missed refreshes
		// cannot make it drift.
		assertEquals(3.hours - 30.minutes, memory.update(short, "Pear", at(30))[5].remaining)
		assertEquals(1.hours, memory.update(short, "Pear", at(120))[5].remaining)
	}

	@Test
	fun `a remembered slot that would have finished reads as ready`() {
		val memory = ForgeMemory()
		memory.update(
			snapshotOf(
				"Pear", empty(1), empty(2), empty(3), empty(4), empty(5),
				busy(6, "Refined Diamond", "30m"), empty(7),
			),
			"Pear", t0,
		)

		val merged = memory.update(
			snapshotOf("Pear", empty(1), empty(2), empty(3), empty(4), empty(5)),
			"Pear", at(45),
		)

		assertEquals(SlotSource.REMEMBERED, merged[5].source)
		assertEquals(ForgeSlotState.READY, merged[5].state)
		assertNull(merged[5].remaining)
	}

	// ------------------------------------------------------ profile changes

	@Test
	fun `changing profile clears the memory`() {
		val memory = ForgeMemory()
		memory.update(
			snapshotOf(
				"Pear", empty(1), empty(2), empty(3), empty(4), empty(5),
				busy(6, "Refined Diamond", "3h"), empty(7),
			),
			"Pear", t0,
		)
		assertEquals(7, memory.size)

		// A different profile is a different forge entirely.
		val merged = memory.update(
			snapshotOf("Mango", empty(1), empty(2), empty(3), empty(4), empty(5)),
			"Mango", at(10),
		)

		assertEquals("Mango", memory.knownProfile)
		assertEquals(
			SlotSource.NONE, merged[5].source,
			"the other profile's slot 6 must not carry over",
		)
		assertEquals(ForgeSlotState.UNKNOWN, merged[5].state)
	}

	@Test
	fun `staying on the same profile keeps the memory`() {
		val memory = ForgeMemory()
		memory.update(
			snapshotOf(
				"Pear", empty(1), empty(2), empty(3), empty(4), empty(5),
				busy(6, "Refined Diamond", "3h"), empty(7),
			),
			"Pear", t0,
		)
		val merged = memory.update(
			snapshotOf("Pear", empty(1), empty(2), empty(3), empty(4), empty(5)),
			"Pear", at(10),
		)
		assertEquals(SlotSource.REMEMBERED, merged[5].source)
	}

	@Test
	fun `an unreadable profile neither reads nor writes the cache`() {
		val memory = ForgeMemory()
		memory.update(
			snapshotOf(
				"Pear", empty(1), empty(2), empty(3), empty(4), empty(5),
				busy(6, "Refined Diamond", "3h"), empty(7),
			),
			"Pear", t0,
		)

		// Profile row truncated away: we cannot tell whose forge this is.
		val merged = memory.update(
			snapshotOf("Pear", empty(1), empty(2), empty(3), empty(4), empty(5)),
			null, at(10),
		)

		assertEquals(
			SlotSource.NONE, merged[5].source,
			"memory must not be served when the profile cannot be confirmed",
		)
		assertEquals("Pear", memory.knownProfile, "and the stored profile must be left alone")
		assertEquals(7, memory.size, "and nothing may be written")
	}

	// -------------------------------------------------------------- expiry

	@Test
	fun `memories older than the limit are forgotten`() {
		val memory = ForgeMemory(maxAge = 6.hours)
		memory.update(
			snapshotOf(
				"Pear", empty(1), empty(2), empty(3), empty(4), empty(5),
				busy(6, "Refined Diamond", "48h"), empty(7),
			),
			"Pear", t0,
		)

		val short = snapshotOf("Pear", empty(1), empty(2), empty(3), empty(4), empty(5))

		// Just inside the window.
		assertEquals(SlotSource.REMEMBERED, memory.update(short, "Pear", at(5 * 60))[5].source)

		// Past it: better to admit ignorance than show something misleading.
		assertEquals(SlotSource.NONE, memory.update(short, "Pear", at(7 * 60))[5].source)
		assertEquals(ForgeSlotState.UNKNOWN, memory.update(short, "Pear", at(7 * 60))[5].state)
	}

	@Test
	fun `the default expiry is six hours`() {
		assertEquals(6.hours, ForgeMemory.DEFAULT_MAX_AGE)
	}

	// ------------------------------------------------------ unparsed rows

	@Test
	fun `a rendered but unrecognised slot is shown live and never remembered`() {
		val memory = ForgeMemory()

		val merged = memory.update(
			snapshotOf(
				"Pear",
				" 1) §6Refined Titanium§7: §dPaused",
				empty(2), empty(3), empty(4), empty(5), empty(6), empty(7),
			),
			"Pear", t0,
		)

		assertEquals(SlotSource.LIVE, merged[0].source, "current information, even if unreadable")
		assertEquals(ForgeSlotState.UNKNOWN, merged[0].state)

		// It must not become a memory: we never understood the state.
		val short = snapshotOf("Pear")
		val later = memory.update(short, "Pear", at(10))
		assertEquals(
			SlotSource.NONE, later[0].source,
			"a state we never understood must not be invented later",
		)
	}

	@Test
	fun `live slots never carry an observed timestamp`() {
		val memory = ForgeMemory()
		val merged = memory.update(
			snapshotOf(
				"Pear", busy(1, "Refined Titanium", "11h"),
				empty(2), empty(3), empty(4), empty(5), empty(6), empty(7),
			),
			"Pear", t0,
		)
		assertEquals(SlotSource.LIVE, merged[0].source)
		assertNull(merged[0].observedAt, "an age label belongs only to remembered values")
		assertNotNull(merged[0].remaining)
	}
}
