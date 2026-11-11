#ifndef NATIVE_OPENGL_CORE_GL_COMMON_H
#define NATIVE_OPENGL_CORE_GL_COMMON_H

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>

typedef struct {
    GLuint vao;
    GLuint vbo;
} Mesh;

typedef struct {
    GLuint program;
    Mesh mesh;
} QuadDrawer;

void throw_state(JNIEnv *env, const char *message);
bool check_gl(JNIEnv *env, const char *operation);
EGLContext require_current_context(JNIEnv *env);
GLuint create_program(JNIEnv *env, const char *vertex_source, const char *fragment_source);
void init_mesh(Mesh *mesh, const GLfloat *vertices, GLsizeiptr size);
void draw_mesh(const Mesh *mesh);
void destroy_mesh(const Mesh *mesh);
void bind_new_texture(GLuint *texture);
bool init_quad(JNIEnv *env, QuadDrawer *drawer, GLboolean flip_y);
void draw_quad(const QuadDrawer *drawer, GLuint texture);
void destroy_quad(const QuadDrawer *drawer);

#endif
