package dev.fraust.forgecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for the false "widget is off" warning.
 *
 * Seen on the Private Island with the widget working, while the panel showed
 * correct forge times. The tab list is built over several packets, so for a
 * moment after a warp the Profile row has arrived and the Forges rows have not
 * - which classifies exactly like a switched-off widget.
 *
 * The warning never corrected itself because COMPLETE is silent by design, so
 * nothing takes back a wrong line. There is deliberately no retraction message;
 * the fix is not firing wrongly.
 *
 * These drive the real pipeline - classify, then [AdviceStabiliser], then
 * [AdviceThrottle] - because the bug lived in how those three combine.
 */
class TransientWarningTest {

	/** A tab list mid-build: the Profile row has arrived, the Forges rows have not. */
	private fun halfBuiltRows(): List<TabRow> = listOf(
		TabRow("!B-a", "               §3§lInfo"),
		TabRow("!B-g", "§e§lProfile: §aTestProfile"),
		TabRow("!B-h", ""),
	)

	private fun completeRows(): List<TabRow> = listOf(
		TabRow("!B-g", "§e§lProfile: §aTestProfile"),
		TabRow("!C-b", "§9§lForges:"),
		TabRow("!C-c", " 1) §6Refined Titanium§7: §b9h"),
		TabRow("!C-d", " 2) §7EMPTY"),
		TabRow("!C-e", " 3) §7EMPTY"),
		TabRow("!C-f", " 4) §7EMPTY"),
		TabRow("!C-g", " 5) §7EMPTY"),
		TabRow("!C-h", " 6) §7EMPTY"),
		TabRow("!C-i", " 7) §7EMPTY"),
	)

	/** Five of seven slots: a genuine, persistent truncation. */
	private fun truncatedRows(): List<TabRow> = listOf(
		TabRow("!B-g", "§e§lProfile: §aTestProfile"),
		TabRow("!D-p", "§9§lForges:"),
		TabRow("!D-q", " 1) §6Refined Titanium§7: §b9h"),
		TabRow("!D-r", " 2) §7EMPTY"),
		TabRow("!D-s", " 3) §7EMPTY"),
		TabRow("!D-t", " 4) §7EMPTY"),
	)

	private fun caseOf(rows: List<TabRow>) = ForgeAdvice.classify(rows, ForgeParser.parse(rows))

	/** Runs a sequence of readings through the whole pipeline. */
	private fun announcementsFor(sequence: List<List<TabRow>>): List<ForgeDataCase> {
		val stabiliser = AdviceStabiliser()
		val throttle = AdviceThrottle()
		return sequence.mapNotNull { rows ->
			stabiliser.offer(caseOf(rows))?.let { throttle.announce(it) }
		}
	}

	// -------------------------------------------------- the reported bug

	@Test
	fun `the half-built tab list is what classifies as WIDGET_OFF`() {
		// Confirms the mechanism rather than assuming it: some ! rows present,
		// no Forges section, spare rows left. Exactly the message that was seen.
		assertEquals(ForgeDataCase.WIDGET_OFF, caseOf(halfBuiltRows()))
		assertEquals(ForgeDataCase.COMPLETE, caseOf(completeRows()))
	}

	@Test
	fun `an empty tab list is a different case, so the report was not that`() {
		// Worth pinning: a fully empty list reads as WIDGETS_OFF_ENTIRELY, whose
		// message is different from the one actually seen. The tab list was
		// partially built, not empty.
		assertEquals(ForgeDataCase.WIDGETS_OFF_ENTIRELY, caseOf(emptyList()))
	}

	@Test
	fun `a single bad reading in a good sequence must not warn`() {
		val announced = announcementsFor(
			listOf(
				completeRows(), completeRows(),
				halfBuiltRows(),          // the loading gap
				completeRows(), completeRows(), completeRows(),
			)
		)
		assertEquals(emptyList<ForgeDataCase>(), announced, "a loading gap must never speak")
	}

