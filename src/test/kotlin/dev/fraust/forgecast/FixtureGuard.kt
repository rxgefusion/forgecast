package dev.fraust.forgecast

/**
 * Checks that committed fixtures carry no real player data.
 *
 * This exists because the fixtures HAVE leaked once: 47 real usernames and a
 * bank balance reached a public repo and had to be scrubbed from history. The
 * scrub is not the safeguard - remembering to scrub is, and memory is exactly
 * what fails. This turns it into a build failure.
 *
 * Pure string checking so it can be unit tested with deliberately bad input:
 * a guard that has never failed is not a guard.
 */
object FixtureGuard {

	/** One problem found, with enough detail to fix it without hunting. */
	data class Violation(
		val file: String,
		val line: Int,
		val found: String,
		val problem: String,
		val fix: String,
	) {
		override fun toString(): String = buildString {
			append("\n  $file:$line")
			append("\n    found:   $found")
			append("\n    problem: $problem")
			append("\n    fix:     $fix")
		}
	}

	/** The only shapes a name may take in a committed fixture. */
	private val ANONYMISED_NAME = Regex("""^Player\d+$""")

	/**
	 * "[450] SomeName" inside widget text, once formatting is stripped.
	 *
	 * The captured part must look like a Minecraft username. Without that
	 * restriction this also matched the player's own "SB Level: [1] 0/100 XP"
	 * row and reported "0/100" as a leaked name - a false positive that failed
	 * the build on clean fixtures.
	 */
	private val LEVEL_AND_NAME = Regex("""\[\d+]\s+([A-Za-z0-9_]{2,16})(?:\s|$)""")

	/** Rows that carry a bracketed number but never a player name. */
	private val NOT_A_PLAYER_ROW = Regex("""SB Level:""")

	private val BANK = Regex("""Bank:\s*(\S+)\s*/\s*(\S+)""")
	private val GEMS = Regex("""Gems:\s*(\S+)""")
	private val INTEREST = Regex("""Interest:\s*\d+\s*Hours\s*\((\S+?)\)""")
	private val SB_LEVEL = Regex("""SB Level:\s*\[(\d+)]\s*(\d+)/""")
	private val PROFILE = Regex("""Profile:\s*(\S+)""")
	private val SERVER = Regex("""Server:\s*(\S+)""")

	/** Anything shaped like a UUID: a Hypixel API key, or a player UUID. */
	private val UUID_SHAPE =
		Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

	private const val PLACEHOLDER_PROFILE = "TestProfile"
	private const val PLACEHOLDER_SERVER = "mini00XX"

	/** GUI fixtures may hold the forge slots and nothing else. */
	private val FORGE_GUI_SLOTS = 10..16

	private const val ANON_HINT =
		"replace with Player<number>; see the 'Anonymise the committed tab-list fixtures' commit"

	fun check(fileName: String, text: String): List<Violation> {
		val violations = mutableListOf<Violation>()
		val lines = text.lines()

		// Applies to every fixture regardless of kind.
		lines.forEachIndexed { index, line ->
			UUID_SHAPE.find(line)?.let {
				violations += Violation(
					fileName, index + 1, it.value,
					"a UUID - either a player UUID or an API key",
					"remove it; identifiers must never be committed",
				)
			}
		}

		when {
			fileName.startsWith("gui-") -> violations += checkGuiDump(fileName, lines)
			fileName.startsWith("dump-") -> violations += checkTabDump(fileName, lines)
			// An unrecognised fixture is not automatically safe, but nothing here
			// knows its shape, so it is reported rather than silently passed.
			else -> violations += Violation(
				fileName, 0, fileName,
				"unrecognised fixture kind, so it cannot be checked for real data",
				"name it dump-*.txt or gui-*.txt, or teach FixtureGuard its shape",
			)
		}
		return violations
	}

