package com.sudanese.desert

/** Shared state between the UI thread (touch) and the GL thread (game loop). */
class GameInput {
    @Volatile var moveX = 0f   // right = +
    @Volatile var moveY = 0f   // forward = +
    @Volatile var jump = false

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
