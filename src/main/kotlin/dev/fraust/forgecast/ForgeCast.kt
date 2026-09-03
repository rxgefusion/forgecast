package dev.fraust.forgecast

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.scores.DisplaySlot
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Optional

/**
 * ForgeCast entry point.
 *
 * This is a CLIENT-ONLY mod. [onInitializeClient] runs once while the game is
 * starting, and is never called on a dedicated server.
 *
 * Everything here reads the tab list through Fabric's ordinary public API
 * (Minecraft -> ClientPacketListener -> PlayerInfo). No mixin is required, so
 * there is deliberately no src/main/java and no mixin config in this project.
 */
object ForgeCast : ClientModInitializer {

	private val logger = LoggerFactory.getLogger("forgecast")

	/** Dumps are written into this folder inside the run directory. */
	private const val DUMP_DIR_NAME = "forgecast-dumps"

	/**
	 * Sortable, with millisecond precision so that two dumps taken in the same
	 * second cannot overwrite one another.
	 */
	private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

	override fun onInitializeClient() {
		// First: everything below reads settings.
		ConfigHolder.load()

		// Commands the client handles by itself. Typing one is intercepted
		// locally and never sent to the server.
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			val root = LiteralArgumentBuilder.literal<FabricClientCommandSource>("forgecast")
				// Bare /forgecast opens the settings screen. The old
				// /forgecast toggle is gone; the panel is switched there now.
				.executes { _ ->
					ConfigScreen.open()
					1
				}
				.then(
					LiteralArgumentBuilder.literal<FabricClientCommandSource>("status")
						.executes { context -> printStatus(context.source) }
				)

			// The capture tools are not merely refused in a release build - they
			// are never registered, so they do not exist to be tab-completed or
			// stumbled into. Execution is gated again inside each command, so
			// switching them off in config takes effect without a restart.
			if (DevTools.available) {
				root.then(
					LiteralArgumentBuilder.literal<FabricClientCommandSource>("dump")
						.executes { context -> dumpTabList(context.source) }
				)
				root.then(
					LiteralArgumentBuilder.literal<FabricClientCommandSource>("dumpgui")
						.executes { context -> dumpGui(context.source) }
				)
			}

			dispatcher.register(root)
		}

		// Draws after the vanilla HUD elements. The element itself decides each
		// frame whether there is anything to show.
		HudElementRegistry.addLast(
			Identifier.fromNamespaceAndPath("forgecast", "forge_panel"),
			ForgeHud,
		)

		// A container GUI swallows the keyboard, so /forgecast dumpgui can never
		// be typed while one is open. Capture on a tick instead; the command
		// then writes whatever was last seen.
		ClientTickEvents.END_CLIENT_TICK.register { client ->
			val now = System.currentTimeMillis()
			// Order matters: notice a transition, then take the single reading
			// of the tab list that both the panel and the advice will use.
			noticeWorldChange(client, now)
			TabListSource.refreshIfDue(client, now)
			captureOpenGui(client)
			readForgeScreen(client, now)
			checkForgeAdvice(client, now)
		}

