package dev.fraust.forgecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Slice 2: the completion alert.
 *
 * The alert fires on a TRANSITION, and a transition needs two sightings. That
 * one rule settles both hard cases without a timer or a heuristic:
 *
 *  - A slot already Ready at login has one sighting, so it is a discovery and
 *    fires nothing.
 *  - A belief that reached READY by arithmetic is not a sighting at all, so it
 *    neither fires nor disturbs the baseline - the alert simply waits until
 *    something can actually confirm it.
 */
class CompletionAlertTest {

	private val t0: Instant = Instant.parse("2026-09-02T19:00:00Z")

	private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

	private fun guiRow(index: Int, name: String, vararg tips: String) =
		GuiSlotRow(index, 1, name, tips.toList())

	private fun gui(remaining: String, item: String = "Refined Titanium") =
		GuiForgeParser.parse(
			listOf(
				guiRow(10, "§6$item", "§7Currently making: §6$item", "§7Time Remaining: §a$remaining"),
			),
			t0,
		)

	private fun widget(vararg slotRows: String) =
		ForgeParser.parse(
			buildList {
				add(TabRow("!C-b", "§9§lForges:"))
				slotRows.forEachIndexed { i, row -> add(TabRow("!C-${'c' + i}", row)) }
			}
		)

	private fun running(slot: Int, item: String, time: String) = " $slot) §6$item§7: §b$time"

	private fun ready(slot: Int, item: String) = " $slot) §6$item§7: §aReady!"

	/** Widget-only beliefs, which is the ordinary case away from the Forge. */
	private fun beliefs(memory: ForgeMemory, snapshot: ForgeSnapshot, now: Instant = t0) =
		memory.believe(snapshot, "TestProfile", now)

	// -------------------------------------------------- the ordinary case

	@Test
	fun `a slot seen running and then Ready fires once`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		watcher.offer(beliefs(memory, widget(running(1, "Refined Titanium", "1h"))), "TestProfile")
		val fired = watcher.offer(beliefs(memory, widget(ready(1, "Refined Titanium")), at(60)), "TestProfile")

