package com.github.smartzeng.micsprite.core


object MicSpriteConfig {

    @JvmStatic
    @Volatile
    var logger: MicSpriteLogger = DefaultLogger()


    @JvmStatic
    @Volatile
    var fileProvider: MicSpriteFileProvider = DefaultMicSpriteFileProvider

    @JvmStatic
    @Volatile
    var frameSizePx: Int = 128


    @JvmStatic
    @Volatile
    var headwearScale: Float = 64f / 48f

    @JvmStatic
    fun headwearScaleOf(avatarPx: Float, headwearPx: Float): Float {
        if (avatarPx <= 0f) return 1f
        return headwearPx / avatarPx
    }

    @JvmStatic
    @Volatile
    var bakeTimeoutMs: Long = 30_000L

    @JvmStatic
    @Volatile
    var svgaBakeTimeoutMs: Long = 10_000L

    @JvmStatic
    @Volatile
    var cacheVersion: Int = 1


    @JvmStatic
    @Volatile
    var stripDiskMaxBytes: Long = 64L * 1024 * 1024


    @JvmStatic
    @Volatile
    var stripDiskMaxEntries: Int = 30


    @JvmStatic
    @Volatile
    var stripCacheQuality: Int = 75

    @JvmStatic
    fun requireFileProvider(): MicSpriteFileProvider = fileProvider
}
