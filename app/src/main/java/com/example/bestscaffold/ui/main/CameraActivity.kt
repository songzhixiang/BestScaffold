package com.example.bestscaffold.ui.main

import android.os.Bundle
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.bestscaffold.App
import com.example.bestscaffold.recording.CameraRecordingController
import com.example.camera_core.view.CameraGLSurfaceView
import com.example.camera_core.view.CameraOesEngine
import com.example.codec_core.muxer.RecordedSegment
import java.io.File

/** 仅管理预览窗口；采集引擎由 Application 持有，不随页面销毁而释放。 */
class CameraActivity : AppCompatActivity() {

    private lateinit var surfaceView: CameraGLSurfaceView
    private lateinit var recordingStatusView: TextView
    private lateinit var loopRecordingSwitch: Switch
    private lateinit var recordButton: Button
    private lateinit var stopButton: Button
    private lateinit var pauseButton: Button
    private lateinit var resumeButton: Button
    private var engine: CameraOesEngine? = null
    private var recordingController: CameraRecordingController? = null
    private val errorListener = CameraOesEngine.ErrorListener { message, cause ->
        Log.e(TAG, message, cause)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    private val recordingListener = object : CameraRecordingController.Listener {
        override fun onStateChanged(state: CameraRecordingController.State) {
            updateRecordingControls(state)
        }

        override fun onSegmentStarted(index: Int, file: File) {
            Log.i(TAG, "开始录制第 ${index + 1} 段：${file.absolutePath}")
            recordingStatusView.text = "正在录制第 ${index + 1} 段"
        }

        override fun onSegmentCompleted(segment: RecordedSegment) {
            val seconds = segment.durationUs / 1_000_000f
            Log.i(
                TAG,
                "第 ${segment.index + 1} 段已完成：${segment.file.absolutePath}，" +
                    "时长=${seconds}秒，成功=${segment.successful}",
            )
            Toast.makeText(
                this@CameraActivity,
                "第 ${segment.index + 1} 段已保存",
                Toast.LENGTH_SHORT,
            ).show()
        }

        override fun onRecordingCompleted() {
            Toast.makeText(this@CameraActivity, "录制已停止，文件已完成封装", Toast.LENGTH_SHORT).show()
        }

        override fun onError(message: String, cause: Throwable?) {
            Log.e(TAG, message, cause)
            Toast.makeText(this@CameraActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = CameraGLSurfaceView(this).apply {
            setAspectRatio(PREVIEW_WIDTH, PREVIEW_HEIGHT)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val previewContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.YELLOW)
            addView(
                surfaceView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        root.addView(
            previewContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )

        val recordingOptions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recordingStatusView = TextView(this).apply {
            text = "录制状态：未录制"
            setPadding(16, 0, 16, 0)
        }
        loopRecordingSwitch = Switch(this).apply {
            text = "循环录制（30秒/段）"
            isChecked = true
        }
        recordingOptions.addView(
            recordingStatusView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        recordingOptions.addView(loopRecordingSwitch)
        root.addView(recordingOptions)

        val recordingControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recordButton = addButton(recordingControls, "录制") { startRecording() }
        stopButton = addButton(recordingControls, "停止") { recordingController?.stop() }
        pauseButton = addButton(recordingControls, "暂停") { recordingController?.pause() }
        resumeButton = addButton(recordingControls, "恢复") { recordingController?.resume() }
        root.addView(recordingControls)

        val cameraControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addButton(cameraControls, "暂停采集") { engine?.pauseCapture() }
        addButton(cameraControls, "恢复采集") { engine?.resumeCapture() }
        addButton(cameraControls, "释放摄像头") {
            (application as App).releaseCameraEngine()
            Toast.makeText(this, "摄像头正在释放", Toast.LENGTH_SHORT).show()
        }
        addButton(cameraControls, "重新打开") { openEngine() }
        root.addView(cameraControls)
        setContentView(root)
        updateRecordingControls(CameraRecordingController.State.IDLE)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                engine?.attachPreviewSurface(holder.surface)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int,
            ) {
                engine?.updateViewport(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                engine?.detachPreviewSurface(holder.surface)
            }
        })
        openEngine()
    }

    override fun onStart() {
        super.onStart()
        engine?.setErrorListener(errorListener)
        recordingController?.setListener(recordingListener)
        attachVisiblePreview()
    }

    override fun onStop() {
        engine?.detachPreviewSurface(surfaceView.holder.surface)
        engine?.clearErrorListener(errorListener)
        recordingController?.clearListener(recordingListener)
        super.onStop()
    }

    override fun onDestroy() {
        engine?.detachPreviewSurface(surfaceView.holder.surface)
        engine?.clearErrorListener(errorListener)
        engine = null
        recordingController = null
        super.onDestroy()
    }

    private fun openEngine() {
        try {
            val current = (application as App).getOrStartCameraEngine(CAMERA_ID)
            engine = current
            current.setErrorListener(errorListener)
            recordingController = (application as App)
                .getOrCreateRecordingController(CAMERA_ID)
                .also { it.setListener(recordingListener) }
            attachVisiblePreview()
            if (current.isCapturing()) {
                Toast.makeText(this, "摄像头已经在采集", Toast.LENGTH_SHORT).show()
            }
        } catch (error: IllegalStateException) {
            Toast.makeText(this, error.message ?: "摄像头暂时无法打开", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        val controller = recordingController
        if (controller == null) {
            Toast.makeText(this, "摄像头尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            controller.start(
                loopRecordingEnabled = loopRecordingSwitch.isChecked,
                segmentDurationMs = SEGMENT_DURATION_MS,
            )
        } catch (error: IllegalStateException) {
            Toast.makeText(this, error.message ?: "当前不能开始录制", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRecordingControls(state: CameraRecordingController.State) {
        recordingStatusView.text = when (state) {
            CameraRecordingController.State.IDLE -> "录制状态：未录制"
            CameraRecordingController.State.STARTING -> "录制状态：正在启动"
            CameraRecordingController.State.RECORDING -> "录制状态：录制中"
            CameraRecordingController.State.PAUSED -> "录制状态：已暂停"
            CameraRecordingController.State.STOPPING -> "录制状态：正在保存"
        }
        recordButton.isEnabled = state == CameraRecordingController.State.IDLE
        stopButton.isEnabled = state == CameraRecordingController.State.STARTING ||
            state == CameraRecordingController.State.RECORDING ||
            state == CameraRecordingController.State.PAUSED
        pauseButton.isEnabled = state == CameraRecordingController.State.RECORDING
        resumeButton.isEnabled = state == CameraRecordingController.State.PAUSED
        loopRecordingSwitch.isEnabled = state == CameraRecordingController.State.IDLE
    }

    private fun attachVisiblePreview() {
        val current = engine ?: return
        if (current.isReleased()) return
        val holder = surfaceView.holder
        if (!holder.surface.isValid) return
        current.attachPreviewSurface(holder.surface)
        current.updateViewport(surfaceView.width, surfaceView.height)
    }

    private fun addButton(row: LinearLayout, title: String, action: () -> Unit): Button {
        val button = Button(this).apply {
            text = title
            setOnClickListener { action() }
        }
        row.addView(
            button,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        return button
    }

    private companion object {
        private const val TAG = "CameraActivity"
        private const val CAMERA_ID = "103"
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
        private const val SEGMENT_DURATION_MS = 30_000L
    }
}
