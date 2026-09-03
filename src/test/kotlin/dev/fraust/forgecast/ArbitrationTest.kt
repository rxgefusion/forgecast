package dev.fraust.forgecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Second step of the merge layer: deciding what to believe.
 *
 * The two sensors are not equals. The GUI is exact to the second, so it is the
 * truth. The widget FLOORS to its unit - slot 3 read "11h" on the widget while
 * the GUI read "11h 59m" at the same moment - so it can never refine a number,
 * only contradict one.
 *
 * That floor is what makes agreement decidable rather than a matter of
 * tolerance-fiddling: a widget value is a LOWER BOUND, so the two agree exactly
 * when the belief falls in the bucket that floors to the widget's value.
 */
class ArbitrationTest {

	private val t0: Instant = Instant.parse("2026-09-02T19:00:00Z")

	private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

	private fun guiRow(index: Int, name: String, vararg tips: String) =
		GuiSlotRow(index, 1, name, tips.toList())

	/** A Forge screen reading of slot 1, exact to the second. */
	private fun gui(remaining: String, item: String = "Refined Titanium") =
		GuiForgeParser.parse(
			listOf(
				guiRow(10, "§6$item", "§7Currently making: §6$item", "§7Time Remaining: §a$remaining"),
			),
			t0,
		)

	/** A tab-list reading. Slot numbers come from the row text, as on the server. */
	private fun widget(vararg slotRows: String) =
		ForgeParser.parse(
			buildList {
				add(TabRow("!C-b", "§9§lForges:"))
				slotRows.forEachIndexed { i, row -> add(TabRow("!C-${'c' + i}", row)) }
			}
		)

	private fun running(item: String, time: String) = " 1) §6$item§7: §b$time"

	private fun ready(item: String) = " 1) §6$item§7: §aReady!"

	/** Memory that has seen the Forge screen once, at t0. */
	private fun seeded(remaining: String, item: String = "Refined Titanium"): ForgeMemory {
		val memory = ForgeMemory()
		memory.recordGuiObservation(gui(remaining, item), "TestProfile", t0)
		return memory
	}

	private fun slot1(beliefs: List<ForgeBelief>) = beliefs.single { it.slot == 1 }

	// ------------------------------------------------ the floor arithmetic

	@Test
	fun `the widget granularity is inferred from the value's own unit`() {
		assertEquals(1.hours, ForgeArbiter.widgetGranularity(11.hours))
		assertEquals(1.minutes, ForgeArbiter.widgetGranularity(45.minutes))
		assertEquals(1.seconds, ForgeArbiter.widgetGranularity(26.seconds))
		assertEquals(1.days, ForgeArbiter.widgetGranularity(2.days))
	}

	@Test
	fun `a belief inside the bucket that floors to the widget value agrees`() {
		// The observed case: GUI 11h 59m, widget 11h, same moment.
		assertTrue(ForgeArbiter.widgetAgrees(11.hours + 59.minutes, 11.hours))
		assertTrue(ForgeArbiter.widgetAgrees(11.hours, 11.hours))
		assertTrue(ForgeArbiter.widgetAgrees(11.hours + 1.seconds, 11.hours))
	}

	@Test
	fun `a widget value below the floor of the belief is a disagreement`() {
		// The widget never overestimates, so it cannot legitimately read lower.
		assertFalse(ForgeArbiter.widgetAgrees(11.hours + 59.minutes, 5.hours))
		assertFalse(ForgeArbiter.widgetAgrees(11.hours + 59.minutes, 10.hours))
	}

	@Test
	fun `a widget value above the belief is a disagreement too - a longer recipe started`() {
		assertFalse(ForgeArbiter.widgetAgrees(2.hours, 5.hours))
	}

	@Test
	fun `the window absorbs one bucket of tick lag but no more`() {
		// The widget updates on a tick and can briefly still show the previous,
		// HIGHER bucket. One unit of that is lag; two is a changed slot.
		assertTrue(ForgeArbiter.widgetAgrees(10.hours + 59.minutes + 59.seconds, 11.hours))
		assertFalse(ForgeArbiter.widgetAgrees(9.hours, 11.hours))
	}

	// -------------------------------------------------- the GUI is the truth

