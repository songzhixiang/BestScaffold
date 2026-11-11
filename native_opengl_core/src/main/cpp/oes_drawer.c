#include "gl_common.h"

#include <GLES2/gl2ext.h>
#include <stdlib.h>

static const char camera_vertex_shader[] =
    "#version 300 es\n"
    "layout(location = 0) in vec4 aPosition;\n"
    "layout(location = 1) in vec4 aTexCoord;\n"
    "uniform mat4 uMvpMatrix;\n"
    "uniform mat4 uSTMatrix;\n"
    "uniform mat4 uUserTextureMatrix;\n"
    "out vec2 vTexCoord;\n"
    "void main() {\n"
    "    gl_Position = uMvpMatrix * aPosition;\n"
    "    vTexCoord = (uSTMatrix * uUserTextureMatrix * aTexCoord).xy;\n"
    "}\n";

static const char camera_fragment_shader[] =
    "#version 300 es\n"
    "#extension GL_OES_EGL_image_external_essl3 : require\n"
    "precision mediump float;\n"
    "in vec2 vTexCoord;\n"
    "layout(location = 0) out vec4 fragColor;\n"
    "uniform samplerExternalOES uTexture;\n"
    "uniform bool uSwirlEnabled;\n"
    "uniform vec2 uSwirlCenter;\n"
    "uniform float uSwirlRadius;\n"
    "uniform float uSwirlStrength;\n"
    "uniform float uTextureAspectRatio;\n"
    "void main() {\n"
    "    vec2 uv = vTexCoord;\n"
    "    if (uSwirlEnabled) {\n"
    "        vec2 delta = uv - uSwirlCenter;\n"
    "        delta.x *= uTextureAspectRatio;\n"
    "        float distanceToCenter = length(delta);\n"
    "        if (distanceToCenter < uSwirlRadius) {\n"
    "            float falloff = (uSwirlRadius - distanceToCenter) / uSwirlRadius;\n"
    "            float angle = uSwirlStrength * falloff * falloff;\n"
    "            float s = sin(angle);\n"
    "            float c = cos(angle);\n"
    "            delta = mat2(c, -s, s, c) * delta;\n"
    "            delta.x /= uTextureAspectRatio;\n"
    "            uv = uSwirlCenter + delta;\n"
    "        }\n"
    "    }\n"
    "    fragColor = texture(uTexture, clamp(uv, 0.0, 1.0));\n"
    "}\n";

// Interleaved x, y, u, v. The camera UVs retain the existing 90 degree rotation.
static const GLfloat camera_vertices[] = {
    -1.f, -1.f, 0.f, 1.f,
     1.f, -1.f, 0.f, 0.f,
    -1.f,  1.f, 1.f, 1.f,
     1.f,  1.f, 1.f, 0.f,
};

typedef struct {
    EGLContext context;
    GLuint program;
    Mesh mesh;
    GLint mvp;
    GLint st_matrix;
    GLint user_matrix;
    GLint swirl_enabled;
    GLint swirl_center;
    GLint swirl_radius;
    GLint swirl_strength;
    GLint aspect_ratio;
} OesDrawer;

static void destroy_oes_drawer(OesDrawer *drawer) {
    if (eglGetCurrentContext() == drawer->context) {
        destroy_mesh(&drawer->mesh);
        glDeleteProgram(drawer->program);
    }
    free(drawer);
}

JNIEXPORT jlong JNICALL
Java_com_example_native_1opengl_1core_NativeGl_createOesDrawer(JNIEnv *env, jobject self) {
    (void)self;
    EGLContext context = require_current_context(env);
    if (context == EGL_NO_CONTEXT) {
        return 0;
    }
    OesDrawer *drawer = calloc(1, sizeof(*drawer));
    if (drawer == NULL) {
        throw_state(env, "Could not allocate OES drawer");
        return 0;
    }
    drawer->context = context;
    drawer->program = create_program(env, camera_vertex_shader, camera_fragment_shader);
    if (drawer->program == 0) {
        destroy_oes_drawer(drawer);
        return 0;
    }

    GLuint program = drawer->program;
    drawer->mvp = glGetUniformLocation(program, "uMvpMatrix");
    drawer->st_matrix = glGetUniformLocation(program, "uSTMatrix");
    drawer->user_matrix = glGetUniformLocation(program, "uUserTextureMatrix");
    drawer->swirl_enabled = glGetUniformLocation(program, "uSwirlEnabled");
    drawer->swirl_center = glGetUniformLocation(program, "uSwirlCenter");
    drawer->swirl_radius = glGetUniformLocation(program, "uSwirlRadius");
    drawer->swirl_strength = glGetUniformLocation(program, "uSwirlStrength");
    drawer->aspect_ratio = glGetUniformLocation(program, "uTextureAspectRatio");
    init_mesh(&drawer->mesh, camera_vertices, sizeof(camera_vertices));
    if (!check_gl(env, "Initializing the OES drawer")) {
        destroy_oes_drawer(drawer);
        return 0;
    }
    return (jlong)(intptr_t)drawer;
}

JNIEXPORT void JNICALL
Java_com_example_native_1opengl_1core_NativeGl_drawOes(
    JNIEnv *env, jobject self, jlong handle, jint oes_texture,
    jfloatArray st_matrix, jfloatArray user_matrix, jfloatArray mvp,
    jboolean swirl_enabled, jfloat center_x, jfloat center_y, jfloat radius,
    jfloat strength, jfloat aspect_ratio) {
    (void)self;
    const OesDrawer *drawer = (const OesDrawer *)(intptr_t)handle;
    jfloatArray matrices[] = {st_matrix, user_matrix, mvp};
    jfloat values[3][16];
    for (int i = 0; i < 3; ++i) {
        (*env)->GetFloatArrayRegion(env, matrices[i], 0, 16, values[i]);
        if ((*env)->ExceptionCheck(env)) {
            return;
        }
    }

    glUseProgram(drawer->program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, (GLuint)oes_texture);
    glUniformMatrix4fv(drawer->st_matrix, 1, GL_FALSE, values[0]);
    glUniformMatrix4fv(drawer->user_matrix, 1, GL_FALSE, values[1]);
    glUniformMatrix4fv(drawer->mvp, 1, GL_FALSE, values[2]);
    glUniform1i(drawer->swirl_enabled, swirl_enabled);
    glUniform2f(drawer->swirl_center, center_x, center_y);
    glUniform1f(drawer->swirl_radius, radius);
    glUniform1f(drawer->swirl_strength, strength);
    glUniform1f(drawer->aspect_ratio, aspect_ratio);
    draw_mesh(&drawer->mesh);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
    glUseProgram(0);
    check_gl(env, "Drawing the OES texture");
}

JNIEXPORT void JNICALL
Java_com_example_native_1opengl_1core_NativeGl_destroyOesDrawer(
    JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    destroy_oes_drawer((OesDrawer *)(intptr_t)handle);
}
