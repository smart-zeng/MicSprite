package io.github.smartzeng.micsprite.decoder.pag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import io.github.smartzeng.micsprite.core.MicSpriteBaker
import io.github.smartzeng.micsprite.core.MicSpriteConfig
import io.github.smartzeng.micsprite.core.MicSpriteDecoder
import io.github.smartzeng.micsprite.core.MicSpriteRaster
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.libpag.PAGDecoder
import org.libpag.PAGFile

class PagDecoder : MicSpriteDecoder {

    override fun supports(url: String): Boolean {
        return MicSpriteBaker.extensionOf(url) == "pag"
    }

    override suspend fun bake(
        context: Context,
        url: String,
        maxTextureSize: Int,
        frameSizePx: Int,
    ): MicSpriteRaster? {
        val file = MicSpriteBaker.materialize(context, url) ?: return null
        return withContext(bakeDispatcher) {
            val composition = PAGFile.Load(file.absolutePath)
            if (composition == null) {
                MicSpriteConfig.logger.w("pag load failed file=${file.absolutePath} size=${file.length()}")
                return@withContext null
            }
            val longSide = maxOf(composition.width(), composition.height()).coerceAtLeast(1)
            val scale = frameSizePx.toFloat() / longSide
            val nativeFps = composition.frameRate().coerceAtLeast(1f)
            val decoder = PAGDecoder.Make(composition, nativeFps, scale)
            if (decoder == null) {
                MicSpriteConfig.logger.w("pag decoder failed file=${file.absolutePath}")
                return@withContext null
            }
            try {
                val frame = Bitmap.createBitmap(
                    decoder.width(),
                    decoder.height(),
                    Bitmap.Config.ARGB_8888,
                )
                try {
                    MicSpriteBaker.buildStrip(
                        totalFrames = decoder.numFrames().coerceAtLeast(1),
                        originalFps = decoder.frameRate().roundToInt().coerceAtLeast(1),
                        maxTextureSize = maxTextureSize,
                        yieldEvery = MicSpriteBaker.backgroundYieldEvery,
                    ) { src, canvas ->
                        frame.eraseColor(Color.TRANSPARENT)
                        if (decoder.copyFrameTo(frame, src)) {
                            MicSpriteBaker.drawFitCenter(canvas, frame, frameSizePx)
                        } else {
                            MicSpriteConfig.logger.w("pag frame $src skipped")
                        }
                    }
                } finally {
                    frame.recycle()
                }
            } finally {
                decoder.release()
            }
        }
    }

    private companion object {
        private val bakeDispatcher = Dispatchers.Default.limitedParallelism(1)
    }
}
