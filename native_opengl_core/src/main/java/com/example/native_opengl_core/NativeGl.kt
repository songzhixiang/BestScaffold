package com.example.native_opengl_core

import android.graphics.Bitmap

internal object NativeGl {
    init {
        System.loadLibrary("native_opengl_core")
    }

    external fun createOesDrawer(): Long
    external fun drawOes(
        handle: Long,
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
    )
    external fun destroyOesDrawer(handle: Long)

    external fun createBitmapDrawer(bitmap: Bitmap): Long
    external fun drawBitmap(handle: Long)
    external fun destroyBitmapDrawer(handle: Long)

    external fun createTexture2dDrawer(): Long
    external fun drawTexture2d(handle: Long, textureId: Int)
    external fun destroyTexture2dDrawer(handle: Long)

    external fun createFbo(width: Int, height: Int): Long
    external fun fboTextureId(handle: Long): Int
    external fun bindFbo(handle: Long)
    external fun unbindFbo()
    external fun destroyFbo(handle: Long)
}
