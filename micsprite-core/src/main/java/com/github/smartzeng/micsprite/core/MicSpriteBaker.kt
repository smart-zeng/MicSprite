package com.github.smartzeng.micsprite.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import com.github.smartzeng.micsprite.core.decoder.MicSpriteDecoderRegistry
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield


object MicSpriteBaker {

    const val backgroundYieldEvery = 8

    private val inFlight = ConcurrentHashMap<String, Deferred<MicSpriteFrameStrip?>>()
    private val downloadMsByUrl = ConcurrentHashMap<String, Long>()
    private val clients = AtomicInteger(0)
    private val bakeScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            MicSpriteConfig.logger.w("headwear bake scope", error)
        },
    )

    class AtlasLayout(
        val cols: Int,
        val rows: Int,
    )

    suspend fun bake(context: Context, url: String, maxTextureSize: Int): MicSpriteFrameStrip? {
        val app = context.applicationContext
        val raw = url.trim()
        if (raw.isEmpty()) return null
        val flightKey =
            "$raw|$maxTextureSize|${MicSpriteConfig.cacheVersion}|${MicSpriteConfig.frameSizePx}"
        val created = CompletableDeferred<MicSpriteFrameStrip?>()
        val existing = inFlight.putIfAbsent(flightKey, created)
        if (existing != null) {
            val shared = existing.await() ?: return null
            val cached = MicSpriteStripCache.load(app, raw, maxTextureSize)
            if (cached != null) return cached
            if (shared.atlas.isRecycled) return null
            return shared.retain()
        }
        val job = bakeScope.launch {
            try {
                val result = if (extensionOf(raw) == "svga") {
                    bakeOnce(app, raw, maxTextureSize)
                } else {
                    withTimeout(MicSpriteConfig.bakeTimeoutMs) { bakeOnce(app, raw, maxTextureSize) }
                }
                created.complete(result)
            } catch (error: TimeoutCancellationException) {
                MicSpriteConfig.logger.w("headwear bake timeout: $raw", error)
                created.complete(null)
            } catch (error: CancellationException) {
                created.cancel(error)
            } catch (error: Throwable) {
                MicSpriteConfig.logger.w("headwear bake failed: $raw", error)
                created.complete(null)
            } finally {
                inFlight.remove(flightKey, created)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null && !created.isCompleted) {
                created.complete(null)
                inFlight.remove(flightKey, created)
            }
        }
        return created.await()
    }

    internal fun registerClient() {
        clients.incrementAndGet()
    }

    internal fun unregisterClient() {
        val left = clients.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        if (left == 0) clearMemoryCache()
    }


    fun clearMemoryCache() {
        MicSpriteStripCache.clearMemory()
    }

    private suspend fun bakeOnce(
        context: Context,
        raw: String,
        maxTextureSize: Int,
    ): MicSpriteFrameStrip? {
        val started = SystemClock.elapsedRealtime()
        val cached = withContext(Dispatchers.Default) {
            MicSpriteStripCache.load(context, raw, maxTextureSize)
        }
        if (cached != null) {
            MicSpriteConfig.logger.i(
                "strip cache hit ${SystemClock.elapsedRealtime() - started}ms " +
                    "atlas=${cached.atlas.width}x${cached.atlas.height} frames=${cached.frameCount}",
            )
            return cached
        }
        val decoder = MicSpriteDecoderRegistry.resolve(raw)
        if (decoder == null) {
            MicSpriteConfig.logger.w("no decoder for $raw")
            return null
        }
        downloadMsByUrl.remove(raw)
        val decodeAt = SystemClock.elapsedRealtime()
        val raster = try {
            decoder.bake(
                context,
                raw,
                maxTextureSize,
                MicSpriteConfig.frameSizePx,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            MicSpriteConfig.logger.w("bake failed: $raw", error)
            null
        }
        val bakeMs = SystemClock.elapsedRealtime() - decodeAt
        val downloadMs = downloadMsByUrl.remove(raw) ?: 0L
        val decodeMs = (bakeMs - downloadMs).coerceAtLeast(0L)
        MicSpriteConfig.logger.i(
            "decode ${decodeMs}ms download ${downloadMs}ms ok=${raster != null} $raw",
        )
        if (raster == null) {
            MicSpriteConfig.logger.w("decoder bake returned null: $raw")
            return null
        }
        var produced: MicSpriteFrameStrip? = null
        val strip = try {
            withContext(Dispatchers.Default) {
                val trimmed = trim(raw, raster)
                if (trimmed == null) {
                    MicSpriteConfig.logger.w("trim dropped empty atlas: $raw")
                    return@withContext null
                }
                produced = trimmed
                try {
                    MicSpriteStripCache.save(context, trimmed, maxTextureSize)
                    discardSource(context, raw)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    MicSpriteConfig.logger.w("save strip failed", error)
                }
                trimmed
            }
        } catch (error: CancellationException) {
            val held = produced?.atlas ?: raster.atlas
            if (!held.isRecycled) held.recycle()
            throw error
        }
        if (strip != null) {
            MicSpriteConfig.logger.i(
                "bake done ${SystemClock.elapsedRealtime() - started}ms " +
                    "atlas=${strip.atlas.width}x${strip.atlas.height} frames=${strip.frameCount} frame=${strip.frameSize}",
            )
        }
        return strip
    }

    fun sniffFormat(file: File): String? {
        if (!file.isFile || file.length() < 12L) return null
        val header = ByteArray(12)
        try {
            DataInputStream(file.inputStream()).use { it.readFully(header) }
        } catch (_: EOFException) {
            return null
        } catch (_: IOException) {
            return null
        }
        return when {
            header.decodeToString(0, 4) == "GIF8" -> "gif"
            header.decodeToString(0, 4) == "RIFF" && header.decodeToString(8, 12) == "WEBP" -> "webp"
            // SVGA 是 ZIP 包，文件头为 PK
            header.size >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() -> "svga"
            // PAG 无稳定 magic
            else -> null
        }
    }

    suspend fun buildStrip(
        totalFrames: Int,
        originalFps: Int,
        maxTextureSize: Int,
        yieldEvery: Int,
        draw: suspend (srcIndex: Int, canvas: Canvas) -> Unit,
    ): MicSpriteRaster? {
        val size = MicSpriteConfig.frameSizePx
        val count = sampleCount(totalFrames, maxTextureSize)
        if (count < totalFrames) {
            MicSpriteConfig.logger.w(
                "frame sampled: total=$totalFrames count=$count maxTexture=$maxTextureSize",
            )
        }
        val layout = layoutFor(count, maxTextureSize)
        val outFps = sampledFps(count, originalFps, totalFrames)
        val frameBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val frameCanvas = Canvas(frameBmp)
        val rawAtlas = Bitmap.createBitmap(size * layout.cols, size * layout.rows, Bitmap.Config.ARGB_8888)
        val atlasCanvas = Canvas(rawAtlas)
        val every = yieldEvery.coerceAtLeast(1)
        var success = false
        try {
            for (index in 0 until count) {
                frameBmp.eraseColor(Color.TRANSPARENT)
                draw(sourceIndex(index, count, totalFrames), frameCanvas)
                val col = index % layout.cols
                val row = index / layout.cols
                atlasCanvas.drawBitmap(frameBmp, (col * size).toFloat(), (row * size).toFloat(), null)
                if (index % every == every - 1) yield()
            }
            success = true
            return MicSpriteRaster(rawAtlas, count, outFps, size, layout.cols, layout.rows)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            MicSpriteConfig.logger.w("sample frames failed", error)
            return null
        } finally {
            frameBmp.recycle()
            if (!success) rawAtlas.recycle()
        }
    }

    private fun sampledFps(sampleCount: Int, originalFps: Int, totalFrames: Int): Float {
        val total = totalFrames.coerceAtLeast(1)
        val fps = originalFps.coerceAtLeast(1).toFloat()
        return (sampleCount * fps / total).coerceAtLeast(0.01f)
    }

    fun drawFitCenter(canvas: Canvas, bitmap: Bitmap, size: Int) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val scale = min(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (size - width) / 2f
        val top = (size - height) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), null)
    }

    private fun maxCells(maxTextureSize: Int): Int =
        (maxTextureSize / MicSpriteConfig.frameSizePx).coerceAtLeast(1)

    fun sampleCount(totalFrames: Int, maxTextureSize: Int): Int {
        val cells = maxCells(maxTextureSize)
        val areaLimit = (cells.toLong() * cells.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return totalFrames.coerceAtLeast(1).coerceAtMost(areaLimit)
    }

    fun layoutFor(frameCount: Int, maxTextureSize: Int): AtlasLayout {
        val cells = maxCells(maxTextureSize)
        val count = frameCount.coerceAtLeast(1)
        var cols = ceil(sqrt(count.toDouble())).toInt().coerceIn(1, cells)
        var rows = ceil(count / cols.toDouble()).toInt().coerceAtLeast(1)
        if (rows > cells) {
            rows = cells
            cols = ceil(count / rows.toDouble()).toInt().coerceIn(1, cells)
        }
        return AtlasLayout(cols, rows)
    }

    fun sourceIndex(sample: Int, sampleCount: Int, totalFrames: Int): Int {
        if (sampleCount <= 1) return 0
        return (sample * (totalFrames - 1).toFloat() / (sampleCount - 1)).toInt()
            .coerceIn(0, totalFrames - 1)
    }

    fun extensionOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        return path.substringAfterLast('.', "").lowercase()
    }

    suspend fun materialize(context: Context, url: String): File? {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> download(context, url)
            url.startsWith("file:") -> {
                val path = android.net.Uri.parse(url).path
                    ?: url.removePrefix("file:").removePrefix("//")
                File(path).takeIf { it.isFile }
            }
            url.startsWith("/") -> File(url).takeIf { it.isFile }
            else -> null
        }
    }

    suspend fun download(context: Context, url: String): File? {
        val started = SystemClock.elapsedRealtime()
        val ext = extensionOf(url).ifEmpty { "bin" }
        val cacheDir = MicSpriteConfig.requireFileProvider().cacheDir(context)
        cacheDir.mkdirs()
        val destination = File(cacheDir, "headwear-${hash(url)}.$ext")
        val done = File(destination.path + ".done")
        if (destination.isFile && destination.length() > 0L && done.isFile) {
            val cost = SystemClock.elapsedRealtime() - started
            downloadMsByUrl[url] = cost
            MicSpriteConfig.logger.i("download cache hit ${cost}ms size=${destination.length()} $url")
            return destination
        }
        destination.delete()
        done.delete()
        val part = File(destination.path + ".part")
        part.delete()
        return try {
            val downloaded = MicSpriteConfig.requireFileProvider().download(context, url, part)
            val cost = SystemClock.elapsedRealtime() - started
            downloadMsByUrl[url] = cost
            if (!downloaded || !part.isFile || part.length() <= 0L) {
                MicSpriteConfig.logger.w(
                    "download failed ${cost}ms: $url downloaded=$downloaded exists=${part.isFile} size=${part.length()}",
                )
                part.delete()
                return null
            }
            if (destination.exists() && !destination.delete()) {
                part.delete()
                return null
            }
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                part.delete()
            }
            done.writeText(destination.length().toString())
            evictSources(cacheDir)
            MicSpriteConfig.logger.i("download ${cost}ms size=${destination.length()} $url")
            destination
        } catch (error: CancellationException) {
            part.delete()
            done.delete()
            throw error
        } catch (error: Throwable) {
            val cost = SystemClock.elapsedRealtime() - started
            downloadMsByUrl[url] = cost
            part.delete()
            done.delete()
            MicSpriteConfig.logger.w("headwear download failed ${cost}ms: $url", error)
            null
        }
    }

    private fun discardSource(context: Context, url: String) {
        val cacheDir = MicSpriteConfig.requireFileProvider().cacheDir(context)
        val ext = extensionOf(url).ifEmpty { "bin" }
        val destination = File(cacheDir, "headwear-${hash(url)}.$ext")
        File(destination.path + ".done").delete()
        File(destination.path + ".part").delete()
        destination.delete()
    }

    private fun evictSources(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            if (name.endsWith(".part") || name.endsWith(".done") || name.endsWith(".tmp")) {
                if (System.currentTimeMillis() - file.lastModified() > 10 * 60_000L) file.delete()
            }
        }
        val sources = dir.listFiles { file ->
            file.isFile && file.name.startsWith("headwear-") &&
                !file.name.endsWith(".done") &&
                !file.name.endsWith(".part") &&
                !file.name.endsWith(".tmp")
        }?.toMutableList() ?: return
        sources.sortBy { it.lastModified() }
        var total = sources.sumOf { it.length() }
        val maxBytes = 8L * 1024 * 1024
        val maxEntries = 8
        while (sources.size > maxEntries || total > maxBytes) {
            if (sources.isEmpty()) return
            val oldest = sources.removeAt(0)
            total -= oldest.length()
            File(oldest.path + ".done").delete()
            oldest.delete()
        }
    }

    private fun hash(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun trim(url: String, raster: MicSpriteRaster): MicSpriteFrameStrip? {
        val rawAtlas = raster.atlas
        val size = raster.frameSize
        val pixels = IntArray(size * size)
        var firstSolid = -1
        for (index in 0 until raster.frameCount) {
            val col = index % raster.cols
            val row = index / raster.cols
            if (frameHasContent(rawAtlas, pixels, col * size, row * size, size)) {
                firstSolid = index
                break
            }
        }
        if (firstSolid < 0) {
            rawAtlas.recycle()
            return null
        }
        return MicSpriteFrameStrip(
            url = url,
            atlas = rawAtlas,
            frameCount = raster.frameCount - firstSolid,
            fps = raster.fps,
            frameSize = size,
            startFrame = firstSolid,
            cols = raster.cols,
            rows = raster.rows,
        )
    }

    private fun frameHasContent(
        strip: Bitmap,
        pixels: IntArray,
        offsetX: Int,
        offsetY: Int,
        size: Int,
    ): Boolean {
        strip.getPixels(pixels, 0, size, offsetX, offsetY, size, size)
        val step = (size / 8).coerceAtLeast(1)
        var y = 0
        while (y < size) {
            val row = y * size
            var x = 0
            while (x < size) {
                if ((pixels[row + x] ushr 24) > 8) return true
                x += step
            }
            y += step
        }
        return false
    }
}
