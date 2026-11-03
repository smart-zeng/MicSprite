package io.github.smartzeng.micsprite.core


data class MicSpriteSeat @JvmOverloads constructor(
    val micId: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val scale: Float = MicSpriteConfig.headwearScale,
)

internal data class MicSpriteDrawItem(
    val micId: Int,
    val url: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val scale: Float,
)
