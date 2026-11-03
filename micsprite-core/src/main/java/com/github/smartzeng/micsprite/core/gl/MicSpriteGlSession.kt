package com.github.smartzeng.micsprite.core.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.github.smartzeng.micsprite.core.MicSpriteConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MicSpriteGlSession(
    private val renderer: MicSpriteRenderer,
) {
    private val thread = HandlerThread("MicSpriteGl").apply { start() }
    private val handler = Handler(thread.looper)
    private var env: EglEnv? = null
    private var released = false

    fun attach(surface: Surface, width: Int, height: Int, onReady: () -> Unit) {
        handler.post {
            if (released) return@post
            env?.release()
            val created = runCatching { EglEnv(surface) }.getOrElse {
                MicSpriteConfig.logger.e("seat anim egl failed", it, "MicSpriteGl")
                return@post
            }
            env = created
            if (!created.makeCurrent()) return@post
            renderer.onContextCreated()
            renderer.onSurfaceChanged(width, height)
            if (!renderer.isSurfaceReady()) return@post
            Handler(Looper.getMainLooper()).post(onReady)
        }
    }

    fun resize(width: Int, height: Int) {
        handler.post {
            val current = env ?: return@post
            if (released || !current.makeCurrent()) return@post
            renderer.onSurfaceChanged(width, height)
            renderer.onDrawFrame()
            current.swap()
        }
    }

    fun queue(block: () -> Unit) {
        if (released) return
        handler.post {
            if (released || env?.makeCurrent() != true) return@post
            block()
        }
    }

    fun requestRender() {
        if (released) return
        handler.post {
            val current = env ?: return@post
            if (released || !current.makeCurrent()) return@post
            renderer.onDrawFrame()
            current.swap()
        }
    }

    fun shutdown() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            if (env?.makeCurrent() == true) renderer.onContextLost()
            env?.release()
            env = null
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }
}

private class EglEnv(surface: Surface) {
    private val display: EGLDisplay
    private val context: EGLContext
    private val eglSurface: EGLSurface
    private var released = false

    init {
        val found = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(found != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay" }
        display = found
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)) { "eglChooseConfig" }
        val config = configs[0] ?: error("no egl config")
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val created = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(created != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        context = created
        val window = try {
            val createdSurface = EGL14.eglCreateWindowSurface(
                display,
                config,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            check(createdSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
            createdSurface
        } catch (error: Throwable) {
            EGL14.eglDestroyContext(display, created)
            throw error
        }
        eglSurface = window
    }

    fun makeCurrent(): Boolean {
        if (released) return false
        return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
    }

    fun swap() {
        if (!released) EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        if (released) return
        released = true
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
    }
}
