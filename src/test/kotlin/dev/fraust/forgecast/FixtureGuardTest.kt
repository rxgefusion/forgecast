package dev.fraust.forgecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Fails the build if any committed fixture carries real player data.
 *
 * The fixtures leaked once - 47 usernames and a bank balance reached a public
 * repo. Scrubbing them was not the safeguard; remembering to scrub was, and
 * that is the part that fails. This makes forgetting impossible.
 *
 * The self-tests below matter as much as the sweep: they feed deliberately bad
 * content in and check it is caught, because a guard that has never failed is
 * not a guard.
 */
class FixtureGuardTest {

	private fun resourceDir(): File {
		// Gradle runs tests from the project directory.
		val direct = File("src/test/resources")
		if (direct.isDirectory) return direct
		// Fall back to the copy on the classpath if that ever stops being true.
		val onClasspath = javaClass.getResource("/dumps")?.toURI()?.let { File(it).parentFile }
		return onClasspath ?: error("Cannot locate src/test/resources - the guard cannot run")
	}

	private fun fixtures(): List<File> =
		resourceDir().walkTopDown().filter { it.isFile }.sortedBy { it.name }.toList()

	// --------------------------------------------------- the actual sweep

	@Test
	fun `every committed fixture is anonymised`() {
		val files = fixtures()
		assertTrue(files.isNotEmpty(), "found no fixtures to check - the guard would pass vacuously")

		val violations = files.flatMap { FixtureGuard.check(it.name, it.readText(Charsets.UTF_8)) }

		assertTrue(
			violations.isEmpty(),
			buildString {
				append("Committed fixtures contain real data. ")
				append("This folder is PUBLIC on GitHub.\n")
				append("${violations.size} problem(s) across ${violations.map { it.file }.distinct().size} file(s):")
				violations.take(25).forEach { append(it) }
				if (violations.size > 25) append("\n  ... and ${violations.size - 25} more")
				append("\n\nNothing is committed until these are fixed.")
			},
		)
	}

	@TestFactory
	fun `each fixture is checked individually`(): List<DynamicTest> =
		fixtures().map { file ->
			DynamicTest.dynamicTest(file.name) {
				val violations = FixtureGuard.check(file.name, file.readText(Charsets.UTF_8))
				assertTrue(violations.isEmpty(), "${file.name} carries real data:$violations")
			}
		}

	// ------------------------------------------- proving the guard can fail

	private fun tabDump(vararg bodyLines: String): String =
		"ForgeCast tab list dump\nentries\t1\ncolumns\tindex\torder\tprofile\traw\n--\n" +
			bodyLines.joinToString("\n")

	@Test
	fun `a real player name in the profile column is caught`() {
		val violations = FixtureGuard.check(
			"dump-test.txt",
			tabDump("1\t0\tSven_067\t<null>"),
		)
		assertEquals(1, violations.size, "$violations")
		assertEquals("Sven_067", violations[0].found)
		assertTrue(violations[0].fix.contains("Player<number>"), violations[0].fix)
		assertEquals(5, violations[0].line, "the line number must point at the offending row")
	}

	@Test
	fun `a real player name inside widget text is caught`() {
		val violations = FixtureGuard.check(
			"dump-test.txt",
			tabDump("1\t0\t!A-b\t§8[§c450§8] §bXkoudai §6ᛃ"),
		)
		assertEquals(1, violations.size, "$violations")
		assertEquals("Xkoudai", violations[0].found)
	}

	@Test
	fun `an anonymised name in the same place passes`() {
		val violations = FixtureGuard.check(
			"dump-test.txt",
			tabDump("1\t0\t!A-b\t§8[§c450§8] §bPlayer06 §6ᛃ", "2\t0\tPlayer06\t<null>"),
		)
		assertTrue(violations.isEmpty(), "$violations")
	}

	@Test
	fun `a real bank balance is caught`() {
		val violations = FixtureGuard.check(
			"dump-test.txt",
			tabDump("1\t0\t!B-i\t Bank: §61.9B§7 / §6426.4M"),
		)
		assertEquals(1, violations.size, "$violations")
		assertTrue(violations[0].problem.contains("bank balance"), violations[0].problem)
	}

	@Test
	fun `real gems, interest, level, profile and server are all caught`() {
		val cases = mapOf(
			"1\t0\t!B-d\t Gems: §a-223" to "gem count",
			"1\t0\t!B-j\t Interest: §e12 Hours§6 (1.1M)" to "interest",
			"1\t0\t!B-h\t SB Level: §8[§c450§8] §b13§3/§b100 XP" to "SkyBlock level",
			"1\t0\t!B-g\t§e§lProfile: §aPear" to "profile name",
			"1\t0\t!B-c\t Server: §8mini26CS" to "server instance",
		)
		for ((line, expected) in cases) {
			val violations = FixtureGuard.check("dump-test.txt", tabDump(line))
			assertEquals(1, violations.size, "not caught: $line")
			assertTrue(
				violations[0].problem.contains(expected),
				"wrong diagnosis for $line: ${violations[0].problem}",
			)
		}
	}

	@Test
	fun `a UUID anywhere is caught`() {
		// Assembled from parts rather than written out, so this line is not
		// itself a UUID. SecretsGuardTest scans every source file for key-shaped
		// strings and correctly flagged the literal that used to be here - the
		// two guards caught each other, which is the point of having both.
		val uuid = listOf("04049c90", "d3e9", "4621", "9caf", "0000aaa46767").joinToString("-")
		val violations = FixtureGuard.check("dump-test.txt", tabDump("1\t0\t!A-b\tprofile $uuid"))
		assertTrue(violations.any { it.problem.contains("UUID") }, "$violations")
	}

	@Test
	fun `inventory slots in a GUI fixture are caught`() {
		val gui = "ForgeCast GUI dump\nscreen\tThe Forge\n--\n" +
			"slot\t10\t1\t§6Refined Titanium\n" +
			"slot\t61\t1\t§9Hyperion\n" +
			"tip\t61\t§7Kills: §a12,345\n"
		val violations = FixtureGuard.check("gui-test.txt", gui)

		assertEquals(2, violations.size, "both the slot and its tip must be caught: $violations")
		assertTrue(violations.all { it.problem.contains("outside the forge slots") }, "$violations")
		assertTrue(violations[0].fix.contains("10-16"), violations[0].fix)
	}

	@Test
	fun `a correctly trimmed GUI fixture passes`() {
		val gui = "ForgeCast GUI dump\nscreen\tThe Forge\n--\n" +
			"slot\t10\t1\t§6Refined Titanium\n" +
			"tip\t16\t§aSlot #7\n"
		assertTrue(FixtureGuard.check("gui-test.txt", gui).isEmpty())
	}

	@Test
	fun `an unrecognised fixture kind is reported rather than waved through`() {
		// Silence on an unknown shape would be a hole: the guard would pass
		// simply because it did not know how to look.
		val violations = FixtureGuard.check("something-new.txt", "anything at all")
		assertEquals(1, violations.size)
		assertTrue(violations[0].problem.contains("unrecognised"), violations[0].problem)
	}
}
