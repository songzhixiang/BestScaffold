#include "gl_common.h"

#include <stdio.h>
#include <stdlib.h>

typedef struct {
    EGLContext context;
    GLsizei width;
    GLsizei height;
    GLuint framebuffer;
    GLuint color_texture;
} FboDrawer;

static bool init_framebuffer(JNIEnv *env, FboDrawer *drawer) {
    bind_new_texture(&drawer->color_texture);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, drawer->width, drawer->height);
    glBindTexture(GL_TEXTURE_2D, 0);
    glGenFramebuffers(1, &drawer->framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, drawer->framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           drawer->color_texture, 0);
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        char message[96];
        snprintf(message, sizeof(message), "Framebuffer is incomplete (0x%x)", status);
        throw_state(env, message);
        return false;
    }
    return check_gl(env, "Creating the framebuffer");
}

static void destroy_fbo(FboDrawer *drawer) {
    if (eglGetCurrentContext() == drawer->context) {
        glDeleteFramebuffers(1, &drawer->framebuffer);
        glDeleteTextures(1, &drawer->color_texture);
    }
    free(drawer);
}

JNIEXPORT jlong JNICALL
Java_com_example_native_1opengl_1core_NativeGl_createFbo(
    JNIEnv *env, jobject self, jint width, jint height) {
    (void)self;
    EGLContext context = require_current_context(env);
    if (context == EGL_NO_CONTEXT) {
        return 0;
    }
    FboDrawer *drawer = calloc(1, sizeof(*drawer));
    if (drawer == NULL) {
        throw_state(env, "Could not allocate FBO");
        return 0;
    }
    drawer->context = context;
    drawer->width = width;
    drawer->height = height;
    glActiveTexture(GL_TEXTURE0);
    if (!init_framebuffer(env, drawer)) {
        destroy_fbo(drawer);
        return 0;
    }
    return (jlong)(intptr_t)drawer;
}

JNIEXPORT jint JNICALL
Java_com_example_native_1opengl_1core_NativeGl_fboTextureId(
    JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    const FboDrawer *drawer = (const FboDrawer *)(intptr_t)handle;
    return (jint)drawer->color_texture;
}

JNIEXPORT void JNICALL
Java_com_example_native_1opengl_1core_NativeGl_bindFbo(JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    const FboDrawer *drawer = (const FboDrawer *)(intptr_t)handle;
    glBindFramebuffer(GL_FRAMEBUFFER, drawer->framebuffer);
    glViewport(0, 0, drawer->width, drawer->height);
}

JNIEXPORT void JNICALL
Java_com_example_native_1opengl_1core_NativeGl_unbindFbo(JNIEnv *env, jobject self) {
    (void)env;
    (void)self;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

JNIEXPORT void JNICALL
Java_com_example_native_1opengl_1core_NativeGl_destroyFbo(JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    destroy_fbo((FboDrawer *)(intptr_t)handle);
}
