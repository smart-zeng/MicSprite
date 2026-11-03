package com.github.smartzeng.micsprite.core

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface MicSpriteFileProvider {


    fun cacheDir(context: Context): File {
        return File(context.cacheDir, "micsprite").apply { mkdirs() }
    }


    fun downloadSync(context: Context, url: String, destination: File): Boolean {
        return defaultDownloadBlocking(url, destination)
    }


    suspend fun download(context: Context, url: String, destination: File): Boolean {
        return withContext(Dispatchers.IO) {
            downloadSync(context, url, destination)
        }
    }
}


object DefaultMicSpriteFileProvider : MicSpriteFileProvider

internal fun defaultDownloadBlocking(url: String, destination: File): Boolean {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MicSprite/1.0")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            MicSpriteConfig.logger.w("download http $code $url")
            return false
        }
        destination.parentFile?.mkdirs()
        connection.inputStream.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        true
    } catch (error: IOException) {
        MicSpriteConfig.logger.w("download io $url", error)
        false
    } finally {
        connection?.disconnect()
    }
}
