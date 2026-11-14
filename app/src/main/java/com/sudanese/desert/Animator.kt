package com.sudanese.desert

import android.opengl.Matrix
import kotlin.math.sqrt

/** Evaluates a skeleton pose (node TRS + clip sample) and produces GPU joint matrices. */
class Animator(private val m: ModelData) {
    companion object {
        const val MAX_JOINTS = 96
    }

    private val n = m.nodes.size
    private val lt = FloatArray(n * 3)
    private val lr = FloatArray(n * 4)
    private val ls = FloatArray(n * 3)
    private val global = FloatArray(n * 16)
    val joints = FloatArray(MAX_JOINTS * 16)
    private val order = IntArray(n)
    private val local = FloatArray(16)
    private val tmp = FloatArray(16)
    private val inv = FloatArray(16)
    private val t3 = FloatArray(3)
    private val r4 = FloatArray(4)
    private val s3 = FloatArray(3)

    init {
        // parents-before-children order
        val children = Array(n) { ArrayList<Int>() }
        val roots = ArrayList<Int>()
        for (i in 0 until n) {
            val p = m.nodes[i].parent
            if (p < 0 || p >= n) roots.add(i) else children[p].add(i)
        }
        var k = 0
        val stack = ArrayList<Int>()
        for (r in roots) {
            stack.add(r)
            while (stack.isNotEmpty()) {
                val c = stack.removeAt(stack.size - 1)
                order[k++] = c
                for (ch in children[c]) stack.add(ch)
            }
        }
        reset()
    }

    fun reset() {
        for (i in 0 until n) {
            val nd = m.nodes[i]
            lt[i * 3] = nd.t[0]; lt[i * 3 + 1] = nd.t[1]; lt[i * 3 + 2] = nd.t[2]
            lr[i * 4] = nd.r[0]; lr[i * 4 + 1] = nd.r[1]; lr[i * 4 + 2] = nd.r[2]; lr[i * 4 + 3] = nd.r[3]
            ls[i * 3] = nd.s[0]; ls[i * 3 + 1] = nd.s[1]; ls[i * 3 + 2] = nd.s[2]
        }
    }

    fun sample(clip: Clip, time: Float) {
        val t = if (clip.duration > 0f) time % clip.duration else 0f
        for (ch in clip.channels) {
            val times = ch.times
            val cnt = times.size
            if (cnt == 0 || ch.node < 0 || ch.node >= n) continue
            // binary search: last index with times[i] <= t
            var lo = 0
            var hi = cnt - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (times[mid] <= t) lo = mid else hi = mid - 1
            }
            val i0 = lo
            val i1 = if (i0 < cnt - 1) i0 + 1 else i0
            val f = if (i1 == i0 || ch.step) 0f else (t - times[i0]) / (times[i1] - times[i0])
            val comps = if (ch.path == 1) 4 else 3
            val o0 = valueOffset(ch, i0, comps)
            val o1 = valueOffset(ch, i1, comps)
            if (o1 + comps > ch.values.size || o0 + comps > ch.values.size) continue
            when (ch.path) {
                0 -> for (c in 0 until 3) lt[ch.node * 3 + c] = ch.values[o0 + c] + (ch.values[o1 + c] - ch.values[o0 + c]) * f
                2 -> for (c in 0 until 3) ls[ch.node * 3 + c] = ch.values[o0 + c] + (ch.values[o1 + c] - ch.values[o0 + c]) * f
                else -> {
                    var dot = 0f
                    for (c in 0 until 4) dot += ch.values[o0 + c] * ch.values[o1 + c]
                    val sign = if (dot < 0f) -1f else 1f
                    var len = 0f
                    for (c in 0 until 4) {
                        val v = ch.values[o0 + c] + (sign * ch.values[o1 + c] - ch.values[o0 + c]) * f
                        r4[c] = v
                        len += v * v
                    }
                    len = sqrt(len)
                    if (len < 1e-6f) len = 1f
                    for (c in 0 until 4) lr[ch.node * 4 + c] = r4[c] / len
                }
            }
        }
    }

    private fun valueOffset(ch: Channel, key: Int, comps: Int): Int =
        if (ch.cubic) (key * 3 + 1) * comps else key * comps

    /** Computes node globals and joint matrices from the current local TRS. */
    fun update() {
        for (oi in 0 until n) {
            val i = order[oi]
            t3[0] = lt[i * 3]; t3[1] = lt[i * 3 + 1]; t3[2] = lt[i * 3 + 2]
            r4[0] = lr[i * 4]; r4[1] = lr[i * 4 + 1]; r4[2] = lr[i * 4 + 2]; r4[3] = lr[i * 4 + 3]
            s3[0] = ls[i * 3]; s3[1] = ls[i * 3 + 1]; s3[2] = ls[i * 3 + 2]
            val p = m.nodes[i].parent
            if (p < 0 || p >= n) {
                NodeMath.trs(t3, r4, s3, global, i * 16)
            } else {
                NodeMath.trs(t3, r4, s3, local, 0)
                Matrix.multiplyMM(global, i * 16, global, p * 16, local, 0)
            }
        }
        val jc = minOf(m.skinJoints.size, MAX_JOINTS)
        if (jc == 0) return
        if (m.skinNode >= 0 && m.skinNode < n) {
            if (!Matrix.invertM(inv, 0, global, m.skinNode * 16)) Matrix.setIdentityM(inv, 0)
        } else {
            Matrix.setIdentityM(inv, 0)
        }
        for (j in 0 until jc) {
            val jn = m.skinJoints[j]
            if (jn < 0 || jn >= n) {
                Matrix.setIdentityM(joints, j * 16)
                continue
            }
            Matrix.multiplyMM(tmp, 0, global, jn * 16, m.skinIbm, j * 16)
            Matrix.multiplyMM(joints, j * 16, inv, 0, tmp, 0)
        }
    }
}
