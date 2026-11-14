package com.sudanese.desert

import android.graphics.Bitmap
import kotlin.math.sqrt

/** One drawable piece of a model (one material). All arrays are per-vertex, tightly packed. */
class Prim(
    val pos: FloatArray,      // 3 per vertex
    val nrm: FloatArray,      // 3
    val uv: FloatArray,       // 2
    val col: FloatArray,      // 4 (vertex color)
    val joints: FloatArray,   // 4 (joint indices stored as floats)
    val wts: FloatArray,      // 4
    val idx: IntArray,
    val base: FloatArray,     // material base color rgba
    val texIndex: Int,        // index into ModelData.images or -1
    val alphaCut: Boolean,
    val skinned: Boolean
) {
    val vertexCount: Int get() = pos.size / 3
}

class NodeData(val name: String, var parent: Int) {
    val t = floatArrayOf(0f, 0f, 0f)
    val r = floatArrayOf(0f, 0f, 0f, 1f)
    val s = floatArrayOf(1f, 1f, 1f)
}

/** path: 0 = translation, 1 = rotation (quaternion), 2 = scale */
class Channel(
    val node: Int,
    val path: Int,
    val times: FloatArray,
    val values: FloatArray,
    val step: Boolean,
    val cubic: Boolean
)

class Clip(val name: String, val duration: Float, val channels: List<Channel>)

class ModelData {
    val prims = ArrayList<Prim>()
    val nodes = ArrayList<NodeData>()
    val images = ArrayList<Bitmap?>()
    val clips = ArrayList<Clip>()
    var skinJoints = IntArray(0)
    var skinIbm = FloatArray(0)      // 16 floats per joint
    var skinNode = -1
    val hasSkin: Boolean get() = skinJoints.isNotEmpty()

    /** [minX, minY, minZ, maxX, maxY, maxZ] (rest pose for skinned models). */
    fun bounds(): FloatArray {
        val b = floatArrayOf(1e9f, 1e9f, 1e9f, -1e9f, -1e9f, -1e9f)
        var j: FloatArray? = null
        if (hasSkin) {
            val an = Animator(this)
            an.update()
            j = an.joints
        }
        for (p in prims) {
            for (v in 0 until p.vertexCount) {
                var x = p.pos[v * 3]
                var y = p.pos[v * 3 + 1]
                var z = p.pos[v * 3 + 2]
                if (p.skinned && j != null) {
                    var ox = 0f
                    var oy = 0f
                    var oz = 0f
                    var wsum = 0f
                    for (k in 0 until 4) {
                        val w = p.wts[v * 4 + k]
                        if (w <= 0f) continue
                        val ji = p.joints[v * 4 + k].toInt()
                        if (ji >= Animator.MAX_JOINTS) continue
                        val o = ji * 16
                        ox += w * (j[o] * x + j[o + 4] * y + j[o + 8] * z + j[o + 12])
                        oy += w * (j[o + 1] * x + j[o + 5] * y + j[o + 9] * z + j[o + 13])
                        oz += w * (j[o + 2] * x + j[o + 6] * y + j[o + 10] * z + j[o + 14])
                        wsum += w
                    }
                    if (wsum > 0f) { x = ox; y = oy; z = oz }
                }
                if (x < b[0]) b[0] = x
                if (y < b[1]) b[1] = y
                if (z < b[2]) b[2] = z
                if (x > b[3]) b[3] = x
                if (y > b[4]) b[4] = y
                if (z > b[5]) b[5] = z
            }
        }
        if (b[0] > b[3]) { b[0] = 0f; b[1] = 0f; b[2] = 0f; b[3] = 1f; b[4] = 1f; b[5] = 1f }
        return b
    }

