package com.sudanese.desert

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class GameRenderer(
    private val input: GameInput,
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
out vec4 fragColor;
void main() {
    float d = max(dot(normalize(vNormal), uLight), 0.0);
    vec3 c = vColor * (0.45 + 0.65 * d);
    float f = clamp((vDist - 60.0) / 160.0, 0.0, 1.0);
    fragColor = vec4(mix(c, uFog, f), 1.0);
}
"""
    }

    private val world = World()

    // player
    private var px = 0f
    private var pz = 0f
    private var feetY = 0f
    private var vy = 0f
    private var yaw = 0f
    private var pitch = 0f
    private var onGround = false
    private var thirdPerson = true
    private var phase = 0f
    private var swing = 0f
    private val tmp = FloatArray(2)

    // GL
    private var program = 0
    private var vao = 0
    private var vbo = 0
    private var charVao = 0
    private var charVbo = 0
    private var uVp = 0
    private var uModel = 0
    private var uCam = 0
    private var uLight = 0
    private var uFog = 0
    private var meshBuf: FloatBuffer? = null
    private var vertexCount = 0
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val ident = FloatArray(16)
    private val m1 = FloatArray(16)
    private val m2 = FloatArray(16)

    // character mesh (body, leg, arm parts in one buffer)
    private val charMb = MeshBuilder()
    private var charBuf: FloatBuffer? = null
    private var bodyCount = 0
    private var legFirst = 0
    private var legCount = 0
    private var armFirst = 0
    private var armCount = 0

    // timing
    private var lastNs = 0L
    private var frames = 0
    private var acc = 0f

    init {
        Matrix.setIdentityM(ident, 0)
        buildCharacter()
        tmp[0] = 0f
        tmp[1] = 0f
        feetY = world.heightAt(0f, 0f)
        world.resolve(tmp, RADIUS, feetY)
        px = tmp[0]
        pz = tmp[1]
        feetY = world.heightAt(px, pz)
    }

    private fun buildCharacter() {
        val mb = charMb
        // body: white robe (jalabiya), head, turban, eyes, backpack
        mb.box(0f, 0.55f, 0f, 0.50f, 0.90f, 0.30f, 0.93f, 0.93f, 0.88f)
        mb.box(0f, 1.45f, 0f, 0.24f, 0.26f, 0.24f, 0.60f, 0.42f, 0.30f)
        mb.box(0f, 1.62f, 0f, 0.31f, 0.16f, 0.31f, 0.96f, 0.96f, 0.96f)
        mb.box(-0.06f, 1.53f, -0.125f, 0.04f, 0.04f, 0.02f, 0.05f, 0.05f, 0.05f)
        mb.box(0.06f, 1.53f, -0.125f, 0.04f, 0.04f, 0.02f, 0.05f, 0.05f, 0.05f)
        mb.box(0f, 0.80f, 0.22f, 0.40f, 0.55f, 0.16f, 0.40f, 0.33f, 0.20f)
        bodyCount = mb.vertexCount
        // leg (pivot at hip, hangs down)
        legFirst = mb.vertexCount
        mb.box(0f, -0.85f, 0f, 0.20f, 0.85f, 0.22f, 0.30f, 0.28f, 0.26f)
        legCount = mb.vertexCount - legFirst
        // arm (pivot at shoulder, hangs down)
        armFirst = mb.vertexCount
        mb.box(0f, -0.62f, 0f, 0.14f, 0.62f, 0.14f, 0.93f, 0.93f, 0.88f)
        mb.box(0f, -0.62f, 0f, 0.15f, 0.12f, 0.15f, 0.60f, 0.42f, 0.30f)
        armCount = mb.vertexCount - armFirst
        charBuf = mb.toBuffer()
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
        val stride = 9 * 4
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, stride, 12)
        GLES20.glEnableVertexAttribArray(2)
        GLES20.glVertexAttribPointer(2, 3, GLES20.GL_FLOAT, false, stride, 24)
        GLES30.glBindVertexArray(0)
        return intArrayOf(va, vb)
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

        if (meshBuf == null) {
            val mb = world.buildMesh()
            vertexCount = mb.vertexCount
            meshBuf = mb.toBuffer()
        }
        val w = upload(meshBuf!!)
        vao = w[0]; vbo = w[1]
        val c = upload(charBuf!!)
        charVao = c[0]; charVbo = c[1]

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

    private fun update(dt: Float) {
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
            feetY = g
            vy = 0f
            onGround = true
        } else {
            onGround = false
        }

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
    }

    private fun render() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val yr = Math.toRadians(yaw.toDouble()).toFloat()
        val pr = Math.toRadians(pitch.toDouble()).toFloat()
        val dx = sin(yr) * cos(pr)
        val dy = sin(pr)
        val dz = -cos(yr) * cos(pr)

        var ex = px
        var ey = feetY + EYE
        var ez = pz
        var showChar = false

        if (thirdPerson) {
            val tx = px
            val ty = feetY + 1.5f
            val tz = pz
            val rx = cos(yr)
            val rz = sin(yr)
            val ox = -dx * CAM_DIST + rx * 0.5f
            val oy = -dy * CAM_DIST + 0.25f
            val oz = -dz * CAM_DIST + rz * 0.5f
            var t = 1f
            val steps = 14
            for (s in 1..steps) {
                val f = s.toFloat() / steps
                if (world.cameraBlocked(tx + ox * f, ty + oy * f, tz + oz * f)) {
                    t = (s - 1).toFloat() / steps * 0.9f
                    break
                }
            }
            ex = tx + ox * t
            ey = ty + oy * t
            ez = tz + oz * t
            showChar = t > 0.3f
        }

        Matrix.setLookAtM(view, 0, ex, ey, ez, ex + dx, ey + dy, ez + dz, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uVp, 1, false, vp, 0)
        GLES20.glUniform3f(uCam, ex, ey, ez)
        GLES20.glUniform3f(uLight, 0.4165f, 0.8329f, 0.3644f)
        GLES20.glUniform3f(uFog, FOG_R, FOG_G, FOG_B)

        // world
        GLES20.glUniformMatrix4fv(uModel, 1, false, ident, 0)
        GLES30.glBindVertexArray(vao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        // character
        if (showChar) drawCharacter()
        GLES30.glBindVertexArray(0)
    }

    private fun drawCharacter() {
        GLES30.glBindVertexArray(charVao)

        Matrix.setIdentityM(m1, 0)
        Matrix.translateM(m1, 0, px, feetY, pz)
        Matrix.rotateM(m1, 0, -yaw, 0f, 1f, 0f)

        drawPart(m1, 0, bodyCount)
        part(-0.13f, 0.85f, swing, legFirst, legCount)    // left leg
        part(0.13f, 0.85f, -swing, legFirst, legCount)    // right leg
        part(-0.34f, 1.42f, -swing, armFirst, armCount)   // left arm
        part(0.34f, 1.42f, swing, armFirst, armCount)     // right arm
    }

    private fun part(ox: Float, oy: Float, angle: Float, first: Int, count: Int) {
        System.arraycopy(m1, 0, m2, 0, 16)
        Matrix.translateM(m2, 0, ox, oy, 0f)
        Matrix.rotateM(m2, 0, angle, 1f, 0f, 0f)
        drawPart(m2, first, count)
    }

    private fun drawPart(m: FloatArray, first: Int, count: Int) {
        GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, first, count)
    }
}
