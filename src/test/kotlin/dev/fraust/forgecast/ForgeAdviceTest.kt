package dev.fraust.forgecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [ForgeAdvice] and [AdviceThrottle].
 *
 * The three "is the data complete" cases are checked against real captures
 * where one exists, and against built rows where the situation has never been
 * captured.
 */
class ForgeAdviceTest {
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
	private fun classify(name: String): ForgeDataCase {
		val rows = loadFixture(name)
		return ForgeAdvice.classify(rows, ForgeParser.parse(rows))
	}
	// --------------------------------------------------- the three cases
	@Test
	fun `a full Dwarven Mines capture is complete and says nothing`() {
		val case = classify("dump-20260902-021808-688.txt")
		assertEquals(ForgeDataCase.COMPLETE, case)
		assertNull(ForgeAdvice.message(case, 7), "a complete reading must be silent")
	}
	@Test
	fun `a wrapped capture is complete too`() {
		// Wrapping on, section spans B into C, all seven slots present.
		assertEquals(ForgeDataCase.COMPLETE, classify("dump-20260902-044129-623.txt"))
	}
	@Test
	fun `the truncated Private Island capture is reported with its count`() {
		val rows = loadFixture("dump-20260902-022501-663.txt")
		val snapshot = ForgeParser.parse(rows)
		val case = ForgeAdvice.classify(rows, snapshot)
		assertEquals(ForgeDataCase.TRUNCATED, case)
		assertEquals(5, snapshot.renderedSlots)
		val message = ForgeAdvice.message(case, snapshot.renderedSlots)
		assertNotNull(message)
		assertTrue(message!!.any { it.contains("5 of 7") }, "the count must be stated: $message")
		assertTrue(
			message.any { it.contains(ForgeAdvice.PATH_WRAPPING) },
			"the fix is Wrapping, which the experiment proved: $message",
		)
	}
	@Test
	fun `no section with room to spare reads as the widget being off`() {
		// Two short columns: plenty of unused rows, and no Forges anywhere.
		val rows = listOf(
			TabRow("!C-a", "               §3§lInfo"),
			TabRow("!C-b", "§b§lArea: §7Hub"),
			TabRow("!C-c", ""),
		)
		val case = ForgeAdvice.classify(rows, ForgeParser.parse(rows))
		assertEquals(ForgeDataCase.WIDGET_OFF, case)
		val message = ForgeAdvice.message(case, 0)
		assertTrue(
			message!!.any { it.contains(ForgeAdvice.PATH_ENABLE) },
			"telling someone to enable an already-on widget is worse than silence",
		)
	}
	@Test
	fun `no section with every column full reads as pushed out`() {
		// One column filled to row t with content and no Forges section.
		val rows = ('a'..'t').map { row -> TabRow("!C-$row", "§7filler $row") }
		val case = ForgeAdvice.classify(rows, ForgeParser.parse(rows))
		assertEquals(ForgeDataCase.PUSHED_OUT, case)
		assertEquals(0, ForgeAdvice.spareRows(rows))
		val message = ForgeAdvice.message(case, 0)
		assertTrue(message!!.any { it.contains("pushed out") }, "$message")
	}
	@Test
	fun `trailing blanks count as spare but a blank in the middle does not`() {
		val trailing = listOf(
			TabRow("!C-a", "§3§lInfo"),
			TabRow("!C-b", "content"),
			TabRow("!C-c", ""),
			TabRow("!C-d", ""),
		)
		// 2 trailing blanks, plus the 16 rows the server never sent.
		assertEquals(2 + 16, ForgeAdvice.spareRows(trailing))
		val separator = listOf(
			TabRow("!C-a", "§3§lInfo"),
			TabRow("!C-b", ""),
			TabRow("!C-c", "content"),
		)
		// The middle blank is a separator, not free space: only the 17 unsent.
		assertEquals(17, ForgeAdvice.spareRows(separator))
	}
	// ------------------------------------------------------- throttling
	@Test
	fun `a case is announced once and then stays quiet`() {
		val throttle = AdviceThrottle()
		assertEquals(ForgeDataCase.TRUNCATED, throttle.announce(ForgeDataCase.TRUNCATED))
		assertNull(throttle.announce(ForgeDataCase.TRUNCATED), "no repeat")
		assertNull(throttle.announce(ForgeDataCase.TRUNCATED), "still no repeat")
	}
	@Test
	fun `fixing the problem and breaking it again re-announces`() {
		val throttle = AdviceThrottle()
		assertEquals(ForgeDataCase.TRUNCATED, throttle.announce(ForgeDataCase.TRUNCATED))
		// Player turns Wrapping on. Complete is recorded but never spoken.
		assertNull(throttle.announce(ForgeDataCase.COMPLETE))
		// It breaks again somewhere else: worth saying again.
		assertEquals(ForgeDataCase.TRUNCATED, throttle.announce(ForgeDataCase.TRUNCATED))
	}
	@Test
	fun `a different problem is announced even without a good state between`() {
		val throttle = AdviceThrottle()
		assertEquals(ForgeDataCase.TRUNCATED, throttle.announce(ForgeDataCase.TRUNCATED))
		assertEquals(ForgeDataCase.WIDGET_OFF, throttle.announce(ForgeDataCase.WIDGET_OFF))
	}
	@Test
	fun `complete is never announced on its own`() {
		val throttle = AdviceThrottle()
		assertNull(throttle.announce(ForgeDataCase.COMPLETE), "nothing to say when all is well")
	}
	@Test
	fun `reset makes the next case announce again`() {
		val throttle = AdviceThrottle()
		assertEquals(ForgeDataCase.TRUNCATED, throttle.announce(ForgeDataCase.TRUNCATED))
		assertNull(throttle.announce(ForgeDataCase.TRUNCATED))
		// Left SkyBlock and came back.
		throttle.reset()
		assertEquals(ForgeDataCase.TRUNCATED, throttle.announce(ForgeDataCase.TRUNCATED))
	}
	// ------------------------------------------------ SkyBlock detection
	@Test
	fun `a Profile row alone confirms SkyBlock`() {
		val rows = listOf(TabRow("!B-g", "§e§lProfile: §aTestProfile"))
		assertTrue(SkyBlockDetector.isSkyBlock(rows, null))
	}
	@Test
	fun `the scoreboard title alone confirms SkyBlock when no widget rows exist`() {
		// The case the whole change exists for: widgets entirely off, so there is
		// no Profile row to read, but the sidebar still says SKYBLOCK.
		val rows = listOf(TabRow("Player01", "<null>"))
		assertTrue(SkyBlockDetector.isSkyBlock(rows, "SKYBLOCK"))
	}
	@Test
	fun `the scoreboard title is matched loosely`() {
		val rows = emptyList<TabRow>()
		assertTrue(SkyBlockDetector.isSkyBlock(rows, "SKYBLOCK"))
		assertTrue(SkyBlockDetector.isSkyBlock(rows, "SkyBlock"))
		assertTrue(SkyBlockDetector.isSkyBlock(rows, "SKY BLOCK"))
	}
	@Test
	fun `no signal at all means stay silent`() {
		// Conservative on purpose: a missed warning costs one message, a false
		// positive spams every lobby.
		val rows = listOf(TabRow("Player01", "<null>"))
		assertFalse(SkyBlockDetector.isSkyBlock(rows, null))
		assertFalse(SkyBlockDetector.isSkyBlock(rows, "BED WARS"))
		assertFalse(SkyBlockDetector.isSkyBlock(rows, ""))
	}
	// ------------------------------------------- the once-per-session case
	@Test
	fun `no widget rows at all is its own case, not pushed out`() {
		val rows = listOf(TabRow("Player01", "<null>"), TabRow("Player02", "<null>"))
		assertEquals(
			ForgeDataCase.WIDGETS_OFF_ENTIRELY,
			ForgeAdvice.classify(rows, ForgeParser.parse(rows)),
		)
	}
	@Test
	fun `widgets-off is announced once per session and never again`() {
		val throttle = AdviceThrottle()
		assertEquals(ForgeDataCase.WIDGETS_OFF_ENTIRELY, throttle.announce(ForgeDataCase.WIDGETS_OFF_ENTIRELY))
		assertNull(throttle.announce(ForgeDataCase.WIDGETS_OFF_ENTIRELY), "no repeat")
	}
	@Test
	fun `widgets-off does not come back after visiting a lobby`() {
		val throttle = AdviceThrottle()
		assertEquals(ForgeDataCase.WIDGETS_OFF_ENTIRELY, throttle.announce(ForgeDataCase.WIDGETS_OFF_ENTIRELY))
		// Walked through a lobby and came back.
		throttle.reset()
		assertNull(
			throttle.announce(ForgeDataCase.WIDGETS_OFF_ENTIRELY),
			"once per SESSION has to survive a reset",
		)
	}
	@Test
	fun `a one-shot case does not block other warnings afterwards`() {
		val throttle = AdviceThrottle()
		throttle.announce(ForgeDataCase.WIDGETS_OFF_ENTIRELY)
		assertEquals(ForgeDataCase.TRUNCATED, throttle.announce(ForgeDataCase.TRUNCATED))
	}
	// ------------------------------------------------- settings screen text
	@Test
	fun `the screen summary names the count and the fix`() {
		val summary = ForgeAdvice.summary(ForgeDataCase.TRUNCATED, 4)
		assertTrue(summary!!.contains("4 of 7"), summary)
		assertEquals(ForgeAdvice.PATH_WRAPPING, ForgeAdvice.fixPath(ForgeDataCase.TRUNCATED))
	}
	@Test
	fun `a complete reading has nothing to show in the screen`() {
		assertNull(ForgeAdvice.summary(ForgeDataCase.COMPLETE, 7))
		assertNull(ForgeAdvice.fixPath(ForgeDataCase.COMPLETE))
	}
	@Test
	fun `every problem case has both a summary and a fix path`() {
		for (case in ForgeDataCase.entries) {
			if (case == ForgeDataCase.COMPLETE) continue
			assertNotNull(ForgeAdvice.summary(case, 3), "no summary for $case")
			assertNotNull(ForgeAdvice.fixPath(case), "no fix path for $case")
		}
	}
}
