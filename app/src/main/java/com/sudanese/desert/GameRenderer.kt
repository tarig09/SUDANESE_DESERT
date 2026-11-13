package com.sudanese.desert

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private class Pickup(val type: Int, val x: Float, val y: Float, val z: Float) {
    var alive = true
}

private class Dummy(val x: Float, val y: Float, val z: Float) {
    var hp = 100f
    var alive = true
    var respawn = 0f
    var flash = 0f
}

private class Tracer(
    val x0: Float, val y0: Float, val z0: Float,
    val x1: Float, val y1: Float, val z1: Float
) {
    var life = 0.07f
}

private class Fx(val x: Float, val y: Float, val z: Float, val r: Float, val g: Float, val b: Float) {
    var life = 0.25f
}

class GameRenderer(
    private val input: GameInput,
    private val sfx: SoundFx,
    private val onFps: (Int) -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        private const val SENS = 0.12f      // degrees per pixel
        private const val SPEED = 6f        // meters per second
        private const val RADIUS = 0.4f     // player collision radius
        private const val EYE = 1.7f        // first-person eye height
        private const val JUMP_V = 7f
        private const val GRAVITY = 20f
        private const val CAM_DIST = 3.8f   // third-person camera distance
        private const val DYN_VERTS = 3000

        // part indices inside the parts buffer
        private const val P_BODY = 0
        private const val P_LEG = 1
        private const val P_ARM = 2
        private const val P_GUN = 3         // 3..6 = pistol, rifle, shotgun, sniper
        private const val P_AMMO = 7
        private const val P_MED = 8
        private const val P_ARMOR = 9

        private val MUZ_L = floatArrayOf(0.3f, 1.1f, 0.95f, 1.35f)
        private val RECOIL = floatArrayOf(0.8f, 0.5f, 3f, 4f)

        // sky / fog color
        private const val FOG_R = 0.74f
        private const val FOG_G = 0.84f
        private const val FOG_B = 0.94f

        private const val VS = """#version 300 es
uniform mat4 uVP;
uniform mat4 uModel;
uniform vec3 uCam;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
out vec3 vColor;
out vec3 vNormal;
out float vDist;
void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    vColor = aColor;
    vNormal = mat3(uModel) * aNormal;
    vDist = length(wp.xyz - uCam);
    gl_Position = uVP * wp;
}
"""

        private const val FS = """#version 300 es
precision mediump float;
in vec3 vColor;
in vec3 vNormal;
in float vDist;
uniform vec3 uLight;
uniform vec3 uFog;
uniform vec3 uTint;
out vec4 fragColor;
void main() {
    float d = max(dot(normalize(vNormal), uLight), 0.0);
    vec3 c = vColor * uTint * (0.45 + 0.65 * d);
    float f = clamp((vDist - 60.0) / 160.0, 0.0, 1.0);
    fragColor = vec4(mix(c, uFog, f), 1.0);
}
"""
    }

    private val world = World()
    private val rng = Random(99)

    // ---- player
    private var px = 0f
    private var pz = 0f
    private var spawnX = 0f
    private var spawnZ = 0f
    private var feetY = 0f
    private var vy = 0f
    private var yaw = 0f
    private var pitch = 0f
    private var onGround = false
    private var thirdPerson = true
    private var phase = 0f
    private var swing = 0f
    private val tmp = FloatArray(2)

    // ---- survival / weapons
    private var hp = 100f
    private var armor = 0f
    private var kills = 0
    private val has = BooleanArray(4)
    private val mag = IntArray(4)
    private val reserve = IntArray(4)
    private var cur = -1
    private var cooldown = 0f
    private var reloading = false
    private var reloadT = 0f
    private var flash = 0f
    private var hurtT = 0f
    private var hitT = 0f
    private var msg = ""
    private var msgT = 0f
    private var gameTime = 0f

    private val pickups = ArrayList<Pickup>()
    private val dummies = ArrayList<Dummy>()
    private val tracers = ArrayList<Tracer>()
    private val fxs = ArrayList<Fx>()
    private val muz = FloatArray(3)

    // ---- camera
    private var camX = 0f
    private var camY = 0f
    private var camZ = 0f
    private var cdx = 0f
    private var cdy = 0f
    private var cdz = -1f
    private var showChar = false

    // ---- GL
    private var program = 0
    private var vao = 0
    private var vbo = 0
    private var partsVao = 0
    private var partsVbo = 0
    private var dynVao = 0
    private var dynVbo = 0
    private var uVp = 0
    private var uModel = 0
    private var uCam = 0
    private var uLight = 0
    private var uFog = 0
    private var uTint = 0
    private var meshBuf: FloatBuffer? = null
    private var vertexCount = 0
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val ident = FloatArray(16)
    private val m1 = FloatArray(16)
    private val m2 = FloatArray(16)
    private val m3 = FloatArray(16)

    // parts buffer: character, guns, pickups
    private val partMb = MeshBuilder()
    private var partBuf: FloatBuffer? = null
    private val partFirst = IntArray(10)
    private val partCount = IntArray(10)

    // dynamic (per-frame) mesh: tracers, sparks, muzzle flash
    private val dyn = MeshBuilder()
    private val dynBuf: FloatBuffer =
        ByteBuffer.allocateDirect(DYN_VERTS * 9 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // timing
    private var lastNs = 0L
    private var frames = 0
    private var acc = 0f

    init {
        Matrix.setIdentityM(ident, 0)
        buildParts()

        tmp[0] = 0f
        tmp[1] = 0f
        world.resolve(tmp, RADIUS, world.heightAt(0f, 0f))
        px = tmp[0]
        pz = tmp[1]
        spawnX = px
        spawnZ = pz
        feetY = world.heightAt(px, pz)

        buildPickupsAndDummies()
        say("ابحث عن سلاح من الأرض")
    }

    // ------------------------------------------------------------ setup

    private fun beginPart(i: Int) {
        partFirst[i] = partMb.vertexCount
    }

    private fun endPart(i: Int) {
        partCount[i] = partMb.vertexCount - partFirst[i]
    }

    private fun buildParts() {
        val mb = partMb

        // body: white robe, head, turban, eyes, backpack
        beginPart(P_BODY)
        mb.box(0f, 0.55f, 0f, 0.50f, 0.90f, 0.30f, 0.93f, 0.93f, 0.88f)
        mb.box(0f, 1.45f, 0f, 0.24f, 0.26f, 0.24f, 0.60f, 0.42f, 0.30f)
        mb.box(0f, 1.62f, 0f, 0.31f, 0.16f, 0.31f, 0.96f, 0.96f, 0.96f)
        mb.box(-0.06f, 1.53f, -0.125f, 0.04f, 0.04f, 0.02f, 0.05f, 0.05f, 0.05f)
        mb.box(0.06f, 1.53f, -0.125f, 0.04f, 0.04f, 0.02f, 0.05f, 0.05f, 0.05f)
        mb.box(0f, 0.80f, 0.22f, 0.40f, 0.55f, 0.16f, 0.40f, 0.33f, 0.20f)
        endPart(P_BODY)

        beginPart(P_LEG)
        mb.box(0f, -0.85f, 0f, 0.20f, 0.85f, 0.22f, 0.30f, 0.28f, 0.26f)
        endPart(P_LEG)

        beginPart(P_ARM)
        mb.box(0f, -0.62f, 0f, 0.14f, 0.62f, 0.14f, 0.93f, 0.93f, 0.88f)
        mb.box(0f, -0.62f, 0f, 0.15f, 0.12f, 0.15f, 0.60f, 0.42f, 0.30f)
        endPart(P_ARM)

        // guns: grip at origin, barrel toward -Z
        beginPart(P_GUN)      // pistol
        mb.box(0f, -0.02f, -0.14f, 0.05f, 0.07f, 0.30f, 0.25f, 0.25f, 0.27f)
        mb.box(0f, -0.14f, -0.03f, 0.045f, 0.14f, 0.07f, 0.20f, 0.15f, 0.10f)
        endPart(P_GUN)

        beginPart(P_GUN + 1)  // rifle
        mb.box(0f, -0.04f, -0.30f, 0.06f, 0.09f, 0.55f, 0.22f, 0.25f, 0.20f)
        mb.box(0f, 0.00f, -0.82f, 0.035f, 0.035f, 0.55f, 0.15f, 0.15f, 0.15f)
        mb.box(0f, -0.06f, 0.18f, 0.05f, 0.12f, 0.30f, 0.45f, 0.30f, 0.15f)
        mb.box(0f, -0.20f, -0.30f, 0.05f, 0.16f, 0.07f, 0.15f, 0.15f, 0.15f)
        mb.box(0f, -0.15f, -0.02f, 0.045f, 0.12f, 0.06f, 0.20f, 0.15f, 0.10f)
        endPart(P_GUN + 1)

        beginPart(P_GUN + 2)  // shotgun
        mb.box(0f, 0.00f, -0.50f, 0.05f, 0.05f, 0.90f, 0.20f, 0.20f, 0.22f)
        mb.box(0f, -0.06f, -0.45f, 0.07f, 0.06f, 0.25f, 0.40f, 0.28f, 0.15f)
        mb.box(0f, -0.05f, -0.05f, 0.06f, 0.10f, 0.30f, 0.25f, 0.25f, 0.25f)
        mb.box(0f, -0.06f, 0.20f, 0.05f, 0.12f, 0.30f, 0.45f, 0.30f, 0.15f)
        endPart(P_GUN + 2)

        beginPart(P_GUN + 3)  // sniper
        mb.box(0f, -0.04f, -0.35f, 0.06f, 0.09f, 0.70f, 0.20f, 0.28f, 0.20f)
        mb.box(0f, 0.00f, -1.00f, 0.03f, 0.03f, 0.70f, 0.12f, 0.12f, 0.12f)
        mb.box(0f, 0.05f, -0.35f, 0.05f, 0.05f, 0.30f, 0.05f, 0.05f, 0.05f)
        mb.box(0f, -0.06f, 0.20f, 0.05f, 0.13f, 0.35f, 0.35f, 0.30f, 0.20f)
        endPart(P_GUN + 3)

        beginPart(P_AMMO)
        mb.box(0f, 0f, 0f, 0.34f, 0.20f, 0.20f, 0.85f, 0.70f, 0.20f)
        mb.box(0f, 0.20f, 0f, 0.30f, 0.03f, 0.16f, 0.35f, 0.28f, 0.10f)
        endPart(P_AMMO)

        beginPart(P_MED)
        mb.box(0f, 0f, 0f, 0.38f, 0.26f, 0.26f, 0.95f, 0.95f, 0.95f)
        mb.box(0f, 0.26f, 0f, 0.22f, 0.02f, 0.07f, 0.85f, 0.10f, 0.10f)
        mb.box(0f, 0.26f, 0f, 0.07f, 0.02f, 0.22f, 0.85f, 0.10f, 0.10f)
        endPart(P_MED)

        beginPart(P_ARMOR)
        mb.box(0f, 0f, 0f, 0.42f, 0.45f, 0.14f, 0.20f, 0.35f, 0.70f)
        mb.box(-0.13f, 0.40f, 0f, 0.07f, 0.10f, 0.15f, 0.10f, 0.12f, 0.18f)
        mb.box(0.13f, 0.40f, 0f, 0.07f, 0.10f, 0.15f, 0.10f, 0.12f, 0.18f)
        endPart(P_ARMOR)

        partBuf = mb.toBuffer()
    }

    private fun pickType(): Int {
        val r = rng.nextInt(100)
        return when {
            r < 20 -> 0
            r < 35 -> 1
            r < 50 -> 2
            r < 60 -> 3
            r < 75 -> 4
            r < 90 -> 5
            else -> 6
        }
    }

    private fun buildPickupsAndDummies() {
        // loot yard around spawn: one of each item
        for (i in 0 until 7) {
            val a = (i * 2.0 * Math.PI / 7.0).toFloat()
            val x = spawnX + cos(a) * 3.5f
            val z = spawnZ + sin(a) * 3.5f
            pickups.add(Pickup(i, x, world.heightAt(x, z), z))
        }
        // loot inside buildings
        for (s in world.lootSpots) pickups.add(Pickup(pickType(), s[0], s[1], s[2]))

        // training dummies near spawn
        for (i in 0 until 6) {
            val a = (i * 60.0 * Math.PI / 180.0).toFloat()
            val r = 14f + (i % 2) * 8f
            tmp[0] = spawnX + cos(a) * r
            tmp[1] = spawnZ + sin(a) * r
            world.resolve(tmp, 0.5f, 0f)
            dummies.add(Dummy(tmp[0], world.heightAt(tmp[0], tmp[1]), tmp[1]))
        }
    }

    private fun upload(buf: FloatBuffer): IntArray {
        buf.position(0)
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        val va = ids[0]
        GLES30.glBindVertexArray(va)
        GLES20.glGenBuffers(1, ids, 0)
        val vb = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vb)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, buf.capacity() * 4, buf, GLES20.GL_STATIC_DRAW)
        setupAttribs()
        GLES30.glBindVertexArray(0)
        return intArrayOf(va, vb)
    }

    private fun setupAttribs() {
        val stride = 9 * 4
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, stride, 12)
        GLES20.glEnableVertexAttribArray(2)
        GLES20.glVertexAttribPointer(2, 3, GLES20.GL_FLOAT, false, stride, 24)
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(FOG_R, FOG_G, FOG_B, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        program = ShaderUtil.createProgram(VS, FS)
        uVp = GLES20.glGetUniformLocation(program, "uVP")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uCam = GLES20.glGetUniformLocation(program, "uCam")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
        uFog = GLES20.glGetUniformLocation(program, "uFog")
        uTint = GLES20.glGetUniformLocation(program, "uTint")

        if (meshBuf == null) {
            val mb = world.buildMesh()
            vertexCount = mb.vertexCount
            meshBuf = mb.toBuffer()
        }
        val w = upload(meshBuf!!)
        vao = w[0]; vbo = w[1]
        val p = upload(partBuf!!)
        partsVao = p[0]; partsVbo = p[1]

        // dynamic buffer
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        dynVao = ids[0]
        GLES30.glBindVertexArray(dynVao)
        GLES20.glGenBuffers(1, ids, 0)
        dynVbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, dynVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, DYN_VERTS * 9 * 4, null, GLES20.GL_STREAM_DRAW)
        setupAttribs()
        GLES30.glBindVertexArray(0)

        lastNs = System.nanoTime()
    }

    override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        val aspect = if (h == 0) 1f else w.toFloat() / h.toFloat()
        Matrix.perspectiveM(proj, 0, 70f, aspect, 0.1f, 400f)
    }

    override fun onDrawFrame(unused: GL10?) {
        val now = System.nanoTime()
        val raw = (now - lastNs) / 1_000_000_000f
        lastNs = now
        val dt = if (raw > 0.05f) 0.05f else raw

        update(dt)
        render()

        frames++
        acc += raw
        if (acc >= 1f) {
            onFps(frames)
            frames = 0
            acc -= 1f
        }
    }

    // ------------------------------------------------------------ game logic

    private fun say(text: String) {
        msg = text
        msgT = 2.5f
    }

    private fun update(dt: Float) {
        gameTime += dt
        if (input.toggleCam) {
            thirdPerson = !thirdPerson
            input.toggleCam = false
        }

        val look = input.takeLook()
        yaw += look[0] * SENS
        pitch = (pitch - look[1] * SENS).coerceIn(-85f, 85f)

        val yr = Math.toRadians(yaw.toDouble()).toFloat()
        val fx = sin(yr)
        val fz = -cos(yr)
        val rx = cos(yr)
        val rz = sin(yr)
        val mx = input.moveX
        val my = input.moveY

        tmp[0] = px + (fx * my + rx * mx) * SPEED * dt
        tmp[1] = pz + (fz * my + rz * mx) * SPEED * dt
        world.resolve(tmp, RADIUS, feetY)
        px = tmp[0]
        pz = tmp[1]

        if (onGround && input.jump) {
            vy = JUMP_V
            onGround = false
        }
        input.jump = false

        vy -= GRAVITY * dt
        feetY += vy * dt
        val g = world.surfaceY(px, pz, feetY)
        if (feetY <= g) {
            if (!onGround && vy < -14f) damagePlayer((-vy - 14f) * 6f)
            feetY = g
            vy = 0f
            onGround = true
        } else {
            onGround = false
        }
        if (feetY < -50f) respawnPlayer()

        // walk animation
        val moving = onGround && hypot(mx, my) > 0.1f
        val target: Float
        if (moving) {
            phase += dt * 10f
            target = sin(phase) * 40f
        } else {
            target = 0f
        }
        swing += (target - swing) * minOf(1f, dt * 15f)

        computeCamera()
        updateWeapons(dt)
        updatePickups()
        updateDummies(dt)
        updateEffects(dt)
        syncHud()
    }

    private fun computeCamera() {
        val yr = Math.toRadians(yaw.toDouble()).toFloat()
        val pr = Math.toRadians(pitch.toDouble()).toFloat()
        cdx = sin(yr) * cos(pr)
        cdy = sin(pr)
        cdz = -cos(yr) * cos(pr)

        camX = px
        camY = feetY + EYE
        camZ = pz
        showChar = false

        if (thirdPerson) {
            val tx = px
            val ty = feetY + 1.5f
            val tz = pz
            val rx = cos(yr)
            val rz = sin(yr)
            val ox = -cdx * CAM_DIST + rx * 0.5f
            val oy = -cdy * CAM_DIST + 0.25f
            val oz = -cdz * CAM_DIST + rz * 0.5f
            var t = 1f
            val steps = 14
            for (s in 1..steps) {
                val f = s.toFloat() / steps
                if (world.cameraBlocked(tx + ox * f, ty + oy * f, tz + oz * f)) {
                    t = (s - 1).toFloat() / steps * 0.9f
                    break
                }
            }
            camX = tx + ox * t
            camY = ty + oy * t
            camZ = tz + oz * t
            showChar = t > 0.3f
        }
    }

    private fun updateWeapons(dt: Float) {
        if (cooldown > 0f) cooldown -= dt
        if (flash > 0f) flash -= dt

        if (input.switchReq) {
            input.switchReq = false
            switchWeapon()
        }
        if (input.reloadReq) {
            input.reloadReq = false
            startReload()
        }
        if (reloading) {
            reloadT -= dt
            if (reloadT <= 0f) finishReload()
        }

        val edge = input.fireEdge
        input.fireEdge = false
        if (cur < 0) return

        val w = Weapons.list[cur]
        val want = if (w.auto) input.fire else edge
        if (want && !reloading && cooldown <= 0f) {
            if (mag[cur] > 0) shoot(w) else startReload()
        }
    }

    private fun switchWeapon() {
        var n = cur
        for (i in 1..4) {
            n = (n + 1) % 4
            if (has[n]) {
                cur = n
                reloading = false
                cooldown = 0.25f
                return
            }
        }
    }

    private fun startReload() {
        if (cur < 0 || reloading) return
        val w = Weapons.list[cur]
        if (mag[cur] >= w.mag || reserve[cur] <= 0) return
        reloading = true
        reloadT = w.reload
        sfx.play(SoundFx.RELOAD)
    }

    private fun finishReload() {
        reloading = false
        if (cur < 0) return
        val w = Weapons.list[cur]
        val take = minOf(w.mag - mag[cur], reserve[cur])
        mag[cur] += take
        reserve[cur] -= take
    }

    private fun muzzle(w: Int) {
        val yr = Math.toRadians(yaw.toDouble()).toFloat()
        val pr = Math.toRadians(pitch.toDouble()).toFloat()
        val len = 0.25f + MUZ_L[w]
        muz[0] = px + 0.3f * cos(yr) + len * cos(pr) * sin(yr)
        muz[1] = feetY + 1.25f + len * sin(pr)
        muz[2] = pz + 0.3f * sin(yr) - len * cos(pr) * cos(yr)
    }

    private fun shoot(w: WeaponDef) {
        mag[cur]--
        cooldown = w.interval
        flash = 0.05f
        sfx.play(cur)
        pitch = (pitch + RECOIL[cur]).coerceAtMost(85f)

        val moving = hypot(input.moveX, input.moveY) > 0.1f
        val spread = w.spread * (if (moving) 1.6f else 1f)
        muzzle(cur)

        for (i in 0 until w.pellets) {
            var dx = cdx + (rng.nextFloat() - 0.5f) * 2f * spread
            var dy = cdy + (rng.nextFloat() - 0.5f) * 2f * spread
            var dz = cdz + (rng.nextFloat() - 0.5f) * 2f * spread
            val l = sqrt(dx * dx + dy * dy + dz * dz)
            dx /= l; dy /= l; dz /= l

            var best = world.raycast(camX, camY, camZ, dx, dy, dz, w.range)
            var target: Dummy? = null
            var head = false
            for (dm in dummies) {
                if (!dm.alive) continue
                val tb = rayBox(
                    camX, camY, camZ, dx, dy, dz,
                    dm.x - 0.3f, dm.x + 0.3f, dm.y, dm.y + 1.45f, dm.z - 0.25f, dm.z + 0.25f
                )
                if (tb >= 0f && tb <= best) { best = tb; target = dm; head = false }
                val th = rayBox(
                    camX, camY, camZ, dx, dy, dz,
                    dm.x - 0.17f, dm.x + 0.17f, dm.y + 1.45f, dm.y + 1.8f, dm.z - 0.17f, dm.z + 0.17f
                )
                if (th >= 0f && th <= best) { best = th; target = dm; head = true }
            }

            val hx = camX + dx * best
            val hy = camY + dy * best
            val hz = camZ + dz * best
            tracers.add(Tracer(muz[0], muz[1], muz[2], hx, hy, hz))
            if (tracers.size > 40) tracers.removeAt(0)

            if (target != null) {
                damageDummy(target, w.dmg * (if (head) 2f else 1f))
                fxs.add(Fx(hx, hy, hz, 0.9f, 0.1f, 0.1f))
            } else if (best < w.range - 0.5f) {
                fxs.add(Fx(hx, hy, hz, 0.85f, 0.72f, 0.45f))
            }
            if (fxs.size > 40) fxs.removeAt(0)
        }
    }

    private fun damageDummy(dm: Dummy, amount: Float) {
        dm.hp -= amount
        dm.flash = 0.12f
        hitT = 0.25f
        sfx.play(SoundFx.HIT, 0.6f)
        if (dm.hp <= 0f) {
            dm.alive = false
            dm.respawn = 5f
            kills++
            say("قتلت هدفاً!")
        }
    }

    private fun rayBox(
        ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float,
        x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float
    ): Float {
        var tmin = 0f
        var tmax = 1e9f

        if (abs(dx) < 1e-6f) {
            if (ox < x0 || ox > x1) return -1f
        } else {
            var t1 = (x0 - ox) / dx
            var t2 = (x1 - ox) / dx
            if (t1 > t2) { val s = t1; t1 = t2; t2 = s }
            if (t1 > tmin) tmin = t1
            if (t2 < tmax) tmax = t2
            if (tmin > tmax) return -1f
        }
        if (abs(dy) < 1e-6f) {
            if (oy < y0 || oy > y1) return -1f
        } else {
            var t1 = (y0 - oy) / dy
            var t2 = (y1 - oy) / dy
            if (t1 > t2) { val s = t1; t1 = t2; t2 = s }
            if (t1 > tmin) tmin = t1
            if (t2 < tmax) tmax = t2
            if (tmin > tmax) return -1f
        }
        if (abs(dz) < 1e-6f) {
            if (oz < z0 || oz > z1) return -1f
        } else {
            var t1 = (z0 - oz) / dz
            var t2 = (z1 - oz) / dz
            if (t1 > t2) { val s = t1; t1 = t2; t2 = s }
            if (t1 > tmin) tmin = t1
            if (t2 < tmax) tmax = t2
            if (tmin > tmax) return -1f
        }
        return tmin
    }

    private fun updatePickups() {
        for (p in pickups) {
            if (!p.alive) continue
            val dx = px - p.x
            val dz = pz - p.z
            if (dx * dx + dz * dz < 2.6f && abs(feetY - p.y) < 1.6f) {
                if (tryPickup(p)) {
                    p.alive = false
                    sfx.play(SoundFx.PICKUP)
                }
            }
        }
    }

    private fun tryPickup(p: Pickup): Boolean {
        when (p.type) {
            0, 1, 2, 3 -> {
                val w = Weapons.list[p.type]
                if (!has[p.type]) {
                    has[p.type] = true
                    mag[p.type] = w.mag
                    reserve[p.type] = w.mag * 2
                    if (cur < 0) cur = p.type
                    say("التقطت: " + w.name)
                } else {
                    reserve[p.type] += w.mag
                    say("ذخيرة: " + w.name)
                }
                return true
            }
            4 -> {
                var any = false
                for (i in 0 until 4) {
                    if (has[i]) {
                        reserve[i] += Weapons.list[i].mag
                        any = true
                    }
                }
                if (any) say("ذخيرة إضافية")
                return any
            }
            5 -> {
                if (hp >= 100f) return false
                hp = minOf(100f, hp + 50f)
                say("حقيبة إسعاف +50")
                return true
            }
            else -> {
                if (armor >= 100f) return false
                armor = minOf(100f, armor + 50f)
                say("درع +50")
                return true
            }
        }
    }

    private fun updateDummies(dt: Float) {
        for (dm in dummies) {
            if (dm.flash > 0f) dm.flash -= dt
            if (!dm.alive) {
                dm.respawn -= dt
                if (dm.respawn <= 0f) {
                    dm.alive = true
                    dm.hp = 100f
                }
            }
        }
    }

    private fun updateEffects(dt: Float) {
        var i = 0
        while (i < tracers.size) {
            tracers[i].life -= dt
            if (tracers[i].life <= 0f) tracers.removeAt(i) else i++
        }
        i = 0
        while (i < fxs.size) {
            fxs[i].life -= dt
            if (fxs[i].life <= 0f) fxs.removeAt(i) else i++
        }
        if (msgT > 0f) msgT -= dt
        if (hitT > 0f) hitT -= dt
        if (hurtT > 0f) hurtT -= dt
    }

    private fun damagePlayer(amount: Float) {
        var a = amount
        if (armor > 0f) {
            val ab = minOf(armor, a * 0.5f)
            armor -= ab
            a -= ab
        }
        hp -= a
        hurtT = 0.5f
        if (hp <= 0f) respawnPlayer()
    }

    private fun respawnPlayer() {
        px = spawnX
        pz = spawnZ
        feetY = world.heightAt(px, pz)
        vy = 0f
        hp = 100f
        armor = 0f
        cur = -1
        reloading = false
        for (i in 0 until 4) {
            has[i] = false
            mag[i] = 0
            reserve[i] = 0
        }
        say("سقطت! بدأت من جديد")
    }

    private fun syncHud() {
        input.hp = hp.toInt()
        input.armor = armor.toInt()
        if (cur >= 0) {
            input.weaponName = Weapons.list[cur].name
            input.mag = mag[cur]
            input.reserve = reserve[cur]
        } else {
            input.weaponName = "بدون سلاح"
            input.mag = 0
            input.reserve = 0
        }
        input.reloading = reloading
        input.reloadFrac = if (reloading && cur >= 0) 1f - reloadT / Weapons.list[cur].reload else 0f
        input.hitMark = hitT
        input.hurt = hurtT
        input.msg = msg
        input.msgTime = msgT
        input.kills = kills
    }

    // ------------------------------------------------------------ rendering

    private fun render() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        Matrix.setLookAtM(
            view, 0, camX, camY, camZ,
            camX + cdx, camY + cdy, camZ + cdz, 0f, 1f, 0f
        )
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uVp, 1, false, vp, 0)
        GLES20.glUniform3f(uCam, camX, camY, camZ)
        GLES20.glUniform3f(uLight, 0.4165f, 0.8329f, 0.3644f)
        GLES20.glUniform3f(uFog, FOG_R, FOG_G, FOG_B)
        GLES20.glUniform3f(uTint, 1f, 1f, 1f)

        // world
        GLES20.glUniformMatrix4fv(uModel, 1, false, ident, 0)
        GLES30.glBindVertexArray(vao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        // parts-based objects
        GLES30.glBindVertexArray(partsVao)
        drawPickups()
        drawDummies()
        if (showChar) drawCharacter()
        GLES20.glUniform3f(uTint, 1f, 1f, 1f)

        // dynamic effects
        drawDynamic()
        GLES30.glBindVertexArray(0)
    }

    private fun drawPart(m: FloatArray, idx: Int) {
        GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, partFirst[idx], partCount[idx])
    }

    /** Draw a limb: copies base, moves to pivot, rotates around X, draws. */
    private fun limb(base: FloatArray, ox: Float, oy: Float, angle: Float, idx: Int) {
        System.arraycopy(base, 0, m2, 0, 16)
        Matrix.translateM(m2, 0, ox, oy, 0f)
        Matrix.rotateM(m2, 0, angle, 1f, 0f, 0f)
        drawPart(m2, idx)
    }

    private fun drawCharacter() {
        Matrix.setIdentityM(m1, 0)
        Matrix.translateM(m1, 0, px, feetY, pz)
        Matrix.rotateM(m1, 0, -yaw, 0f, 1f, 0f)

        drawPart(m1, P_BODY)
        limb(m1, -0.13f, 0.85f, swing, P_LEG)
        limb(m1, 0.13f, 0.85f, -swing, P_LEG)
        if (cur >= 0) {
            limb(m1, -0.34f, 1.42f, 70f + pitch, P_ARM)
            limb(m1, 0.34f, 1.42f, 80f + pitch, P_ARM)

            // gun in hand
            Matrix.setIdentityM(m3, 0)
            Matrix.translateM(m3, 0, px, feetY + 1.25f, pz)
            Matrix.rotateM(m3, 0, -yaw, 0f, 1f, 0f)
            Matrix.rotateM(m3, 0, pitch, 1f, 0f, 0f)
            Matrix.translateM(m3, 0, 0.30f, 0f, -0.25f)
            drawPart(m3, P_GUN + cur)
        } else {
            limb(m1, -0.34f, 1.42f, -swing, P_ARM)
            limb(m1, 0.34f, 1.42f, swing, P_ARM)
        }
    }

    private fun drawDummies() {
        for (dm in dummies) {
            if (!dm.alive) continue
            val ddx = px - dm.x
            val ddz = pz - dm.z
            if (ddx * ddx + ddz * ddz > 150f * 150f) continue
            val ang = Math.toDegrees(atan2(ddx.toDouble(), -ddz.toDouble())).toFloat()
            Matrix.setIdentityM(m1, 0)
            Matrix.translateM(m1, 0, dm.x, dm.y, dm.z)
            Matrix.rotateM(m1, 0, -ang, 0f, 1f, 0f)
            if (dm.flash > 0f) GLES20.glUniform3f(uTint, 1.8f, 1.2f, 1.2f)
            else GLES20.glUniform3f(uTint, 1f, 0.5f, 0.45f)
            drawPart(m1, P_BODY)
            limb(m1, -0.13f, 0.85f, 0f, P_LEG)
            limb(m1, 0.13f, 0.85f, 0f, P_LEG)
            limb(m1, -0.34f, 1.42f, 0f, P_ARM)
            limb(m1, 0.34f, 1.42f, 0f, P_ARM)
        }
        GLES20.glUniform3f(uTint, 1f, 1f, 1f)
    }

    private fun drawPickups() {
        for (p in pickups) {
            if (!p.alive) continue
            val dx = px - p.x
            val dz = pz - p.z
            if (dx * dx + dz * dz > 70f * 70f) continue
            Matrix.setIdentityM(m3, 0)
            Matrix.translateM(m3, 0, p.x, p.y + 0.55f + 0.08f * sin(gameTime * 2f + p.x), p.z)
            Matrix.rotateM(m3, 0, gameTime * 90f, 0f, 1f, 0f)
            val idx: Int
            if (p.type < 4) {
                Matrix.scaleM(m3, 0, 1.5f, 1.5f, 1.5f)
                Matrix.translateM(m3, 0, 0f, 0f, 0.4f)
                idx = P_GUN + p.type
            } else {
                idx = P_AMMO + (p.type - 4)
            }
            drawPart(m3, idx)
        }
    }

    private fun drawDynamic() {
        dyn.clear()
        for (t in tracers) {
            dyn.ribbon(t.x0, t.y0, t.z0, t.x1, t.y1, t.z1, 0.025f, 1f, 0.9f, 0.45f)
        }
        for (f in fxs) {
            val s = 0.12f + (0.25f - f.life) * 0.6f
            dyn.box(f.x, f.y - s / 2, f.z, s, s, s, f.r, f.g, f.b)
        }
        if (flash > 0f && cur >= 0 && showChar) {
            muzzle(cur)
            dyn.box(muz[0], muz[1] - 0.09f, muz[2], 0.18f, 0.18f, 0.18f, 1f, 0.85f, 0.3f)
        }
        val count = dyn.fill(dynBuf)
        if (count == 0) return

        GLES20.glUniformMatrix4fv(uModel, 1, false, ident, 0)
        GLES30.glBindVertexArray(dynVao)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, dynVbo)
        dynBuf.position(0)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, count * 9 * 4, dynBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
    }
}
