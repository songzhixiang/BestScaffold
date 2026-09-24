package com.example.codec_core.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import com.example.codec_core.api.EncoderCallback
import com.example.codec_core.api.EncoderState
import com.example.codec_core.api.VideoEncoder
import com.example.codec_core.config.VideoEncoderConfig
import com.example.codec_core.internal.MediaCodecEncoder

/** H.264 与 H.265 共用的 Surface 输入实现。 */
abstract class SurfaceVideoEncoder internal constructor(
    private val mimeType: String,
    private val config: VideoEncoderConfig,
    callback: EncoderCallback,
    threadName: String,
) : MediaCodecEncoder(mimeType, threadName, callback), VideoEncoder {

    private var eosSignalled = false

    @Volatile
    final override var inputSurface: Surface? = null
        private set

    final override fun createMediaFormat(): MediaFormat =
        MediaFormat.createVideoFormat(mimeType, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_BITRATE_MODE, config.bitRateMode)
            config.profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
            config.level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
        }

    final override fun onCodecConfigured(codec: MediaCodec) {
        inputSurface = codec.createInputSurface()
    }

    final override fun onCodecStarted(codec: MediaCodec) {
        eosSignalled = false
    }

    final override fun onSignalEndOfStream(codec: MediaCodec) {
        if (!eosSignalled) {
            eosSignalled = true
            codec.signalEndOfInputStream()
        }
    }

    final override fun requestKeyFrame() {
        if (state != EncoderState.RUNNING) return
        codecHandler.post {
            val current = codec ?: return@post
            try {
                current.setParameters(
                    Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    },
                )
            } catch (error: Throwable) {
                notifyError("请求视频关键帧失败", error)
            }
        }
    }

    final override fun onBeforeCodecReleased() {
        eosSignalled = false
        inputSurface?.release()
        inputSurface = null
    }
}
