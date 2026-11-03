package io.github.smartzeng.micsprite.core

import android.util.Log

interface MicSpriteLogger {


    fun i(msg: String, tag: String = TAG)


    fun w(msg: String, error: Throwable? = null, tag: String = TAG)


    fun e(msg: String, error: Throwable? = null, tag: String = TAG)

    companion object {
        const val TAG = "MicSprite"
    }
}


class DefaultLogger : MicSpriteLogger {

    override fun i(msg: String, tag: String) {
        Log.i(tag, msg)
    }

    override fun w(msg: String, error: Throwable?, tag: String) {
        if (error != null) {
            Log.w(tag, msg, error)
        } else {
            Log.w(tag, msg)
        }
    }

    override fun e(msg: String, error: Throwable?, tag: String) {
        if (error != null) {
            Log.e(tag, msg, error)
        } else {
            Log.e(tag, msg)
        }
    }
}
