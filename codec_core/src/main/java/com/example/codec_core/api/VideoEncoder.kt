package com.example.codec_core.api

import android.view.Surface

/** 使用 Surface 接收 OpenGL 或 Camera 画面的硬件视频编码器。 */
interface VideoEncoder : Encoder {

    /** start() 成功后可用；stop() 或 release() 后失效。 */
    val inputSurface: Surface?

    /** 请求编码器尽快生成一个关键帧。 */
    fun requestKeyFrame()
}
