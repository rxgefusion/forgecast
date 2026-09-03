package dev.fraust.forgecast

import net.fabricmc.loader.api.FabricLoader

/**
 * Gate for the capture tools used to work out Hypixel's formats.
 *
 * These are developer instruments, not player features, so they are not in the
 * settings menu at all - that menu is for players.
 *
 * Availability, in full:
 *
 *  - **Development environment** (launched from Gradle): available, ON by
 *    default. This is a working session; the tools should just be there.
 *  - **Released jar**: NOT available, and the commands are not even registered,
 *    so nothing about them is discoverable or reachable.
 *  - **Released jar plus [LAUNCH_FLAG]**: available. This is the escape hatch
 *    for debugging alongside one specific person - hand them the flag and they
 *    can send a dump back.
 *
 * There is deliberately no username check. Restricting the tools to one account
 * would make it impossible for a user with a bug to produce the dump that would
 * explain it, which is the opposite of what they are for.
 */
object DevTools {

	/** Add `-Dforgecast.devtools=true` to the launch arguments. */
	const val LAUNCH_FLAG = "forgecast.devtools"

	/**
	 * Whether the game was launched from the development environment.
	 *
	 * Read once: it cannot change while the game runs, and this is checked on
	 * a tick.
	 */
	val inDevelopmentEnvironment: Boolean = FabricLoader.getInstance().isDevelopmentEnvironment

	/** Whether the debugging launch flag was supplied. Also read once. */
	val launchFlagSet: Boolean =
		System.getProperty(LAUNCH_FLAG)?.equals("true", ignoreCase = true) == true

	/**
	 * Whether the capture tools may run.
	 *
	 * On in a development build; off in a released one unless the launch flag
	 * was deliberately supplied.
	 */
	val available: Boolean get() = inDevelopmentEnvironment || launchFlagSet

	/** Human-readable reason the tools are unavailable, or null when they are. */
	fun unavailableReason(): String? =
		if (available) null
		else "the capture tools are not enabled in this build"
}
