cmake_minimum_required(VERSION 3.22.1)
project(native_opengl_core LANGUAGES C)

add_library(native_opengl_core SHARED
    gl_common.c
    oes_drawer.c
    bitmap_drawer.c
    texture_2d_drawer.c
    fbo_drawer.c
    combined/camera_renderer.c
)
target_compile_features(native_opengl_core PRIVATE c_std_11)
target_compile_options(native_opengl_core PRIVATE -Wall -Wextra)
target_link_libraries(native_opengl_core PRIVATE EGL GLESv3 jnigraphics)
