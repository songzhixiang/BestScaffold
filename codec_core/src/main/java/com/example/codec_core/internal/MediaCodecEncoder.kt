package com.example.codec_core.internal

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.example.codec_core.api.EncodedFrame
import com.example.codec_core.api.Encoder
import com.example.codec_core.api.EncoderCallback
import com.example.codec_core.api.EncoderState
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference

/** MediaCodec 异步编码器的公共生命周期与输出处理。 */
abstract class MediaCodecEncoder internal constructor(
    private val mimeType: String,
    threadName: String,
    private val callback: EncoderCallback,
) : Encoder {

    private val stateReference = AtomicReference(EncoderState.IDLE)
    private val codecThread = HandlerThread(threadName).apply { start() }
    protected val codecHandler = Handler(codecThread.looper)

    /** 只能在 codecHandler 所在线程访问。 */
    protected var codec: MediaCodec? = null
        private set
    private var codecStarted = false

    final override val state: EncoderState
        get() = stateReference.get()

    final override fun start() {
        runOnCodecThreadBlocking {
            check(state != EncoderState.RELEASED) { "编码器已经释放" }
            check(state == EncoderState.IDLE || state == EncoderState.STOPPED || state == EncoderState.ERROR) {
                "当前状态 $state 不能启动编码器"
            }
            stateReference.set(EncoderState.STARTING)
            try {
                val created = MediaCodec.createEncoderByType(mimeType)
                codec = created
                created.setCallback(codecCallback, codecHandler)
                created.configure(createMediaFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                onCodecConfigured(created)
                created.start()
                codecStarted = true
                stateReference.set(EncoderState.RUNNING)
                onCodecStarted(created)
            } catch (error: Throwable) {
                stateReference.set(EncoderState.ERROR)
                releaseCodecOnCodecThread()
                notifyError("启动 $mimeType 编码器失败", error)
                throw error
            }
        }
    }

    final override fun signalEndOfStream() {
        runOnCodecThreadBlocking {
            check(state == EncoderState.RUNNING) { "只有运行中的编码器才能发送 EOS" }
            val current = checkNotNull(codec) { "MediaCodec 尚未创建" }
            onSignalEndOfStream(current)
        }
    }

    final override fun stop() {
        if (state == EncoderState.RELEASED || state == EncoderState.STOPPED || state == EncoderState.IDLE) return
        runOnCodecThreadBlocking {
            if (state == EncoderState.RELEASED || state == EncoderState.STOPPED) return@runOnCodecThreadBlocking
            stateReference.set(EncoderState.STOPPING)
            releaseCodecOnCodecThread()
            stateReference.set(EncoderState.STOPPED)
        }
    }

    final override fun release() {
        if (state == EncoderState.RELEASED) return
        runOnCodecThreadBlocking {
            if (state == EncoderState.RELEASED) return@runOnCodecThreadBlocking
            stateReference.set(EncoderState.STOPPING)
            releaseCodecOnCodecThread()
            stateReference.set(EncoderState.RELEASED)
            codecThread.quitSafely()
        }
    }

    protected abstract fun createMediaFormat(): MediaFormat

    protected open fun onCodecConfigured(codec: MediaCodec) = Unit

    protected open fun onCodecStarted(codec: MediaCodec) = Unit

    protected abstract fun onSignalEndOfStream(codec: MediaCodec)

    protected open fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

    /** 在 MediaCodec 停止后、释放前调用，用于清理输入 Surface 或输入队列。 */
    protected open fun onBeforeCodecReleased() = Unit

    protected fun notifyError(message: String, cause: Throwable?) {
        Log.e(TAG, message, cause)
        try {
            callback.onError(message, cause)
        } catch (callbackError: Throwable) {
            Log.e(TAG, "编码器错误回调执行失败", callbackError)
        }
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mediaCodec: MediaCodec, index: Int) {
            if (codec !== mediaCodec || state != EncoderState.RUNNING) return
            try {
                this@MediaCodecEncoder.onInputBufferAvailable(mediaCodec, index)
            } catch (error: Throwable) {
                handleCodecFailure(mediaCodec, "处理编码器输入缓冲区失败", error)
            }
        }

        override fun onOutputBufferAvailable(
            mediaCodec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            if (codec !== mediaCodec) {
                releaseOutputBufferSafely(mediaCodec, index)
                return
            }
            try {
                val output = mediaCodec.getOutputBuffer(index)
                val data = if (info.size > 0 && output != null) {
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    ByteArray(info.size).also(output::get)
                } else {
                    ByteArray(0)
                }
                val frame = EncodedFrame(data, info.presentationTimeUs, info.flags)
                releaseOutputBufferSafely(mediaCodec, index)
                try {
                    callback.onEncodedFrame(frame)
                } catch (callbackError: Throwable) {
                    Log.e(TAG, "编码数据回调执行失败", callbackError)
                }
            } catch (error: Throwable) {
                releaseOutputBufferSafely(mediaCodec, index)
                handleCodecFailure(mediaCodec, "读取编码数据失败", error)
            }
        }

        override fun onOutputFormatChanged(mediaCodec: MediaCodec, format: MediaFormat) {
            if (codec !== mediaCodec) return
            try {
                callback.onOutputFormatChanged(format)
            } catch (callbackError: Throwable) {
                Log.e(TAG, "输出格式回调执行失败", callbackError)
            }
        }

        override fun onError(mediaCodec: MediaCodec, error: MediaCodec.CodecException) {
            handleCodecFailure(mediaCodec, "$mimeType 编码器运行失败", error)
        }
    }

    private fun handleCodecFailure(mediaCodec: MediaCodec, message: String, error: Throwable) {
        if (codec !== mediaCodec || state == EncoderState.RELEASED || state == EncoderState.STOPPING) return
        stateReference.set(EncoderState.ERROR)
        notifyError(message, error)
        releaseCodecOnCodecThread()
    }

    private fun releaseCodecOnCodecThread() {
        val current = codec
        codec = null
        if (current != null) {
            if (codecStarted) {
                try {
                    current.stop()
                } catch (error: Throwable) {
                    Log.w(TAG, "停止 MediaCodec 失败", error)
                }
            }
        }
        try {
            onBeforeCodecReleased()
        } catch (error: Throwable) {
            Log.w(TAG, "清理编码器输入资源失败", error)
        }
        if (current != null) {
            try {
                current.release()
            } catch (error: Throwable) {
                Log.w(TAG, "释放 MediaCodec 失败", error)
            }
        }
        codecStarted = false
    }

    private fun releaseOutputBufferSafely(mediaCodec: MediaCodec, index: Int) {
        try {
            mediaCodec.releaseOutputBuffer(index, false)
        } catch (error: Throwable) {
            Log.w(TAG, "释放输出缓冲区失败，index=$index", error)
        }
    }

    private fun <T> runOnCodecThreadBlocking(block: () -> T): T {
        check(state != EncoderState.RELEASED || Looper.myLooper() == codecThread.looper) {
            "编码器已经释放"
        }
        if (Looper.myLooper() == codecThread.looper) return block()
        val task = FutureTask(block)
        check(codecHandler.post(task)) { "编码器线程已经停止" }
        return try {
            task.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private companion object {
        private const val TAG = "MediaCodecEncoder"
    }
}
