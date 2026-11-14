package com.sudanese.desert

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.Matrix
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Loads .glb / .gltf (meshes, textures, skin, animations) from the app assets. */
object GltfLoader {

    private class Acc(val data: FloatArray, val comps: Int)

    private fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

    fun load(am: AssetManager, path: String): ModelData {
        val dir = if (path.contains('/')) path.substringBeforeLast('/') else ""
        var glbBin: ByteArray? = null
        val json: JSONObject
        if (path.endsWith(".glb", ignoreCase = true)) {
            val bytes = am.open(path).use { it.readBytes() }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var off = 12
            var jsonStr = "{}"
            while (off + 8 <= bytes.size) {
                val len = bb.getInt(off)
                val type = bb.getInt(off + 4)
                val start = off + 8
                if (len < 0 || start + len > bytes.size) break
                if (type == 0x4E4F534A) jsonStr = String(bytes, start, len, Charsets.UTF_8)
                else if (type == 0x004E4942) glbBin = bytes.copyOfRange(start, start + len)
                off = start + len
            }
            json = JSONObject(jsonStr)
        } else {
            json = JSONObject(am.open(path).bufferedReader().use { it.readText() })
        }

        // ---- buffers
        val bufs = ArrayList<ByteArray>()
        val jb = json.optJSONArray("buffers")
        if (jb != null) {
            for (i in 0 until jb.length()) {
                val uri = jb.getJSONObject(i).optString("uri", "")
                bufs.add(
                    when {
                        uri.isEmpty() -> glbBin ?: ByteArray(0)
                        uri.startsWith("data:") -> Base64.decode(uri.substringAfter(','), Base64.DEFAULT)
                        else -> am.open(join(dir, URLDecoder.decode(uri, "UTF-8"))).use { it.readBytes() }
                    }
                )
            }
        }

        val md = ModelData()

        // ---- images
        val ji = json.optJSONArray("images")
        if (ji != null) {
            for (i in 0 until ji.length()) {
                md.images.add(
                    try {
                        decodeImage(am, dir, ji.getJSONObject(i), json, bufs)
                    } catch (e: Exception) {
                        null
                    }
                )
            }
        }

        // ---- nodes
        val jn = json.optJSONArray("nodes") ?: JSONArray()
        val n = jn.length()
        val nodeMesh = IntArray(n) { -1 }
        val nodeSkin = IntArray(n) { -1 }
        val children = ArrayList<IntArray>()
        for (i in 0 until n) {
            val o = jn.getJSONObject(i)
            val nd = NodeData(o.optString("name", "node$i"), -1)
            val mj = o.optJSONArray("matrix")
            if (mj != null && mj.length() == 16) {
                NodeMath.decompose(FloatArray(16) { mj.getDouble(it).toFloat() }, nd)
            } else {
                o.optJSONArray("translation")?.let { for (k in 0 until 3) nd.t[k] = it.getDouble(k).toFloat() }
                o.optJSONArray("rotation")?.let { for (k in 0 until 4) nd.r[k] = it.getDouble(k).toFloat() }
                o.optJSONArray("scale")?.let { for (k in 0 until 3) nd.s[k] = it.getDouble(k).toFloat() }
            }
            md.nodes.add(nd)
            nodeMesh[i] = o.optInt("mesh", -1)
            nodeSkin[i] = o.optInt("skin", -1)
            val ch = o.optJSONArray("children")
            children.add(IntArray(ch?.length() ?: 0) { ch!!.getInt(it) })
        }
        for (i in 0 until n) for (c in children[i]) if (c in 0 until n) md.nodes[c].parent = i

        // rest-pose global matrices (for baking static meshes)
        val glob = FloatArray(n * 16)
        val done = BooleanArray(n)
        fun compute(i: Int) {
            if (done[i]) return
            done[i] = true
            val nd = md.nodes[i]
            val l = FloatArray(16)
            NodeMath.trs(nd.t, nd.r, nd.s, l, 0)
            val p = nd.parent
            if (p < 0) {
                System.arraycopy(l, 0, glob, i * 16, 16)
            } else {
                compute(p)
                Matrix.multiplyMM(glob, i * 16, glob, p * 16, l, 0)
            }
        }
        for (i in 0 until n) compute(i)

        // ---- meshes
        val meshes = json.optJSONArray("meshes")
        val materials = json.optJSONArray("materials")
        val textures = json.optJSONArray("textures")
        val skins = json.optJSONArray("skins")

        for (i in 0 until n) {
            val mi = nodeMesh[i]
            if (mi < 0 || meshes == null) continue
            val skinIdx = nodeSkin[i]
            val skinned = skinIdx >= 0 && skins != null
            if (skinned && !md.hasSkin) {
                val sk = skins!!.getJSONObject(skinIdx)
                val jarr = sk.getJSONArray("joints")
                md.skinJoints = IntArray(jarr.length()) { jarr.getInt(it) }
                md.skinNode = i
                md.skinIbm = if (sk.has("inverseBindMatrices")) {
                    readAccessor(json, bufs, sk.getInt("inverseBindMatrices")).data
                } else {
                    val f = FloatArray(jarr.length() * 16)
                    for (j in 0 until jarr.length()) {
                        f[j * 16] = 1f; f[j * 16 + 5] = 1f; f[j * 16 + 10] = 1f; f[j * 16 + 15] = 1f
                    }
                    f
                }
            }
            val prims = meshes.getJSONObject(mi).getJSONArray("primitives")
            for (pi in 0 until prims.length()) {
                val p = prims.getJSONObject(pi)
                if (p.optInt("mode", 4) != 4) continue
                val attrs = p.getJSONObject("attributes")
                if (!attrs.has("POSITION")) continue

                val pos = readAccessor(json, bufs, attrs.getInt("POSITION")).data
                val vc = pos.size / 3
                val nrm = if (attrs.has("NORMAL")) readAccessor(json, bufs, attrs.getInt("NORMAL")).data
                else FloatArray(vc * 3) { if (it % 3 == 1) 1f else 0f }
                val uv = if (attrs.has("TEXCOORD_0")) readAccessor(json, bufs, attrs.getInt("TEXCOORD_0")).data
                else FloatArray(vc * 2)

                val col = FloatArray(vc * 4) { 1f }
                if (attrs.has("COLOR_0")) {
                    val c = readAccessor(json, bufs, attrs.getInt("COLOR_0"))
                    if (c.comps >= 3) {
                        for (v in 0 until vc) {
                            col[v * 4] = c.data[v * c.comps]
                            col[v * 4 + 1] = c.data[v * c.comps + 1]
                            col[v * 4 + 2] = c.data[v * c.comps + 2]
                            col[v * 4 + 3] = if (c.comps >= 4) c.data[v * c.comps + 3] else 1f
                        }
                    }
                }

                var joints = FloatArray(vc * 4)
                var wts = FloatArray(vc * 4)
                var isSkinned = false
                if (skinned && attrs.has("JOINTS_0") && attrs.has("WEIGHTS_0")) {
                    joints = readAccessor(json, bufs, attrs.getInt("JOINTS_0")).data
                    wts = readAccessor(json, bufs, attrs.getInt("WEIGHTS_0")).data
                    isSkinned = true
                }

                val idx = if (p.has("indices")) {
                    val d = readAccessor(json, bufs, p.getInt("indices")).data
                    IntArray(d.size) { d[it].toInt() }
                } else IntArray(vc) { it }

                // material
                val base = floatArrayOf(1f, 1f, 1f, 1f)
                var tex = -1
                var cut = false
                val mat = p.optInt("material", -1)
                if (mat >= 0 && materials != null && mat < materials.length()) {
                    val mo = materials.getJSONObject(mat)
                    val pbr = mo.optJSONObject("pbrMetallicRoughness")
                    if (pbr != null) {
                        pbr.optJSONArray("baseColorFactor")?.let { for (k in 0 until 4) base[k] = it.getDouble(k).toFloat() }
                        val bt = pbr.optJSONObject("baseColorTexture")
                        if (bt != null && textures != null) {
                            val ti = bt.getInt("index")
                            if (ti < textures.length()) tex = textures.getJSONObject(ti).optInt("source", -1)
                        }
                    }
                    if (mo.optString("alphaMode", "OPAQUE") == "MASK") cut = true
                }

                if (!isSkinned) {
                    // bake the node's global transform into the vertices
                    val g = glob
                    val o = i * 16
                    for (v in 0 until vc) {
                        val x = pos[v * 3]
                        val y = pos[v * 3 + 1]
                        val z = pos[v * 3 + 2]
                        pos[v * 3] = g[o] * x + g[o + 4] * y + g[o + 8] * z + g[o + 12]
                        pos[v * 3 + 1] = g[o + 1] * x + g[o + 5] * y + g[o + 9] * z + g[o + 13]
                        pos[v * 3 + 2] = g[o + 2] * x + g[o + 6] * y + g[o + 10] * z + g[o + 14]
                        val nx = nrm[v * 3]
                        val ny = nrm[v * 3 + 1]
                        val nz = nrm[v * 3 + 2]
                        var ox = g[o] * nx + g[o + 4] * ny + g[o + 8] * nz
                        var oy = g[o + 1] * nx + g[o + 5] * ny + g[o + 9] * nz
                        var oz = g[o + 2] * nx + g[o + 6] * ny + g[o + 10] * nz
                        val l = sqrt(ox * ox + oy * oy + oz * oz)
                        if (l > 1e-6f) { ox /= l; oy /= l; oz /= l }
                        nrm[v * 3] = ox; nrm[v * 3 + 1] = oy; nrm[v * 3 + 2] = oz
                    }
                }
                md.prims.add(Prim(pos, nrm, uv, col, joints, wts, idx, base, tex, cut, isSkinned))
            }
        }

        // ---- animations
        val anims = json.optJSONArray("animations")
        if (anims != null) {
            for (a in 0 until anims.length()) {
                val ao = anims.getJSONObject(a)
                val samplers = ao.getJSONArray("samplers")
                val chs = ao.getJSONArray("channels")
                val list = ArrayList<Channel>()
                var dur = 0f
                for (c in 0 until chs.length()) {
                    val co = chs.getJSONObject(c)
                    val tgt = co.getJSONObject("target")
                    if (!tgt.has("node")) continue
                    val path = when (tgt.getString("path")) {
                        "translation" -> 0
                        "rotation" -> 1
                        "scale" -> 2
                        else -> -1
                    }
                    if (path < 0) continue
                    val so = samplers.getJSONObject(co.getInt("sampler"))
                    val times = readAccessor(json, bufs, so.getInt("input")).data
                    val vals = readAccessor(json, bufs, so.getInt("output")).data
                    val interp = so.optString("interpolation", "LINEAR")
                    if (times.isNotEmpty() && times[times.size - 1] > dur) dur = times[times.size - 1]
                    list.add(Channel(tgt.getInt("node"), path, times, vals, interp == "STEP", interp == "CUBICSPLINE"))
                }
                md.clips.add(Clip(ao.optString("name", "anim$a"), dur, list))
            }
        }
        return md
    }

