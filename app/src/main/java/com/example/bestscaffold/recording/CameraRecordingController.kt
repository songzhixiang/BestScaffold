package com.example.bestscaffold.recording

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.camera_core.view.CameraOesEngine
import com.example.codec_core.config.VideoEncoderConfig
import com.example.codec_core.muxer.RecordedSegment
import com.example.codec_core.muxer.SegmentedMp4Recorder
import com.example.codec_core.muxer.SegmentedRecorderConfig
import com.example.codec_core.muxer.SegmentedRecorderListener
import com.example.codec_core.video.H264Encoder
import java.io.File

/** 将相机的 OES 输出、H.264 编码器和分段 MP4 封装器串联起来。 */
class CameraRecordingController(
    context: Context,
    private val cameraEngine: CameraOesEngine,
) {

    enum class State {
        IDLE,
        STARTING,
        RECORDING,
        PAUSED,
        STOPPING,
    }

    interface Listener {
        fun onStateChanged(state: State) = Unit
        fun onSegmentStarted(index: Int, file: File) = Unit
        fun onSegmentCompleted(segment: RecordedSegment) = Unit
        fun onRecordingCompleted() = Unit
        fun onError(message: String, cause: Throwable?) = Unit
    }

    private data class Session(
        val id: Long,
        val recorder: SegmentedMp4Recorder,
        val encoder: H264Encoder,
        val inputSurface: android.view.Surface,
    )

    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    private var listener: Listener? = null

    private var session: Session? = null
    private var nextSessionId = 0L

    fun setListener(listener: Listener?) {
        this.listener = listener
        listener?.onStateChanged(state)
    }

    fun clearListener(listener: Listener) {
        if (this.listener === listener) this.listener = null
    }

    fun start(
        loopRecordingEnabled: Boolean = true,
        segmentDurationMs: Long = DEFAULT_SEGMENT_DURATION_MS,
    ) {
        val sessionId: Long
        synchronized(lock) {
            check(state == State.IDLE) { "当前状态 $state 不能开始录制" }
            state = State.STARTING
            sessionId = ++nextSessionId
        }
        notifyStateChanged(State.STARTING)

        var recorder: SegmentedMp4Recorder? = null
        var encoder: H264Encoder? = null
        try {
            val outputDirectory = File(
                checkNotNull(applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)) {
                    "无法取得应用视频目录"
                },
                OUTPUT_DIRECTORY_NAME,
            )
            val createdRecorder = SegmentedMp4Recorder(
                config = SegmentedRecorderConfig(
                    outputDirectory = outputDirectory,
                    filePrefix = FILE_PREFIX,
                    loopRecordingEnabled = loopRecordingEnabled,
                    segmentDurationMs = segmentDurationMs,
                    audioEnabled = false,
                ),
                listener = createRecorderListener(sessionId),
            )
            recorder = createdRecorder
            val createdEncoder = H264Encoder(
                config = VideoEncoderConfig(
                    width = VIDEO_WIDTH,
                    height = VIDEO_HEIGHT,
                    bitRate = VIDEO_BIT_RATE,
                    frameRate = VIDEO_FRAME_RATE,
                    iFrameIntervalSeconds = I_FRAME_INTERVAL_SECONDS,
                ),
                callback = createdRecorder.videoCallback,
            )
            encoder = createdEncoder
            createdRecorder.setKeyFrameRequester(createdEncoder::requestKeyFrame)
            createdRecorder.start()
            createdEncoder.start()
            val inputSurface = checkNotNull(createdEncoder.inputSurface) {
                "H.264 编码器没有创建输入 Surface"
            }
            val createdSession = Session(sessionId, createdRecorder, createdEncoder, inputSurface)
            synchronized(lock) {
                check(state == State.STARTING && session == null) { "录制启动过程已被取消" }
                session = createdSession
            }
            cameraEngine.attachEncoderSurface(
                inputSurface,
                VIDEO_WIDTH,
                VIDEO_HEIGHT,
            ) { error ->
                if (error == null) {
                    completeStart(createdSession)
                } else {
                    failSession(createdSession, "编码画面输出启动失败", error)
                }
            }
        } catch (error: Throwable) {
            try {
                recorder?.stop()
            } catch (cleanupError: Throwable) {
                Log.w(TAG, "停止未完成的封装器失败", cleanupError)
            }
            try {
                encoder?.release()
            } catch (cleanupError: Throwable) {
                Log.w(TAG, "释放未完成的编码器失败", cleanupError)
            }
            synchronized(lock) {
                session = null
                state = State.IDLE
            }
            notifyError("开始录制失败", error)
            notifyStateChanged(State.IDLE)
        }
    }

    fun stop() {
        val current: Session
        synchronized(lock) {
            if (state == State.IDLE || state == State.STOPPING) return
            current = session ?: run {
                state = State.IDLE
                notifyStateChanged(State.IDLE)
                return
            }
            state = State.STOPPING
        }
        notifyStateChanged(State.STOPPING)
        cameraEngine.detachEncoderSurface(current.inputSurface) {
            if (!isCurrentSession(current)) return@detachEncoderSurface
            try {
                current.encoder.signalEndOfStream()
            } catch (error: Throwable) {
                notifyError("通知视频编码器结束失败", error)
                current.recorder.stop()
            }
            scheduleStopTimeout(current)
        }
    }

    fun pause() {
        val current: Session
        synchronized(lock) {
            if (state != State.RECORDING) return
            current = session ?: return
            state = State.PAUSED
        }
        cameraEngine.pauseEncoderOutput(current.inputSurface)
        notifyStateChanged(State.PAUSED)
    }

    fun resume() {
        val current: Session
        synchronized(lock) {
            if (state != State.PAUSED) return
            current = session ?: return
            state = State.RECORDING
        }
        cameraEngine.resumeEncoderOutput(current.inputSurface)
        current.encoder.requestKeyFrame()
        notifyStateChanged(State.RECORDING)
    }

    /** Application 释放摄像头前调用；录制文件仍会异步完成收尾。 */
    fun release() {
        stop()
        listener = null
    }

    private fun completeStart(createdSession: Session) {
        synchronized(lock) {
            if (session !== createdSession || state != State.STARTING) return
            state = State.RECORDING
        }
        notifyStateChanged(State.RECORDING)
    }

    private fun failSession(current: Session, message: String, cause: Throwable?) {
        if (!isCurrentSession(current)) return
        notifyError(message, cause)
        synchronized(lock) {
            if (session === current) state = State.STOPPING
        }
        notifyStateChanged(State.STOPPING)
        cameraEngine.detachEncoderSurface(current.inputSurface) {
            try {
                current.encoder.stop()
            } catch (error: Throwable) {
                Log.w(TAG, "停止异常编码器失败", error)
            }
            current.recorder.stop()
        }
    }

    private fun completeSession(sessionId: Long) {
        val current = synchronized(lock) {
            session?.takeIf { it.id == sessionId }
        } ?: return
        cameraEngine.detachEncoderSurface(current.inputSurface) {
            try {
                current.encoder.release()
            } catch (error: Throwable) {
                Log.w(TAG, "释放视频编码器失败", error)
            }
            synchronized(lock) {
                if (session === current) {
                    session = null
                    state = State.IDLE
                } else {
                    return@detachEncoderSurface
                }
            }
            notifyStateChanged(State.IDLE)
            dispatch { it.onRecordingCompleted() }
        }
    }

    private fun scheduleStopTimeout(current: Session) {
        mainHandler.postDelayed({
            if (!isCurrentSession(current) || state != State.STOPPING) return@postDelayed
            Log.w(TAG, "等待编码器 EOS 超时，强制结束当前录制")
            try {
                current.encoder.stop()
            } catch (error: Throwable) {
                Log.w(TAG, "强制停止视频编码器失败", error)
            }
            current.recorder.stop()
        }, STOP_TIMEOUT_MS)
    }

    private fun createRecorderListener(sessionId: Long): SegmentedRecorderListener =
        object : SegmentedRecorderListener {
            override fun onSegmentStarted(index: Int, file: File) {
                dispatch { it.onSegmentStarted(index, file) }
            }

            override fun onSegmentCompleted(segment: RecordedSegment) {
                dispatch { it.onSegmentCompleted(segment) }
            }

            override fun onRecordingCompleted() {
                completeSession(sessionId)
            }

            override fun onError(message: String, cause: Throwable?) {
                notifyError(message, cause)
            }
        }

    private fun isCurrentSession(current: Session): Boolean =
        synchronized(lock) { session === current }

    private fun notifyStateChanged(newState: State) {
        dispatch { it.onStateChanged(newState) }
    }

    private fun notifyError(message: String, cause: Throwable?) {
        Log.e(TAG, message, cause)
        dispatch { it.onError(message, cause) }
    }

    private fun dispatch(block: (Listener) -> Unit) {
        val callback = listener ?: return
        mainHandler.post {
            if (listener === callback) block(callback)
        }
    }

    private companion object {
        private const val TAG = "CameraRecording"
        private const val OUTPUT_DIRECTORY_NAME = "recordings"
        private const val FILE_PREFIX = "dvr"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_BIT_RATE = 4_000_000
        private const val VIDEO_FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SECONDS = 2
        private const val DEFAULT_SEGMENT_DURATION_MS = 30_000L
        private const val STOP_TIMEOUT_MS = 8_000L
    }
}
