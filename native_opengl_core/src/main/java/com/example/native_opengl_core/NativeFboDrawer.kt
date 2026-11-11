package com.example.native_opengl_core

/** Owns a framebuffer and its color texture in the current GL context. */
class NativeFboDrawer {
    private var handle = 0L

    var fboTextureId = 0
        private set

    fun init(width: Int, height: Int) {
        check(handle == 0L) { "FBO is already initialized" }
        require(width > 0 && height > 0)
        handle = NativeGl.createFbo(width, height)
        fboTextureId = NativeGl.fboTextureId(handle)
    }

    fun bind() {
        check(handle != 0L) { "FBO is not initialized" }
        NativeGl.bindFbo(handle)
    }

    fun unbind() {
        NativeGl.unbindFbo()
    }

    fun release() {
        if (handle == 0L) return
        NativeGl.destroyFbo(handle)
        handle = 0L
        fboTextureId = 0
    }
}