	@Test
	fun `an exact GUI reading is shown instead of the rounded widget one`() {
		val memory = seeded("8h 46m 2s")
		val beliefs = memory.believe(widget(running("Refined Titanium", "8h")), "TestProfile", t0)

		val slot = slot1(beliefs)
		assertEquals(Confidence.EXACT, slot.confidence)
		assertEquals(ObservationSource.GUI, slot.source)
		assertEquals(8.hours + 46.minutes + 2.seconds, slot.remaining)
	}

	@Test
	fun `a rounded widget time does NOT overwrite an exact belief`() {
		// The case that motivated the whole floor rule.
		val memory = seeded("11h 59m")
		val beliefs = memory.believe(widget(running("Refined Titanium", "11h")), "TestProfile", t0)

		val slot = slot1(beliefs)
		assertEquals(11.hours + 59.minutes, slot.remaining, "the floored widget value must not win")
		assertEquals(Confidence.EXACT, slot.confidence)
	}

	@Test
	fun `the exact belief counts down on its own, without the widget refreshing it`() {
		val memory = seeded("8h 46m 2s")
		val beliefs = memory.believe(widget(running("Refined Titanium", "8h")), "TestProfile", at(3600))

		assertEquals(7.hours + 46.minutes + 2.seconds, slot1(beliefs).remaining)
	}

	@Test
	fun `a slot truncated out of the widget still reports its exact time`() {
		// The payoff: the widget cannot see slot 1 at all here.
		val memory = seeded("8h 46m 2s")
		val beliefs = memory.believe(widget(" 2) §7EMPTY"), "TestProfile", t0)

		val slot = slot1(beliefs)
		assertEquals(Confidence.EXACT, slot.confidence)
		assertEquals(8.hours + 46.minutes + 2.seconds, slot.remaining)
	}

	// ---------------------------------- the widget detects change, not time

	@Test
	fun `the widget detects a changed item and the belief is abandoned`() {
		val memory = seeded("8h 46m 2s", "Refined Titanium")
		val beliefs = memory.believe(widget(running("Refined Diamond", "3h")), "TestProfile", at(60))

		val slot = slot1(beliefs)
		assertEquals("Refined Diamond", slot.itemName, "the widget can see the slot right now")
		assertEquals(3.hours, slot.remaining)
		assertEquals(Confidence.APPROXIMATE, slot.confidence, "rounded, so never exact")
		assertEquals(ObservationSource.WIDGET, slot.source)
	}

	@Test
	fun `the widget detects Ready sooner than the belief predicted`() {
		val memory = seeded("8h 46m 2s")
		val beliefs = memory.believe(widget(ready("Refined Titanium")), "TestProfile", at(60))

		val slot = slot1(beliefs)
		assertEquals(ForgeSlotState.READY, slot.state)
		assertEquals(ObservationSource.WIDGET, slot.source, "the belief said 8 hours; it was wrong")
		assertNull(slot.remaining)
	}

	@Test
	fun `the widget detects a slot that has been emptied`() {
		val memory = seeded("8h 46m 2s")
		val beliefs = memory.believe(widget(" 1) §7EMPTY"), "TestProfile", at(60))

		assertEquals(ForgeSlotState.EMPTY, slot1(beliefs).state)
	}

	@Test
	fun `a disproved belief is dropped, not merely ignored`() {
		val memory = seeded("8h 46m 2s")
		memory.believe(widget(running("Refined Diamond", "3h")), "TestProfile", at(60))

		assertEquals(
			0, memory.guiSize,
			"a belief kept after being disproved would return the moment the slot truncated",
		)

		// And it does not resurrect once the widget stops seeing the slot.
		val later = memory.believe(widget(" 2) §7EMPTY"), "TestProfile", at(120))
		assertNotEquals(ObservationSource.GUI, slot1(later).source)
	}

	@Test
	fun `a slot the widget cannot see cannot disprove anything`() {
		val memory = seeded("8h 46m 2s")
		memory.believe(widget(" 2) §7EMPTY"), "TestProfile", at(60))
		assertEquals(1, memory.guiSize, "absence of evidence is not evidence")
	}

	@Test
	fun `an unreadable widget row does not disprove a belief either`() {
		val memory = seeded("8h 46m 2s")
		val beliefs = memory.believe(widget(" 1) §6Refined Titanium§7: §bsoonish"), "TestProfile", t0)

		assertEquals(
			Confidence.EXACT, slot1(beliefs).confidence,
			"a row we failed to parse says nothing about whether the belief is right",
		)
	}

	// --------------------------------------- past the predicted finish time

