package dev.fraust.forgecast

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import org.slf4j.LoggerFactory
import java.io.File
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

	/** Written into the run directory, overwritten on every dump. */
	private const val DUMP_FILE_NAME = "forgecast-dump.txt"

	override fun onInitializeClient() {
		// Registers a command that the client handles by itself. Typing
		// "/forgecast dump" is intercepted locally and never sent to the server.
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				LiteralArgumentBuilder.literal<FabricClientCommandSource>("forgecast")
					.then(
						LiteralArgumentBuilder.literal<FabricClientCommandSource>("dump")
							.executes { context -> dumpTabList(context.source) }
					)
			)
		}
		logger.info("ForgeCast ready - /forgecast dump is registered")
	}

	/**
	 * Writes every current tab-list entry to [DUMP_FILE_NAME].
	 *
	 * Returns a Brigadier result code: 1 for success, 0 for "did nothing".
	 */
	private fun dumpTabList(source: FabricClientCommandSource): Int {
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
		val target = File(client.gameDirectory, DUMP_FILE_NAME)

		val out = StringBuilder()
		out.append("ForgeCast tab list dump\n")
		out.append("entries\t").append(entries.size).append('\n')
		out.append("columns\tindex\torder\tprofile\traw\n")
		out.append("--\n")

		entries.forEachIndexed { index, info ->
			// getTabListDisplayName() is null when the server sent no custom
			// name, in which case the game falls back to the profile name.
			val display = info.tabListDisplayName
			val raw = if (display == null) "<null>" else toLegacyString(display)
			val profileName = info.profile.name ?: "<no-profile-name>"

			out.append(index + 1).append('\t')
				.append(info.tabListOrder).append('\t')
				.append(profileName).append('\t')
				.append(raw).append('\n')
		}

		// Overwrite rather than append. UTF-8 matters: section signs are not ASCII.
		target.writeText(out.toString(), Charsets.UTF_8)

		logger.info("Wrote {} tab list entries to {}", entries.size, target.absolutePath)
		source.sendFeedback(
			Component.literal("ForgeCast: wrote ${entries.size} entries to $DUMP_FILE_NAME")
		)
		return 1
	}

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
