package com.example.camera_core.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.example.camera_core.opengl.OesDrawer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 将 Camera2 画面送入 OES 纹理，并在预览 Surface 可用时绘制。
 * Camera、SurfaceTexture 和 EGL 资源均由 GL 线程持有；release() 后不能再次启动。
 */
class CameraOesEngine(context: Context) : SurfaceTexture.OnFrameAvailableListener {

    fun interface ErrorListener {
        fun onError(message: String, cause: Throwable?)
    }

    /** 在 GL 线程回调；纹理只可在回调期间使用，不能跨线程直接访问。 */
    fun interface FrameListener {
        fun onFrame(textureId: Int, transformMatrix: FloatArray, timestampNs: Long)
    }

    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val glThread = HandlerThread("OES-Render-Thread").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val started = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val shouldCapture = AtomicBoolean(false)

    @Volatile
    private var capturing = false

    @Volatile
    private var releaseComplete = false

    @Volatile
    private var errorListener: ErrorListener? = null
    @Volatile
    private var frameListener: FrameListener? = null
    @Volatile
    private var releaseListener: (() -> Unit)? = null

    // 以下状态只在 GL 线程访问。
    private var cameraOpening = false
    private var cameraClosing = false
    private var cleanupDone = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var sessionOpening = false
    private var sessionGeneration = 0L

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var pbufferSurface = EGL14.EGL_NO_SURFACE
    private var previewEglSurface = EGL14.EGL_NO_SURFACE
    private var previewSourceSurface: Surface? = null
    private var eglConfig: EGLConfig? = null
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraInputSurface: Surface? = null
    private val oesDrawer = OesDrawer()
    private val transformMatrix = FloatArray(16)
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var frameCount = 0L

    fun setErrorListener(listener: ErrorListener?) {
        errorListener = listener
    }

    fun clearErrorListener(listener: ErrorListener) {
        if (errorListener === listener) errorListener = null
    }

    fun setFrameListener(listener: FrameListener?) {
        frameListener = listener
    }

    fun setReleaseListener(listener: (() -> Unit)?) {
        releaseListener = listener
    }

    fun isCapturing(): Boolean = capturing

    fun isReleased(): Boolean = released.get()

    fun isReleaseComplete(): Boolean = releaseComplete

    fun startEngine(cameraId: String) {
        if (released.get()) {
            reportError("引擎已释放，无法再次打开摄像头")
            return
        }
        if (!started.compareAndSet(false, true)) {
            reportError("引擎已经启动，不能重复打开摄像头")
            return
        }
        shouldCapture.set(true)
        val posted = glHandler.post {
            if (released.get()) return@post
            try {
                initEglContext()
                if (released.get()) return@post
                createOesTextureAndSurface()
                if (released.get()) return@post
                openCamera(cameraId)
            } catch (error: Exception) {
                fail("初始化摄像头或 OpenGL 失败", error)
            }
        }
        if (!posted) {
            reportError("GL 线程已停止，无法启动引擎")
            released.set(true)
        }
    }

    /** 停止相机连续取帧；设备仍被占用，直到调用 release()。 */
    fun pauseCapture() {
        if (!started.get() || released.get()) return
        shouldCapture.set(false)
        glHandler.post {
            if (released.get()) return@post
            sessionGeneration++
            capturing = false
            closeCaptureSession()
            Log.i(TAG, "摄像头采集已暂停")
        }
    }

    /** 恢复取帧；即使没有 UI Surface，OES 输入也会持续接收画面。 */
    fun resumeCapture() {
        if (!started.get() || released.get()) return
        shouldCapture.set(true)
        glHandler.post {
            if (released.get() || !shouldCapture.get()) return@post
            val device = cameraDevice
            if (device != null && captureSession == null && !sessionOpening) {
                try {
                    createCaptureSession(device)
                } catch (error: Exception) {
                    fail("恢复摄像头采集失败", error)
                }
            }
        }
    }

    private fun initEglContext() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        checkEgl(eglDisplay != EGL14.EGL_NO_DISPLAY, "eglGetDisplay")

        val version = IntArray(2)
        checkEgl(EGL14.eglInitialize(eglDisplay, version, 0, version, 1), "eglInitialize")

