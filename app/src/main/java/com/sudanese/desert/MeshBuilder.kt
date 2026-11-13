package com.sudanese.desert

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
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

    /** Box centered on (cx, cz), sitting on y = by. Bottom face only if closed. */
    fun box(cx: Float, by: Float, cz: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float, closed: Boolean = false) {
        val x0 = cx - sx / 2; val x1 = cx + sx / 2
        val y0 = by; val y1 = by + sy
        val z0 = cz - sz / 2; val z1 = cz + sz / 2
        quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0f, 1f, 0f, r, g, b)
        quad(x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1f, 0f, 0f, r, g, b)
        quad(x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, -1f, 0f, 0f, r, g, b)
        quad(x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, 0f, 0f, 1f, r, g, b)
        quad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0f, 0f, -1f, r, g, b)
        if (closed) quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0f, -1f, 0f, r, g, b)
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

    fun clear() {
        n = 0
    }

    /** Copies vertices into a reusable buffer (truncating if too big). Returns vertex count. */
    fun fill(fb: FloatBuffer): Int {
        fb.clear()
        val cap = (fb.capacity() / 9) * 9
        val m = if (n > cap) cap else n
        fb.put(a, 0, m)
        fb.position(0)
        return m / 9
    }

    /** Thin camera-agnostic ribbon (two crossed quads) between two points. */
    fun ribbon(
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        h: Float, r: Float, g: Float, b: Float
    ) {
        var dx = x1 - x0
        var dy = y1 - y0
        var dz = z1 - z0
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-4f) return
        dx /= len; dy /= len; dz /= len
        val rx: Float
        val ry: Float
        val rz: Float
        if (abs(dy) < 0.9f) { rx = 0f; ry = 1f; rz = 0f } else { rx = 1f; ry = 0f; rz = 0f }
        var sx = dy * rz - dz * ry
        var sy = dz * rx - dx * rz
        var sz = dx * ry - dy * rx
        val sl = sqrt(sx * sx + sy * sy + sz * sz)
        sx /= sl; sy /= sl; sz /= sl
        val ux = dy * sz - dz * sy
        val uy = dz * sx - dx * sz
        val uz = dx * sy - dy * sx
        quad(
            x0 + sx * h, y0 + sy * h, z0 + sz * h, x0 - sx * h, y0 - sy * h, z0 - sz * h,
            x1 - sx * h, y1 - sy * h, z1 - sz * h, x1 + sx * h, y1 + sy * h, z1 + sz * h,
            ux, uy, uz, r, g, b
        )
        quad(
            x0 + ux * h, y0 + uy * h, z0 + uz * h, x0 - ux * h, y0 - uy * h, z0 - uz * h,
            x1 - ux * h, y1 - uy * h, z1 - uz * h, x1 + ux * h, y1 + uy * h, z1 + uz * h,
            sx, sy, sz, r, g, b
        )
    }

    fun toBuffer(): FloatBuffer {
        val fb = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(a, 0, n)
        fb.position(0)
        return fb
    }
}
