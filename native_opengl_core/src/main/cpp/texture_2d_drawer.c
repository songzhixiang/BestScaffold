#include "gl_common.h"

#include <stdlib.h>

typedef struct {
    EGLContext context;
    QuadDrawer quad;
} Texture2dDrawer;

static void destroy_texture_2d_drawer(Texture2dDrawer *drawer) {
    if (eglGetCurrentContext() == drawer->context) {
        destroy_quad(&drawer->quad);
    }
    free(drawer);
}

JNIEXPORT jlong JNICALL
Java_com_example_native_1opengl_1core_NativeGl_createTexture2dDrawer(JNIEnv *env, jobject self) {
    (void)self;
    EGLContext context = require_current_context(env);
    if (context == EGL_NO_CONTEXT) {
        return 0;
    }
    Texture2dDrawer *drawer = calloc(1, sizeof(*drawer));
    if (drawer == NULL) {
        throw_state(env, "Could not allocate 2D texture drawer");
        return 0;
    }
    drawer->context = context;
    if (!init_quad(env, &drawer->quad, GL_FALSE)) {
        destroy_texture_2d_drawer(drawer);
        return 0;
    }
    return (jlong)(intptr_t)drawer;
}

JNIEXPORT void JNICALL
Java_com_example_native_1opengl_1core_NativeGl_drawTexture2d(
    JNIEnv *env, jobject self, jlong handle, jint texture) {
    (void)self;
    const Texture2dDrawer *drawer = (const Texture2dDrawer *)(intptr_t)handle;
    draw_quad(&drawer->quad, (GLuint)texture);
    check_gl(env, "Drawing the 2D texture");
}

JNIEXPORT void JNICALL
Java_com_example_native_1opengl_1core_NativeGl_destroyTexture2dDrawer(
    JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    destroy_texture_2d_drawer((Texture2dDrawer *)(intptr_t)handle);
}
