package com.github.smartzeng.micsprite.core.decoder

import com.github.smartzeng.micsprite.core.MicSpriteConfig
import com.github.smartzeng.micsprite.core.MicSpriteDecoder
import java.util.ServiceConfigurationError
import java.util.ServiceLoader


object MicSpriteDecoderRegistry {

    private val lock = Any()
    private val decoders = mutableListOf<MicSpriteDecoder>()

    @Volatile
    private var discovered = false

    fun register(decoder: MicSpriteDecoder) {
        synchronized(lock) {
            addIfAbsent(decoder)
        }
    }

    fun resolve(url: String): MicSpriteDecoder? {
        discover()
        synchronized(lock) {
            return decoders.firstOrNull { it.supports(url) }
        }
    }

    fun discover() {
        if (discovered) return
        synchronized(lock) {
            if (discovered) return
            try {
                val loader = ServiceLoader.load(
                    MicSpriteDecoder::class.java,
                    MicSpriteDecoder::class.java.classLoader,
                )
                val iterator = loader.iterator()
                while (true) {
                    val decoder = try {
                        if (!iterator.hasNext()) break
                        iterator.next()
                    } catch (error: ClassNotFoundException) {
                        MicSpriteConfig.logger.w("加载 MicSpriteDecoder 失败", error)
                        continue
                    } catch (error: ServiceConfigurationError) {
                        MicSpriteConfig.logger.w("加载 MicSpriteDecoder 失败", error)
                        continue
                    }
                    addIfAbsent(decoder)
                }
            } catch (error: ClassNotFoundException) {
                MicSpriteConfig.logger.w("ServiceLoader 加载 MicSpriteDecoder 失败", error)
            } catch (error: ServiceConfigurationError) {
                MicSpriteConfig.logger.w("ServiceLoader 加载 MicSpriteDecoder 失败", error)
            }
            loadKnownDecoders()
            discovered = true
        }
    }

    private fun addIfAbsent(decoder: MicSpriteDecoder) {
        val type = decoder::class.java
        if (decoders.none { it::class.java == type }) {
            decoders.add(decoder)
        }
    }

    private fun loadKnownDecoders() {
        for (name in knownDecoders) {
            try {
                val clazz = Class.forName(name)
                val decoder = clazz.getDeclaredConstructor().newInstance() as MicSpriteDecoder
                addIfAbsent(decoder)
            } catch (_: ClassNotFoundException) {
            } catch (error: Throwable) {
                MicSpriteConfig.logger.w("注册 $name 失败", error)
            }
        }
    }

    private val knownDecoders = listOf(
        "com.github.smartzeng.micsprite.decoder.svga.SvgaDecoder",
        "com.github.smartzeng.micsprite.decoder.pag.PagDecoder",
        "com.github.smartzeng.micsprite.decoder.frames.FrameSequenceDecoder",
    )
}
