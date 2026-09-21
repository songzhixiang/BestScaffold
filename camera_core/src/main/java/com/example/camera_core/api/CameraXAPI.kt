package com.example.camera_core.api

import android.content.Context
import android.media.Image
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ICamera] 的 CameraX 实现。
 *
 * CameraX 需要 [LifecycleOwner] 管理底层设备生命周期；[pauseCamera] 和 [resumeCamera]
 * 则分别解绑、重新绑定此实例的用例。
 *
 * @param context 任意 Context；内部仅持有 Application Context
 * @param lifecycleOwner CameraX 绑定预览和图像分析用例所需的生命周期所有者
 * @param cameraSelector 用于选择 CameraX 摄像头的选择器；精确指定 Camera2 ID 请使用 [Camera2API]
 * @param requestedPreviewSize 预览和 YUV 图像分析的目标分辨率
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class, ExperimentalCamera2Interop::class])
class CameraXAPI @JvmOverloads constructor(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    private val requestedPreviewSize: Size = Size(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT),
) : ICamera {

    private val applicationContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(applicationContext)
    private val surfaceExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CameraXSurface").apply { isDaemon = true }
    }
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CameraXAnalysis").apply { isDaemon = true }
    }
    private val inventoryExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CameraXInventory").apply { isDaemon = true }
    }
    private val providerFuture = ProcessCameraProvider.getInstance(applicationContext)

    private val released = AtomicBoolean(false)
    private val shouldPreview = AtomicBoolean(false)

    @Volatile
    private var previewing = false

    private var previewSurface: Surface? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var openCallbackRef: WeakReference<ICameraOpenCallback>? = null
    private var previewCallbackRef: WeakReference<ICameraPreviewDataCallback>? = null

    init {
        require(requestedPreviewSize.width > 0 && requestedPreviewSize.height > 0) {
            "requestedPreviewSize must have positive dimensions"
        }
    }

    override fun openCamera(surface: Surface) {
        if (released.get()) {
            notifyOpenError("CameraXAPI has already been released")
            return
        }
        if (!surface.isValid) {
            notifyOpenError("Preview surface is not valid")
            return
        }

        shouldPreview.set(true)
        previewSurface = surface
        inventoryExecutor.execute { reportCameraInventory() }
        providerFuture.addListener(
            {
                if (released.get() || !shouldPreview.get()) return@addListener
                try {
                    cameraProvider = providerFuture.get()
                    bindUseCases(notifyOpened = true)
                } catch (error: Exception) {
                    previewing = false
                    notifyOpenError(error.message ?: "Unable to obtain CameraX provider")
                }
            },
            mainExecutor,
        )
    }

    private fun bindUseCases(notifyOpened: Boolean) {
        if (released.get() || !shouldPreview.get()) return
        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            notifyOpenError("LifecycleOwner has already been destroyed")
            return
        }

        val provider = cameraProvider ?: run {
            notifyOpenError("CameraX provider is not ready")
            return
        }
        val cameraXIds = provider.availableCameraInfos.mapNotNull { info ->
            try {
                Camera2CameraInfo.from(info).cameraId
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "CameraX camera info has no Camera2 ID", error)
                null
            }
        }
        Log.i(TAG, "CameraX bindable camera count=${cameraXIds.size}, ids=$cameraXIds")
        if (notifyOpened) {
            openCallbackRef?.get()?.onCameraXAvailableCameraIds(cameraXIds)
        }
        val surface = previewSurface
        if (surface == null || !surface.isValid) {
            notifyOpenError("Preview surface is not available")
            return
        }

        unbindUseCases(provider)

        val newPreview = Preview.Builder()
            .setTargetResolution(requestedPreviewSize)
            .build()
            .also { useCase ->
                useCase.setSurfaceProvider { request ->
                    request.provideSurface(surface, surfaceExecutor) { result ->
                        if (result.resultCode !=
                            androidx.camera.core.SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY
                        ) {
                            Log.w(TAG, "CameraX surface result: ${result.resultCode}")
                        }
                    }
                }
            }

        val newAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(requestedPreviewSize)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(analysisExecutor, PreviewAnalyzer { previewCallbackRef })
            }

        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                newPreview,
                newAnalysis,
            )
            preview = newPreview
            imageAnalysis = newAnalysis
            previewing = true
            if (notifyOpened) notifyOpenSuccess()
        } catch (error: Exception) {
            newAnalysis.clearAnalyzer()
            provider.unbind(newPreview, newAnalysis)
            previewing = false
            notifyOpenError(error.message ?: "Unable to bind CameraX use cases")
        }
    }

    override fun pauseCamera() {
        if (released.get()) return
        shouldPreview.set(false)
        mainExecutor.execute {
            previewing = false
            cameraProvider?.let(::unbindUseCases)
        }
    }

    override fun resumeCamera() {
        if (released.get() || previewing) return
        shouldPreview.set(true)
        mainExecutor.execute {
            if (!released.get() && shouldPreview.get() && !previewing) {
                bindUseCases(notifyOpened = false)
            }
        }
    }

    override fun releaseCamera() {
        if (!released.compareAndSet(false, true)) return
        shouldPreview.set(false)
        previewing = false
        mainExecutor.execute {
            cameraProvider?.let(::unbindUseCases)
            cameraProvider = null
            previewSurface = null
            openCallbackRef = null
            previewCallbackRef = null
            analysisExecutor.shutdown()
            surfaceExecutor.shutdown()
            inventoryExecutor.shutdown()
        }
    }

    override fun isPreviewOn(): Boolean = previewing

    override fun getCameraInventory(): CameraInventory = CameraInventoryQuery.query(applicationContext)

    override fun addCameraOpenCallback(callback: ICameraOpenCallback) {
        openCallbackRef = WeakReference(callback)
    }

    override fun addCameraPreviewDataCallback(callback: ICameraPreviewDataCallback) {
        previewCallbackRef = WeakReference(callback)
    }

    private fun unbindUseCases(provider: ProcessCameraProvider) {
        imageAnalysis?.clearAnalyzer()
        val useCases = listOfNotNull(preview, imageAnalysis)
        if (useCases.isNotEmpty()) provider.unbind(*useCases.toTypedArray())
        preview = null
        imageAnalysis = null
    }

    private fun notifyOpenSuccess() {
        mainExecutor.execute {
            openCallbackRef?.get()?.let { callback ->
                callback.onPreview(requestedPreviewSize.width, requestedPreviewSize.height)
                callback.onOpenSuccess()
            }
        }
    }

    private fun notifyOpenError(message: String) {
        Log.e(TAG, message)
        mainExecutor.execute { openCallbackRef?.get()?.onOpenError(message) }
    }

    private fun reportCameraInventory() {
        try {
            val inventory = getCameraInventory()
            CameraInventoryQuery.log(inventory, TAG)
            mainExecutor.execute { openCallbackRef?.get()?.onCameraInventory(inventory) }
        } catch (error: Exception) {
            val message = error.message ?: "Unable to query camera inventory"
            Log.w(TAG, message, error)
            mainExecutor.execute { openCallbackRef?.get()?.onCameraInventoryError(message) }
        }
    }

    private class PreviewAnalyzer(
        private val callbackProvider: () -> WeakReference<ICameraPreviewDataCallback>?,
    ) : ImageAnalysis.Analyzer {

        override fun analyze(imageProxy: ImageProxy) {
            try {
                val callback = callbackProvider()?.get() ?: return
                val image: Image = imageProxy.image ?: return
                callback.onPreviewBufferFrame(image)
                callback.onFrameSuccessCallback()
            } catch (error: Throwable) {
                Log.e(TAG, "CameraX preview callback failed", error)
                callbackProvider()?.get()?.onFrameErrorCallback()
            } finally {
                imageProxy.close()
            }
        }
    }

    private companion object {
        private val TAG = CameraXAPI::class.java.simpleName
        private const val DEFAULT_PREVIEW_WIDTH = 1280
        private const val DEFAULT_PREVIEW_HEIGHT = 720
    }
}
