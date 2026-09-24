package com.example.codec_core.api

import java.io.Closeable

/** 编码器通用状态。 */
enum class EncoderState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR,
    RELEASED,
}

/**
 * 编码器的统一生命周期接口。
 *
 * start()、stop() 和 release() 可以从任意线程调用；release() 后实例不能再次使用。
 */
interface Encoder : Closeable {

    val state: EncoderState

    /** 创建并启动底层 MediaCodec。 */
    fun start()

    /** 通知编码器不再有输入，编码器会在输出端产生 EOS。 */
    fun signalEndOfStream()

    /** 停止当前编码过程；停止后可以再次调用 start()。 */
    fun stop()

    /** 彻底释放编码器及其工作线程。 */
    fun release()

    override fun close() = release()
}
