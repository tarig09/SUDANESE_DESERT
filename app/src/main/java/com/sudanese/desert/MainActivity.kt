package com.sudanese.desert

import android.app.Activity
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var sfx: SoundFx

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val input = GameInput()

        val fpsText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "FPS --"
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }

        sfx = SoundFx(applicationContext)
        val renderer = GameRenderer(input, sfx, assets) { fps ->
            fpsText.post { fpsText.text = "FPS $fps" }
        }

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            preserveEGLContextOnPause = true
        }

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val root = FrameLayout(this)
        root.addView(glView, FrameLayout.LayoutParams(match, match))
        root.addView(ControlsView(this, input), FrameLayout.LayoutParams(match, match))
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(24, 16, 0, 0)
        }
        root.addView(fpsText, lp)
        setContentView(root)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        sfx.release()
    }
}
