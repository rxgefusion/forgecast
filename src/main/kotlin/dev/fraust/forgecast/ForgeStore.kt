package dev.fraust.forgecast

/**
 * The one [ForgeMemory] the whole mod shares.
 *
 * It previously lived inside [ForgeHud], which was fine while the panel was the
 * only thing that wrote to it. Now the Forge-screen reader writes to it too and
 * /forgecast status reads it, so owning it from the HUD would have meant the
 * display class holding state two other things depend on.
 */
object ForgeStore {
	val memory = ForgeMemory()
}
