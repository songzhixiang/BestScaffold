// Combined implementation for comparison with the four independent drawers.
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/bitmap.h>
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
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

static const char quad_vertex_shader[] =
    "#version 300 es\n"
    "layout(location = 0) in vec4 aPosition;\n"
    "layout(location = 1) in vec2 aTexCoord;\n"
    "out vec2 vTexCoord;\n"
    "void main() {\n"
    "    gl_Position = aPosition;\n"
    "    vTexCoord = aTexCoord;\n"
    "}\n";

static const char quad_fragment_shader[] =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec2 vTexCoord;\n"
    "layout(location = 0) out vec4 fragColor;\n"
    "uniform sampler2D uTexture;\n"
    "uniform bool uFlipY;\n"
    "void main() {\n"
    "    vec2 uv = uFlipY ? vec2(vTexCoord.x, 1.0 - vTexCoord.y) : vTexCoord;\n"
    "    fragColor = texture(uTexture, uv);\n"
    "}\n";

// Interleaved x, y, u, v. The camera UVs retain the existing 90 degree rotation.
static const GLfloat camera_vertices[] = {
    -1.f, -1.f, 0.f, 1.f,
     1.f, -1.f, 0.f, 0.f,
    -1.f,  1.f, 1.f, 1.f,
     1.f,  1.f, 1.f, 0.f,
};

static const GLfloat quad_vertices[] = {
    -1.f, -1.f, 0.f, 0.f,
     1.f, -1.f, 1.f, 0.f,
    -1.f,  1.f, 0.f, 1.f,
     1.f,  1.f, 1.f, 1.f,
};

typedef struct {
    GLuint vao;
    GLuint vbo;
} Mesh;

typedef struct {
    EGLContext context;
    GLsizei width;
    GLsizei height;
    GLuint camera_program;
    GLint mvp;
    GLint st_matrix;
    GLint user_matrix;
    GLint swirl_enabled;
    GLint swirl_center;
    GLint swirl_radius;
    GLint swirl_strength;
    GLint aspect_ratio;
    GLuint quad_program;
    GLint flip_y;
    Mesh camera_mesh;
    Mesh quad_mesh;
    GLuint framebuffer;
    GLuint color_texture;
    GLuint sticker_texture;
} CameraRenderer;

static void throw_state(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) {
        return;
    }
    jclass exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (exception != NULL) {
        (*env)->ThrowNew(env, exception, message);
        (*env)->DeleteLocalRef(env, exception);
    }
}

static bool check_gl(JNIEnv *env, const char *operation) {
    GLenum error = glGetError();
    if (error == GL_NO_ERROR) {
        return true;
    }
    char message[128];
    snprintf(message, sizeof(message), "%s failed (GL error 0x%x)", operation, error);
    throw_state(env, message);
    return false;
}

static GLuint compile_shader(JNIEnv *env, GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) {
        throw_state(env, "glCreateShader failed");
        return 0;
    }
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);

    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log[1024] = {0};
        glGetShaderInfoLog(shader, sizeof(log), NULL, log);
        char message[1152];
        snprintf(message, sizeof(message), "%s shader compilation failed: %s",
                 type == GL_VERTEX_SHADER ? "Vertex" : "Fragment", log);
        glDeleteShader(shader);
        throw_state(env, message);
        return 0;
    }
    return shader;
}

static GLuint create_program(JNIEnv *env, const char *vertex_source, const char *fragment_source) {
    //创建顶点着色器
    GLuint vertex = compile_shader(env, GL_VERTEX_SHADER, vertex_source);
    if (vertex == 0) {
        return 0;
    }
    //创建片元着色器
    GLuint fragment = compile_shader(env, GL_FRAGMENT_SHADER, fragment_source);
    if (fragment == 0) {
        glDeleteShader(vertex);
        return 0;
    }
    //创建程序对象
    GLuint program = glCreateProgram();
    if (program != 0) {
        //附加着色器
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        //链接程序
        glLinkProgram(program);
    }
    glDeleteShader(vertex);
    glDeleteShader(fragment);

    if (program == 0) {
        throw_state(env, "glCreateProgram failed");
        return 0;
    }
    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[1024] = {0};
        glGetProgramInfoLog(program, sizeof(log), NULL, log);
        char message[1152];
        snprintf(message, sizeof(message), "Program linking failed: %s", log);
        glDeleteProgram(program);
        throw_state(env, message);
        return 0;
    }
    // Active sampler uniforms start at zero after linking, so both use GL_TEXTURE0.
    return program;
}

