package com.example.camera_core.opengl;

import android.opengl.GLES20;

/**
 * Owns one framebuffer and the GL_TEXTURE_2D attached to its color attachment.
 * Every method must be called on the thread that owns the current EGL context.
 */
public final class FboHelper {
    private int fboId;
    private int fboTextureId;
    private int width;
    private int height;

    public void init(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("FBO width and height must be greater than zero");
        }

        release();
        this.width = width;
        this.height = height;

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        fboTextureId = textures[0];
        if (fboTextureId == 0) {
            resetSize();
            throw new IllegalStateException("Failed to create the FBO color texture");
        }

        int[] framebuffers = new int[1];
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId);
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                width,
                height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null
            );
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            );
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            );
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            );
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            );

            GLES20.glGenFramebuffers(1, framebuffers, 0);
            fboId = framebuffers[0];
            if (fboId == 0) {
                throw new IllegalStateException("Failed to create the framebuffer");
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                fboTextureId,
                0
            );

            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException(
                    "Framebuffer is incomplete, status=0x" + Integer.toHexString(status)
                );
            }
        } catch (RuntimeException error) {
            release();
            throw error;
        } finally {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }
    }

    /** Subsequent draw calls write into {@link #getFboTextureId()}. */
    public void bind() {
        if (fboId == 0) {
            throw new IllegalStateException("FBO has not been initialized");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glViewport(0, 0, width, height);
    }

    /** Restores drawing to the current EGL surface's default framebuffer. */
    public void unbind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    public int getFboId() {
        return fboId;
    }

    public int getFboTextureId() {
        return fboTextureId;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void release() {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{fboId}, 0);
            fboId = 0;
        }
        if (fboTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{fboTextureId}, 0);
            fboTextureId = 0;
        }
        resetSize();
    }

    private void resetSize() {
        width = 0;
        height = 0;
    }
}
