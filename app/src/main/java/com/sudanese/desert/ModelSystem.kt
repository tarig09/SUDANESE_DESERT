package com.sudanese.desert

import android.content.res.AssetManager
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GpuPrim(
    val vao: Int, val count: Int, val tex: Int,
    val base: FloatArray, val cut: Boolean, val skinned: Boolean
)

class GpuModel(val data: ModelData, val norm: FloatArray) {
    val prims = ArrayList<GpuPrim>()
}

class PropInfo(val count: Int, val radius: Float)

/**
 * Optional external models loaded from assets/models:
 *   character/   one skinned .glb/.gltf character
 *   animations/  one .glb/.gltf with animation clips (same rig as the character)
 *   weapons/     pistol / rifle / shotgun / sniper models (.obj, .glb, .gltf)
 *   props/       cars, trees, barrels, crates, tents, rocks ... (.obj, .glb, .gltf)
 * Anything missing falls back to the built-in box models.
 */
class ModelSystem(private val am: AssetManager) {
    companion object {
        private const val TAG = "ModelSystem"
        private val GUN_LEN = floatArrayOf(0.24f, 0.95f, 1.0f, 1.25f)

        private const val VS = """#version 300 es
uniform mat4 uVP;
uniform mat4 uModel;
uniform vec3 uCam;
uniform mat4 uJoints[96];
uniform int uSkinned;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUV;
layout(location = 3) in vec4 aColor;
layout(location = 4) in vec4 aJoints;
layout(location = 5) in vec4 aWeights;
out vec3 vNormal;
out vec2 vUV;
out vec4 vColor;
out float vDist;
void main() {
    vec4 p = vec4(aPos, 1.0);
    vec3 n = aNormal;
    if (uSkinned == 1) {
        mat4 sk = aWeights.x * uJoints[int(aJoints.x)]
                + aWeights.y * uJoints[int(aJoints.y)]
                + aWeights.z * uJoints[int(aJoints.z)]
                + aWeights.w * uJoints[int(aJoints.w)];
        p = sk * p;
        n = mat3(sk) * n;
    }
    vec4 wp = uModel * p;
    vNormal = mat3(uModel) * n;
    vUV = aUV;
    vColor = aColor;
    vDist = length(wp.xyz - uCam);
    gl_Position = uVP * wp;
}
"""

        private const val FS = """#version 300 es
precision mediump float;
in vec3 vNormal;
in vec2 vUV;
in vec4 vColor;
in float vDist;
uniform sampler2D uTex;
uniform int uHasTex;
uniform int uCut;
uniform vec4 uBase;
uniform vec3 uLight;
uniform vec3 uFog;
uniform vec3 uTint;
out vec4 fragColor;
void main() {
    vec4 c = uBase * vColor;
    if (uHasTex == 1) c *= texture(uTex, vUV);
    if (uCut == 1 && c.a < 0.5) discard;
    float d = max(dot(normalize(vNormal), uLight), 0.0);
    vec3 col = c.rgb * uTint * (0.5 + 0.6 * d);
    float f = clamp((vDist - 60.0) / 160.0, 0.0, 1.0);
    fragColor = vec4(mix(col, uFog, f), 1.0);
}
"""
    }

    var ready = false
        private set
    var status = ""
        private set

    var character: GpuModel? = null
        private set
    val guns = arrayOfNulls<GpuModel>(4)
    val props = ArrayList<GpuModel>()
    val propInfo = ArrayList<PropInfo>()

    // ---- CPU-side data (loaded once, re-uploaded on GL context loss)
    private var loaded = false
    private var charData: ModelData? = null
    private var charNorm = FloatArray(16)
    private val gunData = arrayOfNulls<ModelData>(4)
    private val gunNorm = Array(4) { FloatArray(16) }
    private val propData = ArrayList<ModelData>()
    private val propNorm = ArrayList<FloatArray>()
    private val propMeta = ArrayList<PropInfo>()
    private val idleClips = arrayOfNulls<Clip>(5)   // index 0 = unarmed, 1..4 = weapon + 1
    private val runClips = arrayOfNulls<Clip>(5)
    private var clipCount = 0
    private var charName = ""

