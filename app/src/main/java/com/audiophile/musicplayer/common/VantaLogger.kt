package com.audiophile.musicplayer.common

import android.util.Log
import androidx.core.net.toUri
import com.audiophile.musicplayer.BuildConfig

/**
 * Thin, tag-enforced logging abstraction.
 *
 * Rules:
 * - All tags must be uppercase snake_case and ≤ 23 chars (Logcat limit).
 * - Use structured key=value pairs in messages for grep-ability.
 * - Debug logs are no-ops in release builds.
 *
 * Example:
 *   VantaLogger.d(Tag.SEARCH, "query='$q' results=${list.size}")
 *   VantaLogger.e(Tag.PLAYBACK, "stream_failed url='$url'", cause)
 */
object VantaLogger {

    /** Canonical log tags — add new ones here rather than in call sites. */
    enum class Tag(val value: String) {
        APP("ENCORE_APP"),
        SEARCH("ENCORE_SEARCH"),
        PLAYBACK("ENCORE_PLAYBACK"),
        STREAM("ENCORE_STREAM"),
        QUEUE("ENCORE_QUEUE"),
        LIBRARY("ENCORE_LIBRARY"),
        STATION("ENCORE_STATION"),
        SOURCE("ENCORE_SOURCE"),
        SETTINGS("ENCORE_SETTINGS"),
        IMPORT("ENCORE_IMPORT"),
        AI_DJ("ENCORE_AI_DJ"),
        LYRICS("ENCORE_LYRICS"),
        CAST("ENCORE_CAST"),
        NETWORK("ENCORE_NETWORK"),
        DI("ENCORE_DI"),
        STARTUP("ENCORE_STARTUP"),
        ACCEPTANCE("ENCORE_ACCEPTANCE"),
        TRACK_TRUTH("ENCORE_TRACK_TRUTH"),
        LIBRARY_ACTION("ENCORE_LIBRARY_ACTION"),
        RADIO_TRUTH("ENCORE_RADIO_TRUTH"),
        POSITION_TRUTH("ENCORE_POSITION_TRUTH"),
    }

    fun v(tag: Tag, msg: String) {
        if (BuildConfig.DEBUG) Log.v(tag.value, msg)
    }

    fun d(tag: Tag, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag.value, msg)
    }

    fun i(tag: Tag, msg: String) = Log.i(tag.value, msg)

    fun w(tag: Tag, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag.value, msg, tr) else Log.w(tag.value, msg)
    }

    fun e(tag: Tag, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag.value, msg, tr) else Log.e(tag.value, msg)
    }

    /** Host-only representation safe for logs; strips paths, queries, and signed tokens. */
    fun urlHost(value: String?): String = runCatching {
        value.orEmpty().toUri().host?.take(100)
    }.getOrNull().orEmpty().ifBlank { "unknown" }
}