	private fun checkTabDump(fileName: String, lines: List<String>): List<Violation> {
		val violations = mutableListOf<Violation>()
		var inBody = false

		lines.forEachIndexed { index, raw ->
			val lineNumber = index + 1
			if (!inBody) {
				if (raw.trim() == "--") inBody = true
				return@forEachIndexed
			}

			val parts = raw.split('\t')
			if (parts.size < 3) return@forEachIndexed
			val profileName = parts[2]
			val text = ForgeParser.stripFormatting(parts.getOrElse(3) { "" })

			// 1. Real players appear in the profile column.
			if (!profileName.startsWith("!") && profileName.isNotBlank() &&
				!ANONYMISED_NAME.matches(profileName) && profileName != "<no-profile-name>"
			) {
				violations += Violation(
					fileName, lineNumber, profileName,
					"a real player name in the profile column",
					ANON_HINT,
				)
			}

			// 2. And again inside the player-list widget rows.
			LEVEL_AND_NAME.find(text)?.groupValues?.get(1)?.let { name ->
				if (!NOT_A_PLAYER_ROW.containsMatchIn(text) && !ANONYMISED_NAME.matches(name)) {
					violations += Violation(
						fileName, lineNumber, name,
						"a real player name inside widget text",
						ANON_HINT,
					)
				}
			}

			// 3. Personal and financial values must be placeholders.
			BANK.find(text)?.let { match ->
				val (purse, account) = match.destructured
				if (purse != "0" || account != "0") {
					violations += Violation(
						fileName, lineNumber, match.value,
						"a real bank balance", "replace both figures with 0",
					)
				}
			}
			GEMS.find(text)?.let { match ->
				if (match.groupValues[1] != "0") {
					violations += Violation(
						fileName, lineNumber, match.value, "a real gem count", "replace with 0",
					)
				}
			}
			INTEREST.find(text)?.let { match ->
				if (match.groupValues[1] != "0") {
					violations += Violation(
						fileName, lineNumber, match.value, "a real interest figure", "replace with 0",
					)
				}
			}
			SB_LEVEL.find(text)?.let { match ->
				if (match.groupValues[1] != "1" || match.groupValues[2] != "0") {
					violations += Violation(
						fileName, lineNumber, match.value,
						"a real SkyBlock level", "replace with [1] 0/100 XP",
					)
				}
			}
			PROFILE.find(text)?.let { match ->
				if (match.groupValues[1] != PLACEHOLDER_PROFILE) {
					violations += Violation(
						fileName, lineNumber, match.value,
						"a real profile name", "replace with $PLACEHOLDER_PROFILE",
					)
				}
			}
			SERVER.find(text)?.let { match ->
				if (match.groupValues[1] != PLACEHOLDER_SERVER) {
					violations += Violation(
						fileName, lineNumber, match.value,
						"a real server instance id", "replace with $PLACEHOLDER_SERVER",
					)
				}
			}
		}
		return violations
	}

	private fun checkGuiDump(fileName: String, lines: List<String>): List<Violation> {
		val violations = mutableListOf<Violation>()
		var inBody = false

		lines.forEachIndexed { index, raw ->
			val lineNumber = index + 1
			if (!inBody) {
				if (raw.trim() == "--") inBody = true
				return@forEachIndexed
			}

			val parts = raw.split('\t')
			if (parts.size < 2) return@forEachIndexed
			if (parts[0] != "slot" && parts[0] != "tip" && parts[0] != "tiperror") return@forEachIndexed

			val slot = parts[1].toIntOrNull() ?: return@forEachIndexed
			if (slot !in FORGE_GUI_SLOTS) {
				violations += Violation(
					fileName, lineNumber, raw.take(60),
					"GUI slot $slot is outside the forge slots - this is inventory content " +
						"(gear, enchants, kill counts)",
					"trim the dump to the header and slots ${FORGE_GUI_SLOTS.first}-${FORGE_GUI_SLOTS.last} only",
				)
			}
		}
		return violations
	}
}
