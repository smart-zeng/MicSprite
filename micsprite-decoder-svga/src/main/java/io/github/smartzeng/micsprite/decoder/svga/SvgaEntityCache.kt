package io.github.smartzeng.micsprite.decoder.svga

import android.content.Context
import android.os.SystemClock
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAVideoEntity
import io.github.smartzeng.micsprite.core.MicSpriteBaker
import io.github.smartzeng.micsprite.core.MicSpriteConfig
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal object SvgaEntityCache {
    private const val maxIdle = 8

    private class Entry(
        val entity: SVGAVideoEntity,
        var refs: Int,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    suspend fun acquire(context: Context, url: String): SVGAVideoEntity? {
        synchronized(lock) {
            entries[url]?.let { entry ->
                entry.refs++
                MicSpriteConfig.logger.i("entity cache hit refs=${entry.refs}")
                return entry.entity
            }
        }
        val started = SystemClock.elapsedRealtime()
        val entity = decode(context.applicationContext, url)
        if (entity == null) {
            MicSpriteConfig.logger.w("decode failed ${SystemClock.elapsedRealtime() - started}ms $url")
            return null
        }
        MicSpriteConfig.logger.i(
            "decode done ${SystemClock.elapsedRealtime() - started}ms frames=${entity.frames} " +
                "${entity.videoSize.width.toInt()}x${entity.videoSize.height.toInt()} fps=${entity.FPS}",
        )
        return synchronized(lock) {
            val existing = entries[url]
            if (existing != null) {
                entity.clear()
                existing.refs++
                existing.entity
            } else {
                entries[url] = Entry(entity, 1)
                evictIdle()
                entity
            }
        }
    }

    fun release(url: String) {
        synchronized(lock) {
            val entry = entries[url] ?: return
            entry.refs = (entry.refs - 1).coerceAtLeast(0)
            evictIdle()
        }
    }

    private fun evictIdle() {
        if (entries.size <= maxIdle) return
        val iterator = entries.entries.iterator()
        while (entries.size > maxIdle && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.refs > 0) continue
            entry.value.entity.clear()
            iterator.remove()
        }
    }

    private suspend fun decode(context: Context, url: String): SVGAVideoEntity? {
        val parser = SVGAParser.shareParser().also { it.init(context) }
        val file = when {
            url.startsWith("http://") || url.startsWith("https://") ||
                url.startsWith("/") || url.startsWith("file:") -> {
                MicSpriteBaker.materialize(context, url)
            }
            else -> null
        }
        return suspendCancellableCoroutine { cont ->
            val callback = object : SVGAParser.ParseCompletion {
                override fun onComplete(videoItem: SVGAVideoEntity) {
                    if (cont.isActive) cont.resume(videoItem) else videoItem.clear()
                }

                override fun onError() {
                    if (cont.isActive) {
                        MicSpriteConfig.logger.w("svga parse error: $url")
                        cont.resume(null)
                    }
                }
            }
            val started = runCatching {
                if (file != null) {
                    parser.decodeFromInputStream(
                        FileInputStream(file),
                        url,
                        callback,
                        true,
                    )
                } else {
                    parser.decodeFromAssets(url, callback)
                }
            }
            if (started.isFailure && cont.isActive) {
                MicSpriteConfig.logger.w("svga dispatch failed: $url", started.exceptionOrNull())
                cont.resume(null)
            }
        }
    }
}
