package dev.fraust.forgecast

/** A slot that has just finished, ready to be announced once. */
data class ForgeCompletion(val slot: Int, val itemName: String?)

/**
 * Notices when a forge slot finishes, and says so exactly once.
 *
 * Reads the arbitrated result; it does not parse or decide anything itself.
 *
 * DISCOVERY IS NOT COMPLETION. Logging in to find slot 3 already Ready is not
 * something that happened while we were watching, and announcing it would be a
 * notification about the past. The distinction is not a timer or a heuristic:
 * a completion is a slot we saw RUNNING and then saw READY. A slot whose first
 * confirmed sighting is READY simply becomes the baseline and announces
 * nothing. There is no state in which we can be fooled about this, because the
 * evidence for a transition is two sightings, and at login we have one.
 *
 * ONLY CONFIRMED SIGHTINGS COUNT. A belief that reached READY by arithmetic -
 * a GUI reading that has passed its predicted finish with nothing able to
 * corroborate it - is not a sighting, and neither fires the alert nor updates
 * the baseline. Leaving the baseline alone is the important half: when the slot
 * does become visible again, the transition is still there to be found, and the
 * alert fires then. A guess defers the alert; it never cancels or triggers one.
 */
class CompletionWatcher {

	private var profile: String? = null

	/** The last state we actually SAW for each slot. Never a guess. */
	private val lastSeen = mutableMapOf<Int, ForgeSlotState>()

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
			lastSeen.clear()
			profile = profileName
			recordBaseline(beliefs)
			return emptyList()
		}

		val finished = mutableListOf<ForgeCompletion>()

		for (belief in beliefs) {
			// Not being looked at: no sighting, so no transition and no baseline
			// change. The alert waits rather than guessing.
			if (!belief.observed) continue

			val previous = lastSeen.put(belief.slot, belief.state)

			// First confirmed sighting of this slot. Baseline only.
			if (previous == null) continue

			if (previous == ForgeSlotState.IN_PROGRESS && belief.state == ForgeSlotState.READY) {
				finished += ForgeCompletion(belief.slot, belief.itemName)
			}
		}

		// Firing once needs no extra bookkeeping: the baseline is now READY, and
		// READY to READY is not a transition. It can only fire again after the
		// slot is seen running again, which means a new item was queued.
		return finished
	}

	/** Records what is visible without announcing any of it. */
	private fun recordBaseline(beliefs: List<ForgeBelief>) {
		for (belief in beliefs) {
			if (belief.observed) lastSeen[belief.slot] = belief.state
		}
	}

	/**
	 * Forgets everything, so the next reading is all discovery.
	 *
	 * Called on leaving the server. We were not watching while away, so we
	 * cannot claim to have seen anything finish.
	 */
	fun reset() {
		lastSeen.clear()
		profile = null
	}
}
