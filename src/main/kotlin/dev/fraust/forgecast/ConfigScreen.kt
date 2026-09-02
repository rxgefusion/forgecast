package dev.fraust.forgecast

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * The settings screen, opened by /forgecast with no arguments.
 *
 * Hand-rolled: no config library. One screen, no sub-menus, per the plan's
 * settings rules - the complaint it quotes is about spending half an hour
 * turning things off.
 *
 * Every button reads its label from the live config and writes straight back
 * through [ConfigHolder], so a change is saved the moment it is made. There is
 * no apply or cancel to get wrong.
 */
class ConfigScreen(private val parent: Screen?) : Screen(Component.literal("ForgeCast")) {

	companion object {
		private const val BUTTON_WIDTH = 220
		private const val BUTTON_HEIGHT = 20
		private const val GAP = 4

		/** Opens the screen on the next tick, which is safe from a command. */
		fun open() {
			val client = Minecraft.getInstance()
			client.execute { client.setScreen(ConfigScreen(client.screen)) }
		}
	}

	private var hudButton: Button? = null
	private var adviceButton: Button? = null
	private var devToolsButton: Button? = null

	private fun onOff(on: Boolean): String = if (on) "ON" else "OFF"

	override fun init() {
		val left = width / 2 - BUTTON_WIDTH / 2
		var y = height / 4

		hudButton = addRenderableWidget(
			Button.builder(Component.literal(hudLabel())) {
				ConfigHolder.update { it.copy(hudEnabled = !it.hudEnabled) }
				hudButton?.message = Component.literal(hudLabel())
			}.bounds(left, y, BUTTON_WIDTH, BUTTON_HEIGHT).build()
		)
		y += BUTTON_HEIGHT + GAP

		addRenderableWidget(
			Button.builder(Component.literal("Move and resize the panel...")) {
				minecraft.setScreen(HudPositionScreen(this))
			}.bounds(left, y, BUTTON_WIDTH, BUTTON_HEIGHT).build()
		)
		y += BUTTON_HEIGHT + GAP

		adviceButton = addRenderableWidget(
			Button.builder(Component.literal(adviceLabel())) {
				ConfigHolder.update { it.copy(adviceEnabled = !it.adviceEnabled) }
				adviceButton?.message = Component.literal(adviceLabel())
			}.bounds(left, y, BUTTON_WIDTH, BUTTON_HEIGHT).build()
		)
		y += BUTTON_HEIGHT + GAP

		// Only offered where the tools can actually run. In a release build they
		// are not registered at all, so a button for them would be a lie.
		if (DevTools.inDevelopmentEnvironment) {
			devToolsButton = addRenderableWidget(
				Button.builder(Component.literal(devToolsLabel())) {
					ConfigHolder.update { it.copy(devToolsEnabled = !it.devToolsEnabled) }
					devToolsButton?.message = Component.literal(devToolsLabel())
				}.bounds(left, y, BUTTON_WIDTH, BUTTON_HEIGHT).build()
			)
			y += BUTTON_HEIGHT + GAP
		}

		addRenderableWidget(
			Button.builder(Component.literal("Done")) { onClose() }
				.bounds(left, y + GAP * 2, BUTTON_WIDTH, BUTTON_HEIGHT).build()
		)
	}

	private fun hudLabel() = "Forge panel: ${onOff(ConfigHolder.current.hudEnabled)}"
	private fun adviceLabel() = "Warn when forge data is incomplete: ${onOff(ConfigHolder.current.adviceEnabled)}"
	private fun devToolsLabel() = "Capture tools (development): ${onOff(ConfigHolder.current.devToolsEnabled)}"

	override fun extractRenderState(
		graphics: GuiGraphicsExtractor,
		mouseX: Int,
		mouseY: Int,
		partialTick: Float,
	) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick)
		graphics.centeredText(font, title, width / 2, height / 4 - 20, 0xFFFFFFFF.toInt())

		if (DevTools.inDevelopmentEnvironment) {
			graphics.centeredText(
				font,
				Component.literal("Development build"),
				width / 2,
				height - 20,
				0xFF808080.toInt(),
			)
		}
	}

	override fun onClose() {
		minecraft.setScreen(parent)
	}

	/** Settings are not worth pausing a singleplayer world for. */
	override fun isPauseScreen(): Boolean = false
}
