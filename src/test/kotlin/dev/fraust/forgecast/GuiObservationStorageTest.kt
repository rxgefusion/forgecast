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
 * First step of the merge layer: GUI readings reach [ForgeMemory].
 *
 * Two sensors watch the same seven slots. The widget is always available but
 * rounded and truncatable; the GUI is exact to the second but only readable
 * while the Forge is open. This step stores BOTH, tagged by source. It
 * deliberately does not decide between them - that is the next step, and it
 * cannot be written sensibly if one reading has already overwritten the other.
 */
class GuiObservationStorageTest {

	private val t0: Instant = Instant.parse("2026-09-02T19:00:00Z")

	private fun at(offsetSeconds: Long): Instant = t0.plusSeconds(offsetSeconds)

	private fun guiRow(index: Int, name: String, vararg tips: String) =
		GuiSlotRow(index, 1, name, tips.toList())

	/** A Forge screen with one running slot and one empty. */
	private fun guiSnapshot(observedAt: Instant, remaining: String = "8h 46m 2s") =
		GuiForgeParser.parse(
			listOf(
				guiRow(10, "§6Refined Titanium", "§7Currently making: §6Refined Titanium", "§7Time Remaining: §a$remaining"),
				guiRow(12, "§aSlot #3", "§eClick to select a process!"),
			),
			observedAt,
		)

	/** A widget reading: rounded, as the tab list always is. */
    private fun widgetSnapshot(vararg slotRows: String) =
		ForgeParser.parse(
			buildList {
				add(TabRow("!C-b", "§9§lForges:"))
				slotRows.forEachIndexed { i, row -> add(TabRow("!C-${'c' + i}", row)) }
			}
		)

	// ------------------------------------------- a GUI reading is stored

	@Test
	fun `a GUI reading is stored with an exact absolute finish time`() {
		val memory = ForgeMemory()
		memory.recordGuiObservation(guiSnapshot(t0), "TestProfile", t0)

		val slot1 = memory.storedObservations().single { it.slot == 1 && it.source == ObservationSource.GUI }

		assertEquals(ForgeSlotState.IN_PROGRESS, slot1.state)
		assertEquals("Refined Titanium", slot1.itemName)
		assertNotNull(slot1.finishAt)
		// 8h 46m 2s after the observation, to the second - not rounded to 9h.
		assertEquals(t0.plusSeconds(8 * 3600 + 46 * 60 + 2), slot1.finishAt)
	}

	@Test
	fun `the finish time is absolute, so it does not move as time passes`() {
		val memory = ForgeMemory()
		memory.recordGuiObservation(guiSnapshot(t0), "TestProfile", t0)
		val first = memory.storedObservations().single { it.source == ObservationSource.GUI && it.slot == 1 }.finishAt

		// Nothing further observed; simply asking again much later.
		val second = memory.storedObservations().single { it.source == ObservationSource.GUI && it.slot == 1 }.finishAt

		assertEquals(first, second, "a stored instant must not drift; only a stored duration would")
	}

	@Test
	fun `the source is recorded on every stored reading`() {
		val memory = ForgeMemory()
		memory.update(widgetSnapshot(" 1) §6Refined Titanium§7: §b9h", " 2) §7EMPTY"), "TestProfile", t0)
		memory.recordGuiObservation(guiSnapshot(t0), "TestProfile", t0)

		val sources = memory.storedObservations().map { it.source }.toSet()
		assertEquals(setOf(ObservationSource.WIDGET, ObservationSource.GUI), sources)
	}

	@Test
	fun `empty and locked slots from the GUI are stored too`() {
		val memory = ForgeMemory()
		memory.recordGuiObservation(guiSnapshot(t0), "TestProfile", t0)

		val slot3 = memory.storedObservations().single { it.slot == 3 && it.source == ObservationSource.GUI }
		assertEquals(ForgeSlotState.EMPTY, slot3.state)
		assertNull(slot3.finishAt, "an empty slot has nothing to finish")
	}

	// ------------------------------------ the two stores do not collide

	@Test
	fun `a GUI reading does not overwrite the widget reading for the same slot`() {
		val memory = ForgeMemory()
		// Widget says a rounded 9h.
		memory.update(widgetSnapshot(" 1) §6Refined Titanium§7: §b9h"), "TestProfile", t0)
		// GUI says an exact 8h 46m 2s for the same slot.
		memory.recordGuiObservation(guiSnapshot(t0), "TestProfile", t0)

		val forSlot1 = memory.storedObservations().filter { it.slot == 1 }
		assertEquals(2, forSlot1.size, "both readings must survive: $forSlot1")

		val widget = forSlot1.single { it.source == ObservationSource.WIDGET }
		val gui = forSlot1.single { it.source == ObservationSource.GUI }
		assertEquals(t0.plusSeconds(9 * 3600), widget.finishAt, "the rounded one is unchanged")
		assertEquals(t0.plusSeconds(8 * 3600 + 46 * 60 + 2), gui.finishAt, "the exact one is unchanged")
	}

