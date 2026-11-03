package io.github.smartzeng.micsprite.core

import android.content.Context


interface MicSpriteDecoder {

    fun supports(url: String): Boolean

    suspend fun bake(
        context: Context,
        url: String,
        maxTextureSize: Int,
        frameSizePx: Int,
    ): MicSpriteRaster?
}
