package dev.fraust.forgecast

/**
 * Holds a classification back until it has proved itself.
 *
 * The tab list is built up over several packets, so for a moment after a warp
 * or a server transfer it can be genuinely half-built: the Profile row has
 * arrived but the Forges rows have not. Classified in that instant it looks
 * exactly like a switched-off widget, and the warning that follows is wrong and
 * never corrects itself - COMPLETE is silent by design, so nothing takes it
 * back.
 *
 * The distinction this exploits is simple: **a real problem persists, a loading
 * gap does not.** A classification must therefore hold for
 * [requiredConsecutive] readings before it is allowed to speak.
 *
 * There is deliberately no retraction message. Not firing wrongly is the fix;
 * an apology after a false alarm is more noise, not less.
 */
class AdviceStabiliser(
	private val requiredConsecutive: Int = DEFAULT_REQUIRED,
) {

	companion object {
		/**
		 * Readings a classification must hold for, at one reading per second.
		 *
		 * Three is the smallest value that outlasts an ordinary tab-list rebuild
		 * after a warp, which takes under two seconds. Longer transitions - a
		 * server transfer, a world load - are handled by suppressing
		 * classification outright for a moment, rather than by inflating this
		 * number: the two mechanisms fail differently, and stacking them is
		 * better than stretching one.
		 *
		 * The cost of waiting is only that a genuine warning arrives three
		 * seconds later, which nothing depends on.
		 */
		const val DEFAULT_REQUIRED = 3
	}

	private var candidate: ForgeDataCase? = null
	private var streak = 0

	/**
	 * Offers one reading.
	 *
	 * @return the case once it has held long enough, otherwise null.
	 */
	fun offer(case: ForgeDataCase): ForgeDataCase? {
		if (case == candidate) {
			streak++
		} else {
			candidate = case
			streak = 1
		}
		return if (streak >= requiredConsecutive) case else null
	}

	/** How many consecutive readings the current candidate has held for. */
	val currentStreak: Int get() = streak

	/**
	 * Forgets the run in progress.
	 *
	 * Called when leaving SkyBlock or on a world change, so that whatever is
	 * observed on the way back has to earn its own streak rather than
	 * inheriting one from before the transition.
	 */
	fun reset() {
		candidate = null
		streak = 0
	}
}
