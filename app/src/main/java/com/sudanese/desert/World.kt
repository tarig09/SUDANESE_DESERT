package com.sudanese.desert

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Desert world: dunes (height field), villages of multi-story enterable buildings,
 * palms, rocks, plus collision data (solids, walkable surfaces, circles).
 */
class World {
    /** 3D collision box. Blocks horizontal movement depending on the player's feet height. */
    class Solid(val x0: Float, val x1: Float, val z0: Float, val z1: Float, val yb: Float, val yt: Float)

    /** Walkable top surface (floors, roofs, stair steps). */
    class Surf(val x0: Float, val x1: Float, val z0: Float, val z1: Float, val top: Float)

    class Building(val x0: Float, val x1: Float, val z0: Float, val z1: Float) {
        val solids = ArrayList<Solid>()
        val surfs = ArrayList<Surf>()
    }

    class Circle(val x: Float, val z: Float, val r: Float)

    companion object {
        const val HALF = 200f
        const val FH = 3.5f          // floor height
        const val STEP_UP = 0.5f     // max step the player can climb
        const val PLAYER_H = 1.7f
    }

    /** Pickup. kind: 0..3 = weapons (see Guns), 4 = ammo, 5 = medkit, 6 = shield. */
    class Item(val kind: Int, val x: Float, val y: Float, val z: Float) {
        var alive = true
    }

    val buildings = ArrayList<Building>()
    val circles = ArrayList<Circle>()
    val villageCenters = ArrayList<FloatArray>()
    val lootSpots = ArrayList<FloatArray>()   // x, y, z of loot positions inside buildings
    val items = ArrayList<Item>()
    val dummySpawns = ArrayList<FloatArray>()   // x, y, z, yaw
    private val spots = ArrayList<FloatArray>()   // building centers: cx, cz
    private val bumps = ArrayList<FloatArray>()   // x, z, radius, height
    private val palms = ArrayList<FloatArray>()   // x, z
    private val rocks = ArrayList<FloatArray>()   // x, z, size
    private val rnd = Random(2024)
    private val mesh = MeshBuilder()

    private fun rf(a: Float, b: Float) = a + (b - a) * rnd.nextFloat()

    init {
        generate()
    }