	@Test
	fun `two consecutive bad readings still must not warn`() {
		// A slower warp. Still shorter than the streak requirement.
		val announced = announcementsFor(
			listOf(
				completeRows(),
				halfBuiltRows(), halfBuiltRows(),
				completeRows(), completeRows(), completeRows(),
			)
		)
		assertEquals(emptyList<ForgeDataCase>(), announced)
	}

	@Test
	fun `a sustained bad reading must still warn, exactly once`() {
		val announced = announcementsFor(List(8) { halfBuiltRows() })
		assertEquals(listOf(ForgeDataCase.WIDGET_OFF), announced)
	}

	@Test
	fun `an all-empty sequence warns that widgets are off entirely`() {
		val announced = announcementsFor(List(6) { emptyList<TabRow>() })
		assertEquals(listOf(ForgeDataCase.WIDGETS_OFF_ENTIRELY), announced)
	}

	@Test
	fun `a real truncation is still reported despite the delay`() {
		// The fix must not silence genuine problems, only transient ones.
		val announced = announcementsFor(List(5) { truncatedRows() })
		assertEquals(listOf(ForgeDataCase.TRUNCATED), announced)
	}

	@Test
	fun `flapping between two problems announces neither`() {
		// Nothing holds long enough to be trusted, which is the correct answer:
		// something is unstable, and guessing which state is real would be worse.
		val announced = announcementsFor(
			listOf(
				halfBuiltRows(), truncatedRows(),
				halfBuiltRows(), truncatedRows(),
				halfBuiltRows(), truncatedRows(),
			)
		)
		assertEquals(emptyList<ForgeDataCase>(), announced)
	}

	@Test
	fun `a warning arrives on exactly the third consecutive reading`() {
		val stabiliser = AdviceStabiliser()
		assertNull(stabiliser.offer(ForgeDataCase.WIDGET_OFF), "first reading is not enough")
		assertNull(stabiliser.offer(ForgeDataCase.WIDGET_OFF), "second reading is not enough")
		assertEquals(ForgeDataCase.WIDGET_OFF, stabiliser.offer(ForgeDataCase.WIDGET_OFF))
	}

	// ------------------------------------------------------- the stabiliser

	@Test
	fun `a changed reading restarts the count`() {
		val stabiliser = AdviceStabiliser()
		stabiliser.offer(ForgeDataCase.WIDGET_OFF)
		stabiliser.offer(ForgeDataCase.WIDGET_OFF)
		assertEquals(2, stabiliser.currentStreak)

		stabiliser.offer(ForgeDataCase.COMPLETE)
		assertEquals(1, stabiliser.currentStreak, "a different reading starts again from one")
	}

	@Test
	fun `reset drops the run so nothing carries across a transition`() {
		val stabiliser = AdviceStabiliser()
		stabiliser.offer(ForgeDataCase.WIDGET_OFF)
		stabiliser.offer(ForgeDataCase.WIDGET_OFF)

		// World changed: whatever is seen next must earn its own streak.
		stabiliser.reset()
		assertEquals(0, stabiliser.currentStreak)
		assertNull(stabiliser.offer(ForgeDataCase.WIDGET_OFF), "the old run must not count")
	}

	@Test
	fun `once settled, every later reading passes through`() {
		val stabiliser = AdviceStabiliser()
		repeat(3) { stabiliser.offer(ForgeDataCase.WIDGET_OFF) }
		// The throttle, not the stabiliser, is what stops repeats.
		assertEquals(ForgeDataCase.WIDGET_OFF, stabiliser.offer(ForgeDataCase.WIDGET_OFF))
	}

	@Test
	fun `the threshold is three readings`() {
		assertEquals(3, AdviceStabiliser.DEFAULT_REQUIRED)
		// One second per reading, so roughly three seconds - longer than an
		// ordinary tab-list rebuild after a warp.
		assertTrue(AdviceStabiliser.DEFAULT_REQUIRED >= 2, "one reading would defeat the purpose")
	}
}
