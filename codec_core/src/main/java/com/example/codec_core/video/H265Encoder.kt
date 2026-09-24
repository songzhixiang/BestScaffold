package com.example.codec_core.video

import android.media.MediaFormat
import com.example.codec_core.api.EncoderCallback
import com.example.codec_core.config.VideoEncoderConfig

/** 使用设备 MediaCodec 实现的 H.265/HEVC Surface 输入编码器。 */
class H265Encoder(
    config: VideoEncoderConfig,
    callback: EncoderCallback,
) : SurfaceVideoEncoder(
    mimeType = MediaFormat.MIMETYPE_VIDEO_HEVC,
    config = config,
    callback = callback,
    threadName = "H265-Encoder-Thread",
)
