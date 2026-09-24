package com.example.codec_core.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.codec_core.api.EncodedFrame
import com.example.codec_core.api.EncoderCallback
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 将持续编码的音视频流切成多个可独立播放的 MP4 文件。
 *
 * MediaCodec 在整个录制期间保持运行；每个分段使用新的 MediaMuxer。切片瞬间，新 Muxer
 * 接管后续样本，旧 Muxer 在独立线程执行 stop/release，避免阻塞编码器回调。
 */
class SegmentedMp4Recorder(
    private val config: SegmentedRecorderConfig,
    private val listener: SegmentedRecorderListener = object : SegmentedRecorderListener {},
) : Closeable {

    enum class State {
        CREATED,
        RUNNING,
        FINALIZING,
        STOPPED,
        ERROR,
    }

    private enum class TrackType { VIDEO, AUDIO }

    private data class PendingSample(
        val trackType: TrackType,
        val frame: EncodedFrame,
    )

    private class Segment(
        val index: Int,
        val file: File,
        val muxer: MediaMuxer,
        val videoTrackIndex: Int,
        val audioTrackIndex: Int?,
        val basePresentationTimeUs: Long,
    ) {
        var lastVideoTimeUs = -1L
        var lastAudioTimeUs = -1L
        var lastSourceTimeUs = basePresentationTimeUs
    }

    private data class RetiringSegment(
        val segment: Segment,
        val boundaryTimeUs: Long,
    )

    private val workerThread = HandlerThread("Segmented-Muxer-Thread").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    private val finalizer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Muxer-Finalizer-Thread")
    }
    private val stopRequested = AtomicBoolean(false)
    private val queuedSampleTasks = AtomicInteger(0)
    private val completionLatch = CountDownLatch(1)
    private val waitingSamples = ArrayDeque<PendingSample>()
    private val waitingForFirstKeyAudio = ArrayDeque<EncodedFrame>()
    private val pendingSplitAudio = ArrayDeque<EncodedFrame>()
    private val sessionId = System.currentTimeMillis()

    @Volatile
    var state: State = State.CREATED
        private set

    @Volatile
    private var keyFrameRequester: (() -> Unit)? = null

    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var activeSegment: Segment? = null
    private var retiringSegment: RetiringSegment? = null
    private var segmentIndex = 0
    private var splitPending = false
    private var firstKeyFrameRequested = false
    private var videoEndOfStream = false
    private var audioEndOfStream = !config.audioEnabled

    val videoCallback: EncoderCallback = object : EncoderCallback {
        override fun onOutputFormatChanged(format: MediaFormat) {
            post { handleFormat(TrackType.VIDEO, format) }
        }

        override fun onEncodedFrame(frame: EncodedFrame) {
            postEncodedSample(TrackType.VIDEO, frame)
        }

        override fun onError(message: String, cause: Throwable?) {
            post { fail("视频编码器错误：$message", cause) }
        }
    }

    val audioCallback: EncoderCallback = object : EncoderCallback {
        override fun onOutputFormatChanged(format: MediaFormat) {
            post { handleFormat(TrackType.AUDIO, format) }
        }

        override fun onEncodedFrame(frame: EncodedFrame) {
            postEncodedSample(TrackType.AUDIO, frame)
        }

        override fun onError(message: String, cause: Throwable?) {
            post { fail("音频编码器错误：$message", cause) }
        }
    }

    /** 应设置为 VideoEncoder.requestKeyFrame，用来在达到分段时长时主动请求关键帧。 */
    fun setKeyFrameRequester(requester: (() -> Unit)?) {
        keyFrameRequester = requester
    }

    fun start() {
        check(state == State.CREATED) { "当前状态 $state 不能开始分段录制" }
        check(config.outputDirectory.exists() || config.outputDirectory.mkdirs()) {
            "无法创建输出目录：${config.outputDirectory.absolutePath}"
        }
        check(config.outputDirectory.isDirectory) {
            "输出路径不是目录：${config.outputDirectory.absolutePath}"
        }
        state = State.RUNNING
    }

    /**
     * 请求结束封装。正常情况下应先让音视频编码器输出 EOS，再调用此方法。
     * 此方法不等待 MP4 收尾完成，可通过 awaitFinalized() 等待或监听 onRecordingCompleted()。
     */
    fun stop() {
        if (state == State.STOPPED || state == State.FINALIZING) return
        if (stopRequested.compareAndSet(false, true)) {
            post(allowWhenStopping = true) { finishOnWorkerThread() }
        }
    }

    fun awaitFinalized(timeout: Long, unit: TimeUnit = TimeUnit.MILLISECONDS): Boolean =
        completionLatch.await(timeout, unit)

    override fun close() {
        stop()
    }

    private fun handleFormat(trackType: TrackType, format: MediaFormat) {
        if (state != State.RUNNING) return
        when (trackType) {
            TrackType.VIDEO -> {
                if (videoFormat != null) {
                    fail("录制过程中视频输出格式发生了第二次变化")
                    return
                }
                videoFormat = format
            }

            TrackType.AUDIO -> {
                if (!config.audioEnabled) return
                if (audioFormat != null) {
                    fail("录制过程中音频输出格式发生了第二次变化")
                    return
                }
                audioFormat = format
            }
        }
        drainWaitingSamplesIfReady()
    }

    private fun handleSample(trackType: TrackType, frame: EncodedFrame) {
        if (state != State.RUNNING) return
        if (!formatsReady()) {
            if (waitingSamples.size >= config.maxBufferedSamples) {
                fail("等待音视频格式时缓存已满")
                return
            }
            waitingSamples.addLast(PendingSample(trackType, frame))
            return
        }
        processSample(trackType, frame)
    }

    private fun drainWaitingSamplesIfReady() {
        if (!formatsReady()) return
        while (waitingSamples.isNotEmpty() && state == State.RUNNING) {
            val sample = waitingSamples.removeFirst()
            processSample(sample.trackType, sample.frame)
        }
    }

    private fun processSample(trackType: TrackType, frame: EncodedFrame) {
        if (!frame.isCodecConfig && frame.data.isNotEmpty()) {
            when (trackType) {
                TrackType.VIDEO -> handleVideoFrame(frame)
                TrackType.AUDIO -> if (config.audioEnabled) handleAudioFrame(frame)
            }
        }

        if (frame.isEndOfStream) {
            when (trackType) {
                TrackType.VIDEO -> videoEndOfStream = true
                TrackType.AUDIO -> audioEndOfStream = true
            }
            if (videoEndOfStream && audioEndOfStream && stopRequested.compareAndSet(false, true)) {
                finishOnWorkerThread()
            }
        }
    }

    private fun handleVideoFrame(frame: EncodedFrame) {
        var current = activeSegment
        if (current == null) {
            if (!frame.isKeyFrame) {
                if (!firstKeyFrameRequested) {
                    firstKeyFrameRequested = true
                    requestKeyFrame()
                }
                return
            }
            current = createSegment(frame.presentationTimeUs) ?: return
            activeSegment = current
            flushFirstSegmentAudio(current)
        }

        if (config.loopRecordingEnabled) {
            val targetTimeUs = current.basePresentationTimeUs + config.segmentDurationMs * 1_000L
            if (frame.presentationTimeUs >= targetTimeUs && !splitPending) beginSplit()

            if (splitPending && frame.isKeyFrame && frame.presentationTimeUs >= targetTimeUs) {
                current = rotateSegment(frame.presentationTimeUs) ?: return
            }
        }

        writeSample(current, TrackType.VIDEO, frame)
        val retiring = retiringSegment
        if (retiring != null &&
            frame.presentationTimeUs - retiring.boundaryTimeUs >= config.audioDrainTimeoutMs * 1_000L
        ) {
            finalizeRetiringSegment()
        }
    }

    private fun handleAudioFrame(frame: EncodedFrame) {
        val current = activeSegment
        if (current == null) {
            addBounded(waitingForFirstKeyAudio, frame, "等待首个视频关键帧时音频缓存已满")
            return
        }

        if (config.loopRecordingEnabled) {
            val targetTimeUs = current.basePresentationTimeUs + config.segmentDurationMs * 1_000L
            if (frame.presentationTimeUs >= targetTimeUs && !splitPending) beginSplit()
            if (splitPending) {
                addBounded(pendingSplitAudio, frame, "等待分段关键帧时音频缓存已满")
                return
            }
        }

        val retiring = retiringSegment
        if (retiring != null && frame.presentationTimeUs < retiring.boundaryTimeUs) {
            writeSample(retiring.segment, TrackType.AUDIO, frame)
        } else {
            if (frame.presentationTimeUs < current.basePresentationTimeUs) {
                Log.w(
                    TAG,
                    "丢弃晚到的音频帧：pts=${frame.presentationTimeUs}, " +
                        "当前分段起点=${current.basePresentationTimeUs}",
                )
                return
            }
            writeSample(current, TrackType.AUDIO, frame)
            if (retiring != null) finalizeRetiringSegment()
        }
    }

    private fun beginSplit() {
        splitPending = true
        requestKeyFrame()
    }

    private fun rotateSegment(boundaryTimeUs: Long): Segment? {
        val old = activeSegment ?: return null
        retiringSegment?.let {
            reportError("上一个分段的音频未及时排空，强制结束旧分段")
            submitFinalization(it.segment)
        }
        val next = createSegment(boundaryTimeUs) ?: return null
        activeSegment = next
        retiringSegment = RetiringSegment(old, boundaryTimeUs)
        splitPending = false

        var crossedBoundary = false
        while (pendingSplitAudio.isNotEmpty()) {
            val audioFrame = pendingSplitAudio.removeFirst()
            if (audioFrame.presentationTimeUs < boundaryTimeUs) {
                writeSample(old, TrackType.AUDIO, audioFrame)
            } else {
                writeSample(next, TrackType.AUDIO, audioFrame)
                crossedBoundary = true
            }
        }
        if (!config.audioEnabled || crossedBoundary) finalizeRetiringSegment()
        return next
    }

    private fun flushFirstSegmentAudio(segment: Segment) {
        while (waitingForFirstKeyAudio.isNotEmpty()) {
            val frame = waitingForFirstKeyAudio.removeFirst()
            if (frame.presentationTimeUs >= segment.basePresentationTimeUs) {
                writeSample(segment, TrackType.AUDIO, frame)
            }
        }
    }

    private fun createSegment(baseTimeUs: Long): Segment? {
        return try {
            val index = segmentIndex++
            val fileName = String.format(
                Locale.US,
                "%s_%d_%05d.mp4",
                config.filePrefix,
                sessionId,
                index,
            )
            val file = File(config.outputDirectory, fileName)
            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (config.orientationHint != 0) muxer.setOrientationHint(config.orientationHint)
            val videoTrack = muxer.addTrack(checkNotNull(videoFormat))
            val audioTrack = if (config.audioEnabled) {
                muxer.addTrack(checkNotNull(audioFormat))
            } else {
                null
            }
            muxer.start()
            Segment(index, file, muxer, videoTrack, audioTrack, baseTimeUs).also {
                notifySegmentStarted(it)
            }
        } catch (error: Throwable) {
            fail("创建 MP4 分段失败", error)
            null
        }
    }

    private fun writeSample(segment: Segment, trackType: TrackType, frame: EncodedFrame) {
        if (frame.data.isEmpty() || frame.isCodecConfig) return
        try {
            val relativeTimeUs = (frame.presentationTimeUs - segment.basePresentationTimeUs)
                .coerceAtLeast(0L)
            val trackIndex: Int
            val adjustedTimeUs: Long
            when (trackType) {
                TrackType.VIDEO -> {
                    trackIndex = segment.videoTrackIndex
                    adjustedTimeUs = relativeTimeUs.coerceAtLeast(segment.lastVideoTimeUs + 1L)
                    segment.lastVideoTimeUs = adjustedTimeUs
                }

                TrackType.AUDIO -> {
                    trackIndex = segment.audioTrackIndex ?: return
                    adjustedTimeUs = relativeTimeUs.coerceAtLeast(segment.lastAudioTimeUs + 1L)
                    segment.lastAudioTimeUs = adjustedTimeUs
                }
            }
            val flags = frame.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG.inv() and
                MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
            val info = MediaCodec.BufferInfo().apply {
                set(0, frame.data.size, adjustedTimeUs, flags)
            }
            segment.muxer.writeSampleData(trackIndex, ByteBuffer.wrap(frame.data), info)
            segment.lastSourceTimeUs = maxOf(segment.lastSourceTimeUs, frame.presentationTimeUs)
        } catch (error: Throwable) {
            fail("写入第 ${segment.index} 个 MP4 分段失败", error)
        }
    }

    private fun finalizeRetiringSegment() {
        val retiring = retiringSegment ?: return
        retiringSegment = null
        submitFinalization(retiring.segment)
    }

    private fun finishOnWorkerThread() {
        if (state == State.FINALIZING || state == State.STOPPED) return
        state = State.FINALIZING

        // 最终结束时不再等待下一个关键帧，缓存音频全部写入当前分段。
        val current = activeSegment
        if (current != null) {
            while (pendingSplitAudio.isNotEmpty()) {
                writeSample(current, TrackType.AUDIO, pendingSplitAudio.removeFirst())
            }
        }
        waitingSamples.clear()
        waitingForFirstKeyAudio.clear()
        finalizeRetiringSegment()
        activeSegment?.let(::submitFinalization)
        activeSegment = null

        finalizer.execute {
            state = State.STOPPED
            try {
                listener.onRecordingCompleted()
            } catch (error: Throwable) {
                Log.e(TAG, "录制完成回调执行失败", error)
            } finally {
                completionLatch.countDown()
            }
        }
        finalizer.shutdown()
        workerThread.quitSafely()
    }

    private fun submitFinalization(segment: Segment) {
        finalizer.execute {
            var successful = true
            try {
                segment.muxer.stop()
            } catch (error: Throwable) {
                successful = false
                reportError("结束第 ${segment.index} 个 MP4 分段失败", error)
            } finally {
                try {
                    segment.muxer.release()
                } catch (error: Throwable) {
                    successful = false
                    reportError("释放第 ${segment.index} 个 MediaMuxer 失败", error)
                }
            }
            val durationUs = (segment.lastSourceTimeUs - segment.basePresentationTimeUs)
                .coerceAtLeast(0L)
            try {
                listener.onSegmentCompleted(
                    RecordedSegment(segment.index, segment.file, durationUs, successful),
                )
            } catch (error: Throwable) {
                Log.e(TAG, "分段完成回调执行失败", error)
            }
        }
    }

    private fun formatsReady(): Boolean =
        videoFormat != null && (!config.audioEnabled || audioFormat != null)

    private fun requestKeyFrame() {
        try {
            keyFrameRequester?.invoke()
        } catch (error: Throwable) {
            reportError("请求视频关键帧失败", error)
        }
    }

    private fun addBounded(queue: ArrayDeque<EncodedFrame>, frame: EncodedFrame, message: String) {
        if (queue.size >= config.maxBufferedSamples) {
            fail(message)
        } else {
            queue.addLast(frame)
        }
    }

    private fun fail(message: String, cause: Throwable? = null) {
        if (state == State.STOPPED || state == State.FINALIZING) return
        state = State.ERROR
        reportError(message, cause)
        if (stopRequested.compareAndSet(false, true)) finishOnWorkerThread()
    }

    private fun reportError(message: String, cause: Throwable? = null) {
        Log.e(TAG, message, cause)
        try {
            listener.onError(message, cause)
        } catch (callbackError: Throwable) {
            Log.e(TAG, "分段录制错误回调执行失败", callbackError)
        }
    }

    private fun notifySegmentStarted(segment: Segment) {
        try {
            listener.onSegmentStarted(segment.index, segment.file)
        } catch (error: Throwable) {
            Log.e(TAG, "分段开始回调执行失败", error)
        }
    }

    private fun post(allowWhenStopping: Boolean = false, block: () -> Unit) {
        if ((!allowWhenStopping && stopRequested.get()) || state == State.STOPPED) return
        if (!workerHandler.post(block)) {
            reportError("分段封装线程已经停止")
        }
    }

    private fun postEncodedSample(trackType: TrackType, frame: EncodedFrame) {
        if (stopRequested.get() || state != State.RUNNING) return
        val queued = queuedSampleTasks.incrementAndGet()
        if (queued > config.maxBufferedSamples) {
            queuedSampleTasks.decrementAndGet()
            if (stopRequested.compareAndSet(false, true)) {
                workerHandler.post {
                    state = State.ERROR
                    reportError("编码数据等待写盘的队列已满，停止录制以避免内存持续增长")
                    finishOnWorkerThread()
                }
            }
            return
        }
        if (!workerHandler.post {
                queuedSampleTasks.decrementAndGet()
                handleSample(trackType, frame)
            }
        ) {
            queuedSampleTasks.decrementAndGet()
            reportError("分段封装线程已经停止")
        }
    }

    private companion object {
        private const val TAG = "SegmentedMp4Recorder"
    }
}
