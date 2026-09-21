package com.example.camera_core.view

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.Image
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.example.camera_core.api.ICamera
import com.example.camera_core.api.ICameraOpenCallback
import com.example.camera_core.api.ICameraPreviewDataCallback

/**
 * 基于 [TextureView] 的相机预览控件。
 *
 * 与 [CameraSurfaceView] 不同，此控件会基于 [SurfaceTexture] 创建并持有一个 [Surface]。
 * 绑定的相机不会持有或释放该 Surface。
 *
 * 可通过 [setCamera] 绑定 `Camera2API` 或 `CameraXAPI`。默认在控件从窗口移除时释放相机。
 * 如果相机由生命周期更长的组件持有，请将 [releaseCameraOnDetach] 设为 `false`。
 */
class CameraTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    /** [onDetachedFromWindow] 时是否释放当前绑定的相机。 */
    var releaseCameraOnDetach: Boolean = true

    /** 每次创建 [SurfaceTexture] 时设置的缓冲区尺寸。 */
    var bufferWidth: Int = DEFAULT_BUFFER_WIDTH
        private set
    var bufferHeight: Int = DEFAULT_BUFFER_HEIGHT
        private set

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
    private var previewSurface: Surface? = null
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
            this@CameraTextureView.previewWidth = previewWidth
            this@CameraTextureView.previewHeight = previewHeight
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
        surfaceTextureListener = this
    }

    /**
     * 设置当前及后续预览 Surface 使用的默认缓冲区尺寸。
     *
     * 如果正在预览，更改尺寸后会重新配置相机捕获会话。
     */
    fun setPreviewBufferSize(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Preview buffer dimensions must be positive" }
        if (bufferWidth == width && bufferHeight == height) return

        bufferWidth = width
        bufferHeight = height
        val texture = surfaceTexture ?: return
        texture.setDefaultBufferSize(width, height)
        openedSurface = null
        openInFlight = false
        if (previewRequested) startPreviewOnCurrentSurface()
    }

    /**
     * 将 [camera] 绑定到此控件。
     *
     * @param releasePrevious 更换相机实例时是否释放之前绑定的相机
     * @param startPreview 已有有效 SurfaceTexture 时是否立即打开相机并开始预览
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

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(bufferWidth, bufferHeight)
        replacePreviewSurface(texture)
        if (previewRequested) startPreviewOnCurrentSurface()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        // 相机缓冲区尺寸与控件的布局尺寸相互独立。
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        boundCamera?.pauseCamera()
        openedSurface = null
        openInFlight = false
        releasePreviewSurface()
        // SurfaceTexture 属于 TextureView，由其负责释放。
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val texture = surfaceTexture
        if (previewSurface == null && texture != null && isAvailable) {
            texture.setDefaultBufferSize(bufferWidth, bufferHeight)
            replacePreviewSurface(texture)
        }
        if (previewRequested) startPreviewOnCurrentSurface()
    }

    override fun onDetachedFromWindow() {
        openedSurface = null
        openInFlight = false
        if (releaseCameraOnDetach) {
            clearCamera(release = true)
        } else {
            boundCamera?.pauseCamera()
        }
        releasePreviewSurface()
        super.onDetachedFromWindow()
    }

    private fun registerCallbacks(camera: ICamera) {
        camera.addCameraOpenCallback(openCallbackAdapter)
        camera.addCameraPreviewDataCallback(previewCallbackAdapter)
    }

    private fun startPreviewOnCurrentSurface() {
        val camera = boundCamera ?: return
        var surface = previewSurface
        if (surface == null) {
            val texture = surfaceTexture ?: return
            if (!isAvailable) return
            texture.setDefaultBufferSize(bufferWidth, bufferHeight)
            replacePreviewSurface(texture)
            surface = previewSurface
        }
        if (surface == null || !surface.isValid) return

        if (openedSurface !== surface) {
            openedSurface = surface
            openInFlight = true
            camera.openCamera(surface)
        } else if (!openInFlight && !camera.isPreviewOn()) {
            camera.resumeCamera()
        }
    }

    private fun replacePreviewSurface(texture: SurfaceTexture) {
        openedSurface = null
        openInFlight = false
        releasePreviewSurface()
        previewSurface = Surface(texture)
    }

    private fun releasePreviewSurface() {
        previewSurface?.release()
        previewSurface = null
    }

    private companion object {
        private const val DEFAULT_BUFFER_WIDTH = 1280
        private const val DEFAULT_BUFFER_HEIGHT = 720
    }
}
