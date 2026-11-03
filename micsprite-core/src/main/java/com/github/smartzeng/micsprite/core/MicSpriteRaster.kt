package com.github.smartzeng.micsprite.core

import android.graphics.Bitmap


class MicSpriteRaster(
    val atlas: Bitmap,
    val frameCount: Int,
    val fps: Float,
    val frameSize: Int,
    val cols: Int,
    val rows: Int,
)