    private fun readAccessor(json: JSONObject, bufs: List<ByteArray>, index: Int): Acc {
        val acc = json.getJSONArray("accessors").getJSONObject(index)
        val count = acc.getInt("count")
        val comps = when (acc.getString("type")) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            "MAT2" -> 4
            "MAT3" -> 9
            "MAT4" -> 16
            else -> 1
        }
        val ctype = acc.getInt("componentType")
        val norm = acc.optBoolean("normalized", false)
        val out = FloatArray(count * comps)
        if (!acc.has("bufferView")) return Acc(out, comps)
        val bv = json.getJSONArray("bufferViews").getJSONObject(acc.getInt("bufferView"))
        val buf = bufs[bv.getInt("buffer")]
        val off = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val csize = when (ctype) {
            5120, 5121 -> 1
            5122, 5123 -> 2
            else -> 4
        }
        val bs = bv.optInt("byteStride", 0)
        val stride = if (bs == 0) csize * comps else bs
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            for (c in 0 until comps) {
                val p = off + i * stride + c * csize
                out[i * comps + c] = when (ctype) {
                    5126 -> bb.getFloat(p)
                    5121 -> (bb.get(p).toInt() and 0xFF).let { if (norm) it / 255f else it.toFloat() }
                    5120 -> bb.get(p).toFloat().let { if (norm) maxOf(it / 127f, -1f) else it }
                    5123 -> (bb.getShort(p).toInt() and 0xFFFF).let { if (norm) it / 65535f else it.toFloat() }
                    5122 -> bb.getShort(p).toFloat().let { if (norm) maxOf(it / 32767f, -1f) else it }
                    5125 -> (bb.getInt(p).toLong() and 0xFFFFFFFFL).toFloat()
                    else -> 0f
                }
            }
        }
        return Acc(out, comps)
    }

    private fun decodeImage(
        am: AssetManager, dir: String, im: JSONObject, json: JSONObject, bufs: List<ByteArray>
    ): Bitmap? {
        val bytes: ByteArray
        val uri = im.optString("uri", "")
        if (uri.isNotEmpty()) {
            bytes = if (uri.startsWith("data:")) Base64.decode(uri.substringAfter(','), Base64.DEFAULT)
            else am.open(join(dir, URLDecoder.decode(uri, "UTF-8"))).use { it.readBytes() }
        } else if (im.has("bufferView")) {
            val bv = json.getJSONArray("bufferViews").getJSONObject(im.getInt("bufferView"))
            val buf = bufs[bv.getInt("buffer")]
            val off = bv.optInt("byteOffset", 0)
            bytes = buf.copyOfRange(off, off + bv.getInt("byteLength"))
        } else return null

        val bo = BitmapFactory.Options()
        bo.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bo)
        var sample = 1
        while (maxOf(bo.outWidth, bo.outHeight) / sample > 2048) sample *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }
}

