package com.example.native_opengl_core

/** Draws a caller-owned 2D texture into the current framebuffer on the GL thread. */
class NativeTexture2dDrawer {
    private var handle = 0L

    fun init() {
        check(handle == 0L) { "2D texture drawer is already initialized" }
        handle = NativeGl.createTexture2dDrawer()
    }

    fun draw(textureId: Int) {
        check(handle != 0L) { "2D texture drawer is not initialized" }
        NativeGl.drawTexture2d(handle, textureId)
    }

    fun release() {
        if (handle == 0L) return
        NativeGl.destroyTexture2dDrawer(handle)
        handle = 0L
    }
}
