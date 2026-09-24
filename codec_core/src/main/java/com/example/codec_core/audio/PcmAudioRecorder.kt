package com.example.codec_core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

data class PcmAudioRecorderConfig(
    val sampleRate: Int = 48_000,
    val channelCount: Int = 1,
    val audioSource: Int = MediaRecorder.AudioSource.MIC,
    val readBufferSize: Int = 4 * 1024,
) {
    init {
        require(sampleRate > 0) { "录音采样率必须大于 0" }
        require(channelCount in 1..2) { "录音只支持单声道或双声道" }
        require(readBufferSize > 0) { "录音读取缓冲区必须大于 0" }
    }
}

/**
 * 从 AudioRecord 持续读取 PCM 16-bit little-endian 数据。
 *
 * 时间戳由已经提交的采样帧数量生成，因此暂停期间丢弃的麦克风数据不会在成片中形成空白间隔。
 */
class PcmAudioRecorder(
    private val config: PcmAudioRecorderConfig = PcmAudioRecorderConfig(),
) : Closeable {

    interface Listener {
        fun onPcmData(data: ByteArray, size: Int, presentationTimeUs: Long)
        fun onError(message: String, cause: Throwable?) = Unit
    }

    enum class State {
        IDLE,
        RUNNING,
        PAUSED,
        STOPPED,
        RELEASED,
        ERROR,
    }

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val resourceLock = Any()

    @Volatile
    var state: State = State.IDLE
        private set

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var submittedSampleFrames = 0L

    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        synchronized(resourceLock) {
            check(state == State.IDLE || state == State.STOPPED) {
                "当前录音状态 $state 不能开始"
            }
            val channelMask = if (config.channelCount == 1) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }
            val bytesPerFrame = config.channelCount * PCM_BYTES_PER_SAMPLE
            val minimumSize = AudioRecord.getMinBufferSize(
                config.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimumSize > 0) { "设备不支持当前 PCM 录音参数，错误码=$minimumSize" }
            val readSize = config.readBufferSize.alignDown(bytesPerFrame)
            check(readSize > 0) { "录音读取缓冲区小于一个采样帧" }
            val recordBufferSize = maxOf(minimumSize * 2, readSize).alignUp(bytesPerFrame)
            val created = AudioRecord.Builder()
                .setAudioSource(config.audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(recordBufferSize)
                .build()
            check(created.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }

            audioRecord = created
            submittedSampleFrames = 0L
            paused.set(false)
            running.set(true)
            try {
                created.startRecording()
                check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "AudioRecord 未进入录音状态"
                }
                state = State.RUNNING
                captureThread = Thread(
                    { captureLoop(created, listener, readSize, bytesPerFrame) },
                    THREAD_NAME,
                ).apply { start() }
            } catch (error: Throwable) {
                running.set(false)
                audioRecord = null
                created.release()
                state = State.ERROR
                throw error
            }
        }
    }

    fun pause() {
        if (state != State.RUNNING) return
        paused.set(true)
        state = State.PAUSED
    }

    fun resume() {
        if (state != State.PAUSED) return
        paused.set(false)
        state = State.RUNNING
    }

    /** 停止读取并等待阻塞中的 AudioRecord.read() 返回。 */
    fun stop() {
        if (!running.getAndSet(false)) {
            if (state != State.RELEASED && state != State.ERROR) state = State.STOPPED
            return
        }
        val currentRecord = synchronized(resourceLock) { audioRecord }
        try {
            currentRecord?.stop()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "停止 AudioRecord 失败", error)
        }
        val thread = synchronized(resourceLock) { captureThread }
        if (thread !== Thread.currentThread()) {
            try {
                thread?.join(STOP_JOIN_TIMEOUT_MS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "等待录音线程退出时被中断", error)
            }
        }
        if (thread?.isAlive == true) {
            Log.w(TAG, "录音线程未在超时时间内退出")
        }
        if (state != State.RELEASED && state != State.ERROR) state = State.STOPPED
    }

    override fun close() = release()

    fun release() {
        if (state == State.RELEASED) return
        stop()
        state = State.RELEASED
    }

    private fun captureLoop(
        record: AudioRecord,
        listener: Listener,
        readSize: Int,
        bytesPerFrame: Int,
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ByteArray(readSize)
        try {
            while (running.get()) {
                val size = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (!running.get()) break
                if (size < 0) {
                    error("读取 PCM 数据失败，错误码=$size")
                }
                val alignedSize = size.alignDown(bytesPerFrame)
                if (alignedSize == 0 || paused.get()) continue

                val presentationTimeUs =
                    submittedSampleFrames * MICROS_PER_SECOND / config.sampleRate
                listener.onPcmData(buffer, alignedSize, presentationTimeUs)
                submittedSampleFrames += alignedSize / bytesPerFrame
            }
        } catch (error: Throwable) {
            if (running.getAndSet(false)) {
                state = State.ERROR
                Log.e(TAG, "PCM 录音线程运行失败", error)
                listener.onError("PCM 录音失败", error)
            }
        } finally {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (error: IllegalStateException) {
                Log.w(TAG, "录音线程结束时停止 AudioRecord 失败", error)
            }
            record.release()
            synchronized(resourceLock) {
                if (audioRecord === record) audioRecord = null
                if (captureThread === Thread.currentThread()) captureThread = null
            }
        }
    }

    private fun Int.alignDown(alignment: Int): Int = this - this % alignment

    private fun Int.alignUp(alignment: Int): Int =
        if (this % alignment == 0) this else this + alignment - this % alignment

    private companion object {
        private const val TAG = "PcmAudioRecorder"
        private const val THREAD_NAME = "PCM-AudioRecord-Thread"
        private const val PCM_BYTES_PER_SAMPLE = 2
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}