    fun nearBuilding(x: Float, z: Float, r: Float): Boolean = spots.any { hypot(it[0] - x, it[1] - z) < r }

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
            val x = rf(-140f, 140f)
            val z = rf(-140f, 140f)
            if (hypot(x, z) < 50f) continue
            if (centers.any { hypot(it[0] - x, it[1] - z) < 80f }) continue
            centers.add(floatArrayOf(x, z))
        }
        villageCenters.addAll(centers)
        for (c in centers) {
            val count = 5 + rnd.nextInt(4)
            var placed = 0
            var t = 0
            while (placed < count && t < 150) {
                t++
                val ang = rf(0f, 6.2831f)
                val dist = rf(8f, 36f)
                val hx = c[0] + cos(ang) * dist
                val hz = c[1] + sin(ang) * dist
                if (spots.any { hypot(it[0] - hx, it[1] - hz) < 19f }) continue
                val w = rf(9f, 13f)
                val d = rf(9.5f, 12.5f)
                val r = rnd.nextFloat()
                val floors = when {
                    r < 0.15f -> 1
                    r < 0.45f -> 2
                    r < 0.75f -> 3
                    r < 0.90f -> 4
                    else -> 5
                }
                spots.add(floatArrayOf(hx, hz, floors.toFloat(), w, d))
                addBuilding(hx, hz, w, d, floors, rf(0f, 1f))
                placed++
            }
            for (k in 0 until 8) {
                val ang = rf(0f, 6.2831f)
                val dist = rf(12f, 42f)
                val px = c[0] + cos(ang) * dist
                val pz = c[1] + sin(ang) * dist
                if (spots.none { hypot(it[0] - px, it[1] - pz) < 13f }) addPalm(px, pz)
            }
        }

        // ---- dunes (kept away from buildings so village ground stays flat) ----
        var made = 0
        tries = 0
        while (made < 40 && tries < 400) {
            tries++
            val r = rf(20f, 45f)
            val x = rf(-190f, 190f)
            val z = rf(-190f, 190f)
            if (spots.any { hypot(it[0] - x, it[1] - z) < r + 12f }) continue
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
            if (spots.any { hypot(it[0] - x, it[1] - z) < 14f }) continue
            addPalm(x, z)
            made++
        }
        for (i in 0 until 70) {
            rocks.add(floatArrayOf(rf(-190f, 190f), rf(-190f, 190f), rf(0.8f, 2.2f)))
        }

        generateLoot()
    }

    private fun pickWeaponKind(): Int {
        val r = rnd.nextFloat()
        return when {
            r < 0.25f -> 0
            r < 0.60f -> 1
            r < 0.75f -> 2
            else -> 3
        }
    }

    private fun generateLoot() {
        // inside buildings: one weapon + one supply item, on random floors (n = roof)
        for (s in spots) {
            val n = s[2].toInt()
            for (i in 0 until 2) {
                val k = rnd.nextInt(n + 1)
                val x = s[0] + rf(-1.5f, 1.5f)
                val z = s[1] + rf(-2.5f, 2.5f)
                val y = k * FH + (if (k == 0) 0.04f else 0f)
                val kind = if (i == 0) pickWeaponKind() else 4 + rnd.nextInt(3)
                items.add(Item(kind, x, y, z))
            }
        }
        // outdoors
        var made = 0
        var tries = 0
        while (made < 30 && tries < 200) {
            tries++
            val x = rf(-185f, 185f)
            val z = rf(-185f, 185f)
            if (spots.any { hypot(it[0] - x, it[1] - z) < 14f }) continue
            val kind = if (rnd.nextFloat() < 0.4f) pickWeaponKind() else 4 + rnd.nextInt(3)
            items.add(Item(kind, x, heightAt(x, z), z))
            made++
        }
        // training dummies near the spawn point
        val near = arrayOf(
            floatArrayOf(10f, -14f), floatArrayOf(-12f, -20f), floatArrayOf(18f, -26f),
            floatArrayOf(0f, -34f), floatArrayOf(-20f, -8f)
        )
        for (p in near) dummySpawns.add(floatArrayOf(p[0], heightAt(p[0], p[1]), p[1], rf(0f, 360f)))
        // dummies inside some buildings
        for ((idx, s) in spots.withIndex()) {
            if (idx % 3 != 0) continue
            val n = s[2].toInt()
            val k = rnd.nextInt(n)
            val y = k * FH + (if (k == 0) 0.04f else 0f)
            dummySpawns.add(floatArrayOf(s[0] + rf(-1f, 1f), y, s[1] + rf(-2f, 2f), rf(0f, 360f)))
        }
    }

    // ---------------------------------------------------------------- building

    /**
     * Multi-story building: n floors + walkable roof with parapet.
     * Doors on north and south walls (ground floor). Stairs alternate between the
     * west column (going south) and the east column (going north), landing on each floor.
     */
    private fun addBuilding(cx: Float, cz: Float, w: Float, d: Float, n: Int, tone: Float) {
        val t = 0.3f
        val x0 = cx - w / 2
        val x1 = cx + w / 2
        val z0 = cz - d / 2
        val z1 = cz + d / 2
        val xi0 = x0 + t
        val xi1 = x1 - t
        val zi0 = z0 + t
        val zi1 = z1 - t
        val b = Building(x0 - 0.2f, x1 + 0.2f, z0 - 0.2f, z1 + 0.2f)
        buildings.add(b)

        // loot on every level (ground, floors, roof), near the middle of the building
        for (lv in 0..n) {
            if (rnd.nextFloat() < 0.7f) {
                lootSpots.add(floatArrayOf(cx + rf(-1.2f, 1.2f), lv * FH, cz + rf(-1.5f, 1.5f)))
            }
        }

        val wr = 0.80f + 0.10f * tone
        val wg = 0.66f + 0.08f * tone
        val wb = 0.45f + 0.06f * tone

        fun solid(
            ax0: Float, ax1: Float, az0: Float, az1: Float, yb: Float, yt: Float,
            r: Float, g: Float, bl: Float, closed: Boolean = false
        ) {
            if (ax1 - ax0 < 0.01f || az1 - az0 < 0.01f) return
            b.solids.add(Solid(ax0, ax1, az0, az1, yb, yt))
            mesh.box((ax0 + ax1) / 2, yb, (az0 + az1) / 2, ax1 - ax0, yt - yb, az1 - az0, r, g, bl, closed)
        }

        fun deco(
            ax0: Float, ax1: Float, az0: Float, az1: Float, yb: Float, yt: Float,
            r: Float, g: Float, bl: Float
        ) {
            mesh.box((ax0 + ax1) / 2, yb, (az0 + az1) / 2, ax1 - ax0, yt - yb, az1 - az0, r, g, bl)
        }

        // interior floor (ground level)
        deco(xi0, xi1, zi0, zi1, 0f, 0.04f, 0.55f, 0.45f, 0.33f)

        // ---- walls ----
        val doorX0 = cx - 0.9f
        val doorX1 = cx + 0.9f
        val doorH = 2.6f
        for (k in 0 until n) {
            val yb = k * FH
            val yt = yb + FH
            solid(x0, x0 + t, z0, z1, yb, yt, wr, wg, wb)       // west
            solid(x1 - t, x1, z0, z1, yb, yt, wr, wg, wb)       // east
            for (side in 0..1) {                                 // 0 = north, 1 = south
                val za = if (side == 0) z0 else z1 - t
                val zb = za + t
                if (k == 0) {
                    solid(xi0, doorX0, za, zb, yb, yt, wr, wg, wb)
                    solid(doorX1, xi1, za, zb, yb, yt, wr, wg, wb)
                    solid(doorX0, doorX1, za, zb, yb + doorH, yt, wr, wg, wb)   // lintel
                } else {
                    solid(xi0, xi1, za, zb, yb, yt, wr, wg, wb)
                }
                val wz = if (side == 0) z0 - 0.04f else z1 - 0.02f
                val xs = if (k == 0) floatArrayOf(cx - w / 4, cx + w / 4)
                else floatArrayOf(cx - w / 3, cx, cx + w / 3)
                for (xw in xs) {
                    deco(xw - 0.6f, xw + 0.6f, wz, wz + 0.06f, yb + 1.3f, yb + 2.3f, 0.15f, 0.20f, 0.28f)
                }
            }
        }

        // ---- roof parapet ----
        val ry = n * FH
        val ph = 0.9f
        solid(x0, x0 + t, z0, z1, ry, ry + ph, wr * 0.9f, wg * 0.9f, wb * 0.9f)
        solid(x1 - t, x1, z0, z1, ry, ry + ph, wr * 0.9f, wg * 0.9f, wb * 0.9f)
        solid(xi0, xi1, z0, z0 + t, ry, ry + ph, wr * 0.9f, wg * 0.9f, wb * 0.9f)
        solid(xi0, xi1, z1 - t, z1, ry, ry + ph, wr * 0.9f, wg * 0.9f, wb * 0.9f)

        // ---- floor slabs (with stairwell holes) ----
        val sw = 1.6f        // stair width
        val land = 1.5f      // landing before the first step
        val ns = 10          // steps per flight
        val sh = FH / ns     // step height
        val tr = 0.45f       // step tread
        val len = ns * tr    // flight length

        fun slab(ax0: Float, ax1: Float, az0: Float, az1: Float, top: Float) {
            if (ax1 - ax0 < 0.01f || az1 - az0 < 0.01f) return
            solid(ax0, ax1, az0, az1, top - 0.3f, top, wr * 0.8f, wg * 0.78f, wb * 0.75f, true)
            b.surfs.add(Surf(ax0, ax1, az0, az1, top))
        }

        for (j in 1..n) {
            val top = j * FH
            val f = j - 1                       // flight that passes through this slab
            if (f % 2 == 0) {                   // west column, ascending south
                val hz0 = zi0 + land
                val hz1 = hz0 + len
                val cxm = xi0 + sw
                slab(xi0, cxm, zi0, hz0, top)
                slab(cxm, xi1, zi0, zi1, top)
                slab(xi0, cxm, hz1, zi1, top)
            } else {                            // east column, ascending north
                val hz1 = zi1 - land
                val hz0 = hz1 - len
                val cxm = xi1 - sw
                slab(cxm, xi1, zi0, hz0, top)
                slab(xi0, cxm, zi0, zi1, top)
                slab(cxm, xi1, hz1, zi1, top)
            }
        }

        // ---- stairs ----
        for (i in 0 until n) {
            val base = i * FH
            for (s in 0 until ns) {
                val top = base + (s + 1) * sh
                if (i % 2 == 0) {
                    val za = zi0 + land + s * tr
                    solid(xi0, xi0 + sw, za, za + tr, base, top, 0.62f, 0.52f, 0.38f)
                    b.surfs.add(Surf(xi0, xi0 + sw, za, za + tr, top))
                } else {
                    val zb = zi1 - land - s * tr
                    solid(xi1 - sw, xi1, zb - tr, zb, base, top, 0.62f, 0.52f, 0.38f)
                    b.surfs.add(Surf(xi1 - sw, xi1, zb - tr, zb, top))
                }
            }
        }
    }

    // ---------------------------------------------------------------- physics

    fun heightAt(x: Float, z: Float): Float {
        var h = 0f
        for (bm in bumps) {
            val dx = x - bm[0]
            val dz = z - bm[1]
            val d2 = dx * dx + dz * dz
            val r2 = bm[2] * bm[2]
            if (d2 < r2) {
                val t = 1f - d2 / r2
                h += bm[3] * t * t
            }
        }
        return h
    }

    /** Distance along the ray to the first static obstacle (ground, walls, floors, palm trunks), or maxD. */
    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxD: Float): Float {
        var t = 0.3f
        val step = 0.25f
        while (t < maxD) {
            if (bulletBlocked(ox + dx * t, oy + dy * t, oz + dz * t)) return t
            t += step
        }
        return maxD
    }

    private fun bulletBlocked(x: Float, y: Float, z: Float): Boolean {
        val gh = heightAt(x, z)
        if (y < gh) return true
        if (y > 80f) return false
        for (b in buildings) {
            if (x < b.x0 || x > b.x1 || z < b.z0 || z > b.z1) continue
            for (s in b.solids) {
                if (x >= s.x0 && x <= s.x1 && z >= s.z0 && z <= s.z1 && y >= s.yb && y <= s.yt) return true
            }
        }
        for (c in circles) {
            val dx = x - c.x
            val dz = z - c.z
            if (abs(dx) < 0.3f && abs(dz) < 0.3f && y < gh + 6.2f) return true
        }
        return false
    }

    /** Highest walkable surface at (x, z) that the player at feetY can stand on / step onto. */
    fun surfaceY(x: Float, z: Float, feetY: Float): Float {
        var best = heightAt(x, z)
        for (b in buildings) {
            if (x < b.x0 || x > b.x1 || z < b.z0 || z > b.z1) continue
            for (s in b.surfs) {
                if (s.top > best && s.top <= feetY + STEP_UP &&
                    x >= s.x0 && x <= s.x1 && z >= s.z0 && z <= s.z1
                ) best = s.top
            }
        }
        return best
    }

    /** Push a circle (p[0]=x, p[1]=z, radius r) out of solids, palms and world bounds. */
    fun resolve(p: FloatArray, r: Float, feetY: Float) {
        for (b in buildings) {
            if (p[0] < b.x0 - r || p[0] > b.x1 + r || p[1] < b.z0 - r || p[1] > b.z1 + r) continue
            for (s in b.solids) {
                if (feetY + STEP_UP >= s.yt) continue
                if (feetY + PLAYER_H <= s.yb) continue
                pushOut(p, r, s.x0, s.x1, s.z0, s.z1)
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

    private fun pushOut(p: FloatArray, r: Float, bx0: Float, bx1: Float, bz0: Float, bz1: Float) {
        val cx = p[0].coerceIn(bx0, bx1)
        val cz = p[1].coerceIn(bz0, bz1)
        val dx = p[0] - cx
        val dz = p[1] - cz
        val d2 = dx * dx + dz * dz
        if (d2 >= r * r) return
        if (d2 > 1e-6f) {
            val d = sqrt(d2)
            p[0] = cx + dx / d * r
            p[1] = cz + dz / d * r
        } else {
            val l = p[0] - bx0
            val rr = bx1 - p[0]
            val t = p[1] - bz0
            val bt = bz1 - p[1]
            val m = minOf(minOf(l, rr), minOf(t, bt))
            if (m == l) p[0] = bx0 - r
            else if (m == rr) p[0] = bx1 + r
            else if (m == t) p[1] = bz0 - r
            else p[1] = bz1 + r
        }
    }

    /** True if a camera at this point would be inside the ground or a building solid. */
    fun cameraBlocked(x: Float, y: Float, z: Float): Boolean {
        if (y < heightAt(x, z) + 0.25f) return true
        for (b in buildings) {
            if (x < b.x0 || x > b.x1 || z < b.z0 || z > b.z1) continue
            for (s in b.solids) {
                if (x >= s.x0 && x <= s.x1 && z >= s.z0 && z <= s.z1 && y >= s.yb && y <= s.yt) return true
            }
        }
        return false
    }

    /** Distance along the ray to the first static hit (ground, building solid, palm) or maxDist. */
    fun rayStatic(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float): Float {
        var t = 0.3f
        while (t < maxDist) {
            val x = ox + dx * t
            val y = oy + dy * t
            val z = oz + dz * t
            if (x < -HALF || x > HALF || z < -HALF || z > HALF) return t
            if (y < heightAt(x, z)) return t
            for (b in buildings) {
                if (x < b.x0 || x > b.x1 || z < b.z0 || z > b.z1) continue
                for (s in b.solids) {
                    if (x >= s.x0 && x <= s.x1 && z >= s.z0 && z <= s.z1 && y >= s.yb && y <= s.yt) return t
                }
            }
            for (c in circles) {
                val ddx = x - c.x
                val ddz = z - c.z
                if (ddx * ddx + ddz * ddz < 0.2f && y < heightAt(c.x, c.z) + 6f) return t
            }
            t += 0.25f
        }
        return maxDist
    }

    // ------------------------------------------------------------------ mesh

    fun buildMesh(): MeshBuilder {
        buildGround(mesh)
        for (p in palms) buildPalm(mesh, p[0], p[1])
        for (r in rocks) {
            mesh.cone(r[0], heightAt(r[0], r[1]) - 0.3f, r[1], r[2], r[2] * 0.8f, 5, 0.45f, 0.42f, 0.38f)
        }
        return mesh
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

    private fun buildPalm(mb: MeshBuilder, x: Float, z: Float) {
        val y = heightAt(x, z)
        mb.box(x, y, z, 0.5f, 6f, 0.5f, 0.45f, 0.30f, 0.15f)
        mb.cone(x, y + 5.6f, z, 3.4f, 1.4f, 7, 0.20f, 0.50f, 0.18f)
        mb.cone(x, y + 6.4f, z, 1.8f, 1.2f, 6, 0.28f, 0.58f, 0.22f)
    }
}
