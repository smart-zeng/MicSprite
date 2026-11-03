package com.github.smartzeng.micsprite.core.gl


interface MicSpriteGlDispatcher {
    fun queue(block: () -> Unit)
    fun requestRender()
}
