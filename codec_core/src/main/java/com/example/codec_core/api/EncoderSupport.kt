package com.example.codec_core.api

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

data class EncoderInfo(
    val name: String,
    val isHardwareAccelerated: Boolean,
    val isSoftwareOnly: Boolean,
    val isVendor: Boolean,
)

/** 查询当前设备公开的编码器能力，特别适合在启用 H.265 前进行判断。 */
object EncoderSupport {

    fun findH264Encoders(): List<EncoderInfo> = findEncoders(MediaFormat.MIMETYPE_VIDEO_AVC)

    fun findH265Encoders(): List<EncoderInfo> = findEncoders(MediaFormat.MIMETYPE_VIDEO_HEVC)

    fun findAacEncoders(): List<EncoderInfo> = findEncoders(MediaFormat.MIMETYPE_AUDIO_AAC)

    fun findEncoders(mimeType: String): List<EncoderInfo> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter(MediaCodecInfo::isEncoder)
            .filter { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
            .map { info ->
                EncoderInfo(
                    name = info.name,
                    isHardwareAccelerated = info.isHardwareAccelerated,
                    isSoftwareOnly = info.isSoftwareOnly,
                    isVendor = info.isVendor,
                )
            }
            .toList()
}
