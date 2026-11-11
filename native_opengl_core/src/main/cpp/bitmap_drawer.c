#include "gl_common.h"

#include <android/bitmap.h>
#include <stdlib.h>

typedef struct {
    EGLContext context;
    QuadDrawer quad;
    GLuint texture;
} BitmapDrawer;

static bool upload_sticker(JNIEnv *env, GLuint *texture, jobject bitmap) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.stride % 4 != 0) {
        throw_state(env, "Sticker must be an RGBA_8888 bitmap");
        return false;
    }
    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throw_state(env, "Could not lock sticker pixels");
        return false;
    }
    bind_new_texture(texture);
    GLint previous_row_length;
    GLint previous_alignment;
    glGetIntegerv(GL_UNPACK_ROW_LENGTH, &previous_row_length);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &previous_alignment);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, (GLint)(info.stride / 4));
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei)info.width, (GLsizei)info.height,
                 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glPixelStorei(GL_UNPACK_ALIGNMENT, previous_alignment);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, previous_row_length);
    glBindTexture(GL_TEXTURE_2D, 0);
    AndroidBitmap_unlockPixels(env, bitmap);
    return check_gl(env, "Uploading the sticker");
}

static void destroy_bitmap_drawer(BitmapDrawer *drawer) {
    if (eglGetCurrentContext() == drawer->context) {
        glDeleteTextures(1, &drawer->texture);
        destroy_quad(&drawer->quad);
    }
    free(drawer);
}

JNIEXPORT jlong JNICALL
Java_com_example_native_1opengl_1core_NativeGl_createBitmapDrawer(
    JNIEnv *env, jobject self, jobject bitmap) {
    (void)self;
    EGLContext context = require_current_context(env);
    if (context == EGL_NO_CONTEXT) {
        return 0;
    }
    BitmapDrawer *drawer = calloc(1, sizeof(*drawer));
    if (drawer == NULL) {
        throw_state(env, "Could not allocate bitmap drawer");
        return 0;
    }
    drawer->context = context;
    glActiveTexture(GL_TEXTURE0);
    if (!init_quad(env, &drawer->quad, GL_TRUE) || !upload_sticker(env, &drawer->texture, bitmap)) {
        destroy_bitmap_drawer(drawer);
        return 0;
    }
    return (jlong)(intptr_t)drawer;
}

JNIEXPORT void JNICALL
Java_com_example_native_1opengl_1core_NativeGl_drawBitmap(JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    const BitmapDrawer *drawer = (const BitmapDrawer *)(intptr_t)handle;
    draw_quad(&drawer->quad, drawer->texture);
}

JNIEXPORT void JNICALL
Java_com_example_native_1opengl_1core_NativeGl_destroyBitmapDrawer(
    JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    destroy_bitmap_drawer((BitmapDrawer *)(intptr_t)handle);
}
