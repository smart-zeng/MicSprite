package com.github.smartzeng.micsprite.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.github.smartzeng.micsprite.core.gl.MicSpriteGlDispatcher
import com.github.smartzeng.micsprite.core.gl.MicSpriteGlSession
import com.github.smartzeng.micsprite.core.gl.MicSpriteRenderer
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MicSpriteController(
    context: Context,
) : MicSpriteGlDispatcher {
    private val appContext = context.applicationContext
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer: MicSpriteRenderer = MicSpriteRenderer(this)
    private val bakingUrls = HashSet<String>()
    private val readyUrls = HashSet<String>()
    private val bakeFailCounts = HashMap<String, Int>()
    private val maxBakeFails = 3
    private val seatById = LinkedHashMap<Int, MicSpriteSeat>()
    private val urlByMic = LinkedHashMap<Int, String>()
    private var session: MicSpriteGlSession? = null
    private var lastDraws = emptyList<MicSpriteDrawItem>()
    private var neededUrls = emptySet<String>()
    private var syncJob: Job? = null
    private var released = false
    private var anchorRetryPosted = false
    private var ticking = false
    private var idleTicks = 0
    private var traceStartMs = 0L
    private var waitLogSec = -1

    private val syncDebounceMs = 80L
    private val anchorRetryMs = 100L
    private val minFrameIntervalMs = 16L
    private val maxFrameIntervalMs = 100L
    private val idleTickLimit = 20

    private val debouncedSync = Runnable { submitNow(force = false) }
    private val anchorRetryRunnable = Runnable {
        anchorRetryPosted = false
        submitNow(force = true)
    }
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            if (renderer.hasContent()) {
                idleTicks = 0
                requestRender()
            } else if (++idleTicks >= idleTickLimit) {
                ticking = false
                return
            }
            mainHandler.postDelayed(this, frameIntervalMs())
        }
    }

    init {
        MicSpriteBaker.registerClient()
    }


    fun setSeats(seats: List<MicSpriteSeat>) {
        if (released) return
        seatById.clear()
        for (seat in seats) {
            seatById[seat.micId] = seat
        }
        markTrace("seats=${seatById.size}")
        scheduleSync(immediate = urlByMic.isEmpty())
    }


    fun setSeat(seat: MicSpriteSeat) {
        if (released) return
        seatById[seat.micId] = seat
        markTrace("seat=${seat.micId}")
        scheduleSync(immediate = urlByMic.isEmpty())
    }

    fun removeSeat(micId: Int) {
        if (released) return
        if (seatById.remove(micId) == null) return
        scheduleSync(immediate = true)
    }


    fun setHeadwear(micId: Int, url: String) {
        if (released) return
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            if (urlByMic.remove(micId) == null) return
        } else {
            urlByMic[micId] = trimmed
        }
        markTrace("headwear mic=$micId")
        scheduleSync(immediate = true)
    }

    fun removeHeadwear(micId: Int) {
        if (released) return
        if (urlByMic.remove(micId) == null) return
        scheduleSync(immediate = true)
    }

    fun setHeadwears(headwears: Map<Int, String>) {
        if (released) return
        urlByMic.clear()
        for ((micId, url) in headwears) {
            val trimmed = url.trim()
            if (trimmed.isNotEmpty()) urlByMic[micId] = trimmed
        }
        markTrace("headwears=${urlByMic.size}")
        scheduleSync(immediate = true)
    }

    fun clearHeadwears() {
        if (released) return
        if (urlByMic.isEmpty()) return
        urlByMic.clear()
        scheduleSync(immediate = true)
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        if (released) return
        detachSurface()
        val created = MicSpriteGlSession(renderer)
        session = created
        renderer.reset()
        val attachAt = SystemClock.elapsedRealtime()
        created.attach(surface, width, height) {
            if (released || session !== created) return@attach
            MicSpriteConfig.logger.i(
                "surface ready ${width}x$height +${sinceTrace()}ms egl=${SystemClock.elapsedRealtime() - attachAt}ms",
            )
            readyUrls.clear()
            submitNow(force = true)
        }
    }

    fun resize(width: Int, height: Int) {
        session?.resize(width, height)
        if (urlByMic.isNotEmpty() && seatById.isNotEmpty()) submitNow(force = true)
    }

    fun detachSurface() {
        val current = session ?: return
        session = null
        readyUrls.clear()
        stopTicking()
        current.shutdown()
        renderer.markSurfaceDestroyed()
    }

    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacks(debouncedSync)
        mainHandler.removeCallbacks(anchorRetryRunnable)
        stopTicking()
        syncJob?.cancel()
        scope.cancel()
        detachSurface()
        renderer.release()
        MicSpriteBaker.unregisterClient()
    }

    override fun queue(block: () -> Unit) {
        session?.queue(block)
    }

    override fun requestRender() {
        session?.requestRender()
    }

    private fun scheduleSync(immediate: Boolean) {
        if (released) return
        mainHandler.removeCallbacks(debouncedSync)
        if (immediate) {
            submitNow(force = true)
        } else {
            mainHandler.postDelayed(debouncedSync, syncDebounceMs)
        }
    }

    private fun submitNow(force: Boolean) {
        if (released) return
        syncJob?.cancel()
        syncJob = scope.launch {
            val draws = snapshotDraws()
            if (draws.isEmpty()) {
                lastDraws = emptyList()
                neededUrls = emptySet()
                bakeFailCounts.clear()
                renderer.updateDraws(emptyList())
                evictUnusedTextures(emptySet())
                stopTicking()
                return@launch
            }
            if (!renderer.isSurfaceReady()) {
                val sec = (sinceTrace() / 1000L).toInt()
                if (sec != waitLogSec) {
                    waitLogSec = sec
                    MicSpriteConfig.logger.i("wait surface +${sinceTrace()}ms seats=${draws.size}")
                }
                scheduleAnchorRetry()
                return@launch
            }
            val urls = draws.map { it.url }.toSet()
            neededUrls = urls
            bakeFailCounts.keys.retainAll(urls)
            if (!force && drawsAlmostSame(lastDraws, draws)) {
                urls.forEach { ensureBaked(it) }
                return@launch
            }
            lastDraws = draws
            renderer.updateDraws(draws)
            evictUnusedTextures(urls)
            requestRender()
            startTicking()
            urls.forEach { ensureBaked(it) }
        }
    }

    private fun snapshotDraws(): List<MicSpriteDrawItem> {
        val draws = ArrayList<MicSpriteDrawItem>(seatById.size)
        for ((micId, seat) in seatById) {
            val url = urlByMic[micId]?.takeIf { it.isNotBlank() } ?: continue
            if (seat.right <= seat.left || seat.bottom <= seat.top) continue
            val scale = seat.scale.takeIf { it > 0f } ?: MicSpriteConfig.headwearScale
            draws += MicSpriteDrawItem(
                micId = micId,
                url = url,
                left = seat.left,
                top = seat.top,
                right = seat.right,
                bottom = seat.bottom,
                scale = scale,
            )
        }
        return draws
    }

    private fun scheduleAnchorRetry() {
        if (anchorRetryPosted || released) return
        anchorRetryPosted = true
        mainHandler.postDelayed(anchorRetryRunnable, anchorRetryMs)
    }

    private fun drawsAlmostSame(
        previous: List<MicSpriteDrawItem>,
        next: List<MicSpriteDrawItem>,
    ): Boolean {
        if (previous.size != next.size) return false
        for (index in previous.indices) {
            val left = previous[index]
            val right = next[index]
            if (left.micId != right.micId || left.url != right.url) return false
            if (abs(left.scale - right.scale) > 0.01f) return false
            if (abs(left.left - right.left) > 1f || abs(left.top - right.top) > 1f) return false
            if (abs(left.right - right.right) > 1f || abs(left.bottom - right.bottom) > 1f) return false
        }
        return true
    }

    private fun frameIntervalMs(): Long {
        val fps = renderer.animFps()
        if (fps <= 0f) return maxFrameIntervalMs
        return (1000f / fps).toLong().coerceIn(minFrameIntervalMs, maxFrameIntervalMs)
    }

    private fun startTicking() {
        idleTicks = 0
        if (ticking) return
        ticking = true
        mainHandler.post(tickRunnable)
    }

    private fun stopTicking() {
        ticking = false
        mainHandler.removeCallbacks(tickRunnable)
    }

    private fun evictUnusedTextures(keepUrls: Set<String>) {
        val removed = readyUrls.filter { it !in keepUrls }
        if (removed.isNotEmpty()) readyUrls.removeAll(removed.toSet())
        renderer.evictUnused(keepUrls)
    }

    private fun ensureBaked(url: String) {
        val key = url.trim()
        if (key.isEmpty() || released) return
        if (readyUrls.contains(key) || bakingUrls.contains(key)) return
        if ((bakeFailCounts[key] ?: 0) >= maxBakeFails) return
        bakingUrls.add(key)
        scope.launch {
            var owned: MicSpriteFrameStrip? = null
            try {
                val limit = renderer.textureSizeLimit()
                val bakeAt = SystemClock.elapsedRealtime()
                MicSpriteConfig.logger.i("bake start +${sinceTrace()}ms limit=$limit")
                owned = try {
                    MicSpriteBaker.bake(appContext, key, limit)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    MicSpriteConfig.logger.w("bake threw +${sinceTrace()}ms", error)
                    null
                }
                MicSpriteConfig.logger.i(
                    "bake back +${sinceTrace()}ms cost=${SystemClock.elapsedRealtime() - bakeAt}ms ok=${owned != null}",
                )
                if (released) return@launch
                val strip = owned ?: run {
                    val fails = (bakeFailCounts[key] ?: 0) + 1
                    bakeFailCounts[key] = fails
                    if (fails < maxBakeFails) {
                        scheduleAnchorRetry()
                    } else {
                        MicSpriteConfig.logger.w("bake stop retrying after $fails tries: $key")
                    }
                    return@launch
                }
                bakeFailCounts.remove(key)
                if (key !in neededUrls || !renderer.isSurfaceReady()) return@launch
                readyUrls.add(key)
                MicSpriteConfig.logger.i("upload queue +${sinceTrace()}ms")
                renderer.uploadStrip(strip)
                owned = null
                startTicking()
            } finally {
                bakingUrls.remove(key)
                owned?.release()
            }
        }
    }

    private fun markTrace(label: String) {
        if (traceStartMs != 0L || urlByMic.isEmpty() || seatById.isEmpty()) return
        traceStartMs = SystemClock.elapsedRealtime()
        MicSpriteConfig.logger.i("$label +0ms")
    }

    private fun sinceTrace(): Long {
        if (traceStartMs == 0L) return 0L
        return SystemClock.elapsedRealtime() - traceStartMs
    }
}
