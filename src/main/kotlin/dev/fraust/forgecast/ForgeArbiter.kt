package dev.fraust.forgecast

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How much a displayed value can be trusted. Never blurred together. */
enum class Confidence {
	/** Derived from a GUI reading and confirmed, or not contradicted. To the second. */
	EXACT,

	/** Rounded, or exact arithmetic resting on an unconfirmed assumption. */
	APPROXIMATE,

	/** Never seen, or a belief that the widget contradicted. */
	UNKNOWN,
}

/** What the mod believes about one slot, and how strongly. */
data class ForgeBelief(
	val slot: Int,
	val state: ForgeSlotState,
	val confidence: Confidence,
	val itemName: String? = null,
	val remaining: Duration? = null,
	val finishAt: Instant? = null,
	/** Which sensor the belief rests on. Null when nothing is known. */
	val source: ObservationSource? = null,
	val observedAt: Instant? = null,
	/**
	 * Whether a sensor is looking at this slot RIGHT NOW and reporting this
	 * state - as opposed to the state being recalled from memory or worked out
	 * by arithmetic.
	 *
	 * A separate axis from [confidence], and the two genuinely come apart. A
	 * live widget reading of "Ready!" is only APPROXIMATE, because every widget
	 * value is floored - but it is directly observed. A GUI belief that has
	 * passed its predicted finish with nothing able to confirm it is also
	 * APPROXIMATE, and is not observed at all: it is a well-founded guess.
	 *
	 * The completion alert needs the second distinction, not the first. Firing
	 * a chime on a guess is worse than silence.
	 */
	val observed: Boolean = false,
)

/**
 * Decides what to believe when the two sensors disagree.
 *
 * The sensors are not equals:
 *
 *  - The **GUI** is exact to the second, so remaining time is arithmetic from a
 *    stored instant, with no drift. It is the truth.
 *  - The **widget** is FLOORED to its unit - "11h 59m" in the GUI reads "11h"
 *    on the widget - so it can never refine a GUI number, only contradict it.
 *    It is a change detector, not a clock.
 *
 * Because the widget floors, a widget reading is a LOWER BOUND. That makes
 * agreement arithmetic rather than guesswork: the two agree when the belief
 * falls inside the bucket that floors to the widget's value.
 */
object ForgeArbiter {

	/**
	 * The result of arbitrating: what to show, and which GUI beliefs the widget
	 * disproved.
	 *
	 * Invalidations are returned rather than applied so this stays a pure
	 * function; the caller owns the store.
	 */
	data class Result(
		val beliefs: List<ForgeBelief>,
		val invalidatedGuiSlots: Set<Int>,
	)

	/**
	 * The size of the bucket a widget value was floored into.
	 *
	 * Inferred from the value itself, because the widget shows a single largest
	 * unit: "11h" is hour-granular, "45m" minute-granular, "26s" second-granular.
	 */
	fun widgetGranularity(widget: Duration): Duration {
		val seconds = widget.inWholeSeconds
		return when {
			seconds >= 86_400 && seconds % 86_400 == 0L -> 1.days
			seconds >= 3_600 && seconds % 3_600 == 0L -> 1.hours
			seconds >= 60 && seconds % 60 == 0L -> 1.minutes
			else -> 1.seconds
		}
	}

	/**
	 * Whether a widget reading is consistent with a belief.
	 *
	 * The widget floors, so agreement means the belief sits in the bucket that
	 * floors to the widget's value: `widget <= belief < widget + unit`.
	 *
	 * The window is widened by one unit downward because the widget updates on a
	 * tick and can briefly still show the PREVIOUS, higher bucket. It can never
	 * legitimately show a LOWER one - that would mean time jumped forward, which
	 * only happens if the slot changed.
	 */
	fun widgetAgrees(beliefRemaining: Duration, widgetRemaining: Duration): Boolean {
		val unit = widgetGranularity(widgetRemaining)
		return beliefRemaining >= widgetRemaining - unit && beliefRemaining < widgetRemaining + unit
	}

	/**
	 * @param widgetView what the widget path already produced, per slot.
	 * @param guiBeliefs GUI readings by slot number.
	 */
	fun arbitrate(
		widgetView: List<MergedSlot>,
		guiBeliefs: Map<Int, StoredObservation>,
		now: Instant,
	): Result {
		val beliefs = mutableListOf<ForgeBelief>()
		val invalidated = mutableSetOf<Int>()

		for (widget in widgetView) {
			val gui = guiBeliefs[widget.slot]
			if (gui == null) {
				beliefs += fromWidgetAlone(widget)
				continue
			}

			val contradiction = contradicts(widget, gui, now)
			if (contradiction) {
				// The world moved on. The belief is stale, so fall back to what
				// the widget can actually see, and stop believing the reading.
				invalidated += widget.slot
				beliefs += fromWidgetAlone(widget)
			} else {
				beliefs += fromGuiBelief(widget, gui, now)
			}
		}

		return Result(beliefs, invalidated)
	}

