package com.example.native_opengl_core

/** Draws a camera OES texture into the current framebuffer on the GL thread. */
class NativeOesDrawer {
    private var handle = 0L

    fun init() {
        check(handle == 0L) { "OES drawer is already initialized" }
        handle = NativeGl.createOesDrawer()
    }

    fun draw(
        textureId: Int,
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
        check(handle != 0L) { "OES drawer is not initialized" }
        NativeGl.drawOes(
            handle, textureId, textureMatrix, userTextureMatrix, mvpMatrix,
            swirlEnabled, swirlCenterX, swirlCenterY, swirlRadius, swirlStrength, textureAspectRatio,
        )
    }

    fun release() {
        if (handle == 0L) return
        NativeGl.destroyOesDrawer(handle)
        handle = 0L
    }
}
