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
 * This screen is for PLAYERS. The capture tools are deliberately not here:
 * they are developer instruments, gated by the launch environment rather than
 * by a setting anyone could stumble into.
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

		private const val COLOR_PROBLEM = 0xFFFFAA00.toInt()
		private const val COLOR_FIX = 0xFFAAAAAA.toInt()

		/** Opens the screen on the next tick, which is safe from a command. */
		fun open() {
			val client = Minecraft.getInstance()
			client.execute { client.setScreen(ConfigScreen(client.screen)) }
		}
	}

	private var hudButton: Button? = null
	private var adviceButton: Button? = null
	private var alertButton: Button? = null
	private var soundButton: Button? = null

	/**
	 * Read once when the screen opens rather than every frame.
	 *
	 * The notice is a status line, not a live readout, and re-reading the whole
	 * tab list per frame to render one sentence would be indefensible.
	 */
	private var problem: Pair<ForgeDataCase, Int>? = null

	private fun onOff(on: Boolean): String = if (on) "ON" else "OFF"

	override fun init() {
		problem = ForgeCast.currentDataProblem(minecraft)

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

		alertButton = addRenderableWidget(
			Button.builder(Component.literal(alertLabel())) {
				ConfigHolder.update { it.copy(completionAlertEnabled = !it.completionAlertEnabled) }
				alertButton?.message = Component.literal(alertLabel())
				// The chime is meaningless without the alert it accompanies.
				soundButton?.active = ConfigHolder.current.completionAlertEnabled
			}.bounds(left, y, BUTTON_WIDTH, BUTTON_HEIGHT).build()
		)
		y += BUTTON_HEIGHT + GAP

		soundButton = addRenderableWidget(
			Button.builder(Component.literal(soundLabel())) {
				ConfigHolder.update { it.copy(completionSoundEnabled = !it.completionSoundEnabled) }
				soundButton?.message = Component.literal(soundLabel())
			}.bounds(left, y, BUTTON_WIDTH, BUTTON_HEIGHT).build()
		)
		soundButton?.active = ConfigHolder.current.completionAlertEnabled
		y += BUTTON_HEIGHT + GAP

		addRenderableWidget(
			Button.builder(Component.literal("Done")) { onClose() }
				.bounds(left, y + GAP * 2, BUTTON_WIDTH, BUTTON_HEIGHT).build()
		)
	}

	private fun hudLabel() = "Forge panel: ${onOff(ConfigHolder.current.hudEnabled)}"
	private fun adviceLabel() = "Warn when forge data is incomplete: ${onOff(ConfigHolder.current.adviceEnabled)}"
	private fun alertLabel() = "Tell me when a slot finishes: ${onOff(ConfigHolder.current.completionAlertEnabled)}"
	private fun soundLabel() = "  ...with a sound: ${onOff(ConfigHolder.current.completionSoundEnabled)}"

	override fun extractRenderState(
		graphics: GuiGraphicsExtractor,
		mouseX: Int,
		mouseY: Int,
		partialTick: Float,
	) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick)
		graphics.centeredText(font, title, width / 2, height / 4 - 20, 0xFFFFFFFF.toInt())

		// The standing data problem, shown every time the menu is opened. The
		// chat warning fires once and can be missed or dismissed; this cannot.
		val current = problem
		if (current != null) {
			val (case, renderedSlots) = current
			ForgeAdvice.summary(case, renderedSlots)?.let {
				graphics.centeredText(font, Component.literal(it), width / 2, height - 44, COLOR_PROBLEM)
			}
			ForgeAdvice.fixPath(case)?.let {
				graphics.centeredText(font, Component.literal(it), width / 2, height - 32, COLOR_FIX)
			}
		}
	}

	override fun onClose() {
		minecraft.setScreen(parent)
	}

	/** Settings are not worth pausing a singleplayer world for. */
	override fun isPauseScreen(): Boolean = false
}
