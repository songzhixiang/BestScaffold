package com.example.codec_core.video

import android.media.MediaFormat
import com.example.codec_core.api.EncoderCallback
import com.example.codec_core.config.VideoEncoderConfig

/** 使用设备 MediaCodec 实现的 H.264/AVC Surface 输入编码器。 */
class H264Encoder(
    config: VideoEncoderConfig,
    callback: EncoderCallback,
) : SurfaceVideoEncoder(
    mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
    config = config,
    callback = callback,
    threadName = "H264-Encoder-Thread",
)