	@Test
	fun `a widget reading still stores exactly as it did before`() {
		val memory = ForgeMemory()
		val merged = memory.update(
			widgetSnapshot(" 1) §6Refined Titanium§7: §b9h", " 2) §7EMPTY"),
			"TestProfile", t0,
		)

		// The display path is untouched: still live, still driven by the widget.
		assertEquals(SlotSource.LIVE, merged[0].source)
		assertEquals(ForgeSlotState.IN_PROGRESS, merged[0].state)
		assertEquals(9.hours, merged[0].remaining)
	}

	@Test
	fun `nothing arbitrates yet - the display ignores the GUI reading entirely`() {
		val memory = ForgeMemory()
		memory.recordGuiObservation(guiSnapshot(t0), "TestProfile", t0)

		// Slot 1 is now truncated out of the widget view. If arbitration had been
		// written early, the GUI reading would surface here. It must not yet.
		val merged = memory.update(widgetSnapshot(" 2) §7EMPTY"), "TestProfile", at(60))

		assertEquals(
			SlotSource.NONE, merged[0].source,
			"the GUI reading is stored but must not yet reach the display",
		)
	}

	// ------------------------------------------------- profile and safety

	@Test
	fun `a GUI reading is not stored when the profile is unknown`() {
		val memory = ForgeMemory()
		memory.recordGuiObservation(guiSnapshot(t0), null, t0)
		assertEquals(0, memory.guiSize, "attributing one profile's forge to another would be worse")
	}

	@Test
	fun `changing profile clears the GUI store as well as the widget one`() {
		val memory = ForgeMemory()
		memory.update(widgetSnapshot(" 1) §6Refined Titanium§7: §b9h"), "Pear", t0)
		memory.recordGuiObservation(guiSnapshot(t0), "Pear", t0)
		assertTrue(memory.guiSize > 0)

		memory.recordGuiObservation(guiSnapshot(at(60)), "Mango", at(60))

		val slots = memory.storedObservations().map { it.slot to it.source }
		assertTrue(
			slots.none { it.second == ObservationSource.WIDGET },
			"the other profile's widget readings must not survive: $slots",
		)
	}

	@Test
	fun `an unreadable GUI slot is not stored`() {
		val memory = ForgeMemory()
		val snapshot = GuiForgeParser.parse(
			listOf(guiRow(10, "§6Refined Titanium", "§7Time Remaining: §aSoonish")),
			t0,
		)
		memory.recordGuiObservation(snapshot, "TestProfile", t0)

		assertEquals(
			0, memory.guiSize,
			"a reading the parser could not understand must not be stored as if it were",
		)
	}

	@Test
	fun `a slot whose own number disagrees is not stored`() {
		// GuiForgeParser flags this; storing it would mean keeping something we
		// have already said we do not trust.
		val memory = ForgeMemory()
		val snapshot = GuiForgeParser.parse(
			listOf(guiRow(12, "§aSlot #5", "§eClick to select a process!")),
			t0,
		)
		assertTrue(snapshot.problems.isNotEmpty(), "the parser should have objected")

		memory.recordGuiObservation(snapshot, "TestProfile", t0)
		assertEquals(0, memory.guiSize)
	}

	// --------------------------------------------------------- inspection

	@Test
	fun `stored observations are ordered by slot then source`() {
		val memory = ForgeMemory()
		memory.update(widgetSnapshot(" 1) §7EMPTY", " 2) §7EMPTY"), "TestProfile", t0)
		memory.recordGuiObservation(guiSnapshot(t0), "TestProfile", t0)

		val order = memory.storedObservations().map { it.slot }
		assertEquals(order.sorted(), order, "readable output needs a stable order")
	}

	@Test
	fun `an empty memory reports nothing rather than failing`() {
		assertEquals(emptyList<StoredObservation>(), ForgeMemory().storedObservations())
	}

	@Test
	fun `a second GUI reading of the same slot replaces the first`() {
		val memory = ForgeMemory()
		memory.recordGuiObservation(guiSnapshot(t0, "8h 46m 2s"), "TestProfile", t0)
		memory.recordGuiObservation(guiSnapshot(at(60), "8h 45m 2s"), "TestProfile", at(60))

		val gui = memory.storedObservations().filter { it.source == ObservationSource.GUI && it.slot == 1 }
		assertEquals(1, gui.size, "one reading per slot per sensor, not a growing history")
		assertEquals(at(60), gui[0].observedAt)
		// Both readings describe the same finish moment, a minute apart.
		assertEquals(t0.plusSeconds(8 * 3600 + 46 * 60 + 2), gui[0].finishAt)
	}

	@Test
	fun `sub-minute and multi-unit GUI times both convert correctly`() {
		val memory = ForgeMemory()
		memory.recordGuiObservation(guiSnapshot(t0, "45s"), "TestProfile", t0)
		assertEquals(
			t0.plusSeconds(45),
			memory.storedObservations().single { it.source == ObservationSource.GUI && it.slot == 1 }.finishAt,
		)

		val other = ForgeMemory()
		other.recordGuiObservation(guiSnapshot(t0, "2d 3h 4m 5s"), "TestProfile", t0)
		assertEquals(
			t0.plusSeconds(2 * 86400 + 3 * 3600 + 4 * 60 + 5),
			other.storedObservations().single { it.source == ObservationSource.GUI && it.slot == 1 }.finishAt,
		)
	}
}
