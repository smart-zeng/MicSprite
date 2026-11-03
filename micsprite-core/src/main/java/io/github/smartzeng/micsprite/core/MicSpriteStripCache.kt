package io.github.smartzeng.micsprite.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

object MicSpriteStripCache {
    private const val dirName = "headwear_strips"
    private const val maxMemoryEntries = 16

    private const val staleTmpMs = 10 * 60_000L

    private val memoryLock = Any()
    private val memory = LinkedHashMap<String, MicSpriteFrameStrip>(16, 0.75f, true)

    fun load(context: Context, url: String, maxTextureSize: Int): MicSpriteFrameStrip? {
        val key = cacheKey(url, maxTextureSize)
        synchronized(memoryLock) {
            val mem = memory[key]
            if (mem != null) {
                if (!mem.atlas.isRecycled) return mem.retain()
                memory.remove(key)
            }
        }
        val dir = directory(context)
        val image = File(dir, "$key.webp")
        val meta = File(dir, "$key.meta")
        deleteLegacyPng(dir, key)
        if (!image.isFile || !meta.isFile) {
            if (image.exists() || meta.exists()) deletePair(image, meta)
            return null
        }
        fun fail(): MicSpriteFrameStrip? {
            deletePair(image, meta)
            return null
        }
        val parts = try {
            meta.readText().lineSequence().filter { it.isNotBlank() }.toList()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return fail()
        }
        if (parts.size < 8) return fail()
        val version = parts[0].toIntOrNull() ?: return fail()
        val frameCount = parts[1].toIntOrNull() ?: return fail()
        val fps = parts[2].toFloatOrNull() ?: return fail()
        val frameSize = parts[3].toIntOrNull() ?: return fail()
        val storedMaxTextureSize = parts[4].toIntOrNull() ?: return fail()
        val cols = parts[5].toIntOrNull() ?: return fail()
        val rows = parts[6].toIntOrNull() ?: return fail()
        val startFrame = parts[7].toIntOrNull() ?: return fail()
        if (version != MicSpriteConfig.cacheVersion ||
            frameCount <= 0 ||
            fps <= 0f ||
            cols <= 0 ||
            rows <= 0 ||
            startFrame < 0 ||
            startFrame + frameCount > cols * rows ||
            frameSize != MicSpriteConfig.frameSizePx ||
            storedMaxTextureSize != maxTextureSize
        ) {
            deletePair(image, meta)
            return null
        }
        val bitmap = BitmapFactory.decodeFile(image.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
        if (bitmap == null || bitmap.width != cols * frameSize || bitmap.height != rows * frameSize) {
            bitmap?.recycle()
            deletePair(image, meta)
            MicSpriteConfig.logger.w("strip cache invalid: $url")
            return null
        }
        if (bitmap.width > maxTextureSize || bitmap.height > maxTextureSize) {
            val width = bitmap.width
            val height = bitmap.height
            bitmap.recycle()
            deletePair(image, meta)
            MicSpriteConfig.logger.i(
                "strip cache miss ${width}x$height > limit=$maxTextureSize, rebake",
            )
            return null
        }
        image.setLastModified(System.currentTimeMillis())
        val strip = MicSpriteFrameStrip(url, bitmap, frameCount, fps, frameSize, startFrame, cols, rows)
        remember(key, strip)
        return strip
    }

    fun save(context: Context, strip: MicSpriteFrameStrip, maxTextureSize: Int) {
        if (strip.atlas.isRecycled) return
        val dir = directory(context)
        val key = cacheKey(strip.url, maxTextureSize)
        remember(key, strip)
        val image = File(dir, "$key.webp")
        val meta = File(dir, "$key.meta")
        val temp = File(dir, "$key.webp.tmp")
        val written = try {
            temp.outputStream().use { output ->
                strip.atlas.compress(webpFormat(), MicSpriteConfig.stripCacheQuality.coerceIn(0, 100), output)
            }
        } catch (error: CancellationException) {
            temp.delete()
            throw error
        } catch (error: Throwable) {
            temp.delete()
            MicSpriteConfig.logger.w("strip cache save failed: ${strip.url}", error)
            return
        }
        if (!written || !temp.isFile || temp.length() <= 0L) {
            temp.delete()
            MicSpriteConfig.logger.w("strip cache save failed: ${strip.url}")
            return
        }
        if (image.exists() && !image.delete()) {
            temp.delete()
            return
        }
        if (!temp.renameTo(image)) {
            temp.delete()
            return
        }
        val metaTemp = File(dir, "$key.meta.tmp")
        val metaText = "${MicSpriteConfig.cacheVersion}\n${strip.frameCount}\n${strip.fps}\n${strip.frameSize}\n" +
            "$maxTextureSize\n${strip.cols}\n${strip.rows}\n${strip.startFrame}\n"
        try {
            metaTemp.writeText(metaText)
        } catch (error: CancellationException) {
            metaTemp.delete()
            image.delete()
            throw error
        } catch (error: Throwable) {
            metaTemp.delete()
            image.delete()
            MicSpriteConfig.logger.w("strip cache meta failed: ${strip.url}", error)
            return
        }
        if ((meta.exists() && !meta.delete()) || !metaTemp.renameTo(meta)) {
            metaTemp.delete()
            image.delete()
            MicSpriteConfig.logger.w("strip cache meta failed: ${strip.url}")
            return
        }
        image.setLastModified(System.currentTimeMillis())
        deleteLegacyPng(dir, key)
        evict(dir)
    }

    fun clearMemory() {
        synchronized(memoryLock) {
            memory.values.forEach { it.release() }
            memory.clear()
        }
    }

    private fun remember(key: String, strip: MicSpriteFrameStrip) {
        if (strip.atlas.isRecycled) return
        synchronized(memoryLock) {
            val previous = memory.put(key, strip.retain())
            previous?.release()
            while (memory.size > maxMemoryEntries) {
                val eldest = memory.entries.iterator().next()
                memory.remove(eldest.key)
                eldest.value.release()
            }
        }
    }

    private fun cacheKey(url: String, maxTextureSize: Int): String =
        hash("${MicSpriteConfig.cacheVersion}|${MicSpriteConfig.frameSizePx}|$maxTextureSize|$url")

    private fun deletePair(image: File, meta: File) {
        image.delete()
        meta.delete()
    }

    private fun deleteLegacyPng(dir: File, key: String) {
        File(dir, "$key.png").delete()
        File(dir, "$key.png.tmp").delete()
    }

    private fun directory(context: Context): File {
        return File(context.cacheDir, dirName).apply { mkdirs() }
    }

    private fun evict(dir: File) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            when {
                file.name.endsWith(".tmp") && now - file.lastModified() > staleTmpMs -> file.delete()
                file.name.endsWith(".png") -> {
                    file.delete()
                    File(dir, file.name.removeSuffix(".png") + ".meta").delete()
                }
                file.name.endsWith(".webp") -> {
                    val meta = File(dir, file.name.removeSuffix(".webp") + ".meta")
                    if (!meta.isFile) file.delete()
                }
                file.name.endsWith(".meta") -> {
                    val webp = File(dir, file.name.removeSuffix(".meta") + ".webp")
                    if (!webp.isFile) file.delete()
                }
            }
        }
        val images = dir.listFiles { file -> file.isFile && file.name.endsWith(".webp") }?.toMutableList() ?: return
        images.sortBy { it.lastModified() }
        var total = images.sumOf { it.length() }
        val maxEntries = MicSpriteConfig.stripDiskMaxEntries.coerceAtLeast(1)
        val maxBytes = MicSpriteConfig.stripDiskMaxBytes.coerceAtLeast(1L)
        while (images.size > maxEntries || total > maxBytes) {
            if (images.isEmpty()) return
            val oldest = images.removeAt(0)
            total -= oldest.length()
            val meta = File(dir, oldest.name.removeSuffix(".webp") + ".meta")
            oldest.delete()
            meta.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
    }

    private fun hash(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
