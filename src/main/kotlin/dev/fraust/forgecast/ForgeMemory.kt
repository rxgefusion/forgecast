package dev.fraust.forgecast

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** Where a displayed slot's information came from. */
enum class SlotSource {
	/** Read from the tab list just now. */
	LIVE,

	/** Not visible now; this is what we last saw, aged forward. */
	REMEMBERED,

	/** Not visible and nothing worth remembering. */
	NONE,
}

/**
 * A slot as it should be shown: either live, or recalled from memory.
 *
 * [remaining] for a REMEMBERED slot is recomputed from the stored absolute
 * finish time every time it is read. Nothing decrements a stored duration.
 */
data class MergedSlot(
	val slot: Int,
	val source: SlotSource,
	val state: ForgeSlotState,
	val itemName: String? = null,
	val remaining: Duration? = null,
	/** When a REMEMBERED slot was last actually seen. Null for LIVE and NONE. */
	val observedAt: Instant? = null,
)

/**
 * Finds which SkyBlock profile the tab list belongs to.
 *
 * Located by text, never by row position - the same lesson the Forges header
 * taught. Does not touch [ForgeParser].
 */
object ProfileReader {

	private val PROFILE_ROW = Regex("""^Profile:\s*(.+)$""")

	fun profileOf(rows: List<TabRow>): String? =
		rows.asSequence()
			.filter { it.profileName.startsWith("!") }
			.map { ForgeParser.stripFormatting(it.rawText).trim() }
			.mapNotNull { PROFILE_ROW.find(it)?.groupValues?.get(1)?.trim() }
			.firstOrNull()
			?.takeIf { it.isNotEmpty() }
}

/**
 * Remembers forge slots that are currently truncated out of the tab list.
 *
 * In memory only - this resets when the game closes, by design for now.
 *
 * The stored value is an ABSOLUTE finish time worked out at the moment of
 * observation. Reading it back subtracts the current time. A stored duration
 * that got decremented would drift with every missed refresh; an absolute
 * instant cannot.
 */
