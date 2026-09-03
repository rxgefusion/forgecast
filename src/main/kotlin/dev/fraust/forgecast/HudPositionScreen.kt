package dev.fraust.forgecast

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Drag-to-position and drag-to-resize editor for the forge panel.
 *
 * Drag the panel body to move it; drag the small handle at its bottom-right
 * corner to resize. Resizing is uniform - [HudLayout.scalePercentFromDrag]
 * reduces both axes to ONE number, so the panel keeps its shape no matter how
 * the cursor moves and can never be stretched.
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
		const val PADDING = 2

		const val COLOR_PANEL_IDLE = 0x30FFFFFF.toInt()
		const val COLOR_PANEL_ACTIVE = 0x60FFFFFF.toInt()
		const val COLOR_HANDLE = 0xFFFFFFFF.toInt()
		const val COLOR_HANDLE_ACTIVE = 0xFF55FF55.toInt()
	}

	private enum class Grab { NONE, MOVING, RESIZING }

	private var grab = Grab.NONE

	/** Cursor offset inside the panel when a move began, so it does not jump. */
	private var dragOffsetX = 0
	private var dragOffsetY = 0

	private fun baseWidth(): Int = SAMPLE_ROWS.maxOf { font.width(it) } + PADDING * 2
	private fun baseHeight(): Int = SAMPLE_ROWS.size * (font.lineHeight + 1) + PADDING * 2

	private fun panelWidth(): Int = HudLayout.scaled(baseWidth(), ConfigHolder.current.hudScale)
	private fun panelHeight(): Int = HudLayout.scaled(baseHeight(), ConfigHolder.current.hudScale)

	override fun init() {
		val left = width / 2 - 110
		addRenderableWidget(
			Button.builder(Component.literal("Reset")) {
				ConfigHolder.update { it.copy(hudX = 4, hudY = 4, hudScale = 100) }
			}.bounds(left, height - 28, 105, 20).build()
		)
		addRenderableWidget(
			Button.builder(Component.literal("Done")) { onClose() }
				.bounds(left + 115, height - 28, 105, 20).build()
		)
	}

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		if (event.button() == 0) {
			val config = ConfigHolder.current
			// Handle first: it overlaps the panel's corner, and whichever is
			// tested first wins that pixel.
			if (HudLayout.overHandle(config.hudX, config.hudY, panelWidth(), panelHeight(), event.x(), event.y())) {
				grab = Grab.RESIZING
				return true
			}
			if (HudLayout.overPanel(config.hudX, config.hudY, panelWidth(), panelHeight(), event.x(), event.y())) {
				grab = Grab.MOVING
				dragOffsetX = (event.x() - config.hudX).toInt()
				dragOffsetY = (event.y() - config.hudY).toInt()
				return true
			}
		}
		return super.mouseClicked(event, doubleClick)
	}

	override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
		val config = ConfigHolder.current
		when (grab) {
			Grab.MOVING -> {
				// Clamped so the panel can never be dragged fully off screen and
				// become impossible to grab again.
				val newX = (event.x() - dragOffsetX).toInt().coerceIn(0, (width - panelWidth()).coerceAtLeast(0))
				val newY = (event.y() - dragOffsetY).toInt().coerceIn(0, (height - panelHeight()).coerceAtLeast(0))
				ConfigHolder.update { it.copy(hudX = newX, hudY = newY) }
				return true
			}

			Grab.RESIZING -> {
				val percent = HudLayout.scalePercentFromDrag(
					baseWidth(),
					baseHeight(),
					event.x() - config.hudX,
					event.y() - config.hudY,
				)
				ConfigHolder.update { it.copy(hudScale = percent) }
				return true
			}

			Grab.NONE -> return super.mouseDragged(event, dragX, dragY)
		}
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		if (grab != Grab.NONE) {
			grab = Grab.NONE
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
		val w = panelWidth()
		val h = panelHeight()

		// A faint box so the panel is grabbable even when the text is short.
		graphics.fill(
			config.hudX, config.hudY, config.hudX + w, config.hudY + h,
			if (grab == Grab.MOVING) COLOR_PANEL_ACTIVE else COLOR_PANEL_IDLE,
		)

		graphics.pose().pushMatrix()
		graphics.pose().translate(config.hudX.toFloat() + PADDING, config.hudY.toFloat() + PADDING)
		graphics.pose().scale(config.hudScale / 100f, config.hudScale / 100f)
		var y = 0
		for (line in SAMPLE_ROWS) {
			graphics.text(font, line, 0, y, 0xFFFFFFFF.toInt())
			y += font.lineHeight + 1
		}
		graphics.pose().popMatrix()

		// The resize handle, drawn last so it sits on top of the panel corner.
		val handleX = config.hudX + w - HudLayout.HANDLE_SIZE
		val handleY = config.hudY + h - HudLayout.HANDLE_SIZE
		graphics.fill(
			handleX, handleY,
			handleX + HudLayout.HANDLE_SIZE, handleY + HudLayout.HANDLE_SIZE,
			if (grab == Grab.RESIZING) COLOR_HANDLE_ACTIVE else COLOR_HANDLE,
		)

		graphics.centeredText(
			font,
			Component.literal("Drag the panel to move it - drag the corner to resize"),
			width / 2, 16, 0xFFFFFFFF.toInt(),
		)
		graphics.centeredText(
			font,
			Component.literal("Sample values - not your real forge      ${config.hudScale}%"),
			width / 2, 28, 0xFF808080.toInt(),
		)
	}

	override fun onClose() {
		minecraft.setScreen(parent)
	}

	override fun isPauseScreen(): Boolean = false
}
