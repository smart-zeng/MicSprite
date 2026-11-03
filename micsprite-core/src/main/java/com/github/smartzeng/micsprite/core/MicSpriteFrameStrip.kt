package com.github.smartzeng.micsprite.core

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicInteger

class MicSpriteFrameStrip(
    val url: String,
    val atlas: Bitmap,
    val frameCount: Int,
    val fps: Float,
    val frameSize: Int,
    val startFrame: Int,
    val cols: Int,
    val rows: Int,
) {
    private val refs = AtomicInteger(1)

    fun retain(): MicSpriteFrameStrip {
        refs.incrementAndGet()
        return this
    }

    fun release() {
        if (refs.decrementAndGet() <= 0 && !atlas.isRecycled) {
            atlas.recycle()
        }
    }
}
