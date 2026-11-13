package com.sudanese.desert

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Touch overlay + HUD: joystick (left), look (right), fire / jump / reload / switch / camera buttons. */
class ControlsView(context: Context, private val input: GameInput) : View(context) {
    private val d = resources.displayMetrics.density
    private val stickR = 55f * d

    private var moveId = -1
    private var lookId = -1
    private var jumpId = -1
    private var fireId = -1
    private var baseX = 0f
    private var baseY = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var fireLastX = 0f
    private var fireLastY = 0f

    // buttons (center x, y) and radii
    private var fbX = 0f; private var fbY = 0f; private val fbR = 44f * d
    private var jbX = 0f; private var jbY = 0f; private val jbR = 30f * d
    private var rbX = 0f; private var rbY = 0f; private val rbR = 28f * d
    private var sbX = 0f; private var sbY = 0f; private val sbR = 28f * d
    private var cbX = 0f; private var cbY = 0f; private val cbR = 28f * d

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = Color.argb(136, 255, 255, 255)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(68, 255, 255, 255)
    }
    private val fireFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(110, 255, 60, 40)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = 13f * d
        textAlign = Paint.Align.CENTER
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f * d
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * d
        textAlign = Paint.Align.LEFT
        setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
    }
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) }
    private val hpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 50, 50) }
    private val armorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 130, 230) }
    private val reloadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 200, 60) }
    private val hitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 70, 70)
        strokeWidth = 3f * d
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fbX = w - 105f * d; fbY = h - 95f * d
        jbX = w - 200f * d; jbY = h - 55f * d
        rbX = w - 55f * d; rbY = h - 195f * d
        sbX = w - 150f * d; sbY = h - 170f * d
        cbX = w - 60f * d; cbY = 50f * d
    }

    private fun hit(x: Float, y: Float, cx: Float, cy: Float, r: Float) = hypot(x - cx, y - cy) <= r * 1.25f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                when {
                    hit(x, y, cbX, cbY, cbR) -> input.toggleCam = true
                    hit(x, y, rbX, rbY, rbR) -> input.reloadReq = true
                    hit(x, y, sbX, sbY, sbR) -> input.switchReq = true
                    fireId == -1 && hit(x, y, fbX, fbY, fbR) -> {
                        fireId = id
                        fireLastX = x; fireLastY = y
                        input.fire = true
                        input.fireEdge = true
                    }
                    jumpId == -1 && hit(x, y, jbX, jbY, jbR) -> {
                        jumpId = id
                        input.jump = true
                    }
                    x < width / 2f -> {
                        if (moveId == -1) {
                            moveId = id
                            baseX = x; baseY = y; knobX = x; knobY = y
                        }
                    }
                    lookId == -1 -> {
                        lookId = id
                        lastX = x; lastY = y
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val x = e.getX(i)
                    val y = e.getY(i)
                    if (id == moveId) {
                        var dx = x - baseX
                        var dy = y - baseY
                        val len = hypot(dx, dy)
                        if (len > stickR) {
                            dx = dx / len * stickR
                            dy = dy / len * stickR
                        }
                        knobX = baseX + dx
                        knobY = baseY + dy
                        input.moveX = dx / stickR
                        input.moveY = -dy / stickR
                    } else if (id == lookId) {
                        input.addLook(x - lastX, y - lastY)
                        lastX = x; lastY = y
                    } else if (id == fireId) {
                        // dragging the fire thumb also aims
                        input.addLook(x - fireLastX, y - fireLastY)
                        fireLastX = x; fireLastY = y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(e.getPointerId(e.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                release(moveId); release(lookId); release(jumpId); release(fireId)
            }
        }
        return true
    }

    private fun release(id: Int) {
        if (id == -1) return
        if (id == moveId) {
            moveId = -1
            input.moveX = 0f
            input.moveY = 0f
        } else if (id == lookId) {
            lookId = -1
        } else if (id == jumpId) {
            jumpId = -1
        } else if (id == fireId) {
            fireId = -1
            input.fire = false
        }
    }

    private fun button(c: Canvas, x: Float, y: Float, r: Float, text: String, paint: Paint = fill) {
        c.drawCircle(x, y, r, paint)
        c.drawCircle(x, y, r, ring)
        c.drawText(text, x, y + 5f * d, label)
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, h: Float, frac: Float, p: Paint) {
        c.drawRect(x, y, x + w, y + h, barBg)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0f) c.drawRect(x, y, x + w * f, y + h, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // hurt flash
        val hurt = input.hurt
        if (hurt > 0f) c.drawColor(Color.argb((hurt * 220f).toInt().coerceIn(0, 120), 255, 0, 0))

        // joystick
        if (moveId != -1) {
            c.drawCircle(baseX, baseY, stickR, ring)
            c.drawCircle(knobX, knobY, stickR * 0.4f, fill)
        } else {
            c.drawCircle(110f * d, h - 100f * d, stickR, ring)
        }

        // buttons
        button(c, fbX, fbY, fbR, "نار", if (fireId != -1) fireFill else fill)
        button(c, jbX, jbY, jbR, "قفز")
        button(c, rbX, rbY, rbR, "تعبئة")
        button(c, sbX, sbY, sbR, "سلاح")
        button(c, cbX, cbY, cbR, "كاميرا")

        // crosshair
        val cx = w / 2f
        val cy = h / 2f
        c.drawCircle(cx, cy, 2.5f * d, fill)
        c.drawCircle(cx, cy, 5f * d, ring)

        // hit marker
        if (input.hitMark > 0f) {
            val a = 6f * d
            val b = 13f * d
            c.drawLine(cx - b, cy - b, cx - a, cy - a, hitPaint)
            c.drawLine(cx + b, cy - b, cx + a, cy - a, hitPaint)
            c.drawLine(cx - b, cy + b, cx - a, cy + a, hitPaint)
            c.drawLine(cx + b, cy + b, cx + a, cy + a, hitPaint)
        }

        // weapon + ammo (top center)
        c.drawText(input.weaponName, cx, 28f * d, big)
        if (input.weaponName != "بدون سلاح") {
            c.drawText("${input.mag} / ${input.reserve}", cx, 50f * d, big)
        }
        if (input.reloading) {
            bar(c, cx - 60f * d, 58f * d, 120f * d, 6f * d, input.reloadFrac, reloadPaint)
        }

        // message
        if (input.msgTime > 0f) {
            c.drawText(input.msg, cx, 96f * d, big)
        }

        // kills
        c.drawText("قتل: ${input.kills}", 14f * d, 60f * d, small)

        // health + armor (bottom center)
        val bw = 200f * d
        val bx = cx - bw / 2f
        bar(c, bx, h - 52f * d, bw, 8f * d, input.armor / 100f, armorPaint)
        bar(c, bx, h - 38f * d, bw, 12f * d, input.hp / 100f, hpPaint)
        c.drawText("${input.hp}", cx, h - 42f * d + 1f * d, label)

        postInvalidateDelayed(33)
    }
}
