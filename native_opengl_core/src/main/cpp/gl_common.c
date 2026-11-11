#include "gl_common.h"

#include <stdio.h>

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

static const GLfloat quad_vertices[] = {
    -1.f, -1.f, 0.f, 0.f,
     1.f, -1.f, 1.f, 0.f,
    -1.f,  1.f, 0.f, 1.f,
     1.f,  1.f, 1.f, 1.f,
};

void throw_state(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) {
        return;
    }
    jclass exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (exception != NULL) {
        (*env)->ThrowNew(env, exception, message);
        (*env)->DeleteLocalRef(env, exception);
    }
}

bool check_gl(JNIEnv *env, const char *operation) {
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

GLuint create_program(JNIEnv *env, const char *vertex_source, const char *fragment_source) {
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

void init_mesh(Mesh *mesh, const GLfloat *vertices, GLsizeiptr size) {
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

void bind_new_texture(GLuint *texture) {
    glGenTextures(1, texture);
    glBindTexture(GL_TEXTURE_2D, *texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

EGLContext require_current_context(JNIEnv *env) {
    EGLContext context = eglGetCurrentContext();
    if (context == EGL_NO_CONTEXT) {
        throw_state(env, "A current OpenGL ES context is required");
    }
    return context;
}

void draw_mesh(const Mesh *mesh) {
    glBindVertexArray(mesh->vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
}

void destroy_mesh(const Mesh *mesh) {
    glDeleteVertexArrays(1, &mesh->vao);
    glDeleteBuffers(1, &mesh->vbo);
}

bool init_quad(JNIEnv *env, QuadDrawer *drawer, GLboolean flip_y) {
    drawer->program = create_program(env, quad_vertex_shader, quad_fragment_shader);
    if (drawer->program == 0) {
        return false;
    }
    glUseProgram(drawer->program);
    glUniform1i(glGetUniformLocation(drawer->program, "uFlipY"), flip_y);
    glUseProgram(0);
    init_mesh(&drawer->mesh, quad_vertices, sizeof(quad_vertices));
    return check_gl(env, "Initializing the 2D drawer");
}

void draw_quad(const QuadDrawer *drawer, GLuint texture) {
    glUseProgram(drawer->program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    draw_mesh(&drawer->mesh);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);
}

void destroy_quad(const QuadDrawer *drawer) {
    destroy_mesh(&drawer->mesh);
    glDeleteProgram(drawer->program);
}
