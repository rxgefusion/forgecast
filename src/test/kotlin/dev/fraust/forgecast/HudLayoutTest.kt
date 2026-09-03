package dev.fraust.forgecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Tests for [HudLayout] - the panel geometry and the drag-to-resize maths.
 *
 * The drawing that uses this cannot be tested here. What CAN be tested is the
 * property the feature hinges on: that resizing is uniform and the panel can
 * never be stretched out of shape.
 */
class HudLayoutTest {

	private val baseWidth = 120
	private val baseHeight = 60

	// ------------------------------------------------------ uniform scaling

	@Test
	fun `dragging to exactly the base size gives 100 percent`() {
		assertEquals(
			100,
			HudLayout.scalePercentFromDrag(baseWidth, baseHeight, baseWidth.toDouble(), baseHeight.toDouble()),
		)
	}

	@Test
	fun `dragging to double the base size gives 200 percent`() {
		assertEquals(
			200,
			HudLayout.scalePercentFromDrag(baseWidth, baseHeight, baseWidth * 2.0, baseHeight * 2.0),
		)
	}

	@Test
	fun `the panel keeps its aspect ratio at every scale`() {
		// The property that matters: one scalar drives both axes, so whatever
		// the drag, width over height is constant.
		val baseRatio = baseWidth.toDouble() / baseHeight
		for (percent in listOf(50, 75, 100, 137, 200, 300)) {
			val w = HudLayout.scaled(baseWidth, percent)
			val h = HudLayout.scaled(baseHeight, percent)
			val ratio = w.toDouble() / h
			assertTrue(
				abs(ratio - baseRatio) < 0.05,
				"aspect drifted at $percent%: expected ~$baseRatio, got $ratio",
			)
		}
	}

	@Test
	fun `a lopsided drag still produces one uniform scale`() {
		// Dragging far right but barely down. The result is a single number, so
		// the panel cannot end up wide and squat.
		val percent = HudLayout.scalePercentFromDrag(baseWidth, baseHeight, baseWidth * 3.0, baseHeight * 1.0)
		// Average of 300% and 100%.
		assertEquals(200, percent)

		val w = HudLayout.scaled(baseWidth, percent)
		val h = HudLayout.scaled(baseHeight, percent)
		assertEquals(baseWidth * 2, w)
		assertEquals(baseHeight * 2, h)
	}

	@Test
	fun `dragging up and left cannot invert or zero the panel`() {
		val percent = HudLayout.scalePercentFromDrag(baseWidth, baseHeight, -500.0, -500.0)
		assertEquals(ForgeCastConfig.MIN_SCALE, percent)
		assertTrue(HudLayout.scaled(baseWidth, percent) > 0, "width must stay positive")
	}

	// --------------------------------------------------------- the clamps

	@Test
	fun `the existing min and max clamps still apply`() {
		assertEquals(
			ForgeCastConfig.MAX_SCALE,
			HudLayout.scalePercentFromDrag(baseWidth, baseHeight, baseWidth * 50.0, baseHeight * 50.0),
		)
		assertEquals(
			ForgeCastConfig.MIN_SCALE,
			HudLayout.scalePercentFromDrag(baseWidth, baseHeight, 1.0, 1.0),
		)
	}

	@Test
	fun `a zero-sized panel cannot divide by zero`() {
		assertEquals(ForgeCastConfig.MIN_SCALE, HudLayout.scalePercentFromDrag(0, 0, 100.0, 100.0))
	}

	// ------------------------------------------------------- hit testing

	@Test
	fun `the handle sits at the bottom right corner`() {
		val x = 10
		val y = 20
		val w = 100
		val h = 50
		// Just inside the corner.
		assertTrue(HudLayout.overHandle(x, y, w, h, (x + w - 1).toDouble(), (y + h - 1).toDouble()))
		// The opposite corner is panel, not handle.
		assertFalse(HudLayout.overHandle(x, y, w, h, x.toDouble(), y.toDouble()))
	}

	@Test
	fun `the handle overlaps the panel, so it must be tested first`() {
		val x = 0
		val y = 0
		val w = 100
		val h = 50
		val cornerX = (w - 1).toDouble()
		val cornerY = (h - 1).toDouble()

		// Both report true for the same pixel. The screen resolves this by
		// checking the handle before the panel; this test records why that
		// ordering is load-bearing rather than incidental.
		assertTrue(HudLayout.overHandle(x, y, w, h, cornerX, cornerY))
		assertTrue(HudLayout.overPanel(x, y, w, h, cornerX, cornerY))
	}

	@Test
	fun `points outside the panel are neither`() {
		assertFalse(HudLayout.overPanel(10, 10, 50, 50, 5.0, 5.0))
		assertFalse(HudLayout.overHandle(10, 10, 50, 50, 5.0, 5.0))
		assertFalse(HudLayout.overPanel(10, 10, 50, 50, 100.0, 100.0))
	}

	@Test
	fun `the panel edges are inclusive at the top left and exclusive at the bottom right`() {
		assertTrue(HudLayout.overPanel(10, 10, 50, 50, 10.0, 10.0), "top-left corner is inside")
		assertFalse(HudLayout.overPanel(10, 10, 50, 50, 60.0, 60.0), "bottom-right edge is outside")
	}
}