    // ---- GL
    private var program = 0
    private var uVp = 0
    private var uModel = 0
    private var uCam = 0
    private var uJoints = 0
    private var uSkinned = 0
    private var uTex = 0
    private var uHasTex = 0
    private var uCut = 0
    private var uBase = 0
    private var uLight = 0
    private var uFog = 0
    private var uTint = 0
    private val mm = FloatArray(16)

    // ------------------------------------------------------------------ init

    fun init() {
        try {
            program = ShaderUtil.createProgram(VS, FS)
            uVp = GLES20.glGetUniformLocation(program, "uVP")
            uModel = GLES20.glGetUniformLocation(program, "uModel")
            uCam = GLES20.glGetUniformLocation(program, "uCam")
            uJoints = GLES20.glGetUniformLocation(program, "uJoints")
            uSkinned = GLES20.glGetUniformLocation(program, "uSkinned")
            uTex = GLES20.glGetUniformLocation(program, "uTex")
            uHasTex = GLES20.glGetUniformLocation(program, "uHasTex")
            uCut = GLES20.glGetUniformLocation(program, "uCut")
            uBase = GLES20.glGetUniformLocation(program, "uBase")
            uLight = GLES20.glGetUniformLocation(program, "uLight")
            uFog = GLES20.glGetUniformLocation(program, "uFog")
            uTint = GLES20.glGetUniformLocation(program, "uTint")

            if (!loaded) {
                loadCpu()
                loaded = true
            }
            uploadAll()
            ready = true
        } catch (t: Throwable) {
            Log.e(TAG, "model init failed", t)
            status = "تعذر تحميل النماذج: " + (t.message ?: t.javaClass.simpleName)
            ready = false
        }
    }

    // ------------------------------------------------------------- file loading

