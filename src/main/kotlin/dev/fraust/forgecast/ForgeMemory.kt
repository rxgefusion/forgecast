package dev.fraust.forgecast

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * Which sensor a stored reading came from.
 *
 * Two sensors watch the same seven slots and they are not equivalent:
 *
 *  - [WIDGET] is available constantly and anywhere, but rounds to one unit
 *    ("6h") and truncates when the tab list runs out of rows.
 *  - [GUI] is exact to the second and always shows all seven, but can only be
 *    read while the Forge screen is open.
 *
 * Recorded so [ForgeArbiter] can decide what to believe when the two disagree.
 * It cannot make that judgement without knowing which sensor said what.
 */
enum class ObservationSource { WIDGET, GUI }

/** A stored reading, exposed for inspection by /forgecast status. */
data class StoredObservation(
	val slot: Int,
	val source: ObservationSource,
	val state: ForgeSlotState,
	val itemName: String? = null,
	/** Absolute finish time. Null for states that are not counting down. */
	val finishAt: java.time.Instant? = null,
	val observedAt: java.time.Instant,
)

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

	/** Widget readings. Drives the display, exactly as before. */
	private val remembered = mutableMapOf<Int, Remembered>()

	/**
	 * GUI readings, kept SEPARATELY from the widget ones.
	 *
	 * Kept apart rather than merged so [ForgeArbiter] can still tell which sensor
	 * said what. Merging on write would destroy exactly the information the
	 * decision needs.
	 *
	 * THESE DO NOT EXPIRE ON A TIMER. They are removed when the widget disproves
	 * them, and not otherwise. A forge slot can only be changed at the Forge
	 * screen, and opening that screen re-reads it within a second, so within a
	 * session a belief cannot go stale unnoticed. The widget's six-hour limit
	 * exists because a rounded reading decays; an absolute instant does not.
	 *
	 * This reasoning depends on memory dying with the session. If these are ever
	 * persisted to disk, a belief can outlive the session that checked it and a
	 * time bound becomes necessary.
	 */
	private val guiObservations = mutableMapOf<Int, Remembered>()

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

	/**
	 * Records a reading of the open Forge screen.
	 *
	 * Stored beside the widget readings rather than over them; [believe] decides
	 * between the two at read time.
	 *
	 * @param profileName taken from the tab list, because the Forge screen does
	 *   not say which profile it belongs to. Null means we cannot tell, and
	 *   nothing is stored - the same rule the widget path already follows, since
	 *   attributing one profile's forge to another would be worse than storing
	 *   nothing.
	 */
	fun recordGuiObservation(
		snapshot: GuiForgeSnapshot,
		profileName: String?,
		now: java.time.Instant,
	) {
		if (profileName == null) return

		if (profileName != profile) {
			// Different profile, different forge. Both stores start again.
			remembered.clear()
			guiObservations.clear()
			profile = profileName
		}

		for (slot in snapshot.slots) {
			// A slot the GUI parser itself flagged as suspect is not worth
			// storing: it already said it does not trust the reading.
			if (!isUnderstood(slot)) continue

			guiObservations[slot.slot] = Remembered(
				state = slot.state,
				itemName = slot.itemName,
				// Absolute at the moment of observation. A duration goes stale
				// immediately; an instant never does.
				finishAt = slot.remaining?.let { now.plusMillis(it.inWholeMilliseconds) },
				observedAt = slot.observedAt ?: now,
			)
		}
	}

	/**
	 * What the mod actually believes, after weighing the two sensors.
	 *
	 * This is what the display should read. [update] still returns the widget's
	 * own view underneath, because arbitration needs to know what the widget can
	 * currently see in order to decide whether it disproves anything.
	 *
	 * A GUI belief the widget contradicts is DROPPED here rather than merely
	 * ignored. Keeping it would mean re-deciding the same contradiction every
	 * second, and worse, resurrecting the stale belief the moment the slot
	 * truncated out of view again.
	 */
	fun believe(snapshot: ForgeSnapshot, profileName: String?, now: Instant): List<ForgeBelief> {
		val widgetView = update(snapshot, profileName, now)

		// Without a profile we cannot tell whose forge the stored readings belong
		// to, so they are not applied - the same rule update() already follows.
		if (profileName == null) {
			return ForgeArbiter.arbitrate(widgetView, emptyMap(), now).beliefs
		}

		val result = ForgeArbiter.arbitrate(widgetView, guiBySlot(), now)
		for (slot in result.invalidatedGuiSlots) guiObservations.remove(slot)
		return result.beliefs
	}

	private fun guiBySlot(): Map<Int, StoredObservation> =
		guiObservations.mapValues { (slot, value) ->
			StoredObservation(
				slot, ObservationSource.GUI, value.state,
				value.itemName, value.finishAt, value.observedAt,
			)
		}

	/**
	 * Everything currently stored, from both sensors.
	 *
	 * Exists so /forgecast status can show what the mod knows without any
	 * display change - which is how this step gets verified at all.
	 */
	fun storedObservations(): List<StoredObservation> {
		val all = mutableListOf<StoredObservation>()
		for ((slot, value) in remembered) {
			all += StoredObservation(
				slot, ObservationSource.WIDGET, value.state,
				value.itemName, value.finishAt, value.observedAt,
			)
		}
		for ((slot, value) in guiObservations) {
			all += StoredObservation(
				slot, ObservationSource.GUI, value.state,
				value.itemName, value.finishAt, value.observedAt,
			)
		}
		return all.sortedWith(compareBy({ it.slot }, { it.source }))
	}

	/** How many GUI readings are held. For tests and diagnostics. */
	val guiSize: Int get() = guiObservations.size

	/** Forgets everything, keeping no profile. */
	fun clear() {
		remembered.clear()
		guiObservations.clear()
		profile = null
	}

	/**
	 * A slot whose state we actually know. A slot that rendered but whose text we
	 * could not recognise is deliberately excluded - remembering a state we never
	 * understood would be inventing data.
	 */
	private fun isUnderstood(slot: ForgeSlot): Boolean = when (slot.state) {
		ForgeSlotState.IN_PROGRESS, ForgeSlotState.READY,
		ForgeSlotState.EMPTY, ForgeSlotState.LOCKED,
		-> true
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

			// A locked slot cannot quietly become unlocked while out of sight -
			// that needs a Heart of the Mountain purchase, which we would see.
			ForgeSlotState.LOCKED -> MergedSlot(
				slot.slot, SlotSource.REMEMBERED, ForgeSlotState.LOCKED,
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
