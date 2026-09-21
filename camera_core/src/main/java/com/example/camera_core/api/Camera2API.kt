package com.example.camera_core.api

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * [ICamera] 的 Camera2 实现。
 *
 * 沿用 DVR 应用中相机设备与会话分离的设计：打开设备时建立预览输出和 YUV [ImageReader]，
 * 暂停或恢复预览时只更换捕获会话。所属组件销毁时应调用 [releaseCamera]；释放后的实例不可重新打开。
 *
 * @param context 任意 Context；内部仅持有 Application Context
 * @param cameraId [CameraManager.getCameraIdList] 返回的摄像头 ID
 * @param requestedPreviewSize 期望的预览及 YUV 尺寸；实际使用最接近的受支持 YUV 尺寸
 * @param maxImages [ImageReader] 可同时持有的最大 YUV 图像数量
 */
class Camera2API @JvmOverloads constructor(
    context: Context,
    private val cameraId: String,
    private val requestedPreviewSize: Size = Size(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT),
    private val maxImages: Int = DEFAULT_MAX_IMAGES,
) : ICamera {

    private val applicationContext = context.applicationContext
    private val cameraManager =
        applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraThread = HandlerThread("Camera2-$cameraId").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageThread = HandlerThread("Camera2Image-$cameraId").apply { start() }
    private val imageHandler = Handler(imageThread.looper)

    private val opening = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val shouldPreview = AtomicBoolean(false)
    private val threadsStopped = AtomicBoolean(false)

    @Volatile
    private var previewing = false

    // 以下相机资源仅在 cameraThread 中访问。
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var previewSize: Size = requestedPreviewSize
    private var sessionGeneration = 0
    private var openRetryCount = 0
    private var openRetryTask: Runnable? = null

    private var openCallbackRef: WeakReference<ICameraOpenCallback>? = null
    private var previewCallbackRef: WeakReference<ICameraPreviewDataCallback>? = null

    init {
        require(requestedPreviewSize.width > 0 && requestedPreviewSize.height > 0) {
            "requestedPreviewSize must have positive dimensions"
        }
        require(maxImages >= 2) { "maxImages must be at least 2" }
    }

    override fun openCamera(surface: Surface) {
        if (released.get()) {
            notifyOpenError("Camera2API has already been released")
            return
        }
        if (!surface.isValid) {
            notifyOpenError("Preview surface is not valid")
            return
        }

        shouldPreview.set(true)
        cameraHandler.post {
            if (released.get()) return@post
            cancelOpenRetry()
            openRetryCount = 0
            reportCameraInventory()
            previewSurface = surface

            if (cameraDevice != null) {
                createPreviewSession(notifyOpened = true)
            } else {
                openCameraDevice()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraDevice() {
        if (!opening.compareAndSet(false, true)) return

        if (applicationContext.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            opening.set(false)
            notifyOpenError("CAMERA permission has not been granted")
            return
        }

        try {
            if (cameraId !in cameraManager.cameraIdList) {
                opening.set(false)
                notifyOpenError("Camera id '$cameraId' is not available")
                return
            }

            previewSize = choosePreviewSize()
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        opening.set(false)
                        if (released.get()) {
                            device.close()
                            shutdownThreads()
                            return
                        }
                        cancelOpenRetry()
                        openRetryCount = 0
                        cameraDevice = device
                        ensureImageReader()
                        if (shouldPreview.get()) createPreviewSession(notifyOpened = true)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        opening.set(false)
                        handleDeviceClosed(device, "Camera '$cameraId' disconnected")
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        opening.set(false)
                        handleDeviceClosed(
                            device,
                            "Camera '$cameraId' error: ${cameraErrorName(error)} ($error)",
                            retryable = error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                                error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE,
                        )
                    }
                },
                cameraHandler,
            )
        } catch (error: SecurityException) {
            opening.set(false)
            handleOpenException(error.message ?: "No permission to open camera '$cameraId'")
        } catch (error: CameraAccessException) {
            opening.set(false)
            handleOpenException(
                error.message ?: "Unable to access camera '$cameraId'",
                retryable = error.reason == CameraAccessException.CAMERA_IN_USE ||
                    error.reason == CameraAccessException.MAX_CAMERAS_IN_USE,
            )
        } catch (error: IllegalArgumentException) {
            opening.set(false)
            handleOpenException(error.message ?: "Invalid camera id '$cameraId'")
        }
    }

    private fun choosePreviewSize(): Size {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            .orEmpty()

        if (sizes.isEmpty()) return requestedPreviewSize

        val requestedRatio = requestedPreviewSize.width.toDouble() / requestedPreviewSize.height
        return sizes.minWithOrNull(
            compareBy<Size> {
                abs(it.width.toDouble() / it.height - requestedRatio) > ASPECT_RATIO_TOLERANCE
            }.thenBy {
                abs(
                    it.width.toLong() * it.height -
                        requestedPreviewSize.width.toLong() * requestedPreviewSize.height,
                )
            },
        ) ?: requestedPreviewSize
    }

    private fun ensureImageReader() {
        val current = imageReader
        if (current != null && current.width == previewSize.width && current.height == previewSize.height) {
            bindImageListener(current)
            return
        }

        current?.setOnImageAvailableListener(null, null)
        current?.close()
        imageReader = ImageReader.newInstance(
            previewSize.width,
            previewSize.height,
            ImageFormat.YUV_420_888,
            maxImages,
        ).also(::bindImageListener)
    }

    private fun bindImageListener(reader: ImageReader) {
        reader.setOnImageAvailableListener(
            ImageAvailableListener { previewCallbackRef },
            imageHandler,
        )
    }

    @Suppress("DEPRECATION")
    private fun createPreviewSession(notifyOpened: Boolean) {
        if (!shouldPreview.get()) return
        val device = cameraDevice ?: run {
            notifyOpenError("Camera '$cameraId' is not open")
            return
        }
        val surface = previewSurface
        if (surface == null || !surface.isValid) {
            notifyOpenError("Preview surface is not available")
            return
        }

        ensureImageReader()
        val readerSurface = imageReader?.surface ?: run {
            notifyOpenError("Unable to create the YUV preview surface")
            return
        }

        previewing = false
        closeCaptureSession()
        val generation = ++sessionGeneration

        try {
            device.createCaptureSession(
                listOf(surface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (released.get() || !shouldPreview.get() ||
                            generation != sessionGeneration ||
                            cameraDevice !== device
                        ) {
                            session.close()
                            return
                        }

                        captureSession = session
                        try {
                            val request = device
                                .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                .apply {
                                    addTarget(surface)
                                    addTarget(readerSurface)
                                }
                                .build()
                            session.setRepeatingRequest(request, null, cameraHandler)
                            previewing = true
                            if (notifyOpened) notifyOpenSuccess()
                        } catch (error: Exception) {
                            previewing = false
                            closeCaptureSession()
                            notifyOpenError(error.message ?: "Unable to start camera preview")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (generation == sessionGeneration) {
                            previewing = false
                            notifyOpenError("Camera preview session configuration failed")
                        }
                    }
                },
                cameraHandler,
            )
        } catch (error: Exception) {
            previewing = false
            notifyOpenError(error.message ?: "Unable to create camera preview session")
        }
    }

    override fun pauseCamera() {
        if (released.get()) return
        shouldPreview.set(false)
        cameraHandler.post {
            cancelOpenRetry()
            openRetryCount = 0
            ++sessionGeneration
            previewing = false
            closeCaptureSession()
        }
    }

    override fun resumeCamera() {
        if (released.get() || previewing) return
        shouldPreview.set(true)
        cameraHandler.post {
            if (released.get() || !shouldPreview.get() || previewing) return@post
            if (cameraDevice == null) {
                if (!opening.get()) openCameraDevice()
            } else {
                createPreviewSession(notifyOpened = false)
            }
        }
    }

    override fun releaseCamera() {
        if (!released.compareAndSet(false, true)) return
        shouldPreview.set(false)
        previewing = false

        cameraHandler.post {
            cancelOpenRetry()
            ++sessionGeneration
            closeCaptureSession()

            cameraDevice?.close()
            cameraDevice = null

            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null

            // Surface 属于 View 或调用方，此处不能释放。
            previewSurface = null
            openCallbackRef = null
            previewCallbackRef = null

            if (!opening.get()) shutdownThreads()
        }
    }

    override fun isPreviewOn(): Boolean = previewing

    override fun getCameraInventory(): CameraInventory = CameraInventoryQuery.query(applicationContext)

    override fun addCameraOpenCallback(callback: ICameraOpenCallback) {
        openCallbackRef = WeakReference(callback)
    }

    override fun addCameraPreviewDataCallback(callback: ICameraPreviewDataCallback) {
        previewCallbackRef = WeakReference(callback)
        if (!released.get()) {
            cameraHandler.post { imageReader?.let(::bindImageListener) }
        }
    }

    private fun closeCaptureSession() {
        val session = captureSession ?: return
        captureSession = null
        try {
            session.stopRepeating()
        } catch (_: Exception) {
            // 相机服务可能已经停止该会话。
        }
        try {
            session.abortCaptures()
        } catch (_: Exception) {
            // 可能没有尚待取消的捕获请求。
        }
        session.close()
    }

    private fun handleDeviceClosed(
        device: CameraDevice,
        message: String,
        retryable: Boolean = false,
    ) {
        device.close()
        if (cameraDevice === device) cameraDevice = null
        ++sessionGeneration
        previewing = false
        closeCaptureSession()
        if (released.get()) {
            shutdownThreads()
        } else if (retryable && shouldPreview.get()) {
            scheduleOpenRetry(message)
        } else {
            notifyOpenError(message)
        }
    }

    private fun handleOpenException(message: String, retryable: Boolean = false) {
        if (released.get()) {
            shutdownThreads()
        } else if (retryable && shouldPreview.get()) {
            scheduleOpenRetry(message)
        } else {
            notifyOpenError(message)
        }
    }

    private fun scheduleOpenRetry(message: String) {
        if (openRetryCount >= OPEN_RETRY_DELAYS_MS.size) {
            notifyOpenError("$message; camera remains busy after ${openRetryCount} retries")
            return
        }

        val delay = OPEN_RETRY_DELAYS_MS[openRetryCount++]
        Log.w(TAG, "$message; retry $openRetryCount in ${delay}ms")
        cancelOpenRetry()
        val task = Runnable {
            openRetryTask = null
            if (!released.get() && shouldPreview.get() && !opening.get() &&
                cameraDevice == null && previewSurface?.isValid == true
            ) {
                openCameraDevice()
            }
        }
        openRetryTask = task
        cameraHandler.postDelayed(task, delay)
    }

    private fun cancelOpenRetry() {
        openRetryTask?.let(cameraHandler::removeCallbacks)
        openRetryTask = null
    }

    private fun shutdownThreads() {
        if (!threadsStopped.compareAndSet(false, true)) return
        imageThread.quitSafely()
        cameraThread.quitSafely()
    }

    private fun notifyOpenSuccess() {
        mainHandler.post {
            openCallbackRef?.get()?.let { callback ->
                callback.onPreview(previewSize.width, previewSize.height)
                callback.onOpenSuccess()
            }
        }
    }

    private fun notifyOpenError(message: String) {
        Log.e(TAG, message)
        mainHandler.post { openCallbackRef?.get()?.onOpenError(message) }
    }

    private fun reportCameraInventory() {
        try {
            val inventory = getCameraInventory()
            CameraInventoryQuery.log(inventory, TAG)
            mainHandler.post { openCallbackRef?.get()?.onCameraInventory(inventory) }
        } catch (error: Exception) {
            val message = error.message ?: "Unable to query camera inventory"
            Log.w(TAG, message, error)
            mainHandler.post { openCallbackRef?.get()?.onCameraInventoryError(message) }
        }
    }

    private class ImageAvailableListener(
        private val callbackProvider: () -> WeakReference<ICameraPreviewDataCallback>?,
    ) : ImageReader.OnImageAvailableListener {

        private var invalidFrameCount = 0

        override fun onImageAvailable(reader: ImageReader) {
            val image = try {
                reader.acquireLatestImage()
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Unable to acquire preview image", error)
                null
            } ?: return

            try {
                val callback = callbackProvider()?.get() ?: return
                callback.onPreviewBufferFrame(image)

                val yBuffer = image.planes.firstOrNull()?.buffer
                val looksInvalid = yBuffer == null || !yBuffer.hasRemaining() ||
                    yBuffer.get(yBuffer.position()) == INVALID_Y_VALUE
                if (looksInvalid) {
                    invalidFrameCount++
                } else {
                    invalidFrameCount = 0
                }

                if (invalidFrameCount >= MAX_INVALID_FRAMES) {
                    callback.onFrameErrorCallback()
                } else {
                    callback.onFrameSuccessCallback()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Preview callback failed", error)
            } finally {
                image.close()
            }
        }
    }

    private fun cameraErrorName(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
        else -> "UNKNOWN"
    }

    private companion object {
        private val TAG = Camera2API::class.java.simpleName
        private const val DEFAULT_PREVIEW_WIDTH = 1280
        private const val DEFAULT_PREVIEW_HEIGHT = 720
        private const val DEFAULT_MAX_IMAGES = 3
        private const val MAX_INVALID_FRAMES = 60
        private const val ASPECT_RATIO_TOLERANCE = 0.01
        private const val INVALID_Y_VALUE: Byte = 16
        private val OPEN_RETRY_DELAYS_MS = longArrayOf(500, 1_000, 2_000, 3_000, 5_000)
    }
}