		assertEquals(listOf(ForgeCompletion(1, "Refined Titanium")), fired)
	}

	@Test
	fun `it does not fire again on later readings of the same Ready slot`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		watcher.offer(beliefs(memory, widget(running(1, "Refined Titanium", "1h"))), "TestProfile")
		watcher.offer(beliefs(memory, widget(ready(1, "Refined Titanium")), at(60)), "TestProfile")

		repeat(5) { i ->
			assertEquals(
				emptyList<ForgeCompletion>(),
				watcher.offer(beliefs(memory, widget(ready(1, "Refined Titanium")), at(120L + i)), "TestProfile"),
				"a slot sitting Ready is not finishing over and over",
			)
		}
	}

	@Test
	fun `a requeued slot can finish again`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		watcher.offer(beliefs(memory, widget(running(1, "Refined Titanium", "1h"))), "TestProfile")
		watcher.offer(beliefs(memory, widget(ready(1, "Refined Titanium")), at(60)), "TestProfile")
		// Collected, and something else queued in the same slot.
		watcher.offer(beliefs(memory, widget(running(1, "Refined Diamond", "3h")), at(120)), "TestProfile")

		val fired = watcher.offer(beliefs(memory, widget(ready(1, "Refined Diamond")), at(180)), "TestProfile")
		assertEquals(listOf(ForgeCompletion(1, "Refined Diamond")), fired)
	}

	@Test
	fun `several slots finishing at once are all reported`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		watcher.offer(
			beliefs(memory, widget(running(1, "Refined Titanium", "1h"), running(2, "Golden Plate", "1h"))),
			"TestProfile",
		)
		val fired = watcher.offer(
			beliefs(memory, widget(ready(1, "Refined Titanium"), ready(2, "Golden Plate")), at(60)),
			"TestProfile",
		)

		assertEquals(
			listOf(ForgeCompletion(1, "Refined Titanium"), ForgeCompletion(2, "Golden Plate")),
			fired,
		)
	}

	// ------------------------------------- discovery is not completion

	@Test
	fun `a slot already Ready at login does not fire`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		// The very first reading of the session, with the slot already Ready.
		val fired = watcher.offer(beliefs(memory, widget(ready(1, "Refined Titanium"))), "TestProfile")

		assertEquals(
			emptyList<ForgeCompletion>(), fired,
			"we never saw it running, so we never saw it finish",
		)
	}

	@Test
	fun `an already-Ready slot found at login stays quiet on every later reading`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		watcher.offer(beliefs(memory, widget(ready(1, "Refined Titanium"))), "TestProfile")

		repeat(5) { i ->
			assertEquals(
				emptyList<ForgeCompletion>(),
				watcher.offer(beliefs(memory, widget(ready(1, "Refined Titanium")), at(60L + i)), "TestProfile"),
			)
		}
	}

	@Test
	fun `a discovered Ready slot still fires normally after it is requeued`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		watcher.offer(beliefs(memory, widget(ready(1, "Refined Titanium"))), "TestProfile")
		watcher.offer(beliefs(memory, widget(running(1, "Golden Plate", "2h")), at(60)), "TestProfile")

		val fired = watcher.offer(beliefs(memory, widget(ready(1, "Golden Plate")), at(120)), "TestProfile")
		assertEquals(
			listOf(ForgeCompletion(1, "Golden Plate")), fired,
			"a discovery at login must not poison the slot for the rest of the session",
		)
	}

	@Test
	fun `reconnecting makes everything a discovery again`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		watcher.offer(beliefs(memory, widget(running(1, "Refined Titanium", "1h"))), "TestProfile")
		watcher.reset()

		assertEquals(
			emptyList<ForgeCompletion>(),
			watcher.offer(beliefs(memory, widget(ready(1, "Refined Titanium")), at(60)), "TestProfile"),
			"we were not watching while away, so we cannot claim to have seen it finish",
		)
	}

	@Test
	fun `switching profile does not fire for the other forge's slots`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		watcher.offer(memory.believe(widget(running(1, "Refined Titanium", "1h")), "Pear", t0), "Pear")
		val fired = watcher.offer(
			memory.believe(widget(ready(1, "Refined Titanium")), "Mango", at(60)), "Mango",
		)

		assertEquals(emptyList<ForgeCompletion>(), fired, "a different profile is a different forge")
	}

	@Test
	fun `nothing is recorded when the profile cannot be read`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		watcher.offer(memory.believe(widget(running(1, "Refined Titanium", "1h")), null, t0), null)
		assertEquals(0, watcher.trackedSlots)
	}

	// -------------------------------- a guess must never fire the alert

	@Test
	fun `an approximate belief crossing its predicted finish does NOT fire`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		// Seen running in the widget, so there is a baseline.
		watcher.offer(beliefs(memory, widget(running(1, "Refined Titanium", "1h"))), "TestProfile")

		// The Forge screen is read, giving an exact finish time.
		memory.recordGuiObservation(gui("1h"), "TestProfile", t0)

		// Slot 1 then truncates out of the widget, and the finish time passes.
		// The belief says READY, but nothing can confirm it.
		val later = memory.believe(widget(running(2, "Golden Plate", "5h")), "TestProfile", at(3700))
		val slot1 = later.single { it.slot == 1 }
		assertEquals(ForgeSlotState.READY, slot1.state)
		assertEquals(Confidence.APPROXIMATE, slot1.confidence)
		assertTrue(!slot1.observed, "nothing is looking at this slot")

		assertEquals(
			emptyList<ForgeCompletion>(), watcher.offer(later, "TestProfile"),
			"guessing at a chime is worse than silence",
		)
	}

	@Test
	fun `the deferred alert fires once the slot is confirmed Ready`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		watcher.offer(beliefs(memory, widget(running(1, "Refined Titanium", "1h"))), "TestProfile")
		memory.recordGuiObservation(gui("1h"), "TestProfile", t0)

		// Out of sight past its finish: no alert, and crucially no baseline change.
		watcher.offer(memory.believe(widget(running(2, "Golden Plate", "5h")), "TestProfile", at(3700)), "TestProfile")

		// Back in view, and the widget confirms it.
		val fired = watcher.offer(
			memory.believe(widget(ready(1, "Refined Titanium")), "TestProfile", at(3800)), "TestProfile",
		)

		assertEquals(
			listOf(ForgeCompletion(1, "Refined Titanium")), fired,
			"a guess defers the alert; it must never cancel it",
		)
	}

	@Test
	fun `a remembered Ready slot does not fire`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		// Seen running, then seen Ready - which fires - then truncated. The
		// remembered READY that follows must not fire a second time.
		watcher.offer(beliefs(memory, widget(running(1, "Refined Titanium", "1h"))), "TestProfile")
		watcher.offer(beliefs(memory, widget(ready(1, "Refined Titanium")), at(60)), "TestProfile")

		val remembered = memory.believe(widget(running(2, "Golden Plate", "5h")), "TestProfile", at(120))
		assertTrue(!remembered.single { it.slot == 1 }.observed)
		assertEquals(emptyList<ForgeCompletion>(), watcher.offer(remembered, "TestProfile"))
	}

	@Test
	fun `a GUI reading confirmed by the widget does fire`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		watcher.offer(beliefs(memory, widget(running(1, "Refined Titanium", "1h"))), "TestProfile")
		memory.recordGuiObservation(gui("1h"), "TestProfile", t0)

		val fired = watcher.offer(
			memory.believe(widget(ready(1, "Refined Titanium")), "TestProfile", at(3700)), "TestProfile",
		)
		assertEquals(listOf(ForgeCompletion(1, "Refined Titanium")), fired)
	}

	@Test
	fun `an unreadable row is not treated as a completion`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		watcher.offer(beliefs(memory, widget(running(1, "Refined Titanium", "1h"))), "TestProfile")
		val fired = watcher.offer(
			beliefs(memory, widget(" 1) §6Refined Titanium§7: §bsoonish"), at(60)), "TestProfile",
		)

		assertEquals(emptyList<ForgeCompletion>(), fired, "UNKNOWN is not READY")
	}

	@Test
	fun `an emptied slot is not a completion`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		watcher.offer(beliefs(memory, widget(running(1, "Refined Titanium", "1h"))), "TestProfile")
		val fired = watcher.offer(beliefs(memory, widget(" 1) §7EMPTY"), at(60)), "TestProfile")

		assertEquals(emptyList<ForgeCompletion>(), fired)
	}

	// -------------------------------------------------------- the config

	@Test
	fun `the alert is on by default and the sound is off`() {
		val defaults = ForgeCastConfig()
		assertTrue(defaults.completionAlertEnabled, "the alert draws nothing and speaks rarely")
		assertTrue(!defaults.completionSoundEnabled, "a sound nobody asked for is an intrusion")
	}

	@Test
	fun `both alert settings survive a save and load`() {
		val config = ForgeCastConfig(completionAlertEnabled = false, completionSoundEnabled = true)
		val restored = ConfigCodec.decode(ConfigCodec.encode(config))

		assertEquals(false, restored.completionAlertEnabled)
		assertEquals(true, restored.completionSoundEnabled)
	}

	@Test
	fun `a config file written before this feature keeps the defaults`() {
		val old = "version=1\nhud.enabled=true\nadvice.enabled=false\n"
		val restored = ConfigCodec.decode(old)

		assertTrue(restored.completionAlertEnabled)
		assertTrue(!restored.completionSoundEnabled)
		assertEquals(true, restored.hudEnabled, "the rest of the file still reads")
	}
}
