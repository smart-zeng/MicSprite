package io.github.smartzeng.micsprite.core.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import io.github.smartzeng.micsprite.core.MicSpriteConfig
import io.github.smartzeng.micsprite.core.MicSpriteDrawItem
import io.github.smartzeng.micsprite.core.MicSpriteFrameStrip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentLinkedQueue

class MicSpriteRenderer(
    private val dispatcher: MicSpriteGlDispatcher,
) {
    private val tag = "MicSpriteRenderer"
    private val maxSeats = 64

    private val maxTextures = 30

    private val maxErrorDrain = 16

    private val fallbackTextureLimit = 2048

    private val vertexShader = """
        uniform mat4 uMVP;
        uniform vec4 uUvRect;
        attribute vec2 aPos;
        attribute vec2 aUv;
        varying vec2 vUv;
        void main() {
            vUv = vec2(uUvRect.x + aUv.x * uUvRect.z, uUvRect.y + aUv.y * uUvRect.w);
            gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
        }
    """.trimIndent()

    private val fragmentShader = """
        precision mediump float;
        uniform sampler2D uTex;
        varying vec2 vUv;
        void main() {
            // Bitmap 像素本身已预乘 alpha，这里再乘一次会让半透明边缘发暗
            gl_FragColor = texture2D(uTex, vUv);
        }
    """.trimIndent()

    private class TexSlot(
        val textureId: Int,
        val frameCount: Int,
        val fps: Float,
        val frameSize: Int,
        val startFrame: Int,
        val cols: Int,
        val rows: Int,
    )

    private class AnchorDraw(
        var micId: Int = -1,
        var url: String = "",
        var left: Float = 0f,
        var top: Float = 0f,
        var right: Float = 0f,
        var bottom: Float = 0f,
        var scale: Float = 64f / 48f,
        var active: Boolean = false,
        var tex: TexSlot? = null,
    )

    private val seats = Array(maxSeats) { AnchorDraw() }

    /** accessOrder=true，迭代顺序即最久未使用在前，供 [trimTextures] 淘汰 */
    private val textures = LinkedHashMap<String, TexSlot>(16, 0.75f, true)
    private val pendingUploads = ConcurrentLinkedQueue<MicSpriteFrameStrip>()

    private var program = 0
    private var uMvp = -1
    private var uUvRect = -1
    private var uTex = -1
    private var aPos = -1
    private var aUv = -1
    private var vbo = 0
    private var ebo = 0
    private val proj = FloatArray(16)
    private val mvp = FloatArray(16)
    private val scratch = FloatArray(16)
    private var startAtMs = 0L

    @Volatile
    private var surfaceReady = false

    @Volatile
    private var destroyed = false

    @Volatile
    private var hasContent = false

    @Volatile
    private var animFps = 0f

    @Volatile
    private var textureLimit = fallbackTextureLimit

    private val quadIdx = shortArrayOf(0, 1, 2, 0, 2, 3)

    /** 单位方块，UV 为帧内归一化坐标，真实取样区间由 uUvRect 平移缩放 */
    private val quadVerts = floatArrayOf(
        0f, 0f, 0f, 0f,
        1f, 0f, 1f, 0f,
        1f, 1f, 1f, 1f,
        0f, 1f, 0f, 1f,
    )
    private val vertBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadVerts.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(quadVerts)
            position(0)
        }
    private val idxBuffer: ShortBuffer = ByteBuffer.allocateDirect(quadIdx.size * 2)
        .order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(quadIdx)
            position(0)
        }

    fun isSurfaceReady(): Boolean = surfaceReady && !destroyed

    fun hasContent(): Boolean = hasContent

    /** 当前纹理中最高的动画帧率，供上层按内容节奏驱动渲染 */
    fun animFps(): Float = animFps

    /** 帧条位图的最大边长上限，超过后 texImage2D 会静默失败 */
    fun textureSizeLimit(): Int = textureLimit

    fun markSurfaceDestroyed() {
        surfaceReady = false
    }

    /** Surface/上下文重建时丢掉失效 texture id，不在失效上下文上 glDelete */
    fun clearTextureCache() {
        if (destroyed) return
        dispatcher.queue {
            deleteAllTextures()
            drainPending()
            rebindSeatTextures()
            dispatcher.requestRender()
        }
    }

    /** 释放当前麦位不再使用的头饰纹理 */
    fun evictUnused(keepUrls: Set<String>) {
        if (destroyed) return
        dispatcher.queue {
            val iterator = textures.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key in keepUrls) continue
                deleteTexture(entry.value.textureId)
                iterator.remove()
            }
            val kept = ArrayList<MicSpriteFrameStrip>(pendingUploads.size)
            while (true) {
                val strip = pendingUploads.poll() ?: break
                if (strip.url in keepUrls) kept.add(strip) else recycleStrip(strip)
            }
            pendingUploads.addAll(kept)
            rebindSeatTextures()
            dispatcher.requestRender()
        }
    }

    internal fun updateDraws(draws: List<MicSpriteDrawItem>) {
        if (destroyed) return
        dispatcher.queue {
            for (seat in seats) seat.active = false
            draws.forEachIndexed { index, draw ->
                if (index >= maxSeats) return@forEachIndexed
                val seat = seats[index]
                seat.micId = draw.micId
                seat.url = draw.url
                seat.left = draw.left
                seat.top = draw.top
                seat.right = draw.right
                seat.bottom = draw.bottom
                seat.scale = draw.scale.takeIf { it > 0f } ?: MicSpriteConfig.headwearScale
                seat.active = draw.right > draw.left && draw.bottom > draw.top
            }
            rebindSeatTextures()
            dispatcher.requestRender()
        }
    }

    fun uploadStrip(strip: MicSpriteFrameStrip) {
        if (destroyed || !surfaceReady) {
            recycleStrip(strip)
            return
        }
        pendingUploads.offer(strip)
        dispatcher.requestRender()
    }

    /** 离开页面：位图在调用线程回收。GL 对象由 [onContextLost] 在上下文还活着时删除。 */
    fun release() {
        destroyed = true
        surfaceReady = false
        hasContent = false
        animFps = 0f
        drainPending()
    }

    /** 重新挂上 Surface 时复位，让新的 GL 上下文能再次初始化 */
    fun reset() {
        destroyed = false
    }

    fun onContextCreated() {
        if (destroyed) return
        textures.clear()
        drainPending()

        textureLimit = queryTextureLimit()
        program = createProgram(vertexShader, fragmentShader)
        if (program == 0) {
            surfaceReady = false
            rebindSeatTextures()
            return
        }
        uMvp = GLES20.glGetUniformLocation(program, "uMVP")
        uUvRect = GLES20.glGetUniformLocation(program, "uUvRect")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")

        val bufs = IntArray(2)
        GLES20.glGenBuffers(2, bufs, 0)
        vbo = bufs[0]
        ebo = bufs[1]
        vertBuffer.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, quadVerts.size * 4, vertBuffer, GLES20.GL_STATIC_DRAW,
        )
        idxBuffer.position(0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ebo)
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER, quadIdx.size * 2, idxBuffer, GLES20.GL_STATIC_DRAW,
        )

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        startAtMs = SystemClock.uptimeMillis()
        surfaceReady = true
        rebindSeatTextures()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        if (destroyed || width <= 0 || height <= 0) return
        GLES20.glViewport(0, 0, width, height)
        Matrix.orthoM(proj, 0, 0f, width.toFloat(), height.toFloat(), 0f, -1f, 1f)
        surfaceReady = program != 0
    }

    /** 上下文还 current 时删掉 GL 对象。不要 eglTerminate，默认 display 是进程共享的。 */
    fun onContextLost() {
        surfaceReady = false
        hasContent = false
        animFps = 0f
        deleteAllTextures()
        drainPending()
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (vbo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
            vbo = 0
        }
        if (ebo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(ebo), 0)
            ebo = 0
        }
    }

    fun onDrawFrame() {
        if (destroyed) return
        flushUploads()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!hasContent || program == 0) return

        val now = SystemClock.uptimeMillis()
        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ebo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, 8)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTex, 0)

        var boundTex = 0
        for (seat in seats) {
            if (!seat.active) continue
            val tex = seat.tex ?: continue
            if (tex.textureId != boundTex) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex.textureId)
                boundTex = tex.textureId
            }
            drawSeat(seat, tex, currentFrame(tex, now))
        }
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    private fun currentFrame(tex: TexSlot, now: Long): Int {
        val elapsed = (now - startAtMs).coerceAtLeast(0L)
        val count = tex.frameCount.coerceAtLeast(1)
        val loop = ((elapsed * tex.fps.toDouble() / 1000.0) % count).toInt()
        return tex.startFrame + loop.coerceIn(0, count - 1)
    }

    private fun drawSeat(seat: AnchorDraw, tex: TexSlot, frame: Int) {
        val srcW = seat.right - seat.left
        val srcH = seat.bottom - seat.top
        if (srcW <= 0f || srcH <= 0f || tex.cols <= 0 || tex.rows <= 0) return
        val scale = seat.scale.coerceAtLeast(0.01f)
        val w = srcW * scale
        val h = srcH * scale
        val left = seat.left - (w - srcW) / 2f
        val top = seat.top - (h - srcH) / 2f

        // 半像素内缩：格子紧邻，GL_LINEAR 会在边界采到相邻帧
        val col = frame % tex.cols
        val row = frame / tex.cols
        val atlasW = (tex.cols * tex.frameSize).toFloat()
        val atlasH = (tex.rows * tex.frameSize).toFloat()
        val u0 = (col * tex.frameSize + 0.5f) / atlasW
        val v0 = (row * tex.frameSize + 0.5f) / atlasH
        val du = (tex.frameSize - 1f) / atlasW
        val dv = (tex.frameSize - 1f) / atlasH
        GLES20.glUniform4f(uUvRect, u0, v0, du, dv)

        Matrix.setIdentityM(scratch, 0)
        Matrix.translateM(scratch, 0, left, top, 0f)
        Matrix.scaleM(scratch, 0, w, h, 1f)
        Matrix.multiplyMM(mvp, 0, proj, 0, scratch, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, 0)
    }

    private fun flushUploads() {
        if (pendingUploads.isEmpty()) return
        var changed = false
        while (true) {
            val strip = pendingUploads.poll() ?: break
            changed = uploadPending(strip) || changed
            recycleStrip(strip)
        }
        if (changed) rebindSeatTextures()
    }

    private fun uploadPending(strip: MicSpriteFrameStrip): Boolean {
        val bitmap = strip.atlas
        if (bitmap.isRecycled) return false
        val limit = textureLimit
        if (bitmap.width > limit || bitmap.height > limit) {
            MicSpriteConfig.logger.w(
                "strip ${bitmap.width}x${bitmap.height} over GL limit $limit: ${strip.url}",
                tag = tag,
            )
            return false
        }
        val uploadAt = SystemClock.elapsedRealtime()
        val id = uploadTexture(bitmap) ?: return false
        MicSpriteConfig.logger.i(
            "gl upload ${bitmap.width}x${bitmap.height} ${SystemClock.elapsedRealtime() - uploadAt}ms",
        )
        textures.remove(strip.url)?.let { deleteTexture(it.textureId) }
        textures[strip.url] = TexSlot(
            textureId = id,
            frameCount = strip.frameCount.coerceAtLeast(1),
            fps = strip.fps.coerceAtLeast(0.01f),
            frameSize = strip.frameSize,
            startFrame = strip.startFrame.coerceAtLeast(0),
            cols = strip.cols.coerceAtLeast(1),
            rows = strip.rows.coerceAtLeast(1),
        )
        return true
    }

    private fun rebindSeatTextures() {
        var content = false
        var fps = 0f
        for (seat in seats) {
            val slot = if (seat.active && program != 0) textures[seat.url] else null
            seat.tex = slot
            if (slot != null) {
                content = true
                if (slot.fps > fps) fps = slot.fps
            }
        }
        hasContent = content
        animFps = fps
        trimTextures()
    }

    private fun trimTextures() {
        if (textures.size <= maxTextures) return
        val inUse = HashSet<String>(maxSeats)
        for (seat in seats) {
            if (seat.active) inUse.add(seat.url)
        }
        val iterator = textures.entries.iterator()
        while (iterator.hasNext() && textures.size > maxTextures) {
            val entry = iterator.next()
            if (entry.key in inUse) continue
            deleteTexture(entry.value.textureId)
            iterator.remove()
        }
    }

    private fun uploadTexture(bitmap: Bitmap): Int? {
        drainGlErrors()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        if (id == 0) return null
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        runCatching { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0) }
            .onFailure { MicSpriteConfig.logger.w("texImage2D threw", it, tag) }
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            MicSpriteConfig.logger.w("texImage2D failed: 0x${err.toString(16)}", tag = tag)
            GLES20.glDeleteTextures(1, ids, 0)
            return null
        }
        return id
    }

    private fun drainGlErrors() {
        var guard = 0
        while (guard < maxErrorDrain) {
            if (GLES20.glGetError() == GLES20.GL_NO_ERROR) return
            guard++
        }
    }

    private fun queryTextureLimit(): Int {
        val value = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, value, 0)
        return if (value[0] > 0) value[0] else fallbackTextureLimit
    }

    private fun deleteAllTextures() {
        for (slot in textures.values) deleteTexture(slot.textureId)
        textures.clear()
    }

    private fun deleteTexture(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    private fun drainPending() {
        while (true) {
            val strip = pendingUploads.poll() ?: break
            recycleStrip(strip)
        }
    }

    private fun recycleStrip(strip: MicSpriteFrameStrip) {
        strip.release()
    }

    private fun createProgram(vs: String, fs: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vs)
        if (vertex == 0) return 0
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        if (fragment == 0) {
            GLES20.glDeleteShader(vertex)
            return 0
        }
        val created = GLES20.glCreateProgram()
        if (created == 0) {
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            return 0
        }
        GLES20.glAttachShader(created, vertex)
        GLES20.glAttachShader(created, fragment)
        GLES20.glLinkProgram(created)
        val status = IntArray(1)
        GLES20.glGetProgramiv(created, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] == 0) {
            MicSpriteConfig.logger.e("link failed: ${GLES20.glGetProgramInfoLog(created)}", tag = tag)
            GLES20.glDeleteProgram(created)
            return 0
        }
        return created
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            MicSpriteConfig.logger.e("compile failed: ${GLES20.glGetShaderInfoLog(shader)}", tag = tag)
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
