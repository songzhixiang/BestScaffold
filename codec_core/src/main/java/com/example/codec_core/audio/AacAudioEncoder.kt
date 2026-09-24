package com.example.codec_core.audio

import android.media.MediaCodec
import android.media.MediaFormat
import com.example.codec_core.api.AudioEncoder
import com.example.codec_core.api.EncoderCallback
import com.example.codec_core.api.EncoderState
import com.example.codec_core.config.AudioEncoderConfig
import com.example.codec_core.internal.MediaCodecEncoder
import java.util.ArrayDeque

/**
 * AAC-LC 编码器，输入格式固定为交错排列的 PCM 16-bit little-endian。
 * 每个实例拥有独立 MediaCodec 线程，不会占用录音线程。
 */
class AacAudioEncoder(
    private val config: AudioEncoderConfig = AudioEncoderConfig(),
    callback: EncoderCallback,
) : MediaCodecEncoder(
    mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
    threadName = "AAC-Encoder-Thread",
    callback = callback,
), AudioEncoder {

    private data class PcmChunk(
        val data: ByteArray,
        val presentationTimeUs: Long,
        var position: Int = 0,
    )

    private val queueLock = Any()
    private val pcmChunks = ArrayDeque<PcmChunk>()
    private val availableInputIndexes = ArrayDeque<Int>()
    private val bytesPerFrame = config.channelCount * PCM_BYTES_PER_SAMPLE

    /** 受 queueLock 保护，可同时被录音线程和编码线程访问。 */
    private var pendingPcmBytes = 0
    private var eosRequested = false
    private var eosQueued = false
    private var eosPresentationTimeUs = 0L

    override fun createMediaFormat(): MediaFormat = config.createMediaFormat()

    override fun queuePcm(
        data: ByteArray,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
    ): Boolean {
        require(offset >= 0 && size >= 0 && offset + size <= data.size) { "PCM 数据范围无效" }
        require(size % bytesPerFrame == 0) { "PCM 数据必须包含完整的音频采样帧" }
        require(presentationTimeUs >= 0) { "PCM 时间戳不能小于 0" }
        if (size == 0) return state == EncoderState.RUNNING

        val copy: ByteArray
        synchronized(queueLock) {
            if (state != EncoderState.RUNNING || eosRequested) return false
            if (pendingPcmBytes + size > config.maxPendingPcmBytes) return false
            pendingPcmBytes += size
            val frameCount = size / bytesPerFrame
            eosPresentationTimeUs = maxOf(
                eosPresentationTimeUs,
                presentationTimeUs + frameCount * MICROS_PER_SECOND / config.sampleRate,
            )
            copy = data.copyOfRange(offset, offset + size)
        }

        if (!codecHandler.post {
                if (state != EncoderState.RUNNING || eosQueued) {
                    releaseReservedBytes(size)
                    return@post
                }
                pcmChunks.addLast(PcmChunk(copy, presentationTimeUs))
                drainInputBuffers()
            }
        ) {
            releaseReservedBytes(size)
            return false
        }
        return true
    }

    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        availableInputIndexes.addLast(index)
        drainInputBuffers()
    }

    override fun onSignalEndOfStream(codec: MediaCodec) {
        synchronized(queueLock) {
            if (eosRequested) return
            eosRequested = true
        }
        drainInputBuffers()
    }

    override fun onBeforeCodecReleased() {
        pcmChunks.clear()
        availableInputIndexes.clear()
        synchronized(queueLock) {
            pendingPcmBytes = 0
            eosRequested = false
            eosQueued = false
            eosPresentationTimeUs = 0L
        }
    }

    private fun drainInputBuffers() {
        val current = codec ?: return
        while (availableInputIndexes.isNotEmpty()) {
            val chunk = pcmChunks.peekFirst()
            if (chunk == null) {
                val shouldQueueEos = synchronized(queueLock) { eosRequested && !eosQueued }
                if (!shouldQueueEos) return
                val inputIndex = availableInputIndexes.removeFirst()
                current.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    synchronized(queueLock) { eosPresentationTimeUs },
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                synchronized(queueLock) { eosQueued = true }
                return
            }

            val inputIndex = availableInputIndexes.removeFirst()
            val inputBuffer = checkNotNull(current.getInputBuffer(inputIndex)) {
                "无法取得 AAC 输入缓冲区，index=$inputIndex"
            }
            inputBuffer.clear()
            val remaining = chunk.data.size - chunk.position
            var writeSize = minOf(inputBuffer.remaining(), remaining)
            writeSize -= writeSize % bytesPerFrame
            check(writeSize > 0) { "AAC 输入缓冲区小于一个 PCM 采样帧" }

            val consumedFrames = chunk.position / bytesPerFrame
            val presentationTimeUs = chunk.presentationTimeUs +
                consumedFrames * MICROS_PER_SECOND / config.sampleRate
            inputBuffer.put(chunk.data, chunk.position, writeSize)
            current.queueInputBuffer(inputIndex, 0, writeSize, presentationTimeUs, 0)

            chunk.position += writeSize
            releaseReservedBytes(writeSize)
            if (chunk.position == chunk.data.size) pcmChunks.removeFirst()
        }
    }

    private fun releaseReservedBytes(size: Int) {
        synchronized(queueLock) {
            pendingPcmBytes = (pendingPcmBytes - size).coerceAtLeast(0)
        }
    }

    private companion object {
        private const val PCM_BYTES_PER_SAMPLE = 2
        private const val MICROS_PER_SECOND = 1_000_000L
    }
}
