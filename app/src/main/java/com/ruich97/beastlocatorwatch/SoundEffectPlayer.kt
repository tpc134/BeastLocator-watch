package com.ruich97.beastlocatorwatch

import android.content.Context

object SoundEffectPlayer {
    private const val PRIORITY_INTERVAL = 1
    private const val PRIORITY_ARRIVAL = 2
    private const val PRIORITY_114514 = 3

    fun play(context: Context, rawResId: Int) {
        SoundPlaybackService.start(context, rawResId, resolvePriority(rawResId))
    }

    private fun resolvePriority(rawResId: Int): Int {
        return when (rawResId) {
            R.raw.distance_114514km -> PRIORITY_114514
            R.raw.arrival_0km -> PRIORITY_ARRIVAL
            R.raw.distance_interval_kankaku -> PRIORITY_INTERVAL
            else -> 0
        }
    }
}
