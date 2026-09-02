package dev.fraust.forgecast

import java.io.File

/**
 * Dev-only harness. Runs [ForgeParser] against saved dump files so the parser
 * can be checked without launching Minecraft.
 *
 * This lives in the test source set, so it is never shipped in the mod jar.
 *
 *   ./gradlew parseDumps
 */

/** Reads a dump file written by /forgecast dump into plain rows. */
private fun readDump(file: File): List<TabRow> {
	val rows = mutableListOf<TabRow>()
	var inBody = false
	file.forEachLine(Charsets.UTF_8) { line ->
		if (!inBody) {
			if (line.trim() == "--") inBody = true
			return@forEachLine
		}
		// index \t order \t profile \t raw
		val parts = line.split('\t')
		if (parts.size >= 3) {
			rows += TabRow(
				profileName = parts[2],
				rawText = if (parts.size >= 4) parts[3] else "",
			)
		}
	}
	return rows
}

/** Pulls the Area line out of a dump purely so the table is readable. */
private fun areaOf(rows: List<TabRow>): String =
	rows.map { ForgeParser.stripFormatting(it.rawText) }
		.firstOrNull { it.contains("Area:") }
		?.substringAfter("Area:")
		?.trim()
		?: "?"

private fun short(state: ForgeSlotState): String = when (state) {
	ForgeSlotState.EMPTY -> "empty"
	ForgeSlotState.IN_PROGRESS -> "busy"
	ForgeSlotState.READY -> "READY"
	ForgeSlotState.UNKNOWN -> "-"
}

fun main(args: Array<String>) {
	val dir = File(args.firstOrNull() ?: "run/forgecast-dumps")
	val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".txt") }
		?.sortedBy { it.name }
		.orEmpty()

	if (files.isEmpty()) {
		println("No dump files found in ${dir.absolutePath}")
		return
	}

	println("Parsing ${files.size} dumps from ${dir.absolutePath}")
	println()

	val header = "%-34s %-16s %-7s %-9s %s".format("dump", "area", "header", "rendered", "slots 1..7")
	println(header)
	println("-".repeat(header.length + 10))

	for (file in files) {
		val rows = readDump(file)
		val snap = ForgeParser.parse(rows)
		val cells = snap.slots.joinToString(" ") { short(it.state).padEnd(5) }
		println(
			"%-34s %-16s %-7s %-9s %s".format(
				file.name.removePrefix("dump-").removeSuffix(".txt").take(33),
				areaOf(rows).take(15),
				snap.headerProfile ?: "none",
				"${snap.renderedSlots}/${snap.slots.size}",
				cells,
			)
		)
	}

	println()
	println("=== DETAIL ===")
	for (file in files) {
		val rows = readDump(file)
		val snap = ForgeParser.parse(rows)
		println()
		println("${file.name}  (${areaOf(rows)})")
		for (slot in snap.slots) {
			val detail = when (slot.state) {
				ForgeSlotState.IN_PROGRESS -> "${slot.itemName} - ${slot.remaining}"
				ForgeSlotState.READY -> "${slot.itemName} - ready to collect"
				ForgeSlotState.EMPTY -> "(empty)"
				ForgeSlotState.UNKNOWN ->
					if (slot.rawText == null) "(did not render - state unknown)"
					else "UNRECOGNISED: ${slot.rawText}"
			}
			println("   slot ${slot.slot}: ${short(slot.state).padEnd(6)} $detail")
		}
		if (snap.unparsedRows.isNotEmpty()) {
			println("   unparsed rows inside the section:")
			snap.unparsedRows.forEach { println("     $it") }
		}
	}

	println()
	println("=== DURATION PARSER SPOT CHECKS ===")
	val cases = listOf("11h", "26s", "4s", "1h30m", "2d3h", "45m", "0s", "Ready!", "11h ago", "", "abc", "10")
	for (c in cases) {
		println("   %-10s -> %s".format("\"$c\"", ForgeParser.parseDuration(c) ?: "null (not a duration)"))
	}
}
