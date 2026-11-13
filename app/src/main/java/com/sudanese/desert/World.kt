package com.sudanese.desert

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Desert world: dunes (height field), villages, palms, rocks + collision data. */
class World {
    class Box(val x0: Float, val x1: Float, val z0: Float, val z1: Float)
    class Circle(val x: Float, val z: Float, val r: Float)
    private class House(
        val cx: Float, val cz: Float,
        val w: Float, val d: Float, val h: Float,
        val tone: Float
    )

    companion object {
        const val HALF = 200f
    }

    val boxes = ArrayList<Box>()
    val circles = ArrayList<Circle>()
    private val houses = ArrayList<House>()
    private val bumps = ArrayList<FloatArray>()   // x, z, radius, height
    private val palms = ArrayList<FloatArray>()   // x, z
    private val rocks = ArrayList<FloatArray>()   // x, z, size
    private val rnd = Random(2024)

    private fun rf(a: Float, b: Float) = a + (b - a) * rnd.nextFloat()

    init {
        generate()
    }

    private fun addPalm(x: Float, z: Float) {
        palms.add(floatArrayOf(x, z))
        circles.add(Circle(x, z, 0.7f))
    }

    private fun generate() {
        // ---- villages ----
        val centers = ArrayList<FloatArray>()
        var tries = 0
        while (centers.size < 6 && tries < 500) {
            tries++
            val x = rf(-150f, 150f)
            val z = rf(-150f, 150f)
            if (hypot(x, z) < 45f) continue
            if (centers.any { hypot(it[0] - x, it[1] - z) < 70f }) continue
            centers.add(floatArrayOf(x, z))
        }
        for (c in centers) {
            val count = 5 + rnd.nextInt(4)
            var placed = 0
            var t = 0
            while (placed < count && t < 100) {
                t++
                val ang = rf(0f, 6.2831f)
                val dist = rf(6f, 28f)
                val hx = c[0] + cos(ang) * dist
                val hz = c[1] + sin(ang) * dist
                val w = rf(7f, 11f)
                val d = rf(6f, 9f)
                if (houses.any { hypot(it.cx - hx, it.cz - hz) < 15f }) continue
                houses.add(House(hx, hz, w, d, rf(3.5f, 4.5f), rf(0f, 1f)))
                boxes.add(Box(hx - w / 2, hx + w / 2, hz - d / 2, hz + d / 2))
                placed++
            }
            for (k in 0 until 8) {
                val ang = rf(0f, 6.2831f)
                val dist = rf(8f, 36f)
                val px = c[0] + cos(ang) * dist
                val pz = c[1] + sin(ang) * dist
                if (houses.none { hypot(it.cx - px, it.cz - pz) < 9f }) addPalm(px, pz)
            }
        }

        // ---- dunes (kept away from houses so village ground stays flat) ----
        var made = 0
        tries = 0
        while (made < 40 && tries < 400) {
            tries++
            val r = rf(20f, 45f)
            val x = rf(-190f, 190f)
            val z = rf(-190f, 190f)
            if (houses.any { hypot(it.cx - x, it.cz - z) < r + 10f }) continue
            bumps.add(floatArrayOf(x, z, r, rf(2f, 7f)))
            made++
        }

        // ---- scattered palms and rocks ----
        made = 0
        tries = 0
        while (made < 40 && tries < 300) {
            tries++
            val x = rf(-190f, 190f)
            val z = rf(-190f, 190f)
            if (houses.any { hypot(it.cx - x, it.cz - z) < 12f }) continue
            addPalm(x, z)
            made++
        }
        for (i in 0 until 70) {
            rocks.add(floatArrayOf(rf(-190f, 190f), rf(-190f, 190f), rf(0.8f, 2.2f)))
        }
    }

    fun heightAt(x: Float, z: Float): Float {
        var h = 0f
        for (b in bumps) {
            val dx = x - b[0]
            val dz = z - b[1]
            val d2 = dx * dx + dz * dz
            val r2 = b[2] * b[2]
            if (d2 < r2) {
                val t = 1f - d2 / r2
                h += b[3] * t * t
            }
        }
        return h
    }