    /** Apply a 4x4 column-major transform to all (static) vertices. */
    fun transform(m: FloatArray) {
        for (p in prims) {
            for (v in 0 until p.vertexCount) {
                val x = p.pos[v * 3]
                val y = p.pos[v * 3 + 1]
                val z = p.pos[v * 3 + 2]
                p.pos[v * 3] = m[0] * x + m[4] * y + m[8] * z + m[12]
                p.pos[v * 3 + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
                p.pos[v * 3 + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
                val nx = p.nrm[v * 3]
                val ny = p.nrm[v * 3 + 1]
                val nz = p.nrm[v * 3 + 2]
                var ox = m[0] * nx + m[4] * ny + m[8] * nz
                var oy = m[1] * nx + m[5] * ny + m[9] * nz
                var oz = m[2] * nx + m[6] * ny + m[10] * nz
                val l = sqrt(ox * ox + oy * oy + oz * oz)
                if (l > 1e-6f) { ox /= l; oy /= l; oz /= l }
                p.nrm[v * 3] = ox
                p.nrm[v * 3 + 1] = oy
                p.nrm[v * 3 + 2] = oz
            }
        }
    }

    /** Mean of vertex coordinates along an axis (0 = x, 1 = y, 2 = z). */
    fun mean(axis: Int): Float {
        var s = 0.0
        var n = 0
        for (p in prims) {
            for (v in 0 until p.vertexCount) {
                s += p.pos[v * 3 + axis]
                n++
            }
        }
        return if (n == 0) 0f else (s / n).toFloat()
    }
}

object NodeMath {
    /** Compose T * R * S into a column-major matrix. */
    fun trs(t: FloatArray, r: FloatArray, s: FloatArray, out: FloatArray, oo: Int) {
        val x = r[0]
        val y = r[1]
        val z = r[2]
        val w = r[3]
        out[oo] = (1f - 2f * (y * y + z * z)) * s[0]
        out[oo + 1] = (2f * (x * y + z * w)) * s[0]
        out[oo + 2] = (2f * (x * z - y * w)) * s[0]
        out[oo + 3] = 0f
        out[oo + 4] = (2f * (x * y - z * w)) * s[1]
        out[oo + 5] = (1f - 2f * (x * x + z * z)) * s[1]
        out[oo + 6] = (2f * (y * z + x * w)) * s[1]
        out[oo + 7] = 0f
        out[oo + 8] = (2f * (x * z + y * w)) * s[2]
        out[oo + 9] = (2f * (y * z - x * w)) * s[2]
        out[oo + 10] = (1f - 2f * (x * x + y * y)) * s[2]
        out[oo + 11] = 0f
        out[oo + 12] = t[0]
        out[oo + 13] = t[1]
        out[oo + 14] = t[2]
        out[oo + 15] = 1f
    }

    /** Decompose a column-major matrix into node TRS. */
    fun decompose(m: FloatArray, n: NodeData) {
        n.t[0] = m[12]; n.t[1] = m[13]; n.t[2] = m[14]
        val sx = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        val sy = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
        val sz = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
        n.s[0] = sx; n.s[1] = sy; n.s[2] = sz
        if (sx < 1e-8f || sy < 1e-8f || sz < 1e-8f) return
        val r00 = m[0] / sx; val r10 = m[1] / sx; val r20 = m[2] / sx
        val r01 = m[4] / sy; val r11 = m[5] / sy; val r21 = m[6] / sy
        val r02 = m[8] / sz; val r12 = m[9] / sz; val r22 = m[10] / sz
        val tr = r00 + r11 + r22
        val qx: Float
        val qy: Float
        val qz: Float
        val qw: Float
        if (tr > 0f) {
            val s = sqrt(tr + 1f) * 2f
            qw = 0.25f * s; qx = (r21 - r12) / s; qy = (r02 - r20) / s; qz = (r10 - r01) / s
        } else if (r00 > r11 && r00 > r22) {
            val s = sqrt(1f + r00 - r11 - r22) * 2f
            qw = (r21 - r12) / s; qx = 0.25f * s; qy = (r01 + r10) / s; qz = (r02 + r20) / s
        } else if (r11 > r22) {
            val s = sqrt(1f + r11 - r00 - r22) * 2f
            qw = (r02 - r20) / s; qx = (r01 + r10) / s; qy = 0.25f * s; qz = (r12 + r21) / s
        } else {
            val s = sqrt(1f + r22 - r00 - r11) * 2f
            qw = (r10 - r01) / s; qx = (r02 + r20) / s; qy = (r12 + r21) / s; qz = 0.25f * s
        }
        n.r[0] = qx; n.r[1] = qy; n.r[2] = qz; n.r[3] = qw
    }
}
