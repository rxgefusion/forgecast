package dev.fraust.forgecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Slice 2: the completion alert, in two kinds.
 *
 * CONFIRMED is an observed transition: seen running, then seen Ready.
 *
 * FORECAST is a GUI-derived finish time passing while nothing can see the slot.
 * That is not a guess - the time came from the Forge screen and is exact to the
 * second, and collecting the item requires opening that screen again, which
 * would have refreshed the reading. It is announced as expectation, not fact.
 *
 * A floored WIDGET countdown reaching its end is a real guess, and never fires.
 *
 * Nothing is believed on a single reading. A state must hold for three
 * consecutive readings, because a glitched "Ready!" would otherwise announce
 * wrongly AND record READY as the baseline, so the real completion would never
 * be announced at all.
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

	/**
	 * Feeds the same reading until it settles, returning what the LAST offer
	 * produced.
	 *
	 * Readings arrive once a second in play, so this is what a few seconds of
	 * standing still looks like. Anything that fires does so on the third.
	 */
	private fun settle(
		watcher: CompletionWatcher,
		memory: ForgeMemory,
		snapshot: ForgeSnapshot,
		now: Instant = t0,
		profile: String? = "TestProfile",
		times: Int = CompletionWatcher.DEFAULT_REQUIRED,
	): List<ForgeCompletion> {
		var last = emptyList<ForgeCompletion>()
		repeat(times) { last = watcher.offer(memory.believe(snapshot, profile, now), profile) }
		return last
	}

	// ---------------------------------------------------- confirmed alerts

	@Test
	fun `a slot seen running and then Ready fires a CONFIRMED alert once`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))
		val fired = settle(watcher, memory, widget(ready(1, "Refined Titanium")), at(60))

		assertEquals(listOf(ForgeCompletion(1, "Refined Titanium", AlertKind.CONFIRMED)), fired)
	}

	@Test
	fun `it does not fire again on later readings of the same Ready slot`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))
		settle(watcher, memory, widget(ready(1, "Refined Titanium")), at(60))

		repeat(5) { i ->
			assertEquals(
				emptyList<ForgeCompletion>(),
				settle(watcher, memory, widget(ready(1, "Refined Titanium")), at(120L + i)),
				"a slot sitting Ready is not finishing over and over",
			)
		}
	}

	@Test
	fun `a requeued slot can finish again`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))
		settle(watcher, memory, widget(ready(1, "Refined Titanium")), at(60))
		settle(watcher, memory, widget(running(1, "Refined Diamond", "3h")), at(120))

		val fired = settle(watcher, memory, widget(ready(1, "Refined Diamond")), at(180))
		assertEquals(listOf(ForgeCompletion(1, "Refined Diamond", AlertKind.CONFIRMED)), fired)
	}

	@Test
	fun `several slots finishing at once are all reported`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(
			watcher, memory,
			widget(running(1, "Refined Titanium", "1h"), running(2, "Golden Plate", "1h")),
		)
		val fired = settle(
			watcher, memory, widget(ready(1, "Refined Titanium"), ready(2, "Golden Plate")), at(60),
		)

		assertEquals(
			listOf(
				ForgeCompletion(1, "Refined Titanium", AlertKind.CONFIRMED),
				ForgeCompletion(2, "Golden Plate", AlertKind.CONFIRMED),
			),
			fired,
		)
	}

	// ----------------------------------------------------- forecast alerts

	@Test
	fun `a GUI finish time passing out of sight fires a FORECAST alert`() {
		// INVERTED, deliberately. This case previously asserted that nothing
		// fired, on the grounds that an unconfirmed belief is a guess. It is not:
		// the finish time came from the Forge screen and is exact, and collecting
		// the item requires reopening that screen, which would have refreshed it.
		// Staying silent here silenced the one thing the mod knows that nothing
		// reading only the tab list can know.
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))
		memory.recordGuiObservation(gui("1h"), "TestProfile", t0)

		// Slot 1 truncates out of the widget, and the finish time passes.
		val fired = settle(watcher, memory, widget(running(2, "Golden Plate", "5h")), at(3700))

		assertEquals(listOf(ForgeCompletion(1, "Refined Titanium", AlertKind.FORECAST)), fired)
	}

	@Test
	fun `a forecast is not re-announced when the slot is later confirmed Ready`() {
		// INVERTED, deliberately. This previously asserted the alert fired HERE,
		// because the forecast was forbidden from firing earlier. Now the
		// forecast fires first and this sighting must stay silent.
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))
		memory.recordGuiObservation(gui("1h"), "TestProfile", t0)

		val forecast = settle(watcher, memory, widget(running(2, "Golden Plate", "5h")), at(3700))
		assertEquals(AlertKind.FORECAST, forecast.single().kind)

		val confirmed = settle(watcher, memory, widget(ready(1, "Refined Titanium")), at(3800))
		assertEquals(
			emptyList<ForgeCompletion>(), confirmed,
			"one completion, one announcement, whichever kind got there first",
		)
	}

	@Test
	fun `a widget-derived belief passing its finish never fires`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		// Only the widget has ever seen this slot, and its "1h" is FLOORED - the
		// real remaining time is anywhere up to 2h. Its expiry is a real guess.
		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))

		val fired = settle(watcher, memory, widget(running(2, "Golden Plate", "5h")), at(3700))
		assertEquals(
			emptyList<ForgeCompletion>(), fired,
			"a floored number reaching zero says nothing about the real finish time",
		)
	}

	@Test
	fun `a confirmed sighting still fires when no forecast preceded it`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "8h")))
		memory.recordGuiObservation(gui("8h"), "TestProfile", t0)

		// The widget sees it Ready earlier than the GUI predicted, so no forecast
		// ever fired. The confirmed alert must still arrive.
		val fired = settle(watcher, memory, widget(ready(1, "Refined Titanium")), at(60))
		assertEquals(listOf(ForgeCompletion(1, "Refined Titanium", AlertKind.CONFIRMED)), fired)
	}

	@Test
	fun `a slot forecast ready can fire again after it is requeued`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))
		memory.recordGuiObservation(gui("1h"), "TestProfile", t0)
		settle(watcher, memory, widget(running(2, "Golden Plate", "5h")), at(3700))

		// Collected and requeued, and this time watched all the way through.
		settle(watcher, memory, widget(running(1, "Refined Diamond", "2h")), at(3800))
		val fired = settle(watcher, memory, widget(ready(1, "Refined Diamond")), at(3900))

		assertEquals(listOf(ForgeCompletion(1, "Refined Diamond", AlertKind.CONFIRMED)), fired)
	}

	// ------------------------------------------ discovery is not completion

	@Test
	fun `a slot already Ready at login does not fire`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		val fired = settle(watcher, memory, widget(ready(1, "Refined Titanium")))

		assertEquals(
			emptyList<ForgeCompletion>(), fired,
			"we never saw it running, so we never saw it finish",
		)
	}

	@Test
	fun `an already-Ready slot found at login stays quiet on every later reading`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(ready(1, "Refined Titanium")))

		repeat(5) { i ->
			assertEquals(
				emptyList<ForgeCompletion>(),
				settle(watcher, memory, widget(ready(1, "Refined Titanium")), at(60L + i)),
			)
		}
	}

	@Test
	fun `a slot already Ready in the GUI at login does not fire either`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		// Opening the Forge for the first time and finding a slot done is still
		// a discovery, however exact the reading is.
		memory.recordGuiObservation(
			GuiForgeParser.parse(
				listOf(guiRow(10, "§6Refined Titanium", "§7Currently making: §6Refined Titanium", "§7Time Remaining: §aCompleted!")),
				t0,
			),
			"TestProfile", t0,
		)
		val fired = settle(watcher, memory, widget(running(2, "Golden Plate", "5h")))

		assertEquals(emptyList<ForgeCompletion>(), fired)
	}

	@Test
	fun `a discovered Ready slot still fires normally after it is requeued`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(ready(1, "Refined Titanium")))
		settle(watcher, memory, widget(running(1, "Golden Plate", "2h")), at(60))

		val fired = settle(watcher, memory, widget(ready(1, "Golden Plate")), at(120))
		assertEquals(
			listOf(ForgeCompletion(1, "Golden Plate", AlertKind.CONFIRMED)), fired,
			"a discovery at login must not poison the slot for the rest of the session",
		)
	}

	@Test
	fun `reconnecting makes everything a discovery again`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))
		watcher.reset()

		assertEquals(
			emptyList<ForgeCompletion>(),
			settle(watcher, memory, widget(ready(1, "Refined Titanium")), at(60)),
			"we were not watching while away, so we cannot claim to have seen it finish",
		)
	}

	@Test
	fun `switching profile does not fire for the other forge's slots`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")), profile = "Pear")
		val fired = settle(
			watcher, memory, widget(ready(1, "Refined Titanium")), at(60), profile = "Mango",
		)

		assertEquals(emptyList<ForgeCompletion>(), fired, "a different profile is a different forge")
	}

	@Test
	fun `nothing is recorded when the profile cannot be read`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")), profile = null)
		assertEquals(0, watcher.trackedSlots)
	}

	// ------------------------------------------------------ the stabiliser

	@Test
	fun `a single glitched Ready reading does not fire`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))

		val fired = watcher.offer(
			memory.believe(widget(ready(1, "Refined Titanium")), "TestProfile", at(60)), "TestProfile",
		)
		assertEquals(emptyList<ForgeCompletion>(), fired, "one reading is not evidence")
	}

	@Test
	fun `a glitched Ready reading does not poison the baseline`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))

		// One bad reading, then the slot is seen running again as normal.
		watcher.offer(memory.believe(widget(ready(1, "Refined Titanium")), "TestProfile", at(60)), "TestProfile")
		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")), at(70))

		// The real completion must still be announced.
		val fired = settle(watcher, memory, widget(ready(1, "Refined Titanium")), at(3700))
		assertEquals(
			listOf(ForgeCompletion(1, "Refined Titanium", AlertKind.CONFIRMED)), fired,
			"the worse half of the glitch bug: a false Ready must not swallow the real one",
		)
	}

	@Test
	fun `a state must hold for exactly three readings, not two`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))

		val readySnapshot = widget(ready(1, "Refined Titanium"))
		repeat(2) {
			assertEquals(
				emptyList<ForgeCompletion>(),
				watcher.offer(memory.believe(readySnapshot, "TestProfile", at(60)), "TestProfile"),
			)
		}
		assertEquals(
			listOf(ForgeCompletion(1, "Refined Titanium", AlertKind.CONFIRMED)),
			watcher.offer(memory.believe(readySnapshot, "TestProfile", at(60)), "TestProfile"),
		)
	}

	@Test
	fun `an interrupted run has to start over`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))

		val readySnapshot = widget(ready(1, "Refined Titanium"))
		watcher.offer(memory.believe(readySnapshot, "TestProfile", at(60)), "TestProfile")
		watcher.offer(memory.believe(readySnapshot, "TestProfile", at(61)), "TestProfile")
		// Flicker back to running: the streak is broken.
		watcher.offer(memory.believe(widget(running(1, "Refined Titanium", "1h")), "TestProfile", at(62)), "TestProfile")

		assertEquals(
			emptyList<ForgeCompletion>(),
			watcher.offer(memory.believe(readySnapshot, "TestProfile", at(63)), "TestProfile"),
			"two before the flicker plus one after is not three in a row",
		)
	}

	@Test
	fun `a slot going out of sight does not reset a settled state`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))
		// Slot 1 truncates. With no GUI belief there is no evidence either way,
		// so the baseline must simply be left alone.
		settle(watcher, memory, widget(running(2, "Golden Plate", "5h")), at(60))

		val fired = settle(watcher, memory, widget(ready(1, "Refined Titanium")), at(120))
		assertEquals(
			listOf(ForgeCompletion(1, "Refined Titanium", AlertKind.CONFIRMED)), fired,
			"silence is not evidence, and must not erase what was known",
		)
	}

	// ----------------------------------------------- states that never fire

	@Test
	fun `an unreadable row is not treated as a completion`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))
		val fired = settle(watcher, memory, widget(" 1) §6Refined Titanium§7: §bsoonish"), at(60))

		assertEquals(emptyList<ForgeCompletion>(), fired, "UNKNOWN is not READY")
	}

	@Test
	fun `an emptied slot is not a completion`() {
		val memory = ForgeMemory()
		val watcher = CompletionWatcher()

		settle(watcher, memory, widget(running(1, "Refined Titanium", "1h")))
		val fired = settle(watcher, memory, widget(" 1) §7EMPTY"), at(60))

		assertEquals(emptyList<ForgeCompletion>(), fired)
	}

	// ------------------------------------------------------------- config

	@Test
	fun `both alerts are on by default and the sound is off`() {
		val defaults = ForgeCastConfig()
		assertTrue(defaults.completionAlertEnabled, "the alert draws nothing and speaks rarely")
		assertTrue(defaults.forecastAlertEnabled, "the one thing a tab-list-only mod cannot do")
		assertTrue(!defaults.completionSoundEnabled, "a sound nobody asked for is an intrusion")
	}

	@Test
	fun `all three alert settings survive a save and load`() {
		val config = ForgeCastConfig(
			completionAlertEnabled = false,
			forecastAlertEnabled = false,
			completionSoundEnabled = true,
		)
		val restored = ConfigCodec.decode(ConfigCodec.encode(config))

		assertEquals(false, restored.completionAlertEnabled)
		assertEquals(false, restored.forecastAlertEnabled)
		assertEquals(true, restored.completionSoundEnabled)
	}

	@Test
	fun `a config file written before this feature keeps the defaults`() {
		val old = "version=1\nhud.enabled=true\nadvice.enabled=false\n"
		val restored = ConfigCodec.decode(old)

		assertTrue(restored.completionAlertEnabled)
		assertTrue(restored.forecastAlertEnabled)
		assertTrue(!restored.completionSoundEnabled)
		assertEquals(true, restored.hudEnabled, "the rest of the file still reads")
	}
}
