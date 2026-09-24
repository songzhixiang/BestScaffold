package com.example.codec_core.api

import android.media.MediaCodec
import android.media.MediaFormat

/** 已从 MediaCodec 输出缓冲区复制出来的编码数据。 */
data class EncodedFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int,
) {
    val isKeyFrame: Boolean
        get() = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

    val isCodecConfig: Boolean
        get() = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0

    val isEndOfStream: Boolean
        get() = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
}

/**
 * 所有回调均发生在编码器自己的工作线程。
 * EncodedFrame 已持有独立字节数组，可以安全地交给其他线程或封装器使用。
 */
interface EncoderCallback {

    /** 输出格式已经确定；其中包含视频 SPS/PPS/VPS 或音频 AudioSpecificConfig。 */
    fun onOutputFormatChanged(format: MediaFormat) = Unit

    /** 收到一帧编码数据，包含配置帧和 EOS 帧。 */
    fun onEncodedFrame(frame: EncodedFrame)

    /** 编码过程发生不可恢复错误。 */
    fun onError(message: String, cause: Throwable?) = Unit
}
