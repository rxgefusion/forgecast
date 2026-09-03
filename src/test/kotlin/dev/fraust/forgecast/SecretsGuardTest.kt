package dev.fraust.forgecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Fails the build if anything key-shaped is about to be committed.
 *
 * Slice 3 will likely want a Hypixel API key. A key is a UUID, which is easy to
 * paste into a config file "just to test" and then forget - and unlike a
 * password it grants access silently, so nothing goes wrong until someone else
 * is using your rate limit.
 *
 * This is deliberately in place before anything uses the API. Rails first.
 *
 * WHERE A KEY SHOULD LIVE - none of these are in the repository:
 *
 *  1. An environment variable, HYPIXEL_API_KEY. Best: never written to disk
 *     inside the project tree at all.
 *  2. The game's config directory, which is outside the repo:
 *     .minecraft/config/forgecast-api-key.txt
 *  3. run/ during development - already gitignored, and the run directory is
 *     regenerated anyway.
 *
 * WHERE IT MUST NEVER GO:
 *
 *  - gradle.properties. This IS committed, and it is the first place people
 *    put credentials because Gradle reads it automatically.
 *  - Any file under src/, including test resources.
 *  - A commit message, which survives even a later file deletion.
 */
class SecretsGuardTest {

	/** A Hypixel API key is a UUID. Also catches player UUIDs. */
	private val uuidShape =
		Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

	/** "apiKey = something", "api_key: something", "token=something". */
	private val assignedSecret = Regex(
		"""(?i)\b(api[_-]?key|apikey|auth[_-]?token|access[_-]?token|secret)\b\s*[=:]\s*["']?([^\s"',;)]{8,})""",
	)

	/** Values that are obviously not real secrets. */
	private val placeholder = Regex(
		"""(?i)^(your[_-]?key|xxx+|<.*>|\$\{.*}|changeme|placeholder|none|null|empty|todo|example.*)$""",
	)

	/** File names that should never exist inside the repository at all. */
	private val secretFileName = Regex(
		"""(?i)^(\.env|.*\.pem|.*\.p12|.*\.keystore|.*api[_-]?key.*|.*secret.*|.*credentials.*)$""",
	)

	/** Text files worth scanning. Binary and build output are skipped. */
	private val scannableExtensions = setOf(
		"kt", "java", "gradle", "properties", "json", "txt", "md", "yml", "yaml", "xml", "cfg", "toml",
	)

	private fun projectRoot(): File =
		File(".").absoluteFile.let { if (it.name == ".") it.parentFile else it }

	/**
	 * Files a commit would carry.
	 *
	 * Excludes this file, which necessarily contains the very patterns it hunts
	 * for and would otherwise report itself.
	 */
	private fun committedTextFiles(): List<File> {
		val root = projectRoot()
		val skipped = setOf("build", ".git", ".gradle", "run", ".idea", ".kotlin", "out")
		return root.walkTopDown()
			.onEnter { it.name !in skipped }
			.filter { it.isFile }
			.filter { it.extension.lowercase() in scannableExtensions }
			.filter { it.name != "SecretsGuardTest.kt" && it.name != "FixtureGuard.kt" }
			.toList()
	}

	// --------------------------------------------------------- the sweep

	@Test
	fun `no file in the project contains anything key-shaped`() {
		val findings = mutableListOf<String>()

		for (file in committedTextFiles()) {
			val relative = file.relativeTo(projectRoot()).path
			file.readLines().forEachIndexed { index, line ->
				uuidShape.find(line)?.let {
					findings += "$relative:${index + 1}  UUID: ${it.value.take(13)}..."
				}
				assignedSecret.find(line)?.let { match ->
					val value = match.groupValues[2]
					if (!placeholder.matches(value)) {
						findings += "$relative:${index + 1}  assigned secret: ${match.groupValues[1]}=***"
					}
				}
			}
		}

		assertTrue(
			findings.isEmpty(),
			buildString {
				appendLine("Something key-shaped is in the project tree:")
				findings.take(20).forEach { appendLine("  $it") }
				appendLine()
				appendLine("A Hypixel API key is a UUID. Put it in ONE of:")
				appendLine("  - the HYPIXEL_API_KEY environment variable (best)")
				appendLine("  - .minecraft/config/forgecast-api-key.txt (outside the repo)")
				appendLine("  - run/ during development (already gitignored)")
				appendLine("NEVER gradle.properties - that file is committed.")
			},
		)
	}