	/** A widget-only slot. Rounded at best, so never EXACT. */
	private fun fromWidgetAlone(widget: MergedSlot): ForgeBelief = when (widget.source) {
		SlotSource.NONE -> ForgeBelief(widget.slot, ForgeSlotState.UNKNOWN, Confidence.UNKNOWN)

		SlotSource.LIVE, SlotSource.REMEMBERED -> ForgeBelief(
			slot = widget.slot,
			state = widget.state,
			confidence = if (widget.state == ForgeSlotState.UNKNOWN) Confidence.UNKNOWN
			else Confidence.APPROXIMATE,
			itemName = widget.itemName,
			remaining = widget.remaining,
			source = ObservationSource.WIDGET,
			observedAt = widget.observedAt,
			// A LIVE widget slot is being rendered by the server this instant.
			// A REMEMBERED one is our own recollection.
			observed = widget.source == SlotSource.LIVE,
		)
	}

	/**
	 * Whether the widget materially disagrees with a GUI belief.
	 *
	 * Only a MATERIAL disagreement counts. A rounded time consistent with the
	 * belief is agreement, not contradiction - that is the whole point of the
	 * floor rule.
	 */
	private fun contradicts(widget: MergedSlot, gui: StoredObservation, now: Instant): Boolean {
		// The widget cannot see the slot, so it cannot disprove anything.
		if (widget.source != SlotSource.LIVE) return false

		// A slot the widget rendered but we could not read says nothing either.
		if (widget.state == ForgeSlotState.UNKNOWN) return false

		// A different item means the slot was emptied and refilled.
		val guiItem = gui.itemName
		val widgetItem = widget.itemName
		if (guiItem != null && widgetItem != null && guiItem != widgetItem) return true

		return when (widget.state) {
			// Ready earlier than predicted: something changed, and the widget is
			// looking at the slot right now.
			ForgeSlotState.READY -> gui.state == ForgeSlotState.IN_PROGRESS &&
				gui.finishAt != null && now.isBefore(gui.finishAt)

			// The slot is no longer running at all.
			ForgeSlotState.EMPTY -> gui.state != ForgeSlotState.EMPTY
			ForgeSlotState.LOCKED -> gui.state != ForgeSlotState.LOCKED

			ForgeSlotState.IN_PROGRESS -> {
				val widgetRemaining = widget.remaining ?: return false
				when (gui.state) {
					// Belief says finished, widget says still counting.
					ForgeSlotState.READY -> true
					ForgeSlotState.EMPTY, ForgeSlotState.LOCKED -> true
					ForgeSlotState.IN_PROGRESS -> {
						val finishAt = gui.finishAt ?: return true
						val beliefRemaining = (finishAt.toEpochMilli() - now.toEpochMilli()).milliseconds
						// Past its finish while the widget still counts down means
						// the belief is about a recipe that is no longer running.
						if (beliefRemaining <= Duration.ZERO) return true
						!widgetAgrees(beliefRemaining, widgetRemaining)
					}

					ForgeSlotState.UNKNOWN -> false
				}
			}

			ForgeSlotState.UNKNOWN -> false
		}
	}

	/** A slot the GUI has seen and the widget has not disproved. */
	private fun fromGuiBelief(
		widget: MergedSlot,
		gui: StoredObservation,
		now: Instant,
	): ForgeBelief {
		// Whether the widget is looking at this slot right now and saying the
		// same thing. Used both for confidence and for [ForgeBelief.observed].
		fun corroborated(state: ForgeSlotState) =
			widget.source == SlotSource.LIVE && widget.state == state

		val base = ForgeBelief(
			slot = gui.slot,
			state = gui.state,
			confidence = Confidence.EXACT,
			itemName = gui.itemName,
			finishAt = gui.finishAt,
			source = ObservationSource.GUI,
			observedAt = gui.observedAt,
			observed = corroborated(gui.state),
		)

		if (gui.state != ForgeSlotState.IN_PROGRESS || gui.finishAt == null) return base

		val remaining = (gui.finishAt.toEpochMilli() - now.toEpochMilli()).milliseconds
		if (remaining > Duration.ZERO) {
			return base.copy(remaining = remaining)
		}

		// The predicted finish has passed. The arithmetic is exact; whether
		// anyone has collected it since is an assumption.
		val widgetConfirms = corroborated(ForgeSlotState.READY)
		return base.copy(
			state = ForgeSlotState.READY,
			remaining = null,
			// Confirmed by a live widget reading, or resting on the assumption
			// that nothing touched the forge. Never silently equal.
			confidence = if (widgetConfirms) Confidence.EXACT else Confidence.APPROXIMATE,
			observed = widgetConfirms,
		)
	}
}
