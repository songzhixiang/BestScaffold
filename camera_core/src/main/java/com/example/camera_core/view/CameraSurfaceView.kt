package com.example.camera_core.view

import android.content.Context
import android.media.Image
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.camera_core.api.ICamera
import com.example.camera_core.api.ICameraOpenCallback
import com.example.camera_core.api.ICameraPreviewDataCallback

/**
 * 基于 [SurfaceView] 的相机预览控件。
 *
 * 可通过 [setCamera] 绑定 `Camera2API` 或 `CameraXAPI`。[Surface] 可用时自动打开相机，
 * Surface 销毁时暂停预览。调用方也可以使用 [startPreview]、[pausePreview] 和 [releaseCamera]
 * 主动控制预览及资源释放。
 *
 * 默认在控件从窗口移除时释放绑定的相机。如果相机由生命周期更长的组件持有，
 * 将 [releaseCameraOnDetach] 设为 `false`，并由该组件负责释放相机。
 */
class CameraSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    /** [onDetachedFromWindow] 时是否释放当前绑定的相机。 */
    var releaseCameraOnDetach: Boolean = true

    /** 相机最近一次回报的预览尺寸。 */
    var previewWidth: Int = 0
        private set
    var previewHeight: Int = 0
        private set

    val camera: ICamera?
        get() = boundCamera

    private var boundCamera: ICamera? = null
    @Volatile
    private var cameraOpenCallback: ICameraOpenCallback? = null
    @Volatile
    private var cameraPreviewDataCallback: ICameraPreviewDataCallback? = null
    private var currentSurface: Surface? = null
    private var openedSurface: Surface? = null
    private var previewRequested = false
    private var openInFlight = false

    // 相机实现仅弱引用回调，因此 View 必须强引用以下回调适配器。
    private val openCallbackAdapter = object : ICameraOpenCallback {
        override fun onCameraInventory(inventory: com.example.camera_core.api.CameraInventory) {
            cameraOpenCallback?.onCameraInventory(inventory)
        }

        override fun onCameraInventoryError(message: String) {
            cameraOpenCallback?.onCameraInventoryError(message)
        }

        override fun onCameraXAvailableCameraIds(cameraIds: List<String>) {
            cameraOpenCallback?.onCameraXAvailableCameraIds(cameraIds)
        }

        override fun onPreview(previewWidth: Int, previewHeight: Int) {
            this@CameraSurfaceView.previewWidth = previewWidth
            this@CameraSurfaceView.previewHeight = previewHeight
            cameraOpenCallback?.onPreview(previewWidth, previewHeight)
        }

        override fun onOpenSuccess() {
            openInFlight = false
            cameraOpenCallback?.onOpenSuccess()
        }

        override fun onOpenError(message: String) {
            openInFlight = false
            cameraOpenCallback?.onOpenError(message)
        }
    }

    private val previewCallbackAdapter = object : ICameraPreviewDataCallback {
        override fun onFrameErrorCallback() {
            cameraPreviewDataCallback?.onFrameErrorCallback()
        }

        override fun onFrameSuccessCallback() {
            cameraPreviewDataCallback?.onFrameSuccessCallback()
        }

        override fun onPreviewBufferFrame(image: Image) {
            cameraPreviewDataCallback?.onPreviewBufferFrame(image)
        }
    }

    init {
        holder.addCallback(this)
    }

    /**
     * 将 [camera] 绑定到此控件。
     *
     * @param releasePrevious 更换相机实例时是否释放之前绑定的相机
     * @param startPreview 已有有效 Surface 时是否立即打开相机并开始预览
     */
    @JvmOverloads
    fun setCamera(
        camera: ICamera,
        releasePrevious: Boolean = true,
        startPreview: Boolean = true,
    ) {
        val previous = boundCamera
        if (previous !== camera) {
            if (releasePrevious) {
                previous?.releaseCamera()
            } else {
                previous?.pauseCamera()
            }
            openedSurface = null
            openInFlight = false
            boundCamera = camera
        }

        registerCallbacks(camera)
        previewRequested = startPreview
        if (startPreview) startPreviewOnCurrentSurface()
    }

    /** 强引用 [callback]，直到回调被替换或相机绑定被清除。 */
    fun setCameraOpenCallback(callback: ICameraOpenCallback?) {
        cameraOpenCallback = callback
        boundCamera?.addCameraOpenCallback(openCallbackAdapter)
    }

    /** 强引用 [callback]，直到回调被替换或相机绑定被清除。 */
    fun setCameraPreviewDataCallback(callback: ICameraPreviewDataCallback?) {
        cameraPreviewDataCallback = callback
        boundCamera?.addCameraPreviewDataCallback(previewCallbackAdapter)
    }

    /** 有有效 Surface 时启动或恢复预览。 */
    fun startPreview() {
        previewRequested = true
        startPreviewOnCurrentSurface()
    }

    /** 暂停预览，但保留相机设备以便快速恢复。 */
    fun pausePreview() {
        previewRequested = false
        openInFlight = false
        boundCamera?.pauseCamera()
    }

    /** 返回绑定的相机当前是否正在持续输出预览画面。 */
    fun isPreviewOn(): Boolean = boundCamera?.isPreviewOn() == true

    /**
     * 解除相机绑定。
     *
     * [release] 为 `false` 时只暂停预览，相机仍由调用方持有。
     */
    @JvmOverloads
    fun clearCamera(release: Boolean = true) {
        previewRequested = false
        if (release) {
            boundCamera?.releaseCamera()
        } else {
            boundCamera?.pauseCamera()
        }
        boundCamera = null
        openedSurface = null
        openInFlight = false
        previewWidth = 0
        previewHeight = 0
        cameraOpenCallback = null
        cameraPreviewDataCallback = null
    }

    /** 释放并解除当前相机绑定。 */
    fun releaseCamera() = clearCamera(release = true)

    override fun surfaceCreated(holder: SurfaceHolder) {
        currentSurface = holder.surface.takeIf { it.isValid }
        if (previewRequested) startPreviewOnCurrentSurface()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val surface = holder.surface.takeIf { it.isValid } ?: return
        if (currentSurface !== surface) {
            currentSurface = surface
            openedSurface = null
        }
        if (previewRequested && !isPreviewOn()) startPreviewOnCurrentSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        currentSurface = null
        openedSurface = null
        openInFlight = false
        boundCamera?.pauseCamera()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (previewRequested) {
            currentSurface = holder.surface.takeIf { it.isValid }
            startPreviewOnCurrentSurface()
        }
    }

    override fun onDetachedFromWindow() {
        currentSurface = null
        openedSurface = null
        openInFlight = false
        if (releaseCameraOnDetach) {
            clearCamera(release = true)
        } else {
            boundCamera?.pauseCamera()
        }
        super.onDetachedFromWindow()
    }

    private fun registerCallbacks(camera: ICamera) {
        camera.addCameraOpenCallback(openCallbackAdapter)
        camera.addCameraPreviewDataCallback(previewCallbackAdapter)
    }

    private fun startPreviewOnCurrentSurface() {
        val camera = boundCamera ?: return
        val surface = currentSurface ?: holder.surface.takeIf { it.isValid } ?: return
        currentSurface = surface

        if (openedSurface !== surface) {
            openedSurface = surface
            openInFlight = true
            camera.openCamera(surface)
        } else if (!openInFlight && !camera.isPreviewOn()) {
            camera.resumeCamera()
        }
    }
}
