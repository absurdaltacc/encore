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
        APP("FRONTIER_APP"),
        SEARCH("FRONTIER_SEARCH"),
        PLAYBACK("FRONTIER_PLAYBACK"),
        STREAM("FRONTIER_STREAM"),
        QUEUE("FRONTIER_QUEUE"),
        LIBRARY("FRONTIER_LIBRARY"),
        STATION("FRONTIER_STATION"),
        SOURCE("FRONTIER_SOURCE"),
        SETTINGS("FRONTIER_SETTINGS"),
        IMPORT("FRONTIER_IMPORT"),
        AI_DJ("FRONTIER_AI_DJ"),
        LYRICS("FRONTIER_LYRICS"),
        CAST("FRONTIER_CAST"),
        NETWORK("FRONTIER_NETWORK"),
        DI("FRONTIER_DI"),
        STARTUP("FRONTIER_STARTUP"),
        ACCEPTANCE("FRONTIER_ACCEPTANCE"),
        TRACK_TRUTH("FRONTIER_TRACK_TRUTH"),
        LIBRARY_ACTION("FRONTIER_LIBRARY_ACTION"),
        RADIO_TRUTH("FRONTIER_RADIO_TRUTH"),
        POSITION_TRUTH("FRONTIER_POSITION_TRUTH"),
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
