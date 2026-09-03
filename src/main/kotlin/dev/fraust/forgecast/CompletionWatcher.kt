package dev.fraust.forgecast

/** How sure the mod is that a slot has finished. */
enum class AlertKind {
	/** A sensor saw it running and then saw it Ready. */
	CONFIRMED,

	/**
	 * A GUI reading passed its finish time while nothing could see the slot.
	 *
	 * Not a guess: the finish time came from the Forge screen and is exact to
	 * the second, and collecting the item requires opening that screen again,
	 * which would have refreshed the reading. But nothing has actually looked,
	 * so it is announced as expectation rather than fact.
	 */
	FORECAST,
}

/** A slot that has finished, ready to be announced once. */
data class ForgeCompletion(val slot: Int, val itemName: String?, val kind: AlertKind)

/**
 * Notices when a forge slot finishes, and says so exactly once.
 *
 * Reads the arbitrated result; it does not parse or decide anything itself.
 *
 * WHAT COUNTS AS SEEING A SLOT. Two things, and deliberately not a third:
 *
 *  - A live sighting: a sensor is rendering the slot right now.
 *  - A GUI-derived belief: the Forge screen was read, so the finish time is
 *    exact, and arithmetic on an exact instant is not guesswork.
 *
 * Not the third: a WIDGET memory that reached READY by arithmetic. Every widget
 * value is floored, so an aged widget countdown really is a guess, and a guess
 * must never ring a bell.
 *
 * DISCOVERY IS NOT COMPLETION. A completion is a slot seen RUNNING and then
 * seen READY. Logging in to find slot 3 already Ready is one sighting, so it
 * becomes the baseline and announces nothing. This cannot be fooled: the
 * evidence for a transition is two sightings, and at login we have one.
 *
 * ONCE PER COMPLETION, WHICHEVER KIND FIRES FIRST. A forecast records READY as
 * the baseline, so the confirmed sighting that follows is READY-to-READY, which
 * is not a transition. No separate bookkeeping is needed to prevent the double
 * announcement, and the slot can still fire again after it is seen running
 * again - which means a new item was queued.
 *
 * NOTHING IS BELIEVED ON ONE READING. A state must hold for [required]
 * consecutive readings before it counts. The mod already shipped a false
 * warning built from a half-built tab list; here the same glitch would be
 * worse, because a spurious "Ready!" would both announce wrongly AND record
 * READY as the baseline, so the real completion would never be announced at
 * all. Two seconds of delay on an eight-hour recipe is not a cost.
 */
class CompletionWatcher(private val required: Int = DEFAULT_REQUIRED) {

	companion object {
		/**
		 * Readings a state must survive before it is believed.
		 *
		 * Three, matching AdviceStabiliser. Readings are once a second, so this
		 * is a two-second delay on something that took hours.
		 */
		const val DEFAULT_REQUIRED = 3
	}

	private data class Pending(val state: ForgeSlotState, val count: Int)

	private var profile: String? = null

	/** The last state we believed for each slot. Never from a single reading. */
	private val lastSeen = mutableMapOf<Int, ForgeSlotState>()

	/** States still proving themselves. */
	private val pending = mutableMapOf<Int, Pending>()

	/** How many slots have a baseline. For tests and diagnostics. */
	val trackedSlots: Int get() = lastSeen.size

	/**
	 * Offers a reading and returns whatever just finished.
	 *
	 * @param profileName from the tab list. Null means we cannot tell whose
	 *   forge this is, so nothing is recorded and nothing fires.
	 */
	fun offer(beliefs: List<ForgeBelief>, profileName: String?): List<ForgeCompletion> {
		if (profileName == null) return emptyList()

		if (profileName != profile) {
			// A different profile is a different forge. Everything about it is a
			// discovery, including slots that happen to be sitting Ready.
			//
			// The reading itself is NOT discarded - it falls through and is
			// counted like any other. Returning here would silently consume one
			// reading, so the first state after a profile change would need four
			// readings to settle rather than three.
			lastSeen.clear()
			pending.clear()
			profile = profileName
		}

		val finished = mutableListOf<ForgeCompletion>()

		for (belief in beliefs) {
			val candidate = sightingOf(belief) ?: continue
			val settled = settle(belief.slot, candidate) ?: continue

			val previous = lastSeen.put(belief.slot, settled)

			// First belief about this slot. Baseline only.
			if (previous == null) continue

			if (previous == ForgeSlotState.IN_PROGRESS && settled == ForgeSlotState.READY) {
				finished += ForgeCompletion(
					belief.slot,
					belief.itemName,
					if (belief.observed) AlertKind.CONFIRMED else AlertKind.FORECAST,
				)
			}
		}

		return finished
	}

	/**
	 * What this belief says about the slot, or null if it says nothing usable.
	 *
	 * Null is not "no state" - it is "no evidence". A slot that says nothing
	 * leaves its pending count and its baseline untouched, so a completion that
	 * happens out of sight is still waiting to be found when the slot returns.
	 */
	private fun sightingOf(belief: ForgeBelief): ForgeSlotState? = when {
		// Something is rendering the slot right now.
		belief.observed -> belief.state

		// The Forge screen was read, so the finish time is exact. Arithmetic on
		// an exact instant is evidence; a floored widget countdown is not.
		belief.source == ObservationSource.GUI -> belief.state

		else -> null
	}

	/** Returns the state once it has held for [required] consecutive readings. */
	private fun settle(slot: Int, state: ForgeSlotState): ForgeSlotState? {
		val current = pending[slot]
		val count = if (current?.state == state) current.count + 1 else 1
		pending[slot] = Pending(state, count)
		return if (count >= required) state else null
	}

	/**
	 * Forgets everything, so the next reading is all discovery.
	 *
	 * Called on leaving the server. We were not watching while away, so we
	 * cannot claim to have seen anything finish.
	 */
	fun reset() {
		lastSeen.clear()
		pending.clear()
		profile = null
	}
}
