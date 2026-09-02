package dev.fraust.forgecast

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Drag-to-position editor for the forge panel.
 *
 * Shows a stand-in panel with sample rows, which the player drags to wherever
 * they want it and resizes with the two buttons. Position and scale are written
 * to config as they change, so closing the screen any way keeps the result.
 *
 * Sample rows rather than live ones on purpose: the editor has to be usable off
 * SkyBlock, and an empty box would be impossible to aim.
 */
class HudPositionScreen(private val parent: Screen?) : Screen(Component.literal("Move the forge panel")) {

	private companion object {
		val SAMPLE_ROWS = listOf(
			"Forges",
			"1) Refined Titanium 8h 46m",
			"2) Refined Titanium 7h 35m",
			"3) empty",
			"4) Bejeweled Handle READY",
		)
		const val SCALE_STEP = 10
		const val PADDING = 2
	}

	/** Cursor offset inside the panel when the drag began, so it does not jump. */
	private var dragOffsetX = 0
	private var dragOffsetY = 0
	private var dragging = false

	private var scaleButton: Button? = null

	private val scale: Float get() = ConfigHolder.current.hudScale / 100f

	private fun panelWidth(): Int =
		((SAMPLE_ROWS.maxOf { font.width(it) } + PADDING * 2) * scale).toInt()

	private fun panelHeight(): Int =
		((SAMPLE_ROWS.size * (font.lineHeight + 1) + PADDING * 2) * scale).toInt()

	override fun init() {
		val left = width / 2 - 110

		scaleButton = addRenderableWidget(
			Button.builder(Component.literal(scaleLabel())) {
				changeScale(SCALE_STEP)
			}.bounds(left, height - 52, 105, 20).build()
		)
		addRenderableWidget(
			Button.builder(Component.literal("Smaller")) {
				changeScale(-SCALE_STEP)
			}.bounds(left + 115, height - 52, 105, 20).build()
		)
		addRenderableWidget(
			Button.builder(Component.literal("Reset position")) {
				ConfigHolder.update { it.copy(hudX = 4, hudY = 4, hudScale = 100) }
				scaleButton?.message = Component.literal(scaleLabel())
			}.bounds(left, height - 28, 105, 20).build()
		)
		addRenderableWidget(
			Button.builder(Component.literal("Done")) { onClose() }
				.bounds(left + 115, height - 28, 105, 20).build()
		)
	}

	private fun scaleLabel() = "Bigger  (${ConfigHolder.current.hudScale}%)"

	private fun changeScale(delta: Int) {
		ConfigHolder.update {
			it.copy(hudScale = (it.hudScale + delta).coerceIn(ForgeCastConfig.MIN_SCALE, ForgeCastConfig.MAX_SCALE))
		}
		scaleButton?.message = Component.literal(scaleLabel())
	}

	private fun overPanel(x: Double, y: Double): Boolean {
		val config = ConfigHolder.current
		return x >= config.hudX && x < config.hudX + panelWidth() &&
			y >= config.hudY && y < config.hudY + panelHeight()
	}

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		if (event.button() == 0 && overPanel(event.x(), event.y())) {
			dragging = true
			dragOffsetX = (event.x() - ConfigHolder.current.hudX).toInt()
			dragOffsetY = (event.y() - ConfigHolder.current.hudY).toInt()
			return true
		}
		return super.mouseClicked(event, doubleClick)
	}

	override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
		if (dragging) {
			// Clamped so the panel can never be dragged fully off screen and
			// become impossible to grab again.
			val newX = (event.x() - dragOffsetX).toInt().coerceIn(0, (width - panelWidth()).coerceAtLeast(0))
			val newY = (event.y() - dragOffsetY).toInt().coerceIn(0, (height - panelHeight()).coerceAtLeast(0))
			ConfigHolder.update { it.copy(hudX = newX, hudY = newY) }
			return true
		}
		return super.mouseDragged(event, dragX, dragY)
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		if (dragging) {
			dragging = false
			return true
		}
		return super.mouseReleased(event)
	}

	override fun extractRenderState(
		graphics: GuiGraphicsExtractor,
		mouseX: Int,
		mouseY: Int,
		partialTick: Float,
	) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick)

		val config = ConfigHolder.current

		// A faint box so the panel is grabbable even when the text is short.
		graphics.fill(
			config.hudX, config.hudY,
			config.hudX + panelWidth(), config.hudY + panelHeight(),
			if (dragging) 0x60FFFFFF.toInt() else 0x30FFFFFF.toInt(),
		)

		graphics.pose().pushMatrix()
		graphics.pose().translate(config.hudX.toFloat() + PADDING, config.hudY.toFloat() + PADDING)
		graphics.pose().scale(scale, scale)
		var y = 0
		for (line in SAMPLE_ROWS) {
			graphics.text(font, line, 0, y, 0xFFFFFFFF.toInt())
			y += font.lineHeight + 1
		}
		graphics.pose().popMatrix()

		graphics.centeredText(
			font,
			Component.literal("Drag the panel to move it"),
			width / 2, 16, 0xFFFFFFFF.toInt(),
		)
		graphics.centeredText(
			font,
			Component.literal("Sample values - not your real forge"),
			width / 2, 28, 0xFF808080.toInt(),
		)
	}

	override fun onClose() {
		minecraft.setScreen(parent)
	}

	override fun isPauseScreen(): Boolean = false
}
