package com.example.camera_core.opengl

import android.opengl.GLES20
import android.util.Log

class FboDrawer {

    var fboId = 0
    var fboTextureId = 0
    var width = 0
    var height = 0


    fun init(width: Int, height: Int) {
        this.width = width
        this.height = height
        // 1. 创建Fbo
        var fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        fboId = fbo[0]
        // 2. 绑定 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        // 3. 创建fbo纹理
        var fboTexture = IntArray(1)
        GLES20.glGenTextures(1, fboTexture, 0)
        fboTextureId = fboTexture[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        // 3.1 设置纹理参数
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // 3.2 分配纹理存储（但不填充数据）
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null  // 不填充数据
        )
        // 4. 将纹理附加到 FBO
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,  // 颜色附件 0
            GLES20.GL_TEXTURE_2D,
            fboTexture[0],
            0
        )
        // 5. 检查 FBO 完整性
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO 不完整: $status")
        }
        // 6. 解绑 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun bind(){
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, width, height)
    }

    fun unbind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    fun release() {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0);
            fboId = 0;
        }
        if (fboTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0);
            fboTextureId = 0;
        }
    }

    companion object {
        val TAG = "FboDrawer"
    }
}