	@Test
	fun `no file is named like a secret`() {
		val offenders = committedTextFiles()
			.filter { secretFileName.matches(it.name) }
			.map { it.relativeTo(projectRoot()).path }

		assertTrue(
			offenders.isEmpty(),
			"Files named like secrets are present: $offenders\n" +
				"Even if empty today, the name invites a key tomorrow. Keep keys outside the repo.",
		)
	}

	@Test
	fun `gitignore covers the obvious key file names`() {
		val ignoreText = File(projectRoot(), ".gitignore").readText()
		// Narrow, specific names. Broad wildcards were tried and rejected: "*secret*"
		// matched SecretsGuardTest.kt and kept this very guard out of the repo.
		val required = listOf(
			".env", "*.pem", "*.keystore", "*.token",
			"secrets.properties", "api-key.txt", "credentials.json",
		)

		val missing = required.filterNot { ignoreText.contains(it) }
		assertTrue(
			missing.isEmpty(),
			"These patterns are missing from .gitignore, so a key file could be committed: $missing",
		)
	}

	@Test
	fun `no ignore rule can exclude source, including this guard`() {
		// This actually happened. A "*secret*" rule matched SecretsGuardTest.kt,
		// so the file was silently never committed - an ignore rule that removed
		// the thing enforcing it, and a guard that would simply not exist in a
		// fresh clone. The negation below is what makes that impossible.
		val ignoreText = File(projectRoot(), ".gitignore").readText()
		assertTrue(
			ignoreText.lineSequence().any { it.trim() == "!*.kt" },
			"'.gitignore' must keep '!*.kt' so no wildcard can ever swallow a source file - " +
				"a broad rule once hid this very test from the repository",
		)

		// And the guards must be present on disk to have run at all.
		val guards = listOf("SecretsGuardTest.kt", "FixtureGuard.kt", "FixtureGuardTest.kt")
		val dir = File(projectRoot(), "src/test/kotlin/dev/fraust/forgecast")
		val missing = guards.filterNot { File(dir, it).isFile }
		assertTrue(missing.isEmpty(), "guard files missing from the source tree: $missing")
	}

	// ----------------------------------------- proving the scan can fail

	@Test
	fun `the UUID pattern matches a real key shape`() {
		// Built from parts so this line is not itself a UUID for the sweep to find.
		val key = listOf("a1b2c3d4", "e5f6", "7890", "abcd", "ef1234567890").joinToString("-")
		assertTrue(uuidShape.containsMatchIn("apiKey=$key"), "a real key shape must be detected")
	}

	@Test
	fun `an assigned secret is detected`() {
		assertTrue(assignedSecret.containsMatchIn("""val apiKey = "s0meRealLookingValue" """))
		assertTrue(assignedSecret.containsMatchIn("hypixel.api_key: abcdefgh12345678"))
	}

	@Test
	fun `placeholders are not reported`() {
		// Otherwise nobody could document the setting.
		for (safe in listOf("YOUR_KEY", "xxxxxxxx", "<your-key-here>", "changeme", "TODO")) {
			val match = assignedSecret.find("apiKey=$safe")
			assertTrue(
				match == null || placeholder.matches(match.groupValues[2]),
				"'$safe' should be treated as a placeholder, not a leak",
			)
		}
	}

	@Test
	fun `the secret file name pattern catches the usual suspects`() {
		for (name in listOf(".env", "api-key.txt", "apikey.properties", "secrets.json", "my.keystore")) {
			assertTrue(secretFileName.matches(name), "$name should be flagged as a secret-shaped name")
		}
		for (name in listOf("ForgeCast.kt", "gradle.properties", "README.md")) {
			assertEquals(false, secretFileName.matches(name), "$name is not a secret file")
		}
	}
}