/** Minimal Wavefront OBJ (+MTL diffuse colors) loader for static props and weapons. */
object ObjLoader {
    private class Group(val color: FloatArray) {
        val pos = ArrayList<Float>()
        val nrm = ArrayList<Float>()
        val uv = ArrayList<Float>()
    }

    fun load(am: AssetManager, path: String): ModelData {
        val dir = if (path.contains('/')) path.substringBeforeLast('/') else ""
        val v = ArrayList<Float>()
        val vn = ArrayList<Float>()
        val vt = ArrayList<Float>()
        val mats = HashMap<String, FloatArray>()
        val groups = LinkedHashMap<String, Group>()
        var cur = "default"
        groups[cur] = Group(floatArrayOf(0.7f, 0.7f, 0.7f, 1f))

        fun readMtl(name: String) {
            try {
                var mn = ""
                am.open(if (dir.isEmpty()) name else "$dir/$name").bufferedReader().useLines { lines ->
                    for (ln in lines) {
                        val t = ln.trim().split(Regex("\\s+"))
                        if (t.isEmpty()) continue
                        if (t[0] == "newmtl" && t.size > 1) mn = t[1]
                        else if (t[0] == "Kd" && t.size >= 4 && mn.isNotEmpty()) {
                            mats[mn] = floatArrayOf(t[1].toFloat(), t[2].toFloat(), t[3].toFloat(), 1f)
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        fun fidx(s: String, size: Int): Int {
            if (s.isEmpty()) return -1
            val i = s.toInt()
            return if (i > 0) i - 1 else size + i
        }

        am.open(path).bufferedReader().useLines { lines ->
            for (ln in lines) {
                val t = ln.trim().split(Regex("\\s+"))
                if (t.isEmpty() || t[0].isEmpty()) continue
                when (t[0]) {
                    "mtllib" -> if (t.size > 1) readMtl(t[1])
                    "usemtl" -> if (t.size > 1) {
                        cur = t[1]
                        if (!groups.containsKey(cur)) groups[cur] = Group(mats[cur] ?: floatArrayOf(0.7f, 0.7f, 0.7f, 1f))
                    }
                    "v" -> if (t.size >= 4) { v.add(t[1].toFloat()); v.add(t[2].toFloat()); v.add(t[3].toFloat()) }
                    "vn" -> if (t.size >= 4) { vn.add(t[1].toFloat()); vn.add(t[2].toFloat()); vn.add(t[3].toFloat()) }
                    "vt" -> if (t.size >= 3) { vt.add(t[1].toFloat()); vt.add(t[2].toFloat()) }
                    "f" -> {
                        val g = groups[cur]!!
                        val vi = IntArray(t.size - 1)
                        val ti = IntArray(t.size - 1)
                        val ni = IntArray(t.size - 1)
                        for (k in 1 until t.size) {
                            val parts = t[k].split("/")
                            vi[k - 1] = fidx(parts[0], v.size / 3)
                            ti[k - 1] = if (parts.size > 1) fidx(parts[1], vt.size / 2) else -1
                            ni[k - 1] = if (parts.size > 2) fidx(parts[2], vn.size / 3) else -1
                        }
                        for (k in 1 until vi.size - 1) {
                            val tri = intArrayOf(0, k, k + 1)
                            // flat normal fallback
                            val a = vi[tri[0]] * 3
                            val b = vi[tri[1]] * 3
                            val c = vi[tri[2]] * 3
                            if (a < 0 || b < 0 || c < 0) continue
                            val ux = v[b] - v[a]; val uy = v[b + 1] - v[a + 1]; val uz = v[b + 2] - v[a + 2]
                            val wx = v[c] - v[a]; val wy = v[c + 1] - v[a + 1]; val wz = v[c + 2] - v[a + 2]
                            var fx = uy * wz - uz * wy
                            var fy = uz * wx - ux * wz
                            var fz = ux * wy - uy * wx
                            val fl = sqrt(fx * fx + fy * fy + fz * fz)
                            if (fl > 1e-8f) { fx /= fl; fy /= fl; fz /= fl }
                            for (q in tri) {
                                val p = vi[q] * 3
                                g.pos.add(v[p]); g.pos.add(v[p + 1]); g.pos.add(v[p + 2])
                                if (ni[q] >= 0) {
                                    val np = ni[q] * 3
                                    g.nrm.add(vn[np]); g.nrm.add(vn[np + 1]); g.nrm.add(vn[np + 2])
                                } else {
                                    g.nrm.add(fx); g.nrm.add(fy); g.nrm.add(fz)
                                }
                                if (ti[q] >= 0) {
                                    g.uv.add(vt[ti[q] * 2]); g.uv.add(vt[ti[q] * 2 + 1])
                                } else {
                                    g.uv.add(0f); g.uv.add(0f)
                                }
                            }
                        }
                    }
                }
            }
        }

        val md = ModelData()
        for ((_, g) in groups) {
            val vc = g.pos.size / 3
            if (vc == 0) continue
            md.prims.add(
                Prim(
                    g.pos.toFloatArray(), g.nrm.toFloatArray(), g.uv.toFloatArray(),
                    FloatArray(vc * 4) { 1f }, FloatArray(vc * 4), FloatArray(vc * 4),
                    IntArray(vc) { it }, g.color, -1, false, false
                )
            )
        }
        return md
    }
}
