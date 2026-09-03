package dev.fraust.forgecast

import kotlin.math.roundToInt

/**
 * Geometry for the forge panel and its resize handle.
 *
 * Pure arithmetic, kept out of the screen class so the awkward parts - uniform
 * scaling and handle hit-testing - can be tested without a running game.
 */
object HudLayout {

	/** Side length of the square resize handle, in screen pixels. */
	const val HANDLE_SIZE = 6

	/**
	 * The scale a drag implies, as a percentage, clamped to the allowed range.
	 *
	 * [dragX] and [dragY] are the cursor's position relative to the panel's
	 * top-left corner - that is, the size the player is asking the panel to be.
	 *
	 * Uniform by construction: the two axes are averaged into ONE number, which
	 * is then applied to both. The panel can therefore never be stretched out of
	 * shape, however the cursor moves. Averaging rather than taking a single
	 * axis means dragging diagonally, horizontally or vertically all feel
	 * responsive, instead of one direction doing nothing.
	 */
	fun scalePercentFromDrag(baseWidth: Int, baseHeight: Int, dragX: Double, dragY: Double): Int {
		if (baseWidth <= 0 || baseHeight <= 0) return ForgeCastConfig.MIN_SCALE

		val wantedWidth = dragX.coerceAtLeast(1.0)
		val wantedHeight = dragY.coerceAtLeast(1.0)
		val ratio = (wantedWidth / baseWidth + wantedHeight / baseHeight) / 2.0

		return (ratio * 100.0).roundToInt()
			.coerceIn(ForgeCastConfig.MIN_SCALE, ForgeCastConfig.MAX_SCALE)
	}

	/** Scaled width of the panel, given its unscaled width. */
	fun scaled(base: Int, scalePercent: Int): Int = (base * scalePercent / 100.0).toInt()

	/**
	 * Whether the cursor is over the resize handle.
	 *
	 * The handle sits at the panel's bottom-right corner and is checked BEFORE
	 * the panel body, so the two never fight over the same click.
	 */
	fun overHandle(
		panelX: Int,
		panelY: Int,
		panelWidth: Int,
		panelHeight: Int,
		mouseX: Double,
		mouseY: Double,
	): Boolean {
		val handleX = panelX + panelWidth - HANDLE_SIZE
		val handleY = panelY + panelHeight - HANDLE_SIZE
		return mouseX >= handleX && mouseX < handleX + HANDLE_SIZE &&
			mouseY >= handleY && mouseY < handleY + HANDLE_SIZE
	}

	/** Whether the cursor is over the panel body. */
	fun overPanel(
		panelX: Int,
		panelY: Int,
		panelWidth: Int,
		panelHeight: Int,
		mouseX: Double,
		mouseY: Double,
	): Boolean =
		mouseX >= panelX && mouseX < panelX + panelWidth &&
			mouseY >= panelY && mouseY < panelY + panelHeight
}
