@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.audiophile.musicplayer.playback

/** OBR is linked into the bundled IAMF decoder; no optional plug-in is required.
 *  The IamfLibrary class is loaded reflectively because media3-decoder-iamf is
 *  an optional Media3 extension not declared as a project dependency. */
object EclipsaObrRenderer {
    val isAvailable: Boolean get() = runCatching {
        Class.forName("androidx.media3.decoder.iamf.IamfLibrary")
            .getMethod("isAvailable")
            .invoke(null) as Boolean
    }.getOrDefault(false)
}