        // 同一个配置必须同时支持后台 Pbuffer 与前台 Window Surface。
        val configAttribs = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        checkEgl(
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0),
            "eglChooseConfig",
        )
        check(numConfigs[0] > 0 && configs[0] != null) {
            "找不到同时支持 Window 与 Pbuffer 的 EGLConfig"
        }
        val config = requireNotNull(configs[0])
        eglConfig = config

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0,
        )
        checkEgl(eglContext != EGL14.EGL_NO_CONTEXT, "eglCreateContext")

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE,
        )
        pbufferSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, config, pbufferAttribs, 0,
        )
        checkEgl(pbufferSurface != EGL14.EGL_NO_SURFACE, "eglCreatePbufferSurface")
        checkEgl(
            EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext),
            "eglMakeCurrent(Pbuffer)",
        )

        oesDrawer.init()
        checkGl("初始化 OES 着色器")
        Log.i(TAG, "EGL 初始化完成：" + version[0] + "." + version[1])
    }

    private fun createOesTextureAndSurface() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        checkGl("生成 OES 纹理")
        check(textures[0] != 0) { "无法生成 OES 纹理" }
        oesTextureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        checkGl("配置 OES 纹理")

        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
            setOnFrameAvailableListener(this@CameraOesEngine, glHandler)
        }
        cameraInputSurface = Surface(surfaceTexture)
        Log.i(TAG, "OES 输入 Surface 已创建，textureId=$oesTextureId")
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String) {
        val manager = applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraOpening = true
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraOpening = false
                    cameraDevice = device
                    if (released.get()) {
                        closeCameraDevice(device)
                        return
                    }
                    if (shouldCapture.get()) {
                        try {
                            createCaptureSession(device)
                        } catch (error: Exception) {
                            fail("摄像头 $cameraId 创建捕获会话失败", error)
                        }
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    cameraOpening = false
                    if (!released.get()) reportError("摄像头 $cameraId 已断开")
                    release()
                    closeCameraDevice(device)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    cameraOpening = false
                    if (!released.get()) {
                        reportError(
                            "摄像头 $cameraId 打开或运行失败：${cameraErrorName(error)} ($error)",
                        )
                    }
                    release()
                    closeCameraDevice(device)
                }

                override fun onClosed(device: CameraDevice) {
                    cameraClosing = false
                    if (cameraDevice === device) cameraDevice = null
                    if (released.get()) {
                        finishReleaseIfReady()
                    } else {
                        fail("摄像头 $cameraId 意外关闭")
                    }
                }
            }, glHandler)
        } catch (error: Exception) {
            cameraOpening = false
            fail("无法打开摄像头 $cameraId", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun createCaptureSession(device: CameraDevice) {
        if (sessionOpening || captureSession != null || !shouldCapture.get()) return
        val surface = checkNotNull(cameraInputSurface) { "OES 输入 Surface 尚未创建" }
        sessionOpening = true
        val generation = ++sessionGeneration
        device.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (released.get() || !shouldCapture.get() ||
                        generation != sessionGeneration || cameraDevice !== device
                    ) {
                        cleanupStep("关闭过期捕获会话") { session.close() }
                        sessionOpening = false
                        if (!released.get() && shouldCapture.get() && cameraDevice === device) {
                            try {
                                createCaptureSession(device)
                            } catch (error: Exception) {
                                fail("恢复摄像头采集失败", error)
                            }
                        }
                        return
                    }
                    sessionOpening = false
                    captureSession = session
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            .apply { addTarget(surface) }
                            .build()
                        session.setRepeatingRequest(request, null, glHandler)
                        capturing = true
                        Log.i(TAG, "摄像头捕获会话已启动")
                    } catch (error: Exception) {
                        fail("启动摄像头连续预览失败", error)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    cleanupStep("关闭配置失败的捕获会话") { session.close() }
                    sessionOpening = false
                    if (generation != sessionGeneration) {
                        if (!released.get() && shouldCapture.get() && cameraDevice === device) {
                            try {
                                createCaptureSession(device)
                            } catch (error: Exception) {
                                fail("恢复摄像头采集失败", error)
                            }
                        }
                        return
                    }
                    if (!released.get() && shouldCapture.get()) fail("摄像头捕获会话配置失败")
                }
            },
            glHandler,
        )
    }

    fun attachPreviewSurface(uiSurface: Surface) {
        if (released.get()) return
        glHandler.post {
            if (released.get()) return@post
            if (!uiSurface.isValid) {
                reportError("预览 Surface 已失效")
                return@post
            }
            if (previewSourceSurface === uiSurface &&
                previewEglSurface != EGL14.EGL_NO_SURFACE
            ) return@post
            try {
                destroyPreviewSurface()
                val config = checkNotNull(eglConfig) { "EGL 尚未初始化" }
                val windowSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, config, uiSurface, intArrayOf(EGL14.EGL_NONE), 0,
                )
                checkEgl(windowSurface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface")
                previewEglSurface = windowSurface
                previewSourceSurface = uiSurface
                Log.i(TAG, "预览窗口已挂载")
            } catch (error: Exception) {
                destroyPreviewSurface()
                reportError("挂载预览窗口失败，采集继续运行", error)
            }
        }
    }

    fun detachPreviewSurface() {
        if (released.get()) return
        glHandler.post {
            destroyPreviewSurface()
            surfaceWidth = 0
            surfaceHeight = 0
        }
    }

    /** 仅卸载指定窗口，避免旧 Activity 的销毁回调卸载新 Activity 的预览。 */
    fun detachPreviewSurface(uiSurface: Surface) {
        if (released.get()) return
        glHandler.post {
            if (previewSourceSurface !== uiSurface) return@post
            destroyPreviewSurface()
            surfaceWidth = 0
            surfaceHeight = 0
        }
    }

    fun updateViewport(width: Int, height: Int) {
        if (released.get()) return
        glHandler.post {
            surfaceWidth = width
            surfaceHeight = height
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        if (released.get() || surfaceTexture == null || surfaceTexture !== this.surfaceTexture) return
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(transformMatrix)
            // 暂停后仍需消费已经排队的帧，但不再向上层分发或绘制。
            if (!shouldCapture.get()) return
            frameListener?.let { listener ->
                try {
                    listener.onFrame(oesTextureId, transformMatrix.copyOf(), surfaceTexture.timestamp)
                } catch (error: Exception) {
                    reportError("处理相机帧回调失败", error)
                }
            }

            if (previewEglSurface != EGL14.EGL_NO_SURFACE &&
                surfaceWidth > 0 && surfaceHeight > 0
            ) {
                var previewError: Exception? = null
                try {
                    checkEgl(
                        EGL14.eglMakeCurrent(
                            eglDisplay, previewEglSurface, previewEglSurface, eglContext,
                        ),
                        "eglMakeCurrent(预览窗口)",
                    )
                    GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    oesDrawer.draw(oesTextureId, transformMatrix)
                    checkEgl(EGL14.eglSwapBuffers(eglDisplay, previewEglSurface), "eglSwapBuffers")
                } catch (error: Exception) {
                    previewError = error
                } finally {
                    checkEgl(
                        EGL14.eglMakeCurrent(
                            eglDisplay, pbufferSurface, pbufferSurface, eglContext,
                        ),
                        "eglMakeCurrent(Pbuffer)",
                    )
                }
                if (previewError != null) {
                    destroyPreviewSurface()
                    reportError("绘制预览失败，采集继续运行", previewError)
                }
            }

            frameCount++
            if (frameCount % FRAME_LOG_INTERVAL == 0L) {
                Log.d(TAG, "已接收 $frameCount 帧，预览尺寸=$surfaceWidth x $surfaceHeight")
            }
        } catch (error: Exception) {
            fail("处理 OES 帧或绘制预览失败", error)
        }
    }

    /** 可从任意线程调用；正常情况下会等相机打开/关闭回调结束，再在 GL 线程释放资源。 */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        shouldCapture.set(false)
        capturing = false
        if (!glHandler.post { beginRelease() }) {
            Log.e(TAG, "GL 线程已停止，无法执行资源释放")
        }
    }

    private fun beginRelease() {
        sessionGeneration++
        closeCaptureSession()
        cameraDevice?.let { device ->
            if (!cameraClosing) closeCameraDevice(device)
        }
        finishReleaseIfReady()
    }

    private fun closeCaptureSession() {
        val session = captureSession ?: return
        captureSession = null
        cleanupStep("停止重复请求") { session.stopRepeating() }
        cleanupStep("取消待处理请求") { session.abortCaptures() }
        cleanupStep("关闭捕获会话") { session.close() }
    }

    private fun closeCameraDevice(device: CameraDevice) {
        if (cameraDevice === device) cameraDevice = null
        if (cameraClosing) return
        cameraClosing = true
        try {
            device.close()
        } catch (error: Exception) {
            cameraClosing = false
            Log.w(TAG, "关闭摄像头失败", error)
            finishReleaseIfReady()
        }
    }

    private fun finishReleaseIfReady() {
        if (!released.get() || cameraOpening || cameraClosing || cleanupDone) return
        cleanupDone = true
        try {
            closeCaptureSession()
            cleanupStep("释放相机输入 Surface") { cameraInputSurface?.release() }
            cameraInputSurface = null
            cleanupStep("移除帧监听") { surfaceTexture?.setOnFrameAvailableListener(null) }
            cleanupStep("释放 SurfaceTexture") { surfaceTexture?.release() }
            surfaceTexture = null

            if (eglDisplay != EGL14.EGL_NO_DISPLAY &&
                eglContext != EGL14.EGL_NO_CONTEXT &&
                pbufferSurface != EGL14.EGL_NO_SURFACE
            ) {
                cleanupEglStep("清理前恢复 Pbuffer 上下文") {
                    EGL14.eglMakeCurrent(
                        eglDisplay, pbufferSurface, pbufferSurface, eglContext,
                    )
                }
            }

            cleanupStep("释放 OES 着色器") { oesDrawer.release() }
            if (oesTextureId != 0) {
                cleanupStep("删除 OES 纹理") {
                    GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                }
                oesTextureId = 0
            }

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                cleanupEglStep("解除 EGL 上下文") {
                    EGL14.eglMakeCurrent(
                        eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                }
                cleanupStep("销毁预览窗口") { destroyPreviewSurface() }
                if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                    cleanupEglStep("销毁 Pbuffer") {
                        EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                    }
                    pbufferSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    cleanupEglStep("销毁 EGL 上下文") {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                    }
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                cleanupEglStep("终止 EGL Display") { EGL14.eglTerminate(eglDisplay) }
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
            eglConfig = null
            cleanupEglStep("释放线程 EGL 状态") { EGL14.eglReleaseThread() }
        } finally {
            frameListener = null
            releaseComplete = true
            val listener = releaseListener
            releaseListener = null
            // 错误通知先于释放通知入队，避免关闭时吞掉最后一次错误。
            mainHandler.post {
                errorListener = null
                listener?.invoke()
            }
            glThread.quitSafely()
            Log.i(TAG, "CameraOesEngine 已释放")
        }
    }

    private fun destroyPreviewSurface() {
        if (previewEglSurface == EGL14.EGL_NO_SURFACE) {
            previewSourceSurface = null
            return
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY &&
            !EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
        ) {
            Log.w(TAG, "销毁预览窗口失败，EGL error=" + eglError())
        }
        previewEglSurface = EGL14.EGL_NO_SURFACE
        previewSourceSurface = null
    }

    private fun checkEgl(success: Boolean, operation: String) {
        check(success) { "$operation 失败，EGL error=" + eglError() }
    }

    private fun eglError(): String = "0x" + EGL14.eglGetError().toString(16)

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "$operation 失败，GL error=0x" + error.toString(16)
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

    private fun fail(message: String, cause: Throwable? = null) {
        if (!released.get()) reportError(message, cause)
        release()
    }

    private fun reportError(message: String, cause: Throwable? = null) {
        Log.e(TAG, message, cause)
        val listener = errorListener
        if (listener != null) {
            mainHandler.post {
                if (errorListener === listener) listener.onError(message, cause)
            }
        }
    }

    private inline fun cleanupStep(name: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            Log.w(TAG, "$name 失败", error)
        }
    }

    private inline fun cleanupEglStep(name: String, action: () -> Boolean) {
        try {
            if (!action()) Log.w(TAG, "$name 失败，EGL error=" + eglError())
        } catch (error: Exception) {
            Log.w(TAG, "$name 失败", error)
        }
    }

    private companion object {
        private const val TAG = "CameraOesEngine"
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
        private const val FRAME_LOG_INTERVAL = 60L
    }
}
