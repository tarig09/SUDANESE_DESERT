package com.sudanese.desert

class WeaponDef(
    val name: String,
    val dmg: Float,        // damage per pellet
    val interval: Float,   // seconds between shots
    val mag: Int,          // magazine size
    val auto: Boolean,     // hold to keep firing
    val pellets: Int,
    val spread: Float,     // random cone (direction offset)
    val range: Float,      // meters
    val reload: Float      // seconds
)

object Weapons {
    // index order is shared with sounds, gun meshes and pickup types: 0 pistol, 1 rifle, 2 shotgun, 3 sniper
    val list = arrayOf(
        WeaponDef("مسدس", 18f, 0.30f, 12, false, 1, 0.012f, 120f, 1.4f),
        WeaponDef("بندقية آلية", 13f, 0.10f, 30, true, 1, 0.022f, 180f, 2.0f),
        WeaponDef("شوتغن", 11f, 0.85f, 6, false, 8, 0.070f, 40f, 2.4f),
        WeaponDef("قناصة", 85f, 1.20f, 5, false, 1, 0.002f, 320f, 2.8f)
    )
}