static void init_mesh(Mesh *mesh, const GLfloat *vertices, GLsizeiptr size) {
    glGenVertexArrays(1, &mesh->vao);
    glGenBuffers(1, &mesh->vbo);
    glBindVertexArray(mesh->vao);
    glBindBuffer(GL_ARRAY_BUFFER, mesh->vbo);
    glBufferData(GL_ARRAY_BUFFER, size, vertices, GL_STATIC_DRAW);

    const GLsizei stride = 4 * sizeof(GLfloat);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, stride, NULL);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, stride,
                          (const void *)(uintptr_t)(2 * sizeof(GLfloat)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

static void bind_new_texture(GLuint *texture) {
    glGenTextures(1, texture);
    glBindTexture(GL_TEXTURE_2D, *texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

static bool init_framebuffer(JNIEnv *env, CameraRenderer *renderer) {
    bind_new_texture(&renderer->color_texture);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, renderer->width, renderer->height);
    glBindTexture(GL_TEXTURE_2D, 0);
    glGenFramebuffers(1, &renderer->framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, renderer->framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           renderer->color_texture, 0);
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

static void destroy_renderer(CameraRenderer *renderer) {
    if (eglGetCurrentContext() == renderer->context) {
        glDeleteFramebuffers(1, &renderer->framebuffer);
        glDeleteTextures(1, &renderer->color_texture);
        glDeleteTextures(1, &renderer->sticker_texture);
        glDeleteVertexArrays(1, &renderer->camera_mesh.vao);
        glDeleteVertexArrays(1, &renderer->quad_mesh.vao);
        glDeleteBuffers(1, &renderer->camera_mesh.vbo);
        glDeleteBuffers(1, &renderer->quad_mesh.vbo);
        glDeleteProgram(renderer->camera_program);
        glDeleteProgram(renderer->quad_program);
    }
    free(renderer);
}

JNIEXPORT jlong JNICALL
Java_com_example_native_1opengl_1core_combined_NativeCameraRenderer_nativeCreate(
    JNIEnv *env, jobject self, jint width, jint height, jobject sticker) {
    (void)self;
    EGLContext context = eglGetCurrentContext();
    if (context == EGL_NO_CONTEXT) {
        throw_state(env, "Native camera renderer requires a current EGL context");
        return 0;
    }
    CameraRenderer *renderer = calloc(1, sizeof(*renderer));
    if (renderer == NULL) {
        throw_state(env, "Could not allocate native camera renderer");
        return 0;
    }
    renderer->context = context;
    renderer->width = width;
    renderer->height = height;
    renderer->camera_program = create_program(env, camera_vertex_shader, camera_fragment_shader);
    if (renderer->camera_program == 0) {
        goto failure;
    }
    renderer->quad_program = create_program(env, quad_vertex_shader, quad_fragment_shader);
    if (renderer->quad_program == 0) {
        goto failure;
    }

    GLuint camera = renderer->camera_program;
    renderer->mvp = glGetUniformLocation(camera, "uMvpMatrix");
    renderer->st_matrix = glGetUniformLocation(camera, "uSTMatrix");
    renderer->user_matrix = glGetUniformLocation(camera, "uUserTextureMatrix");
    renderer->swirl_enabled = glGetUniformLocation(camera, "uSwirlEnabled");
    renderer->swirl_center = glGetUniformLocation(camera, "uSwirlCenter");
    renderer->swirl_radius = glGetUniformLocation(camera, "uSwirlRadius");
    renderer->swirl_strength = glGetUniformLocation(camera, "uSwirlStrength");
    renderer->aspect_ratio = glGetUniformLocation(camera, "uTextureAspectRatio");
    renderer->flip_y = glGetUniformLocation(renderer->quad_program, "uFlipY");

    init_mesh(&renderer->camera_mesh, camera_vertices, sizeof(camera_vertices));
    init_mesh(&renderer->quad_mesh, quad_vertices, sizeof(quad_vertices));
    if (!check_gl(env, "Creating vertex arrays")) {
        goto failure;
    }
    glActiveTexture(GL_TEXTURE0);
    if (!init_framebuffer(env, renderer) || !upload_sticker(env, &renderer->sticker_texture, sticker)) {
        goto failure;
    }
    return (jlong)(intptr_t)renderer;

failure:
    destroy_renderer(renderer);
    return 0;
}

static void draw_quad(const CameraRenderer *renderer, GLuint texture, GLboolean flip_y) {
    glUseProgram(renderer->quad_program);
    glBindTexture(GL_TEXTURE_2D, texture);
    glUniform1i(renderer->flip_y, flip_y);
    glBindVertexArray(renderer->quad_mesh.vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);
}

JNIEXPORT void JNICALL
Java_com_example_native_1opengl_1core_combined_NativeCameraRenderer_nativeCompose(
    JNIEnv *env, jobject self, jlong handle, jint oes_texture,
    jfloatArray st_matrix, jfloatArray user_matrix, jfloatArray mvp,
    jboolean swirl_enabled, jfloat center_x, jfloat center_y, jfloat radius,
    jfloat strength, jfloat aspect_ratio) {
    (void)self;
    CameraRenderer *renderer = (CameraRenderer *)(intptr_t)handle;
    jfloatArray matrices[] = {st_matrix, user_matrix, mvp};
    jfloat values[3][16];
    for (int i = 0; i < 3; ++i) {
        (*env)->GetFloatArrayRegion(env, matrices[i], 0, 16, values[i]);
        if ((*env)->ExceptionCheck(env)) {
            return;
        }
    }

    // Pass 1. Draw the camera and the sticker to the same offscreen texture.
    glBindFramebuffer(GL_FRAMEBUFFER, renderer->framebuffer);
    glViewport(0, 0, renderer->width, renderer->height);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(renderer->camera_program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, (GLuint)oes_texture);
    glUniformMatrix4fv(renderer->st_matrix, 1, GL_FALSE, values[0]);
    glUniformMatrix4fv(renderer->user_matrix, 1, GL_FALSE, values[1]);
    glUniformMatrix4fv(renderer->mvp, 1, GL_FALSE, values[2]);
    glUniform1i(renderer->swirl_enabled, swirl_enabled);
    glUniform2f(renderer->swirl_center, center_x, center_y);
    glUniform1f(renderer->swirl_radius, radius);
    glUniform1f(renderer->swirl_strength, strength);
    glUniform1f(renderer->aspect_ratio, aspect_ratio);
    glBindVertexArray(renderer->camera_mesh.vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    draw_quad(renderer, renderer->sticker_texture, GL_TRUE);
    glDisable(GL_BLEND);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    check_gl(env, "Compositing the camera frame");
}

JNIEXPORT void JNICALL
Java_com_example_native_1opengl_1core_combined_NativeCameraRenderer_nativeDrawOutput(
    JNIEnv *env, jobject self, jlong handle, jint width, jint height) {
    (void)self;
    const CameraRenderer *renderer = (const CameraRenderer *)(intptr_t)handle;

    // Pass 2. Framebuffer zero belongs to the EGL draw surface selected by Kotlin.
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, width, height);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glActiveTexture(GL_TEXTURE0);
    draw_quad(renderer, renderer->color_texture, GL_FALSE);
    check_gl(env, "Drawing the camera output");
}

JNIEXPORT void JNICALL
Java_com_example_native_1opengl_1core_combined_NativeCameraRenderer_nativeDestroy(
    JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    destroy_renderer((CameraRenderer *)(intptr_t)handle);
}
