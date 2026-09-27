@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.audiophile.musicplayer.playback

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.audiophile.musicplayer.playback.dsp.VantaEqualizerProcessor
import android.os.Build
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

// Reflection-loaded Media3 optional decoders. The Java wrapper classes for
// IAMF (libiamf) and MPEG-H (libmpegh) live in Media3's optional extension
// modules which are not published as standalone Maven artifacts at the
// version pinned in this build. We load them reflectively so the build
// compiles whether or not the wrappers are on the classpath.
private object OptionalMedia3Decoders {
    val libIamfAudioRendererClass: Class<*>? by lazy {
        runCatching { Class.forName("androidx.media3.decoder.iamf.LibiamfAudioRenderer") }.getOrNull()
    }
    val mpeghAudioRendererClass: Class<*>? by lazy {
        runCatching { Class.forName("androidx.media3.decoder.mpegh.MpeghAudioRenderer") }.getOrNull()
    }
    val iamfLibraryClass: Class<*>? by lazy {
        runCatching { Class.forName("androidx.media3.decoder.iamf.IamfLibrary") }.getOrNull()
    }
}

/** Binaural IAMF has its own sink so the stereo widening/EQ chain cannot render it twice. */
class VantaSpatialRenderersFactory(context: Context, private val equalizer: VantaEqualizerProcessor? = null,
    private val iamfProcessors: Array<androidx.media3.common.audio.AudioProcessor> = emptyArray()) :
    DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
    }
    @Suppress("DEPRECATION")
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        // Qobuz enables float output so hi-res decode stays in float right up to
        // the AudioTrack write. Default off here preserves the existing PCM16
        // behavior; turn it on in Settings → Audio for float-tracks if wanted.
        val floatOutput = PlaybackOutputPreferences(context).floatOutputEnabled()
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(floatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .apply { equalizer?.let { setAudioProcessors(arrayOf(it)) } }
            .build()
        AudioSinkHolder.register(sink)
        OutputSwitchController.applySelection(context, sink)
        // Return the truth proxy to Media3; the real sink stays registered so
        // OutputSwitchController's DefaultAudioSink casts keep working.
        return VantaAudioSinkProxy(sink)
    }

    override fun buildAudioRenderers(context: Context, extensionRendererMode: Int,
        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: Handler,
        eventListener: AudioRendererEventListener, out: ArrayList<Renderer>) {
        val headphoneProcessors: Array<androidx.media3.common.audio.AudioProcessor> =
            if (equalizer != null) arrayOf(equalizer, *iamfProcessors) else iamfProcessors
        // IAMF (libiamf) optional renderer — loaded reflectively so the build
        // doesn't require the media3-decoder-iamf extension artifact.
        OptionalMedia3Decoders.libIamfAudioRendererClass?.let { cls ->
            runCatching {
                val ctor = cls.getConstructor(
                    Context::class.java,
                    Handler::class.java,
                    AudioRendererEventListener::class.java,
                    AudioSink::class.java
                )
                out.add(
                    ctor.newInstance(
                        context,
                        eventHandler,
                        eventListener,
                        DefaultAudioSink.Builder(context).setAudioProcessors(headphoneProcessors).build()
                    ) as Renderer
                )
            }
        }
        // LC streams use Ittiam; baseline/unspecified MPEG-H uses Fraunhofer.
        out.add(com.audiophile.musicplayer.playback.spatial.SpatialAudioRenderer(1, eventHandler, eventListener,
            DefaultAudioSink.Builder(context).setAudioProcessors(headphoneProcessors).build()))
        // MPEG-H (libmpegh) optional renderer — also reflectively loaded.
        OptionalMedia3Decoders.mpeghAudioRendererClass?.let { cls ->
            runCatching {
                val ctor = cls.getConstructor(
                    Handler::class.java,
                    AudioRendererEventListener::class.java,
                    AudioSink::class.java
                )
                out.add(
                    ctor.newInstance(
                        eventHandler,
                        eventListener,
                        DefaultAudioSink.Builder(context).setAudioProcessors(headphoneProcessors).build()
                    ) as Renderer
                )
            }
        }
        if (!SpatialDecoderCapabilities.supportsAtmosOutput()) {
            out.add(com.audiophile.musicplayer.playback.spatial.SpatialAudioRenderer(0, eventHandler, eventListener,
                DefaultAudioSink.Builder(context).setAudioProcessors(headphoneProcessors).build()))
        }
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector,
            enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        val pixelAtmosGuard = Build.MANUFACTURER.equals("Google", ignoreCase = true) && Build.MODEL.startsWith("Pixel")
        val fiioLegacyFlac = Build.VERSION.SDK_INT <= 29 && Build.MODEL.startsWith("FiiO", ignoreCase = true)
        if (pixelAtmosGuard || fiioLegacyFlac) {
            for (index in out.indices) {
                if (out[index] !is androidx.media3.exoplayer.audio.MediaCodecAudioRenderer) continue
                out[index] = object : androidx.media3.exoplayer.audio.MediaCodecAudioRenderer(
                    context, getCodecAdapterFactory(), mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, audioSink
                ) {
                    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
                        // The M11 Plus Android 10 OMX decoder drops STREAMINFO on flush.
                        // Recreate it so MediaCodec receives its codec-specific data again.
                        if (fiioLegacyFlac && sampleStreamIsResetToKeyFrame &&
                            codecInfo?.name == "OMX.google.flac.decoder") {
                            releaseCodec()
                        }
                        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
                    }

                    override fun supportsFormat(selector: MediaCodecSelector, format: androidx.media3.common.Format): Int {
                        // Guard the original format: Media3's soft match queries
                        // E-AC-3 separately, so a selector-only filter is insufficient.
                        if (pixelAtmosGuard && com.audiophile.musicplayer.playback.spatial.SpatialMimeSupport.isOpenJocInput(format) &&
                            !SpatialDecoderCapabilities.supportsAtmosOutput()) {
                            return androidx.media3.exoplayer.RendererCapabilities.create(
                                androidx.media3.common.C.FORMAT_UNSUPPORTED_SUBTYPE)
                        }
                        return super.supportsFormat(selector, format)
                    }
                }
            }
        }
    }
}
