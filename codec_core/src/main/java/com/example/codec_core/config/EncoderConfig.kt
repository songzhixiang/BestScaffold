package com.example.codec_core.config

import android.media.MediaCodecInfo
import android.media.MediaFormat

data class VideoEncoderConfig(
    val width: Int,
    val height: Int,
    val bitRate: Int,
    val frameRate: Int = 30,
    val iFrameIntervalSeconds: Int = 2,
    val bitRateMode: Int = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
    val profile: Int? = null,
    val level: Int? = null,
) {
    init {
        require(width > 0 && height > 0) { "视频宽高必须大于 0" }
        require(bitRate > 0) { "视频码率必须大于 0" }
        require(frameRate > 0) { "视频帧率必须大于 0" }
        require(iFrameIntervalSeconds >= 0) { "关键帧间隔不能小于 0" }
    }
}

data class AudioEncoderConfig(
    val sampleRate: Int = 48_000,
    val channelCount: Int = 1,
    val bitRate: Int = 128_000,
    val aacProfile: Int = MediaCodecInfo.CodecProfileLevel.AACObjectLC,
    val maxInputSize: Int = 16 * 1024,
    val maxPendingPcmBytes: Int = 2 * 1024 * 1024,
) {
    init {
        require(sampleRate > 0) { "音频采样率必须大于 0" }
        require(channelCount in 1..2) { "AAC 编码器目前只支持单声道或双声道" }
        require(bitRate > 0) { "音频码率必须大于 0" }
        require(maxInputSize > 0) { "音频输入缓冲区大小必须大于 0" }
        require(maxPendingPcmBytes >= maxInputSize) { "PCM 队列容量不能小于单个输入缓冲区" }
    }

    internal fun createMediaFormat(): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
            .apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
            }
}