    /** Push a circle (p[0]=x, p[1]=z, radius r) out of houses, palms and world bounds. */
    fun resolve(p: FloatArray, r: Float) {
        for (b in boxes) {
            val cx = p[0].coerceIn(b.x0, b.x1)
            val cz = p[1].coerceIn(b.z0, b.z1)
            val dx = p[0] - cx
            val dz = p[1] - cz
            val d2 = dx * dx + dz * dz
            if (d2 < r * r) {
                if (d2 > 1e-6f) {
                    val d = sqrt(d2)
                    p[0] = cx + dx / d * r
                    p[1] = cz + dz / d * r
                } else {
                    val l = p[0] - b.x0
                    val rr = b.x1 - p[0]
                    val t = p[1] - b.z0
                    val bt = b.z1 - p[1]
                    val m = minOf(minOf(l, rr), minOf(t, bt))
                    if (m == l) p[0] = b.x0 - r
                    else if (m == rr) p[0] = b.x1 + r
                    else if (m == t) p[1] = b.z0 - r
                    else p[1] = b.z1 + r
                }
            }
        }
        for (c in circles) {
            val dx = p[0] - c.x
            val dz = p[1] - c.z
            val rad = r + c.r
            val d2 = dx * dx + dz * dz
            if (d2 < rad * rad) {
                val d = sqrt(d2)
                if (d < 1e-4f) {
                    p[0] = c.x + rad
                } else {
                    p[0] = c.x + dx / d * rad
                    p[1] = c.z + dz / d * rad
                }
            }
        }
        val lim = HALF - 2f
        p[0] = p[0].coerceIn(-lim, lim)
        p[1] = p[1].coerceIn(-lim, lim)
    }

    // ------------------------------------------------------------------ mesh

    fun buildMesh(): MeshBuilder {
        val mb = MeshBuilder()
        buildGround(mb)
        for (h in houses) buildHouse(mb, h)
        for (p in palms) buildPalm(mb, p[0], p[1])
        for (r in rocks) {
            mb.cone(r[0], heightAt(r[0], r[1]) - 0.3f, r[1], r[2], r[2] * 0.8f, 5, 0.45f, 0.42f, 0.38f)
        }
        return mb
    }

    private fun buildGround(mb: MeshBuilder) {
        val step = 4f
        val n = (2 * HALF / step).toInt()
        for (i in 0 until n) {
            for (j in 0 until n) {
                val x0 = -HALF + i * step
                val z0 = -HALF + j * step
                val x1 = x0 + step
                val z1 = z0 + step
                groundVertex(mb, x0, z0); groundVertex(mb, x1, z0); groundVertex(mb, x1, z1)
                groundVertex(mb, x0, z0); groundVertex(mb, x1, z1); groundVertex(mb, x0, z1)
            }
        }
    }

    private fun groundVertex(mb: MeshBuilder, x: Float, z: Float) {
        val y = heightAt(x, z)
        val dx = (heightAt(x + 1f, z) - heightAt(x - 1f, z)) / 2f
        val dz = (heightAt(x, z + 1f) - heightAt(x, z - 1f)) / 2f
        val len = sqrt(dx * dx + 1f + dz * dz)
        val k = 0.92f + 0.10f * hash(x, z) + y * 0.012f
        mb.v(x, y, z, -dx / len, 1f / len, -dz / len, 0.86f * k, 0.72f * k, 0.46f * k)
    }

    private fun hash(x: Float, z: Float): Float {
        val s = sin(x * 12.9898f + z * 78.233f) * 43758.5453f
        return s - floor(s)
    }

    private fun buildHouse(mb: MeshBuilder, h: House) {
        val t = h.tone
        val wr = 0.80f + 0.10f * t
        val wg = 0.66f + 0.08f * t
        val wb = 0.45f + 0.06f * t
        mb.box(h.cx, 0f, h.cz, h.w, h.h, h.d, wr, wg, wb)                                  // walls
        mb.box(h.cx, h.h, h.cz, h.w + 0.6f, 0.4f, h.d + 0.6f, wr * 0.75f, wg * 0.70f, wb * 0.65f) // roof
        val fz = h.cz + h.d / 2 + 0.05f
        mb.box(h.cx, 0f, fz, 1.6f, 2.5f, 0.12f, 0.35f, 0.22f, 0.12f)                       // door
        mb.box(h.cx - h.w / 4, 1.6f, fz, 1.2f, 1.0f, 0.12f, 0.15f, 0.20f, 0.28f)           // windows
        mb.box(h.cx + h.w / 4, 1.6f, fz, 1.2f, 1.0f, 0.12f, 0.15f, 0.20f, 0.28f)
    }

    private fun buildPalm(mb: MeshBuilder, x: Float, z: Float) {
        val y = heightAt(x, z)
        mb.box(x, y, z, 0.5f, 6f, 0.5f, 0.45f, 0.30f, 0.15f)
        mb.cone(x, y + 5.6f, z, 3.4f, 1.4f, 7, 0.20f, 0.50f, 0.18f)
        mb.cone(x, y + 6.4f, z, 1.8f, 1.2f, 6, 0.28f, 0.58f, 0.22f)
    }
}