	@Test
	fun `a belief past its finish, confirmed Ready by the widget, is exact`() {
		val memory = seeded("1h")
		val beliefs = memory.believe(widget(ready("Refined Titanium")), "TestProfile", at(3700))

		val slot = slot1(beliefs)
		assertEquals(ForgeSlotState.READY, slot.state)
		assertEquals(Confidence.EXACT, slot.confidence)
	}

	@Test
	fun `a belief past its finish with nothing to confirm it degrades rather than lying`() {
		val memory = seeded("1h")
		// Slot 1 is truncated out of the widget, so nothing can confirm it.
		val beliefs = memory.believe(widget(" 2) §7EMPTY"), "TestProfile", at(3700))

		val slot = slot1(beliefs)
		assertEquals(ForgeSlotState.READY, slot.state, "the arithmetic is still sound")
		assertEquals(
			Confidence.APPROXIMATE, slot.confidence,
			"but 'nobody collected it' is an assumption, and must not be shown as a fact",
		)
	}

	@Test
	fun `a belief past its finish while the widget still counts down is abandoned`() {
		val memory = seeded("1h")
		val beliefs = memory.believe(widget(running("Refined Titanium", "4h")), "TestProfile", at(3700))

		val slot = slot1(beliefs)
		assertEquals(4.hours, slot.remaining, "the belief describes a recipe no longer running")
		assertEquals(Confidence.APPROXIMATE, slot.confidence)
	}

	// ---------------------------------------------------- the three tiers

	@Test
	fun `a widget-only slot is approximate, never exact`() {
		val memory = ForgeMemory()
		val beliefs = memory.believe(widget(running("Refined Titanium", "9h")), "TestProfile", t0)

		val slot = slot1(beliefs)
		assertEquals(Confidence.APPROXIMATE, slot.confidence, "every widget number is floored")
		assertEquals(ObservationSource.WIDGET, slot.source)
	}

	@Test
	fun `a slot never seen by either sensor is unknown`() {
		val memory = ForgeMemory()
		val beliefs = memory.believe(widget(" 2) §7EMPTY"), "TestProfile", t0)

		val slot = slot1(beliefs)
		assertEquals(Confidence.UNKNOWN, slot.confidence)
		assertNull(slot.source, "nothing to attribute it to")
	}

	@Test
	fun `a rendered but unrecognised row is unknown, not silently dropped`() {
		val memory = ForgeMemory()
		val beliefs = memory.believe(widget(" 1) §6Refined Titanium§7: §bsoonish"), "TestProfile", t0)

		val slot = slot1(beliefs)
		assertEquals(Confidence.UNKNOWN, slot.confidence)
		assertEquals(
			ObservationSource.WIDGET, slot.source,
			"the widget did render it, so it is visible-but-unreadable, not absent",
		)
	}

	@Test
	fun `all three tiers can appear in one reading`() {
		// Slot 1 exact from the GUI, slot 2 approximate from the widget,
		// slot 3 unknown to both.
		val memory = seeded("8h 46m 2s")
		val beliefs = memory.believe(widget(" 2) §6Refined Diamond§7: §b9h"), "TestProfile", t0)

		assertEquals(Confidence.EXACT, beliefs.single { it.slot == 1 }.confidence)
		assertEquals(Confidence.APPROXIMATE, beliefs.single { it.slot == 2 }.confidence)
		assertEquals(Confidence.UNKNOWN, beliefs.single { it.slot == 3 }.confidence)
	}

	// ---------------------------------------------------- profile safety

	@Test
	fun `beliefs are not applied when the profile cannot be read`() {
		val memory = seeded("8h 46m 2s")
		val beliefs = memory.believe(widget(" 2) §7EMPTY"), null, t0)

		assertNotEquals(
			ObservationSource.GUI, slot1(beliefs).source,
			"without a profile we cannot tell whose forge the belief describes",
		)
	}

	@Test
	fun `a remembered widget slot is approximate and carries its age`() {
		val memory = ForgeMemory()
		memory.believe(widget(running("Refined Titanium", "9h")), "TestProfile", t0)
		val beliefs = memory.believe(widget(" 2) §7EMPTY"), "TestProfile", at(600))

		val slot = slot1(beliefs)
		assertEquals(Confidence.APPROXIMATE, slot.confidence)
		assertEquals(t0, slot.observedAt, "the HUD renders this as an age")
		assertEquals(8.hours + 50.minutes, slot.remaining)
	}
}