    private fun listModels(dir: String, withObj: Boolean): List<String> {
        val names = try {
            am.list(dir)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val ok = names.filter {
            val l = it.lowercase()
            l.endsWith(".glb") || l.endsWith(".gltf") || (withObj && l.endsWith(".obj"))
        }
        // prefer .glb (self-contained) first
        return ok.sortedWith(compareBy({ !it.lowercase().endsWith(".glb") }, { it.lowercase() })).map { "$dir/$it" }
    }

    private fun loadModel(path: String): ModelData =
        if (path.lowercase().endsWith(".obj")) ObjLoader.load(am, path) else GltfLoader.load(am, path)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun loadCpu() {
        // ---- character + animations
        val cf = listModels("models/character", false)
        if (cf.isNotEmpty()) {
            try {
                val d = loadModel(cf[0])
                if (d.hasSkin) {
                    charData = d
                    charName = cf[0].substringAfterLast('/')
                    val b = d.bounds()
                    val s = 1.8f / maxOf(0.001f, b[4] - b[1])
                    Matrix.setIdentityM(charNorm, 0)
                    charNorm[0] = s; charNorm[5] = s; charNorm[10] = s
                    charNorm[12] = -(b[0] + b[3]) / 2f * s
                    charNorm[13] = -b[1] * s
                    charNorm[14] = -(b[2] + b[5]) / 2f * s
                    collectClips(d, listModels("models/animations", false))
                } else {
                    Log.w(TAG, "character has no skin: ${cf[0]}")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "character load failed", e)
            }
        }

        // ---- weapons
        val wf = listModels("models/weapons", true)
        val used = BooleanArray(wf.size)
        for (i in wf.indices) {
            val slot = gunSlot(wf[i])
            if (slot >= 0 && gunData[slot] == null) {
                if (prepareGun(slot, wf[i])) used[i] = true
            }
        }
        for (i in wf.indices) {
            if (used[i]) continue
            val free = (0 until 4).firstOrNull { gunData[it] == null } ?: break
            if (prepareGun(free, wf[i])) used[i] = true
        }

        // ---- props
        val pf = listModels("models/props", true)
        for (i in 0 until minOf(pf.size, 40)) {
            try {
                val d = loadModel(pf[i])
                if (d.prims.isEmpty()) continue
                val spec = classify(pf[i].substringAfterLast('/'))
                val b = d.bounds()
                val dim = maxOf(maxOf(b[3] - b[0], b[4] - b[1]), maxOf(b[5] - b[2], 0.001f))
                val s = spec[0] / dim
                val m = FloatArray(16)
                Matrix.setIdentityM(m, 0)
                m[0] = s; m[5] = s; m[10] = s
                m[12] = -(b[0] + b[3]) / 2f * s
                m[13] = -b[1] * s
                m[14] = -(b[2] + b[5]) / 2f * s
                propData.add(d)
                propNorm.add(m)
                propMeta.add(PropInfo(spec[2].toInt(), spec[0] * spec[1]))
            } catch (e: Throwable) {
                Log.e(TAG, "prop load failed: ${pf[i]}", e)
            }
        }
    }

    private fun has(n: String, vararg k: String): Boolean = k.any { n.contains(it) }

    /** [size meters, collision radius as fraction of size, instance count] */
    private fun classify(file: String): FloatArray {
        val n = file.lowercase()
        return when {
            has(n, "car", "truck", "van", "jeep", "vehicle", "taxi", "bus", "pickup") -> floatArrayOf(4.5f, 0.42f, 4f)
            has(n, "palm", "tree") -> floatArrayOf(6.5f, 0.07f, 12f)
            has(n, "tent") -> floatArrayOf(4f, 0.4f, 4f)
            has(n, "barrel") -> floatArrayOf(1.0f, 0.45f, 10f)
            has(n, "crate", "box") -> floatArrayOf(1.0f, 0.55f, 10f)
            has(n, "rock", "stone") -> floatArrayOf(2.0f, 0.45f, 10f)
            has(n, "bush", "grass", "plant", "shrub") -> floatArrayOf(1.2f, 0f, 8f)
            has(n, "wall", "fence") -> floatArrayOf(3f, 0f, 6f)
            else -> floatArrayOf(1.5f, 0.35f, 6f)
        }
    }

    private fun gunSlot(path: String): Int {
        val n = path.substringAfterLast('/').lowercase()
        return when {
            has(n, "pistol", "glock", "revolver", "handgun") -> 0
            has(n, "shotgun", "pump") -> 2
            has(n, "sniper", "awp", "barrett", "dragunov") -> 3
            has(n, "rifle", "ak", "m4", "assault", "smg") -> 1
            else -> -1
        }
    }

    /** Loads a gun, orients the barrel toward -Z and builds its normalization matrix. */
    private fun prepareGun(slot: Int, path: String): Boolean {
        try {
            val d = loadModel(path)
            if (d.prims.isEmpty()) return false
            val rot = FloatArray(16)
            var b = d.bounds()
            if (b[3] - b[0] > b[5] - b[2]) {
                Matrix.setRotateM(rot, 0, 90f, 0f, 1f, 0f)
                d.transform(rot)
                b = d.bounds()
            }
            if (d.mean(2) < (b[2] + b[5]) / 2f) {
                Matrix.setRotateM(rot, 0, 180f, 0f, 1f, 0f)
                d.transform(rot)
                b = d.bounds()
            }
            val len = GUN_LEN[slot]
            val s = len / maxOf(0.001f, b[5] - b[2])
            val m = gunNorm[slot]
            Matrix.setIdentityM(m, 0)
            m[0] = s; m[5] = s; m[10] = s
            m[12] = -(b[0] + b[3]) / 2f * s
            m[13] = -b[4] * s + 0.06f
            m[14] = 0.2f * len - b[5] * s
            gunData[slot] = d
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "gun load failed: $path", e)
            return false
        }
    }

    // ---------------------------------------------------------------- animations

