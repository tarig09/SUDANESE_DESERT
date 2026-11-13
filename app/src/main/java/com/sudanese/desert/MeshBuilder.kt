package com.sudanese.desert

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Builds interleaved vertex data: position(3) + normal(3) + color(3) = 9 floats per vertex. */
class MeshBuilder {
    private var a = FloatArray(1 shl 16)
    private var n = 0

    val vertexCount: Int get() = n / 9

    fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, r: Float, g: Float, b: Float) {
        if (n + 9 > a.size) a = a.copyOf(a.size * 2)
        a[n++] = x; a[n++] = y; a[n++] = z
        a[n++] = nx; a[n++] = ny; a[n++] = nz
        a[n++] = r; a[n++] = g; a[n++] = b
    }

    private fun quad(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float
    ) {
        v(x0, y0, z0, nx, ny, nz, r, g, b)
        v(x1, y1, z1, nx, ny, nz, r, g, b)
        v(x2, y2, z2, nx, ny, nz, r, g, b)
        v(x0, y0, z0, nx, ny, nz, r, g, b)
        v(x2, y2, z2, nx, ny, nz, r, g, b)
        v(x3, y3, z3, nx, ny, nz, r, g, b)
    }

    /** Box centered on (cx, cz), sitting on y = by. No bottom face. */
    fun box(cx: Float, by: Float, cz: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float) {
        val x0 = cx - sx / 2; val x1 = cx + sx / 2
        val y0 = by; val y1 = by + sy
        val z0 = cz - sz / 2; val z1 = cz + sz / 2
        quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0f, 1f, 0f, r, g, b)
        quad(x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1f, 0f, 0f, r, g, b)
        quad(x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, -1f, 0f, 0f, r, g, b)
        quad(x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, 0f, 0f, 1f, r, g, b)
        quad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0f, 0f, -1f, r, g, b)
    }

    /** Open cone (no base), apex up. */
    fun cone(cx: Float, by: Float, cz: Float, radius: Float, height: Float, segments: Int, r: Float, g: Float, b: Float) {
        val slope = sqrt(height * height + radius * radius)
        for (i in 0 until segments) {
            val a0 = (2.0 * PI * i / segments).toFloat()
            val a1 = (2.0 * PI * (i + 1) / segments).toFloat()
            val am = (a0 + a1) / 2f
            val nx = cos(am) * height / slope
            val ny = radius / slope
            val nz = sin(am) * height / slope
            v(cx + cos(a0) * radius, by, cz + sin(a0) * radius, nx, ny, nz, r, g, b)
            v(cx + cos(a1) * radius, by, cz + sin(a1) * radius, nx, ny, nz, r, g, b)
            v(cx, by + height, cz, nx, ny, nz, r, g, b)
        }
    }

    fun toBuffer(): FloatBuffer {
        val fb = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(a, 0, n)
        fb.position(0)
        return fb
    }
}
