package io.github.smartzeng.micsprite.decoder.svga

import android.content.Context
import android.widget.ImageView
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SVGAVideoEntity
import io.github.smartzeng.micsprite.core.MicSpriteBaker
import io.github.smartzeng.micsprite.core.MicSpriteConfig
import io.github.smartzeng.micsprite.core.MicSpriteDecoder
import io.github.smartzeng.micsprite.core.MicSpriteRaster
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SvgaDecoder : MicSpriteDecoder {

    override fun supports(url: String): Boolean {
        if (MicSpriteBaker.extensionOf(url) == "svga") return true
        val file = localFile(url) ?: return false
        return MicSpriteBaker.sniffFormat(file) == "svga"
    }

    override suspend fun bake(
        context: Context,
        url: String,
        maxTextureSize: Int,
        frameSizePx: Int,
    ): MicSpriteRaster? {
        return bakeSvga(context, url, maxTextureSize, frameSizePx)
    }

    private suspend fun bakeSvga(
        context: Context,
        url: String,
        maxTextureSize: Int,
        frameSizePx: Int,
    ): MicSpriteRaster? {
        val entity = try {
            SvgaEntityCache.acquire(context, url)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            MicSpriteConfig.logger.w("svga acquire failed: $url", error)
            null
        } ?: return null
        return try {
            withContext(bakeDispatcher) {
                try {
                    withTimeout(MicSpriteConfig.svgaBakeTimeoutMs) {
                        rasterize(entity, maxTextureSize, frameSizePx)
                    }
                } catch (error: TimeoutCancellationException) {
                    MicSpriteConfig.logger.w("svga rasterize timeout: $url", error)
                    null
                }
            }
        } finally {
            SvgaEntityCache.release(url)
        }
    }

    private suspend fun rasterize(
        entity: SVGAVideoEntity,
        maxTextureSize: Int,
        frameSizePx: Int,
    ): MicSpriteRaster? {
        val size = frameSizePx
        val drawable = SVGADrawable(entity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBounds(0, 0, size, size)
        }
        setCleared(drawable, false)
        return MicSpriteBaker.buildStrip(
            totalFrames = entity.frames.coerceAtLeast(1),
            originalFps = entity.FPS.coerceAtLeast(1),
            maxTextureSize = maxTextureSize,
            yieldEvery = MicSpriteBaker.backgroundYieldEvery,
        ) { src, canvas ->
            setCurrentFrame(drawable, src)
            drawable.draw(canvas)
        }
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

        private val clearedField = SVGADrawable::class.java.getDeclaredField("cleared").apply {
            isAccessible = true
        }
        private val currentFrameField = SVGADrawable::class.java.getDeclaredField("currentFrame").apply {
            isAccessible = true
        }

        private fun setCleared(drawable: SVGADrawable, value: Boolean) {
            clearedField.setBoolean(drawable, value)
        }

        private fun setCurrentFrame(drawable: SVGADrawable, frame: Int) {
            currentFrameField.setInt(drawable, frame)
        }
    }
}
