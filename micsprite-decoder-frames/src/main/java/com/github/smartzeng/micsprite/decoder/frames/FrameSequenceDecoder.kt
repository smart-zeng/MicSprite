package com.github.smartzeng.micsprite.decoder.frames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.github.penfeizhou.animation.decode.FrameSeqDecoder
import com.github.penfeizhou.animation.gif.decode.GifDecoder
import com.github.penfeizhou.animation.loader.FileLoader
import com.github.penfeizhou.animation.webp.decode.WebPDecoder
import com.github.smartzeng.micsprite.core.MicSpriteBaker
import com.github.smartzeng.micsprite.core.MicSpriteConfig
import com.github.smartzeng.micsprite.core.MicSpriteDecoder
import com.github.smartzeng.micsprite.core.MicSpriteRaster
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class FrameSequenceDecoder : MicSpriteDecoder {

    override fun supports(url: String): Boolean {
        val ext = MicSpriteBaker.extensionOf(url)
        if (ext == "gif" || ext == "webp") return true
        val file = localFile(url) ?: return false
        val format = MicSpriteBaker.sniffFormat(file)
        return format == "gif" || format == "webp"
    }

    override suspend fun bake(
        context: Context,
        url: String,
        maxTextureSize: Int,
        frameSizePx: Int,
    ): MicSpriteRaster? {
        val file = MicSpriteBaker.materialize(context, url) ?: return null
        val ext = MicSpriteBaker.extensionOf(url)
        val gif = when (ext) {
            "gif" -> true
            "webp" -> false
            else -> MicSpriteBaker.sniffFormat(file) == "gif"
        }
        val raster = bakeFrameSequence(file, maxTextureSize, frameSizePx, gif)
        if (raster != null) return raster
        if (gif) return bakeWithMovie(file, maxTextureSize, frameSizePx)
        return null
    }

    private suspend fun bakeFrameSequence(
        file: File,
        maxTextureSize: Int,
        frameSizePx: Int,
        gif: Boolean,
    ): MicSpriteRaster? {
        return withContext(bakeDispatcher) {
            val decoder: FrameSeqDecoder<*, *> = if (gif) {
                GifDecoder(FileLoader(file.absolutePath), null)
            } else {
                WebPDecoder(FileLoader(file.absolutePath), null)
            }
            try {
                // getFrameBitmap 结束会 innerStop() 并清空 frames。
                // 用 getBounds() 解析，先读 frameCount / 时长，再逐帧抽图。
                val bounds = try {
                    decoder.bounds
                } catch (error: Exception) {
                    MicSpriteConfig.logger.w(
                        "decode bounds failed file=${file.absolutePath} size=${file.length()}",
                        error,
                    )
                    return@withContext null
                }
                if (bounds.width() <= 0 || bounds.height() <= 0) {
                    MicSpriteConfig.logger.w(
                        "empty bounds file=${file.absolutePath} size=${file.length()} gif=$gif",
                    )
                    return@withContext null
                }
                val total = decoder.frameCount
                if (total <= 0) {
                    MicSpriteConfig.logger.w("frameCount=$total after bounds: ${file.absolutePath}")
                    return@withContext null
                }
                val fps = sequenceFps(decoder, total)
                MicSpriteBaker.buildStrip(
                    totalFrames = total,
                    originalFps = fps,
                    maxTextureSize = maxTextureSize,
                    yieldEvery = MicSpriteBaker.backgroundYieldEvery,
                ) { src, canvas ->
                    val bitmap = try {
                        decoder.getFrameBitmap(src)
                    } catch (error: Exception) {
                        MicSpriteConfig.logger.w("frame $src skipped", error)
                        null
                    }
                    if (bitmap == null) {
                        MicSpriteConfig.logger.w("frame $src skipped")
                        return@buildStrip
                    }
                    try {
                        MicSpriteBaker.drawFitCenter(canvas, bitmap, frameSizePx)
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                decoder.stop()
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun bakeWithMovie(
        file: File,
        maxTextureSize: Int,
        frameSizePx: Int,
    ): MicSpriteRaster? {
        return withContext(bakeDispatcher) {
            val movie = android.graphics.Movie.decodeFile(file.absolutePath)
            if (movie == null || movie.width() <= 0 || movie.height() <= 0) {
                MicSpriteConfig.logger.w(
                    "movie decode failed file=${file.absolutePath} size=${file.length()}",
                )
                return@withContext null
            }
            val duration = movie.duration().coerceAtLeast(1)
            val fps = 12
            val total = ((duration / 1000f) * fps).roundToInt().coerceIn(1, 48)
            MicSpriteBaker.buildStrip(
                totalFrames = total,
                originalFps = fps,
                maxTextureSize = maxTextureSize,
                yieldEvery = MicSpriteBaker.backgroundYieldEvery,
            ) { src, canvas ->
                val time = if (total <= 1) 0 else src * duration / total
                movie.setTime(time)
                // 不引入 core-ktx，与库内其它 Bitmap.createBitmap 保持一致。
                @Suppress("UseKtx")
                val frame = Bitmap.createBitmap(
                    movie.width().coerceAtLeast(1),
                    movie.height().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                frame.eraseColor(Color.TRANSPARENT)
                movie.draw(Canvas(frame), 0f, 0f)
                try {
                    MicSpriteBaker.drawFitCenter(canvas, frame, frameSizePx)
                } finally {
                    frame.recycle()
                }
            }
        }
    }

    /** getFrame 只读已解析的帧时长，像素由后面的 getFrameBitmap 解码。 */
    private fun sequenceFps(decoder: FrameSeqDecoder<*, *>, count: Int): Int {
        var durationMs = 0
        for (index in 0 until count) {
            durationMs += decoder.getFrame(index)?.frameDuration ?: 0
        }
        if (durationMs <= 0) return 12
        return (count * 1000f / durationMs).roundToInt().coerceIn(1, 60)
    }

    private fun localFile(url: String): File? {
        val path = when {
            url.startsWith("file:") -> {
                android.net.Uri.parse(url).path
                    ?: url.removePrefix("file:").removePrefix("//")
            }
            url.startsWith("/") -> url
            else -> return null
        }
        return File(path).takeIf { it.isFile }
    }

    private companion object {
        private val bakeDispatcher = Dispatchers.Default.limitedParallelism(1)
    }
}
