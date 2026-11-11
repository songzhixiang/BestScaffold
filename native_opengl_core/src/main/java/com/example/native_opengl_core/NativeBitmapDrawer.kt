package com.example.native_opengl_core

import android.graphics.Bitmap

/** Owns the bitmap texture and draws it into the current framebuffer on the GL thread. */
class NativeBitmapDrawer {
    private var handle = 0L

    fun init(bitmap: Bitmap) {
        check(handle == 0L) { "Bitmap drawer is already initialized" }
        handle = NativeGl.createBitmapDrawer(bitmap)
    }

    fun draw() {
        check(handle != 0L) { "Bitmap drawer is not initialized" }
        NativeGl.drawBitmap(handle)
    }

    fun release() {
        if (handle == 0L) return
        NativeGl.destroyBitmapDrawer(handle)
        handle = 0L
    }
}
