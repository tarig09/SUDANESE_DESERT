package com.sudanese.desert

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** Sound effects generated into res/raw. Indices 0..3 = weapon shots (same order as Weapons.list). */
class SoundFx(context: Context) {
    companion object {
        const val RELOAD = 4
        const val PICKUP = 5
        const val HIT = 6
    }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = IntArray(7)

    init {
        ids[0] = pool.load(context, R.raw.s_pistol, 1)
        ids[1] = pool.load(context, R.raw.s_rifle, 1)
        ids[2] = pool.load(context, R.raw.s_shotgun, 1)
        ids[3] = pool.load(context, R.raw.s_sniper, 1)
        ids[4] = pool.load(context, R.raw.s_reload, 1)
        ids[5] = pool.load(context, R.raw.s_pickup, 1)
        ids[6] = pool.load(context, R.raw.s_hit, 1)
    }

    fun play(i: Int, vol: Float = 1f) {
        if (i < 0 || i >= ids.size) return
        pool.play(ids[i], vol, vol, 1, 0, 1f)
    }

    fun release() {
        pool.release()
    }
}
