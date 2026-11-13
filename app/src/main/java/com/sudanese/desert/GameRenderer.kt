package com.sudanese.desert

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class GameRenderer(
    private val input: GameInput,
    private val onFps: (Int) -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        private const val SENS = 0.12f      // degrees per pixel
        private const val SPEED = 6f        // meters per second
        private const val RADIUS = 0.4f     // player collision radius
        private const val EYE = 1.7f        // eye height
        private const val JUMP_V = 7f
        private const val GRAVITY = 20f

        // sky / fog color
        private const val FOG_R = 0.74f
        private const val FOG_G = 0.84f
        private const val FOG_B = 0.94f

        private const val VS = """#version 300 es
uniform mat4 uMVP;
uniform vec3 uCam;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
out vec3 vColor;
out vec3 vNormal;
out float vDist;
void main() {
    vColor = aColor;
    vNormal = aNormal;
    vDist = length(aPos - uCam);
    gl_Position = uMVP * vec4(aPos, 1.0);
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
    private val tmp = FloatArray(2)

    // GL
    private var program = 0
    private var vao = 0
    private var vbo = 0
    private var uMvp = 0
    private var uCam = 0
    private var uLight = 0
    private var uFog = 0
    private var meshBuf: FloatBuffer? = null
    private var vertexCount = 0
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)

    // timing
    private var lastNs = 0L
    private var frames = 0
    private var acc = 0f

    init {
        tmp[0] = 0f; tmp[1] = 0f
        world.resolve(tmp, RADIUS)
        px = tmp[0]; pz = tmp[1]
        feetY = world.heightAt(px, pz)
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(FOG_R, FOG_G, FOG_B, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        program = ShaderUtil.createProgram(VS, FS)
        uMvp = GLES20.glGetUniformLocation(program, "uMVP")
        uCam = GLES20.glGetUniformLocation(program, "uCam")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
        uFog = GLES20.glGetUniformLocation(program, "uFog")

        if (meshBuf == null) {
            val mb = world.buildMesh()
            vertexCount = mb.vertexCount
            meshBuf = mb.toBuffer()
        }
        val buf = meshBuf!!
        buf.position(0)

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glBindVertexArray(vao)

        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, buf.capacity() * 4, buf, GLES20.GL_STATIC_DRAW)

        val stride = 9 * 4
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, stride, 12)
        GLES20.glEnableVertexAttribArray(2)
        GLES20.glVertexAttribPointer(2, 3, GLES20.GL_FLOAT, false, stride, 24)
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

    private fun update(dt: Float) {
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
        world.resolve(tmp, RADIUS)
        px = tmp[0]
        pz = tmp[1]

        if (onGround && input.jump) {
            vy = JUMP_V
            onGround = false
        }
        input.jump = false

        vy -= GRAVITY * dt
        feetY += vy * dt
        val g = world.heightAt(px, pz)
        if (feetY <= g) {
            feetY = g
            vy = 0f
            onGround = true
        } else {
            onGround = false
        }
    }

    private fun render() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val ey = feetY + EYE
        val yr = Math.toRadians(yaw.toDouble()).toFloat()
        val pr = Math.toRadians(pitch.toDouble()).toFloat()
        val dx = sin(yr) * cos(pr)
        val dy = sin(pr)
        val dz = -cos(yr) * cos(pr)
        Matrix.setLookAtM(view, 0, px, ey, pz, px + dx, ey + dy, pz + dz, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, vp, 0)
        GLES20.glUniform3f(uCam, px, ey, pz)
        GLES20.glUniform3f(uLight, 0.4165f, 0.8329f, 0.3644f)
        GLES20.glUniform3f(uFog, FOG_R, FOG_G, FOG_B)

        GLES30.glBindVertexArray(vao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES30.glBindVertexArray(0)
    }
}
