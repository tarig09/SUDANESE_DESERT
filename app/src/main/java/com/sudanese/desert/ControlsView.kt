package com.sudanese.desert

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Transparent touch overlay: left = floating joystick, right = look, button = jump. */
class ControlsView(context: Context, private val input: GameInput) : View(context) {
    private val d = resources.displayMetrics.density
    private val stickR = 55f * d
    private val jr = 38f * d

    private var moveId = -1
    private var lookId = -1
    private var jumpId = -1
    private var baseX = 0f
    private var baseY = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var jx = 0f
    private var jy = 0f

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = Color.argb(136, 255, 255, 255)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(68, 255, 255, 255)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(221, 255, 255, 255)
        textSize = 14f * d
        textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        jx = w - 100f * d
        jy = h - 90f * d
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                if (jumpId == -1 && hypot(x - jx, y - jy) <= jr * 1.4f) {
                    jumpId = id
                    input.jump = true
                } else if (x < width / 2f) {
                    if (moveId == -1) {
                        moveId = id
                        baseX = x; baseY = y; knobX = x; knobY = y
                    }
                } else if (lookId == -1) {
                    lookId = id
                    lastX = x; lastY = y
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
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(e.getPointerId(e.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                release(moveId); release(lookId); release(jumpId)
            }
        }
        invalidate()
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
        }
    }

    override fun onDraw(c: Canvas) {
        if (moveId != -1) {
            c.drawCircle(baseX, baseY, stickR, ring)
            c.drawCircle(knobX, knobY, stickR * 0.4f, fill)
        } else {
            c.drawCircle(110f * d, height - 100f * d, stickR, ring)
        }
        c.drawCircle(jx, jy, jr, fill)
        c.drawCircle(jx, jy, jr, ring)
        c.drawText("قفز", jx, jy + 5f * d, label)
    }
}