class ForgeMemory(
	private val maxAge: Duration = DEFAULT_MAX_AGE,
) {

	companion object {
		/**
		 * How long a memory stays usable.
		 *
		 * A forge can only be altered at the Forge itself, in the Dwarven Mines,
		 * which is exactly where all seven slots render - so going to change one
		 * refreshes our reading anyway. That makes memories safer than they look
		 * and argues for a generous window. Against that: this cache dies on
		 * restart, so anything beyond a session never fires, and past a few hours
		 * "remembered" starts describing a world that may have moved on.
		 */
		val DEFAULT_MAX_AGE: Duration = 6.hours
	}

	private data class Remembered(
		val state: ForgeSlotState,
		val itemName: String?,
		/** Absolute finish time. Null for EMPTY and for already-READY slots. */
		val finishAt: Instant?,
		val observedAt: Instant,
	)

	private var profile: String? = null
	private val remembered = mutableMapOf<Int, Remembered>()

	/** The profile the current memories belong to. */
	val knownProfile: String? get() = profile

	/** How many slots are currently held in memory. For tests and diagnostics. */
	val size: Int get() = remembered.size

	/**
	 * Merges a fresh snapshot into memory and returns what should be displayed.
	 *
	 * A slot visible now overwrites whatever was remembered. A slot that did not
	 * render keeps its remembered value.
	 *
	 * @param profileName the SkyBlock profile, or null if it could not be read.
	 */
	fun update(snapshot: ForgeSnapshot, profileName: String?, now: Instant): List<MergedSlot> {
		// Without a profile we cannot tell whose forge this is. Neither read nor
		// write the cache: showing another profile's slots would be worse than
		// showing nothing.
		if (profileName == null) {
			return snapshot.slots.map { liveOnly(it) }
		}

		if (profileName != profile) {
			// Different profile, different forge. Nothing carries over.
			remembered.clear()
			profile = profileName
		}

		for (slot in snapshot.slots) {
			if (isUnderstood(slot)) {
				remembered[slot.slot] = rememberedFrom(slot, now)
			}
		}

		expire(now)

		return snapshot.slots.map { display(it, now) }
	}

	/** Forgets everything, keeping no profile. */
	fun clear() {
		remembered.clear()
		profile = null
	}

	/**
	 * A slot whose state we actually know. A slot that rendered but whose text we
	 * could not recognise is deliberately excluded - remembering a state we never
	 * understood would be inventing data.
	 */
	private fun isUnderstood(slot: ForgeSlot): Boolean = when (slot.state) {
		ForgeSlotState.IN_PROGRESS, ForgeSlotState.READY, ForgeSlotState.EMPTY -> true
		ForgeSlotState.UNKNOWN -> false
	}

	private fun rememberedFrom(slot: ForgeSlot, now: Instant): Remembered = Remembered(
		state = slot.state,
		itemName = slot.itemName,
		// Convert to an absolute instant here, once, at observation.
		finishAt = slot.remaining?.let { now.plusMillis(it.inWholeMilliseconds) },
		observedAt = now,
	)

	private fun expire(now: Instant) {
		remembered.entries.removeIf { age(it.value.observedAt, now) > maxAge }
	}

	private fun age(observedAt: Instant, now: Instant): Duration =
		(now.toEpochMilli() - observedAt.toEpochMilli()).milliseconds

	private fun liveOnly(slot: ForgeSlot): MergedSlot =
		if (isUnderstood(slot) || slot.rawText != null) {
			MergedSlot(slot.slot, SlotSource.LIVE, slot.state, slot.itemName, slot.remaining)
		} else {
			MergedSlot(slot.slot, SlotSource.NONE, ForgeSlotState.UNKNOWN)
		}

	private fun display(slot: ForgeSlot, now: Instant): MergedSlot {
		// Anything the server rendered wins, including text we failed to parse:
		// that is still current information.
		if (isUnderstood(slot) || slot.rawText != null) {
			return MergedSlot(slot.slot, SlotSource.LIVE, slot.state, slot.itemName, slot.remaining)
		}

		val memory = remembered[slot.slot]
			?: return MergedSlot(slot.slot, SlotSource.NONE, ForgeSlotState.UNKNOWN)

		if (age(memory.observedAt, now) > maxAge) {
			return MergedSlot(slot.slot, SlotSource.NONE, ForgeSlotState.UNKNOWN)
		}

		return when (memory.state) {
			ForgeSlotState.EMPTY -> MergedSlot(
				slot.slot, SlotSource.REMEMBERED, ForgeSlotState.EMPTY,
				observedAt = memory.observedAt,
			)

			ForgeSlotState.READY -> MergedSlot(
				slot.slot, SlotSource.REMEMBERED, ForgeSlotState.READY,
				itemName = memory.itemName, observedAt = memory.observedAt,
			)

			ForgeSlotState.IN_PROGRESS -> {
				val finishAt = memory.finishAt
					?: return MergedSlot(slot.slot, SlotSource.NONE, ForgeSlotState.UNKNOWN)
				val left = (finishAt.toEpochMilli() - now.toEpochMilli()).milliseconds
				if (left <= Duration.ZERO) {
					// It would have finished while we were not looking.
					MergedSlot(
						slot.slot, SlotSource.REMEMBERED, ForgeSlotState.READY,
						itemName = memory.itemName, observedAt = memory.observedAt,
					)
				} else {
					MergedSlot(
						slot.slot, SlotSource.REMEMBERED, ForgeSlotState.IN_PROGRESS,
						itemName = memory.itemName, remaining = left,
						observedAt = memory.observedAt,
					)
				}
			}

			ForgeSlotState.UNKNOWN -> MergedSlot(slot.slot, SlotSource.NONE, ForgeSlotState.UNKNOWN)
		}
	}
}
