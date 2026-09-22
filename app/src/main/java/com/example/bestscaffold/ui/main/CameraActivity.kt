package com.example.bestscaffold.ui.main

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.bestscaffold.App
import com.example.camera_core.view.CameraOesEngine

/** 仅管理预览窗口；采集引擎由 Application 持有，不随页面销毁而释放。 */
class CameraActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private var engine: CameraOesEngine? = null
    private val errorListener = CameraOesEngine.ErrorListener { message, cause ->
        Log.e(TAG, message, cause)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            surfaceView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addButton(controls, "暂停采集") { engine?.pauseCapture() }
        addButton(controls, "恢复采集") { engine?.resumeCapture() }
        addButton(controls, "释放") {
            (application as App).releaseCameraEngine()
            Toast.makeText(this, "摄像头正在释放", Toast.LENGTH_SHORT).show()
        }
        addButton(controls, "重新打开") { openEngine() }
        root.addView(controls)
        setContentView(root)

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
        attachVisiblePreview()
    }

    override fun onStop() {
        engine?.detachPreviewSurface(surfaceView.holder.surface)
        engine?.clearErrorListener(errorListener)
        super.onStop()
    }

    override fun onDestroy() {
        engine?.detachPreviewSurface(surfaceView.holder.surface)
        engine?.clearErrorListener(errorListener)
        engine = null
        super.onDestroy()
    }

    private fun openEngine() {
        try {
            val current = (application as App).getOrStartCameraEngine(CAMERA_ID)
            engine = current
            current.setErrorListener(errorListener)
            attachVisiblePreview()
            if (current.isCapturing()) {
                Toast.makeText(this, "摄像头已经在采集", Toast.LENGTH_SHORT).show()
            }
        } catch (error: IllegalStateException) {
            Toast.makeText(this, error.message ?: "摄像头暂时无法打开", Toast.LENGTH_SHORT).show()
        }
    }

    private fun attachVisiblePreview() {
        val current = engine ?: return
        if (current.isReleased()) return
        val holder = surfaceView.holder
        if (!holder.surface.isValid) return
        current.attachPreviewSurface(holder.surface)
        current.updateViewport(surfaceView.width, surfaceView.height)
    }

    private fun addButton(row: LinearLayout, title: String, action: () -> Unit) {
        row.addView(
            Button(this).apply {
                text = title
                setOnClickListener { action() }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private companion object {
        private const val TAG = "CameraActivity"
        private const val CAMERA_ID = "103"
    }
}
