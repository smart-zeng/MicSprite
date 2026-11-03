package com.github.smartzeng.micsprite.view

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.github.smartzeng.micsprite.core.MicSpriteController
import com.github.smartzeng.micsprite.core.MicSpriteSeat

class MicSpriteSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val controller = MicSpriteController(context.applicationContext)

    init {
        holder.addCallback(this)
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
    }

    fun setSeats(seats: List<MicSpriteSeat>) = controller.setSeats(seats)

    fun setSeat(seat: MicSpriteSeat) = controller.setSeat(seat)

    fun removeSeat(micId: Int) = controller.removeSeat(micId)

    fun setHeadwear(micId: Int, url: String) = controller.setHeadwear(micId, url)

    fun removeHeadwear(micId: Int) = controller.removeHeadwear(micId)

    fun setHeadwears(headwears: Map<Int, String>) = controller.setHeadwears(headwears)

    fun clearHeadwears() = controller.clearHeadwears()

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        val w = width.takeIf { it > 0 } ?: frame.width()
        val h = height.takeIf { it > 0 } ?: frame.height()
        controller.attachSurface(holder.surface, w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        controller.resize(w, h)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        controller.detachSurface()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    fun release() {
        controller.release()
        holder.removeCallback(this)
    }
}