    private fun collectClips(character: ModelData, animFiles: List<String>) {
        var clips: List<Clip> = character.clips
        if (animFiles.isNotEmpty()) {
            try {
                val ad = loadModel(animFiles[0])
                if (ad.clips.isNotEmpty()) {
                    clips = ad.clips.map { remap(it, ad, character) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "animation file failed", e)
            }
        }
        clipCount = clips.size

        val weaponWords = listOf("pistol", "rifle", "gun", "shoot", "sword", "bow", "spell", "sit", "crouch",
            "lie", "sleep", "dance", "talk", "hit", "death", "melee", "punch", "kick", "jump")
        idleClips[0] = findClip(clips, listOf("idle", "loop"), weaponWords) ?: findClip(clips, listOf("idle"), weaponWords)
        runClips[0] = findClip(clips, listOf("jog", "fwd"), weaponWords)
            ?: findClip(clips, listOf("jog"), weaponWords)
            ?: findClip(clips, listOf("run"), weaponWords)
            ?: findClip(clips, listOf("sprint"), weaponWords)
            ?: findClip(clips, listOf("walk"), weaponWords)
        for (w in 0 until 4) {
            val key = if (w == 0) "pistol" else "rifle"
            idleClips[w + 1] = findClip(clips, listOf(key, "idle")) ?: idleClips[0]
            runClips[w + 1] = findClip(clips, listOf(key, "jog"))
                ?: findClip(clips, listOf(key, "run"))
                ?: findClip(clips, listOf(key, "walk"))
                ?: runClips[0]
        }
    }

    private fun findClip(clips: List<Clip>, all: List<String>, none: List<String> = emptyList()): Clip? {
        for (c in clips) {
            val n = c.name.lowercase().replace('_', ' ').replace('-', ' ').replace('.', ' ')
            if (all.all { n.contains(it) } && none.none { n.contains(it) }) return c
        }
        return null
    }

    /** Re-targets a clip onto another skeleton by node name (translation only on the root/hips). */
    private fun remap(c: Clip, from: ModelData, to: ModelData): Clip {
        val map = HashMap<String, Int>()
        for (i in to.nodes.indices) map[norm(to.nodes[i].name)] = i
        val out = ArrayList<Channel>()
        for (ch in c.channels) {
            if (ch.node < 0 || ch.node >= from.nodes.size) continue
            val name = norm(from.nodes[ch.node].name)
            val ti = map[name] ?: continue
            if (ch.path == 0 && !(name.contains("pelvis") || name.contains("hips") || name.contains("root"))) continue
            out.add(Channel(ti, ch.path, ch.times, ch.values, ch.step, ch.cubic))
        }
        return Clip(c.name, c.duration, out)
    }

    fun clipFor(moving: Boolean, weapon: Int): Clip? {
        val i = weapon + 1
        return if (moving) runClips[i] ?: runClips[0] else idleClips[i] ?: idleClips[0]
    }

    fun idleClip(): Clip? = idleClips[0]

    // ------------------------------------------------------------------ upload

    private fun uploadAll() {
        character = null
        props.clear()
        propInfo.clear()
        for (i in 0 until 4) guns[i] = null

        charData?.let { character = upload(it, charNorm) }
        for (i in 0 until 4) {
            val d = gunData[i]
            if (d != null) guns[i] = upload(d, gunNorm[i])
        }
        for (i in propData.indices) {
            props.add(upload(propData[i], propNorm[i]))
            propInfo.add(propMeta[i])
        }

        val gc = guns.count { it != null }
        status = "النماذج: شخصية " + (if (character != null) "✓" else "✗") +
            " | حركات " + clipCount + " | أسلحة " + gc + " | دعائم " + props.size
    }

    private fun upload(d: ModelData, norm: FloatArray): GpuModel {
        val gm = GpuModel(d, norm)
        val texIds = IntArray(d.images.size)
        for (p in d.prims) {
            val vc = p.vertexCount
            val fb = ByteBuffer.allocateDirect(vc * 20 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (v in 0 until vc) {
                fb.put(p.pos, v * 3, 3)
                fb.put(p.nrm, v * 3, 3)
                fb.put(p.uv, v * 2, 2)
                fb.put(p.col, v * 4, 4)
                fb.put(p.joints, v * 4, 4)
                fb.put(p.wts, v * 4, 4)
            }
            fb.position(0)
            val ib = ByteBuffer.allocateDirect(p.idx.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            ib.put(p.idx)
            ib.position(0)

            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0)
            val vao = ids[0]
            GLES30.glBindVertexArray(vao)
            GLES20.glGenBuffers(1, ids, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vc * 20 * 4, fb, GLES20.GL_STATIC_DRAW)
            GLES20.glGenBuffers(1, ids, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, p.idx.size * 4, ib, GLES20.GL_STATIC_DRAW)
            val stride = 20 * 4
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, stride, 0)
            GLES20.glEnableVertexAttribArray(1)
            GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, stride, 12)
            GLES20.glEnableVertexAttribArray(2)
            GLES20.glVertexAttribPointer(2, 2, GLES20.GL_FLOAT, false, stride, 24)
            GLES20.glEnableVertexAttribArray(3)
            GLES20.glVertexAttribPointer(3, 4, GLES20.GL_FLOAT, false, stride, 32)
            GLES20.glEnableVertexAttribArray(4)
            GLES20.glVertexAttribPointer(4, 4, GLES20.GL_FLOAT, false, stride, 48)
            GLES20.glEnableVertexAttribArray(5)
            GLES20.glVertexAttribPointer(5, 4, GLES20.GL_FLOAT, false, stride, 64)
            GLES30.glBindVertexArray(0)

            var tex = 0
            if (p.texIndex >= 0 && p.texIndex < d.images.size) {
                if (texIds[p.texIndex] == 0) {
                    val bmp = d.images[p.texIndex]
                    if (bmp != null) {
                        val t = IntArray(1)
                        GLES20.glGenTextures(1, t, 0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
                        texIds[p.texIndex] = t[0]
                    }
                }
                tex = texIds[p.texIndex]
            }
            gm.prims.add(GpuPrim(vao, p.idx.size, tex, p.base, p.alphaCut, p.skinned))
        }
        return gm
    }

    // ------------------------------------------------------------------ drawing

    fun begin(vp: FloatArray, cx: Float, cy: Float, cz: Float, lx: Float, ly: Float, lz: Float,
              fr: Float, fg: Float, fb: Float) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uVp, 1, false, vp, 0)
        GLES20.glUniform3f(uCam, cx, cy, cz)
        GLES20.glUniform3f(uLight, lx, ly, lz)
        GLES20.glUniform3f(uFog, fr, fg, fb)
        GLES20.glUniform3f(uTint, 1f, 1f, 1f)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** Draws a model with world matrix [world] (the model's own normalization is applied inside). */
    fun draw(model: GpuModel, world: FloatArray, tr: Float, tg: Float, tb: Float, anim: Animator?) {
        Matrix.multiplyMM(mm, 0, world, 0, model.norm, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, mm, 0)
        GLES20.glUniform3f(uTint, tr, tg, tb)
        if (anim != null) GLES20.glUniformMatrix4fv(uJoints, Animator.MAX_JOINTS, false, anim.joints, 0)
        for (p in model.prims) {
            val skin = p.skinned && anim != null
            GLES20.glUniform1i(uSkinned, if (skin) 1 else 0)
            GLES20.glUniform4f(uBase, p.base[0], p.base[1], p.base[2], p.base[3])
            GLES20.glUniform1i(uCut, if (p.cut) 1 else 0)
            if (p.tex != 0) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, p.tex)
                GLES20.glUniform1i(uHasTex, 1)
            } else {
                GLES20.glUniform1i(uHasTex, 0)
            }
            GLES30.glBindVertexArray(p.vao)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, p.count, GLES20.GL_UNSIGNED_INT, 0)
        }
        GLES30.glBindVertexArray(0)
    }

    fun end() {
        GLES20.glUniform3f(uTint, 1f, 1f, 1f)
        GLES20.glUniform1i(uSkinned, 0)
    }
}
