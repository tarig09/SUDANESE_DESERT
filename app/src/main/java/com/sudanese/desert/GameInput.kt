package com.sudanese.desert

/** Shared state between the UI thread (touch / HUD) and the GL thread (game loop). */
class GameInput {
    // ---- controls (UI -> game)
    @Volatile var moveX = 0f   // right = +
    @Volatile var moveY = 0f   // forward = +
    @Volatile var jump = false
    @Volatile var toggleCam = false
    @Volatile var fire = false       // fire button held
    @Volatile var fireEdge = false   // fire button just pressed
    @Volatile var reloadReq = false
    @Volatile var switchReq = false

    // ---- HUD (game -> UI)
    @Volatile var hp = 100
    @Volatile var armor = 0
    @Volatile var mag = 0
    @Volatile var reserve = 0
    @Volatile var weaponName = ""
    @Volatile var reloading = false
    @Volatile var reloadFrac = 0f
    @Volatile var hitMark = 0f
    @Volatile var hurt = 0f
    @Volatile var msg = ""
    @Volatile var msgTime = 0f
    @Volatile var kills = 0

    private var lx = 0f
    private var ly = 0f

    @Synchronized
    fun addLook(dx: Float, dy: Float) {
        lx += dx
        ly += dy
    }

    @Synchronized
    fun takeLook(): FloatArray {
        val r = floatArrayOf(lx, ly)
        lx = 0f
        ly = 0f
        return r
    }
}
