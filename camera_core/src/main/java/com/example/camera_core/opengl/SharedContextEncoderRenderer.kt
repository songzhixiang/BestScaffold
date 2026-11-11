package com.example.camera_core.opengl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * 在独立线程和独立 EGLContext 中把共享的 GL_TEXTURE_2D 绘制到 MediaCodec Surface。
 *
 * 编码 Context 通过 eglCreateContext 的 share_context 参数加入主渲染 Context 的
 * share group，因此可以采样由主 Context 创建的 FBO 颜色纹理。编码 EGLSurface、
 * 默认 framebuffer 和 Texture2dDrawer 则由编码线程独占。
 */
internal class SharedContextEncoderRenderer {

    private val thread = HandlerThread("Encoder-GL-Thread").apply { start() }
    private val handler = Handler(thread.looper)

    // 以下成员只允许在 Encoder-GL-Thread 访问。
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var drawer: Texture2dDrawer? = null
    private var width = 0
    private var height = 0
    private var released = false

    /** 必须在主 EGLContext 仍有效时调用。 */
    fun attach(
        display: EGLDisplay,
        config: EGLConfig,
        sharedContext: EGLContext,
        surface: Surface,
        width: Int,
        height: Int,
    ) {
        require(display != EGL14.EGL_NO_DISPLAY) { "共享 EGLDisplay 无效" }
        require(sharedContext != EGL14.EGL_NO_CONTEXT) { "共享 EGLContext 无效" }
        require(surface.isValid) { "编码器输入 Surface 已失效" }
        require(width > 0 && height > 0) { "编码画面宽高必须大于 0" }

        runBlocking {
            check(!released) { "编码 GL 线程已经释放" }
            releaseEglResources()
            eglDisplay = display
            this.width = width
            this.height = height

            try {
                val contextAttributes = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION,
                    3,
                    EGL14.EGL_NONE,
                )
                eglContext = EGL14.eglCreateContext(
                    display,
                    config,
                    sharedContext,
                    contextAttributes,
                    0,
                )
                checkEgl(eglContext != EGL14.EGL_NO_CONTEXT, "eglCreateContext(共享编码上下文)")

                eglSurface = EGL14.eglCreateWindowSurface(
                    display,
                    config,
                    surface,
                    intArrayOf(EGL14.EGL_NONE),
                    0,
                )
                checkEgl(eglSurface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface(编码器)")
                makeCurrent()

                drawer = Texture2dDrawer().also { it.init() }
                checkGl("初始化编码线程 2D 纹理绘制器")
            } catch (error: Throwable) {
                releaseEglResources()
                throw error
            }
        }
    }

    /**
     * 主线程在调用前完成共享纹理的写入；本方法返回后编码 Context 已完成该纹理的读取。
     * 阻塞交接避免主 Context 在编码 Context 采样期间覆盖同一张 FBO 纹理。
     */
    fun drawFrame(textureId: Int, presentationTimeNs: Long) {
        require(textureId != 0) { "共享纹理 ID 不能为 0" }
        runBlocking {
            check(!released) { "编码 GL 线程已经释放" }
            check(eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE) {
                "编码 EGL 尚未挂载"
            }
            makeCurrent()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            checkNotNull(drawer) { "编码纹理绘制器尚未初始化" }.draw(textureId)
            checkEgl(
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs),
                "eglPresentationTimeANDROID(编码器)",
            )
            checkEgl(EGL14.eglSwapBuffers(eglDisplay, eglSurface), "eglSwapBuffers(编码器)")

            // 保证共享纹理的读取在返回前完成，主 Context 随后才会覆写该纹理。
            GLES20.glFinish()
            checkGl("完成编码帧")
        }
    }

    fun detach() {
        runBlocking {
            if (!released) releaseEglResources()
        }
    }

    fun release() {
        runBlocking {
            if (released) return@runBlocking
            releaseEglResources()
            released = true
            thread.quitSafely()
        }
    }

    private fun makeCurrent() {
        checkEgl(
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext),
            "eglMakeCurrent(编码器共享上下文)",
        )
    }

    private fun releaseEglResources() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY &&
            eglContext != EGL14.EGL_NO_CONTEXT &&
            eglSurface != EGL14.EGL_NO_SURFACE
        ) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            drawer?.release()
            drawer = null
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
        } else {
            drawer = null
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }
        eglSurface = EGL14.EGL_NO_SURFACE

        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
        }
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        width = 0
        height = 0
        EGL14.eglReleaseThread()
    }

    private fun checkEgl(success: Boolean, operation: String) {
        check(success) {
            "$operation 失败，EGL error=0x${EGL14.eglGetError().toString(16)}"
        }
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "$operation 失败，GL error=0x${error.toString(16)}"
        }
    }

    private fun <T> runBlocking(block: () -> T): T {
        if (Looper.myLooper() == thread.looper) return block()
        val task = FutureTask(block)
        check(handler.post(task)) { "编码 GL 线程已经停止" }
        return try {
            task.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
}
