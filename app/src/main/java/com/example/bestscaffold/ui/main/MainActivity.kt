package com.example.bestscaffold.ui.main

import android.media.Image
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.Toast
import com.example.bestscaffold.App
import com.example.bestscaffold.R
import com.example.bestscaffold.databinding.ActivityMainBinding
import com.example.bestscaffold.ui.base.BaseActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.camera_core.api.Camera2API
import com.example.camera_core.api.CameraInventory
import com.example.camera_core.api.ICameraOpenCallback
import com.example.camera_core.api.ICameraPreviewDataCallback
import com.example.camera_core.view.CameraOesEngine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicLong

class MainActivity : BaseActivity<ActivityMainBinding>() {

    private val cameraId = "103" //103 倒车，101 DMS
    private var cameraOesEngine: CameraOesEngine? = null
    private val oesErrorListener = CameraOesEngine.ErrorListener { message, cause ->
        Log.e(TAG, message, cause)
    }

    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    override fun layoutRes(): Int = R.layout.activity_main

    override fun inflateBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    private val frameCount = AtomicLong(0)
    private val validFrameCount = AtomicLong(0)
    private val lastFrameErrorLogTime = AtomicLong(0)

    private val callback = object : ICameraOpenCallback {

        override fun onCameraInventory(inventory: CameraInventory) {
            Log.i(TAG, "Camera2 可见摄像头数量=${inventory.count}，ID=${inventory.cameraIds}")
            inventory.cameras.forEach { camera ->
                Log.i(
                    TAG,
                    "摄像头 ID=${camera.cameraId}，朝向=${lensFacingName(camera.lensFacing)}，" +
                        "SurfaceView=${camera.surfaceViewSizes.toSizeText()}，" +
                        "TextureView=${camera.textureViewSizes.toSizeText()}，" +
                        "YUV_420_888=${camera.yuv420Sizes.toSizeText()}",
                )
                camera.inspectionError?.let { error ->
                    Log.w(TAG, "摄像头 ${camera.cameraId} 信息查询失败：$error")
                }
            }
        }

        override fun onCameraInventoryError(message: String) {
            Log.e(TAG, "查询摄像头列表失败：$message")
        }

        override fun onCameraXAvailableCameraIds(cameraIds: List<String>) {
            Log.i(TAG, "CameraX 可绑定摄像头数量=${cameraIds.size}，ID=$cameraIds")
        }

        override fun onOpenError(message: String) {
            Log.e(TAG, "摄像头 101 打开或预览失败：$message")
        }

        override fun onOpenSuccess() {
            frameCount.set(0)
            validFrameCount.set(0)
            lastFrameErrorLogTime.set(0)
            Log.i(TAG, "摄像头 $cameraId 打开成功，开始预览")
        }

        override fun onPreview(previewWidth: Int, previewHeight: Int) {
            Log.i(TAG, "摄像头 $cameraId 实际预览尺寸=${previewWidth}x${previewHeight}")
        }
    }

    private val previewCallback = object : ICameraPreviewDataCallback {
        override fun onPreviewBufferFrame(image: Image) {
            val count = frameCount.incrementAndGet()
            if (count == 1L || count % FRAME_LOG_INTERVAL == 0L) {
                Log.d(
                    TAG,
                    "预览帧 #$count：${image.width}x${image.height}，" +
                        "format=${image.format}，timestamp=${image.timestamp}，" +
                        "planes=${image.planes.size}",
                )
            }
        }

        override fun onFrameErrorCallback() {
            val now = SystemClock.elapsedRealtime()
            val previous = lastFrameErrorLogTime.get()
            if (now - previous >= FRAME_ERROR_LOG_INTERVAL_MS &&
                lastFrameErrorLogTime.compareAndSet(previous, now)
            ) {
                Log.e(TAG, "摄像头 $cameraId 连续收到无效预览帧，当前总帧数=${frameCount.get()}")
            }
        }

        override fun onFrameSuccessCallback() {
            val count = validFrameCount.incrementAndGet()
            if (count == 1L || count % FRAME_LOG_INTERVAL == 0L) {
                Log.d(TAG, "摄像头 $cameraId 已收到有效帧 $count 帧")
            }
        }
    }

    private fun List<Size>.toSizeText(): String =
        if (isEmpty()) "无" else joinToString { "${it.width}x${it.height}" }

    private fun lensFacingName(lensFacing: Int?): String = when (lensFacing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "前置"
        CameraCharacteristics.LENS_FACING_BACK -> "后置"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
        else -> "未知($lensFacing)"
    }

    override fun initView() {
        binding.btnAction.setOnClickListener {
            viewModel.handleEvent(MainEvent.OnButtonClick)
        }

//        if (binding.cameraSurfaceview.camera == null) {
//            binding.cameraSurfaceview.setCameraOpenCallback(callback)
//            binding.cameraSurfaceview.setCameraPreviewDataCallback(previewCallback)
//            binding.cameraSurfaceview.setCamera(Camera2API(this, cameraId),false)
//        }

        cameraOesEngine = (application as App).getOrStartCameraEngine(cameraId).also { engine ->
            engine.setErrorListener(oesErrorListener)
        }
    }

    override fun onStart() {
        super.onStart()
        cameraOesEngine?.setErrorListener(oesErrorListener)
    }

    override fun onStop() {
        cameraOesEngine?.clearErrorListener(oesErrorListener)
        // 页面退到后台只解除 UI 监听，不暂停进程级摄像头采集。
//        binding.cameraSurfaceview.pausePreview()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
//        if (binding.cameraSurfaceview.camera != null) {
//            binding.cameraSurfaceview.startPreview()
//        }
    }

    override fun onDestroy() {
        cameraOesEngine?.clearErrorListener(oesErrorListener)
        cameraOesEngine = null
        super.onDestroy()
    }

    override fun observeState() {
        viewModel.state
            .onEach { state ->
                binding.tvContent.text = state.text
            }
            .launchIn(lifecycleScope)

        lifecycleScope.launchWhenStarted {
            viewModel.effect.collect { effect ->
                when (effect) {
                    is MainEffect.ShowToast -> {
                        Toast.makeText(this@MainActivity, effect.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private companion object {
        private const val TAG = "MainActivity.Camera"
        private const val FRAME_LOG_INTERVAL = 60L
        private const val FRAME_ERROR_LOG_INTERVAL_MS = 5_000L
    }
}
