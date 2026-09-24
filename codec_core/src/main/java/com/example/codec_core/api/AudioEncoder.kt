package com.example.codec_core.api

/** 接收交错排列 PCM 数据的音频编码器。 */
interface AudioEncoder : Encoder {

    /**
     * 提交 PCM 16-bit little-endian 数据。
     * presentationTimeUs 表示 data 中第一个采样帧的时间戳。
     *
     * @return true 表示数据已进入队列；false 表示编码器未运行、已收到 EOS 或队列已满。
     */
    fun queuePcm(
        data: ByteArray,
        offset: Int = 0,
        size: Int = data.size - offset,
        presentationTimeUs: Long,
    ): Boolean
}
