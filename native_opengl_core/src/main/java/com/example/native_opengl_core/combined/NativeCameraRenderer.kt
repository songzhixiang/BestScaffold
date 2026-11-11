package com.example.native_opengl_core.combined

import android.graphics.Bitmap

/** Owns both passes. All calls use the GL thread and the same current OpenGL ES context. */
class NativeCameraRenderer {
    private var handle = 0L

    fun init(width: Int, height: Int, sticker: Bitmap) {
        check(handle == 0L) { "Native camera renderer is already initialized" }
        require(width > 0 && height > 0)
        handle = nativeCreate(width, height, sticker)
    }

    fun compose(
        oesTextureId: Int,
        textureMatrix: FloatArray,
        userTextureMatrix: FloatArray,
        mvpMatrix: FloatArray,
        swirlEnabled: Boolean,
        swirlCenterX: Float,
        swirlCenterY: Float,
        swirlRadius: Float,
        swirlStrength: Float,
        textureAspectRatio: Float,
    ) {
        check(handle != 0L) { "Native camera renderer is not initialized" }
        nativeCompose(
            handle, oesTextureId, textureMatrix, userTextureMatrix, mvpMatrix,
            swirlEnabled, swirlCenterX, swirlCenterY, swirlRadius, swirlStrength, textureAspectRatio,
        )
    }

    fun drawOutput(width: Int, height: Int) {
        check(handle != 0L) { "Native camera renderer is not initialized" }
        require(width > 0 && height > 0)
        nativeDrawOutput(handle, width, height)
    }

    fun release() {
        if (handle == 0L) return
        nativeDestroy(handle)
        handle = 0L
    }

    private external fun nativeCreate(width: Int, height: Int, sticker: Bitmap): Long

    private external fun nativeCompose(
        handle: Long,
        oesTextureId: Int,
        textureMatrix: FloatArray,
        userTextureMatrix: FloatArray,
        mvpMatrix: FloatArray,
        swirlEnabled: Boolean,
        swirlCenterX: Float,
        swirlCenterY: Float,
        swirlRadius: Float,
        swirlStrength: Float,
        textureAspectRatio: Float,
    )

    private external fun nativeDrawOutput(handle: Long, width: Int, height: Int)
    private external fun nativeDestroy(handle: Long)

    private companion object {
        init {
            System.loadLibrary("native_opengl_core")
        }
    }
}