		logger.info("ForgeCast ready - /forgecast opens settings")
	}

	// ------------------------------------------------ reading the Forge screen

	/** The Forge screen's title, once formatting is stripped. */
	private const val FORGE_SCREEN_TITLE = "The Forge"

	/** GUI slots 10..16 hold forge slots 1..7. Nothing else is read. */
	private val FORGE_GUI_SLOTS = GuiForgeParser.FIRST_FORGE_GUI_SLOT until
		(GuiForgeParser.FIRST_FORGE_GUI_SLOT + ForgeParser.EXPECTED_SLOT_COUNT)

	private const val FORGE_READ_INTERVAL_MS = 1_000L
	private var lastForgeReadMs = 0L

	/**
	 * Records the open Forge screen into memory. Changes nothing on screen.
	 *
	 * Deliberately NOT the dev-tool capture, which is gated off by default and
	 * walked every slot of EVERY open screen twice a second - the prime suspect
	 * for the loading stutter. This is a narrow hook by comparison:
	 *
	 *  - a type check and one string compare per tick, and nothing else unless
	 *    the Forge itself is open;
	 *  - at most once per second;
	 *  - seven tooltips rather than ninety, because only slots 10-16 matter.
	 *
	 * Roughly twenty-six times less work than the dev tool did, and none at all
	 * while the Forge is closed.
	 */
	private fun readForgeScreen(client: Minecraft, now: Long) {
		val screen = client.screen
		if (screen !is AbstractContainerScreen<*>) return
		if (!ForgeParser.stripFormatting(toLegacyString(screen.title)).trim()
				.equals(FORGE_SCREEN_TITLE, ignoreCase = true)
		) {
			return
		}

		if (now - lastForgeReadMs < FORGE_READ_INTERVAL_MS) return
		lastForgeReadMs = now

		val level = client.level ?: return
		val player = client.player ?: return
		val tooltipContext = Item.TooltipContext.of(level)

		val slots = screen.menu.slots
		val rows = mutableListOf<GuiSlotRow>()
		for (index in FORGE_GUI_SLOTS) {
			val slot = slots.getOrNull(index) ?: continue
			val stack = slot.item
			if (stack.isEmpty) continue
			val tooltip = runCatching {
				stack.getTooltipLines(tooltipContext, player, TooltipFlag.NORMAL).map { toLegacyString(it) }
			}.getOrElse { emptyList() }
			rows += GuiSlotRow(index, stack.count, toLegacyString(stack.hoverName), tooltip)
		}
		if (rows.isEmpty()) return

		val observedAt = Instant.now()
		ForgeStore.memory.recordGuiObservation(
			GuiForgeParser.parse(rows, observedAt),
			// The Forge screen does not say which profile it belongs to, so the
			// profile comes from the tab list. Null there means nothing is stored.
			TabListSource.profile,
			observedAt,
		)
	}

	// ------------------------------------------------- incomplete-data advice

	private val adviceThrottle = AdviceThrottle()
	private val adviceStabiliser = AdviceStabiliser()

	/** Which reading we last classified, so each one is judged exactly once. */
	private var lastAdviceGeneration = 0L

	/**
	 * How long to stop classifying after a world or server change.
	 *
	 * A transfer takes noticeably longer than an ordinary warp, and during it
	 * the tab list is rebuilt from nothing. Rather than stretch the streak
	 * requirement to cover the worst case, the two mechanisms are stacked: this
	 * window covers a known-bad period, the streak covers ordinary noise.
	 */
	private const val TRANSITION_GRACE_MS = 5_000L

	private var lastLevel: Any? = null
	private var suppressUntilMs = 0L

	/**
	 * Starts a quiet period whenever the world instance changes.
	 *
	 * The stabiliser is reset so nothing observed after the transition inherits
	 * a streak from before it. The throttle is deliberately NOT reset: it
	 * remembers what has already been said, and re-announcing the same problem
	 * after every warp would be exactly the nagging it exists to prevent.
	 */
	private fun noticeWorldChange(client: Minecraft, nowMs: Long) {
		val level = client.level
		if (level !== lastLevel) {
			lastLevel = level
			suppressUntilMs = nowMs + TRANSITION_GRACE_MS
			adviceStabiliser.reset()
			TabListSource.clear()
		}
	}

	/**
	 * Tells the player once when the forge data is incomplete, and how to fix
	 * it.
	 *
	 * Runs independently of the HUD toggle: the reading is either trustworthy
	 * or it is not, regardless of whether the panel happens to be shown.
	 *
	 * Reads the shared [TabListSource] rather than sampling the tab list itself,
	 * so this can never disagree with what the panel is showing.
	 */
	private fun checkForgeAdvice(client: Minecraft, now: Long) {
		if (!ConfigHolder.current.adviceEnabled) return

		// Quiet while a transition settles.
		if (now < suppressUntilMs) return

		// One judgement per reading, no more.
		val generation = TabListSource.generation
		if (generation == lastAdviceGeneration) return
		lastAdviceGeneration = generation

		if (!isOnHypixel(client)) {
			adviceThrottle.reset()
			adviceStabiliser.reset()
			return
		}

		val rows = TabListSource.rows
		val snapshot = TabListSource.snapshot ?: return

		// Two independent SkyBlock signals. The Profile row is itself a widget
		// row, so it disappears exactly when the widgets are switched off - the
		// case we most want to warn about. The scoreboard title survives that.
		if (!SkyBlockDetector.isSkyBlock(rows, sidebarTitle(client))) {
			adviceThrottle.reset()
			adviceStabiliser.reset()
			return
		}

		val case = ForgeAdvice.classify(rows, snapshot)

		// A real problem persists; a half-built tab list does not. Nothing is
		// announced until the same reading has held for several seconds.
		val settled = adviceStabiliser.offer(case) ?: return

		val toAnnounce = adviceThrottle.announce(settled) ?: return
		val lines = ForgeAdvice.message(toAnnounce, snapshot.renderedSlots) ?: return

		val player = client.player ?: return
		for (line in lines) {
			player.sendSystemMessage(
				prefix().append(Component.literal(line).withStyle(ChatFormatting.GRAY))
			)
		}
	}

	// ------------------------------------------------------- reading the game
	//
	// Both commands go through these helpers, so the text a dump records is
	// exactly the text the parser sees live. Without that shared path a fixture
	// could pass its tests while the live command quietly behaved differently.

	private fun profileNameOf(info: PlayerInfo): String =
		info.profile.name ?: "<no-profile-name>"

	private fun rowTextOf(info: PlayerInfo): String {
		// getTabListDisplayName() is null when the server sent no custom name,
		// in which case the game falls back to the profile name.
		val display = info.tabListDisplayName
		return if (display == null) "<null>" else toLegacyString(display)
	}

	/**
	 * The current forge-data problem, for the settings screen to display.
	 *
	 * Null means there is nothing to say - either all seven slots are readable,
	 * or we cannot confirm we are on SkyBlock at all. Same conservatism as the
	 * chat warning: silence beats a wrong claim.
	 */
	internal fun currentDataProblem(client: Minecraft): Pair<ForgeDataCase, Int>? {
		if (!isOnHypixel(client)) return null
		val connection = client.connection ?: return null
		val rows = readTabRows(connection)
		if (!SkyBlockDetector.isSkyBlock(rows, sidebarTitle(client))) return null

		val snapshot = ForgeParser.parse(rows)
		val case = ForgeAdvice.classify(rows, snapshot)
		return if (case == ForgeDataCase.COMPLETE) null else case to snapshot.renderedSlots
	}

	/**
	 * The sidebar scoreboard's title with formatting stripped, or null when
	 * there is no sidebar.
	 *
	 * This is the SkyBlock signal that does not come from the widget system, so
	 * it still answers when the widgets are switched off.
	 */
	internal fun sidebarTitle(client: Minecraft): String? {
		val objective = client.level?.scoreboard?.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return null
		return ForgeParser.stripFormatting(toLegacyString(objective.displayName)).trim()
	}

	/**
	 * Whether we are connected to a Hypixel address.
	 *
	 * Shared rather than repeated: the HUD and the advice check both need it,
	 * and two copies of a rule like this drift apart silently. The project has
	 * already had one near-miss with two duration parsers.
	 */
	internal fun isOnHypixel(client: Minecraft): Boolean {
		if (client.connection == null) return false
		val address = client.currentServer?.ip ?: return false
		return address.lowercase().contains("hypixel")
	}

	/** The live tab list, in the shape [ForgeParser] expects. */
	internal fun readTabRows(connection: ClientPacketListener): List<TabRow> =
		connection.listedOnlinePlayers.map { info ->
			TabRow(profileNameOf(info), rowTextOf(info))
		}

	// --------------------------------------------------- /forgecast status

	/**
	 * Reads the live tab list, runs it through [ForgeParser] unchanged, and
	 * prints one chat line per forge slot.
	 */
	private fun printStatus(source: FabricClientCommandSource): Int {
		val connection = Minecraft.getInstance().connection
		if (connection == null) {
			source.sendError(Component.literal("ForgeCast: not connected to a server."))
			return 0
		}

		val snapshot = ForgeParser.parse(readTabRows(connection))

		if (!snapshot.foundSection) {
			source.sendFeedback(
				prefix().append(
					Component.literal("No Forges section in the tab list here.")
						.withStyle(ChatFormatting.GRAY)
				)
			)
			return 1
		}

		val slots = snapshot.slots
		var i = 0
		while (i < slots.size) {
			if (didNotRender(slots[i])) {
				// Collapse a run of missing slots into one line: "6-7) not visible".
				var end = i
				while (end + 1 < slots.size && didNotRender(slots[end + 1])) end++
				source.sendFeedback(notVisibleLine(slots[i].slot, slots[end].slot))
				i = end + 1
			} else {
				source.sendFeedback(slotLine(slots[i]))
				i++
			}
		}

		printStoredObservations(source)
		return 1
	}

	/**
	 * Dumps what memory holds, per slot and per sensor.
	 *
	 * This is how the GUI plumbing gets verified without changing anything on
	 * screen: open the Forge, walk away, and check the mod still knows the exact
	 * finish times it read there.
	 */
	private fun printStoredObservations(source: FabricClientCommandSource) {
		val stored = ForgeStore.memory.storedObservations()
		if (stored.isEmpty()) return

		source.sendFeedback(
			prefix().append(
				Component.literal("stored readings (widget = rounded, GUI = exact):")
					.withStyle(ChatFormatting.DARK_GRAY)
			)
		)

		val now = Instant.now()
		for (entry in stored) {
			val line = prefix()
				.append(Component.literal("  ${entry.slot}) ").withStyle(ChatFormatting.GRAY))
				.append(
					Component.literal(
						if (entry.source == ObservationSource.GUI) "GUI    " else "widget ",
					).withStyle(
						if (entry.source == ObservationSource.GUI) ChatFormatting.AQUA
						else ChatFormatting.DARK_AQUA,
					)
				)
				.append(
					Component.literal("${entry.state} ").withStyle(ChatFormatting.WHITE)
				)

			entry.itemName?.let {
				line.append(Component.literal("$it ").withStyle(ChatFormatting.GRAY))
			}

			line.append(
				Component.literal(
					entry.finishAt?.let { "finishes ${describeGap(it, now)} " } ?: "",
				).withStyle(ChatFormatting.GREEN)
			)
			line.append(
				Component.literal("seen ${describeGap(entry.observedAt, now)}")
					.withStyle(ChatFormatting.DARK_GRAY)
			)
			source.sendFeedback(line)
		}
	}

	/** "in 8h 46m" or "3m ago", whichever side of now the instant falls. */
	private fun describeGap(instant: Instant, now: Instant): String {
		val seconds = (instant.toEpochMilli() - now.toEpochMilli()) / 1000
		val magnitude = kotlin.math.abs(seconds)
		val text = when {
			magnitude < 60 -> "${magnitude}s"
			magnitude < 3600 -> "${magnitude / 60}m ${magnitude % 60}s"
			else -> "${magnitude / 3600}h ${(magnitude % 3600) / 60}m"
		}
		return if (seconds >= 0) "in $text" else "$text ago"
	}

	/**
	 * A slot the server never rendered, as opposed to one whose text we failed
	 * to recognise. Only the former is "not visible"; the latter still has text
	 * worth showing.
	 */
	private fun didNotRender(slot: ForgeSlot): Boolean =
		slot.state == ForgeSlotState.UNKNOWN && slot.rawText == null

	private fun prefix(): MutableComponent =
		Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
			.append(Component.literal("ForgeCast").withStyle(ChatFormatting.GOLD))
			.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY))

	private fun notVisibleLine(from: Int, to: Int): Component {
		val label = if (from == to) "$from) " else "$from-$to) "
		return prefix()
			.append(Component.literal(label).withStyle(ChatFormatting.DARK_GRAY))
			.append(
				Component.literal("not visible (list truncated)")
					.withStyle(ChatFormatting.DARK_GRAY)
			)
	}

	private fun slotLine(slot: ForgeSlot): Component {
		val line = prefix()
			.append(Component.literal("${slot.slot}) ").withStyle(ChatFormatting.GRAY))

		when (slot.state) {
			ForgeSlotState.IN_PROGRESS -> {
				line.append(Component.literal(slot.itemName ?: "?").withStyle(ChatFormatting.WHITE))
				line.append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
				line.append(
					Component.literal(slot.remaining?.toString() ?: "?")
						.withStyle(ChatFormatting.AQUA)
				)
			}

			ForgeSlotState.READY -> {
				line.append(Component.literal(slot.itemName ?: "?").withStyle(ChatFormatting.WHITE))
				line.append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
				line.append(Component.literal("READY").withStyle(ChatFormatting.GREEN))
			}

			ForgeSlotState.EMPTY -> {
				line.append(Component.literal("empty").withStyle(ChatFormatting.DARK_GRAY))
			}

			ForgeSlotState.LOCKED -> {
				line.append(Component.literal("locked").withStyle(ChatFormatting.DARK_GRAY))
			}

			ForgeSlotState.UNKNOWN -> {
				// Rendered, but in a shape we do not recognise. Show the text so
				// it can be reported rather than silently dropped.
				line.append(Component.literal("unrecognised").withStyle(ChatFormatting.RED))
				slot.rawText?.let {
					line.append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
					line.append(Component.literal(it).withStyle(ChatFormatting.GRAY))
				}
			}
		}
		return line
	}

	// ----------------------------------------------------- /forgecast dump

	/**
	 * Writes every current tab-list entry to a new timestamped file inside
	 * [DUMP_DIR_NAME]. Nothing is ever overwritten.
	 *
	 * Returns a Brigadier result code: 1 for success, 0 for "did nothing".
	 */
	private fun dumpTabList(source: FabricClientCommandSource): Int {
		DevTools.unavailableReason()?.let { reason ->
			source.sendError(Component.literal("ForgeCast: $reason."))
			return 0
		}

		val client = Minecraft.getInstance()

		// getConnection() is null when not connected to any server.
		val connection = client.connection
		if (connection == null) {
			source.sendError(Component.literal("ForgeCast: not connected to a server."))
			return 0
		}

		// "Listed" players are exactly those the server asked to show in the tab
		// list - which on Hypixel includes its fake widget entries.
		val entries = connection.listedOnlinePlayers.toList()

		// One file per dump, so a rapid sequence of captures cannot lose data.
		// The time is recorded in the filename AND inside the file, so a capture
		// can be lined up against whatever was counting down at the time.
		val now = LocalDateTime.now()
		val dir = File(client.gameDirectory, DUMP_DIR_NAME)
		dir.mkdirs()
		val target = File(dir, "dump-${now.format(FILE_STAMP)}.txt")

		val out = StringBuilder()
		out.append("ForgeCast tab list dump\n")
		out.append("taken\t").append(now).append('\n')
		out.append("entries\t").append(entries.size).append('\n')
		out.append("columns\tindex\torder\tprofile\traw\n")
		out.append("--\n")

		entries.forEachIndexed { index, info ->
			out.append(index + 1).append('\t')
				.append(info.tabListOrder).append('\t')
				.append(profileNameOf(info)).append('\t')
				.append(rowTextOf(info)).append('\n')
		}

		// UTF-8 matters: section signs are not ASCII.
		target.writeText(out.toString(), Charsets.UTF_8)

		logger.info("Wrote {} tab list entries to {}", entries.size, target.absolutePath)
		source.sendFeedback(
			Component.literal("ForgeCast: wrote ${entries.size} entries to $DUMP_DIR_NAME/${target.name}")
		)
		return 1
	}

	// -------------------------------------------------- /forgecast dumpgui

	/**
	 * Writes every slot of the currently open container screen, with each
	 * item's full tooltip, to a timestamped file.
	 *
	 * Read-only: nothing is clicked and nothing is sent to the server. Same
	 * philosophy as the tab-list dump - capture now, work out the format later.
	 */
	/**
	 * The most recent container screen, already rendered to text.
	 *
	 * A chat command cannot be typed while a container GUI is open - the GUI
	 * takes the keyboard - so capturing at command time is impossible. Instead
	 * the screen is captured on a tick WHILE it is open, and the command writes
	 * whatever was last captured. Open the Forge, close it, run the command.
	 */
	private var lastGuiCapture: String? = null
	private var lastGuiScreenName: String? = null
	private var lastGuiOccupied = 0
	private var lastGuiSlots = 0
	private var lastGuiCaptureMs = 0L

	/** Re-capturing every tick would be wasteful; the contents barely move. */
	private const val GUI_CAPTURE_INTERVAL_MS = 500L

	private fun captureOpenGui(client: Minecraft) {
		// The most expensive thing this mod can do: every slot of every open
		// container, with every tooltip rebuilt. Off unless explicitly asked for.
		if (!DevTools.available) return

		val screen = client.screen
		if (screen !is AbstractContainerScreen<*>) return

		val now = System.currentTimeMillis()
		if (now - lastGuiCaptureMs < GUI_CAPTURE_INTERVAL_MS) return
		lastGuiCaptureMs = now

		buildGuiDump(client, screen)
	}

	/** Renders the open screen to text and stores it. Read-only throughout. */
	private fun buildGuiDump(client: Minecraft, screen: AbstractContainerScreen<*>) {
		val level = client.level ?: return
		val player = client.player ?: return

		// Tooltips are generated the same way the game generates hover text, so
		// what lands in the file is what Hypixel actually sent.
		val tooltipContext = Item.TooltipContext.of(level)
		val tooltipFlag = TooltipFlag.NORMAL

		val slots = screen.menu.slots
		val out = StringBuilder()
		out.append("ForgeCast GUI dump\n")
		out.append("captured\t").append(LocalDateTime.now()).append('\n')
		out.append("screen\t").append(toLegacyString(screen.title)).append('\n')
		out.append("screenClass\t").append(screen.javaClass.simpleName).append('\n')
		out.append("slots\t").append(slots.size).append('\n')
		out.append("format\tslot<TAB>index<TAB>count<TAB>rawName   and   tip<TAB>index<TAB>rawLine\n")
		out.append("--\n")

		var occupied = 0
		slots.forEachIndexed { index, slot ->
			val stack = slot.item
			if (stack.isEmpty) {
				out.append("slot\t").append(index).append("\t0\t<empty>\n")
				return@forEachIndexed
			}
			occupied++
			out.append("slot\t").append(index).append('\t')
				.append(stack.count).append('\t')
				.append(toLegacyString(stack.hoverName)).append('\n')

			// Defensive: an item with odd data should not lose the whole capture.
			val lines = runCatching { stack.getTooltipLines(tooltipContext, player, tooltipFlag) }
				.getOrElse { error ->
					out.append("tiperror\t").append(index).append('\t').append(error).append('\n')
					emptyList()
				}
			for (line in lines) {
				out.append("tip\t").append(index).append('\t')
					.append(toLegacyString(line)).append('\n')
			}
		}

		lastGuiCapture = out.toString()
		lastGuiScreenName = ForgeParser.stripFormatting(toLegacyString(screen.title)).trim()
		lastGuiOccupied = occupied
		lastGuiSlots = slots.size
	}

	private fun dumpGui(source: FabricClientCommandSource): Int {
		DevTools.unavailableReason()?.let { reason ->
			source.sendError(Component.literal("ForgeCast: $reason."))
			return 0
		}

		val client = Minecraft.getInstance()

		// If a container somehow is open (a macro, say), take a fresh capture.
		(client.screen as? AbstractContainerScreen<*>)?.let { buildGuiDump(client, it) }

		val text = lastGuiCapture
		if (text == null) {
			source.sendError(
				Component.literal(
					"ForgeCast: no container screen has been opened yet. Open the Forge, close it, then run this again."
				)
			)
			return 0
		}

		val target = File(File(client.gameDirectory, DUMP_DIR_NAME).apply { mkdirs() },
			"gui-${LocalDateTime.now().format(FILE_STAMP)}.txt")
		target.writeText(text, Charsets.UTF_8)

		logger.info("Wrote GUI capture of '{}' to {}", lastGuiScreenName, target.absolutePath)
		source.sendFeedback(
			Component.literal(
				"ForgeCast: wrote '${lastGuiScreenName}' " +
					"($lastGuiOccupied occupied of $lastGuiSlots slots) to $DUMP_DIR_NAME/${target.name}"
			)
		)
		return 1
	}

	// ------------------------------------------------------ text conversion

	/**
	 * Rebuilds a legacy section-sign string from a [Component].
	 *
	 * Component.getString() throws formatting away, so instead we walk the
	 * component tree with visit(). Minecraft hands us each run of text together
	 * with its fully resolved style, and we re-emit the matching legacy codes.
	 */
	private fun toLegacyString(component: Component): String {
		val out = StringBuilder()
		component.visit(
			FormattedText.StyledContentConsumer<Unit> { style, text ->
				out.append(legacyPrefix(style)).append(text)
				Optional.empty<Unit>()
			},
			Style.EMPTY
		)
		return out.toString()
	}

	/** Turns one [Style] into the section-sign codes that would produce it. */
	private fun legacyPrefix(style: Style): String {
		val out = StringBuilder()

		val color: TextColor? = style.color
		if (color != null) {
			// Only the 16 classic colours have a legacy code. Anything else is a
			// true RGB colour, which we show as hex so it is still visible.
			val named = ChatFormatting.values()
				.firstOrNull { it.isColor && TextColor.fromLegacyFormat(it) == color }
			if (named != null) {
				out.append(ChatFormatting.PREFIX_CODE).append(named.getChar())
			} else {
				out.append("<#%06X>".format(color.value and 0xFFFFFF))
			}
		}

		if (style.isBold) out.append(ChatFormatting.PREFIX_CODE).append(ChatFormatting.BOLD.getChar())
		if (style.isItalic) out.append(ChatFormatting.PREFIX_CODE).append(ChatFormatting.ITALIC.getChar())
		if (style.isUnderlined) out.append(ChatFormatting.PREFIX_CODE).append(ChatFormatting.UNDERLINE.getChar())
		if (style.isStrikethrough) out.append(ChatFormatting.PREFIX_CODE).append(ChatFormatting.STRIKETHROUGH.getChar())
		if (style.isObfuscated) out.append(ChatFormatting.PREFIX_CODE).append(ChatFormatting.OBFUSCATED.getChar())

		return out.toString()
	}
}
