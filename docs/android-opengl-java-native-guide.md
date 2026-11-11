# Android Java/Kotlin 与 Native OpenGL ES 开发指南

BestScaffold 使用 OpenGL ES 3.0 处理相机画面。相机帧经 `SurfaceTexture` 进入 OES 纹理，渲染器在 FBO 里合成相机画面、漩涡和贴纸，再把成品纹理画到预览和 MediaCodec 的输入 Surface。

## 1. 两种写法如何进入同一条渲染链路

```text
Java/Kotlin   GLES20 / GLES30 → Android 提供的 JNI 绑定 → OpenGL ES 驱动
Native        Kotlin → 项目的 JNI 入口 → C 绘制器 → OpenGL ES 驱动
```

Android 的 `GLES20` 和 `GLES30` 提供 Java API，Kotlin 可以直接调用。框架在 JNI 里把这些调用转发给底层 OpenGL ES 函数。例如 `GLES20.glDrawArrays()` 和 C 中的 `glDrawArrays()` 最终都通过当前线程上的 EGL Context 执行。项目请求的是 ES 3.0 Context，因此可以用 `GLES20` 调用共有函数，再用 `GLES30` 调用 VAO 等 ES 3.0 新增函数。 [Android GLES30 API](https://developer.android.com/reference/android/opengl/GLES30) · [AOSP 的 glDrawArrays 绑定](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/jni/android_opengl_GLES20.cpp#L1233-L1242)

Native 版用 CMake 编译 `libnative_opengl_core.so`。C 代码包含 `<GLES3/gl3.h>` 并链接 `GLESv3`。模块也链接了 `EGL` 和 `jnigraphics`，分别用于访问当前 EGL Context 和读取 Android Bitmap 像素。Kotlin 的 `NativeGl` 通过 `System.loadLibrary()` 加载库，`external` 方法进入对应的 C 函数。 [CMake 配置](../native_opengl_core/src/main/cpp/CMakeLists.txt) · [JNI 声明](../native_opengl_core/src/main/java/com/example/native_opengl_core/NativeGl.kt) · [Android NDK 图形 API](https://developer.android.com/ndk/guides/stable_apis#graphics)

## 2. 同一组 VAO 和 VBO 的写法

相机矩形的每个顶点有四个 `float`，排列为 `x, y, u, v`。VBO 保存顶点数据，VAO 保存属性布局。下面是简化后的初始化片段，两个版本都假设 shader 的位置属性在 `0`、UV 属性在 `1`。

**Java/Kotlin 层**

```kotlin
val byteCount = vertexData.size * Float.SIZE_BYTES
val vertexBuffer = ByteBuffer.allocateDirect(byteCount)
    .order(ByteOrder.nativeOrder())
    .asFloatBuffer()
    .put(vertexData)
    .apply { position(0) }

val vao = IntArray(1)
val vbo = IntArray(1)
GLES30.glGenVertexArrays(1, vao, 0)
GLES20.glGenBuffers(1, vbo, 0)

GLES30.glBindVertexArray(vao[0])
GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, byteCount, vertexBuffer, GLES20.GL_STATIC_DRAW)

val stride = 4 * Float.SIZE_BYTES
GLES20.glEnableVertexAttribArray(0)
GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, stride, 0)
GLES20.glEnableVertexAttribArray(1)
GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, stride, 2 * Float.SIZE_BYTES)

GLES30.glBindVertexArray(0)
GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
```

`vertexBuffer` 是按 `ByteOrder.nativeOrder()` 创建的直接 `FloatBuffer`。`glGenBuffers` 把 ID 写进 `IntArray`，`glVertexAttribPointer` 的最后一个 `Int` 是 VBO 内的字节偏移。项目中的 Kotlin 实现通过 `glGetAttribLocation` 查找属性位置。 [Kotlin OesDrawer](../camera_core/src/main/java/com/example/camera_core/opengl/OesDrawer.kt)

**Native C 层**

```c
GLuint vao = 0;
GLuint vbo = 0;
glGenVertexArrays(1, &vao);
glGenBuffers(1, &vbo);

glBindVertexArray(vao);
glBindBuffer(GL_ARRAY_BUFFER, vbo);
glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

const GLsizei stride = 4 * sizeof(GLfloat);
glEnableVertexAttribArray(0);
glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, stride, NULL);
glEnableVertexAttribArray(1);
glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, stride,
                      (const void *)(uintptr_t)(2 * sizeof(GLfloat)));

glBindVertexArray(0);
glBindBuffer(GL_ARRAY_BUFFER, 0);
```

这里的 `vertices` 是当前作用域内的 `GLfloat` 数组，`sizeof(vertices)` 得到整个数组的字节数。如果改成接收数组指针的函数，需要像项目的 `init_mesh` 一样单独传入长度。`&vbo` 让 GL 直接写入 C 变量。VBO 已绑定时，`glVertexAttribPointer` 最后一项虽然是指针类型，GL 会把它解释为 VBO 内的字节偏移。 [C 的 init_mesh](../native_opengl_core/src/main/cpp/gl_common.c) · [OpenGL ES 3.0 规范 §2.10.6](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf)

每帧的绘制动作在两端对应一致。绑定 program 和纹理，上传这一帧的 uniform，绑定 VAO，调用 `glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)`。GLSL 版本由 shader 自己声明。项目两边都使用 `#version 300 es`，相机 shader 通过 `GL_OES_EGL_image_external_essl3` 扩展采样 `samplerExternalOES`。 [C 的 OES shader 和绘制](../native_opengl_core/src/main/cpp/oes_drawer.c) · [Khronos OES 扩展](https://registry.khronos.org/OpenGL/extensions/OES/OES_EGL_image_external_essl3.txt)

## 3. 这个项目如何分配职责

| 原 Java/Kotlin 层组件 | Native 对应组件 | 管理的资源和动作 |
| --- | --- | --- |
| `OesDrawer` | `NativeOesDrawer` · [oes_drawer.c](../native_opengl_core/src/main/cpp/oes_drawer.c) | 管理相机 shader 和 VAO/VBO，采样调用方传入的 OES 纹理并绘制。 |
| `BitmapDrawer` | `NativeBitmapDrawer` · [bitmap_drawer.c](../native_opengl_core/src/main/cpp/bitmap_drawer.c) | 初始化时上传 Bitmap，持有贴纸纹理并绘制。 |
| `Texture2dDrawer` | `NativeTexture2dDrawer` · [texture_2d_drawer.c](../native_opengl_core/src/main/cpp/texture_2d_drawer.c) | 将调用方传入的普通 2D 纹理画到当前 framebuffer。 |
| `FboDrawer` | `NativeFboDrawer` · [fbo_drawer.c](../native_opengl_core/src/main/cpp/fbo_drawer.c) | 创建 FBO 和颜色纹理，绑定 FBO 并设置对应 viewport。 |

`CameraOesEngine` 在 GL 线程上更新 `SurfaceTexture`，拿到纹理矩阵后按顺序调用 `fboHelper.bind()`、`oesDrawer.draw()`、`bitmapDrawer.draw()` 和 `fboHelper.unbind()`。输出时，Kotlin 用 `eglMakeCurrent` 选中编码或预览 Surface，再通过 `texture2dDrawer.draw(fboHelper.fboTextureId)` 绘制。录制的 PTS 仍由 Kotlin 在编码 Surface 的 `eglSwapBuffers` 前提交。 [相机引擎](../camera_core/src/main/java/com/example/camera_core/view/CameraOesEngine.kt)

JNI 调用在发起它的 GL 线程上执行。C 绘制器和 Kotlin 可以在同一个当前 EGL Context 中使用同一组纹理 ID 和 GL 状态，所以 C 的 OES draw 能直接写入 Kotlin 刚选中的目标。创建、绘制和释放应遵守同一个上下文的资源生命周期。JNI 的 `Long` handle 指向 C 结构体，GL 纹理 ID 是另一种标识。 [EGL 规范 §3.7.3](https://registry.khronos.org/EGL/specs/eglspec.1.5.pdf) · [JNI 线程说明](https://developer.android.com/ndk/guides/jni-tips#javavm-and-jnienv)

## 4. 性能收益来自哪些地方

一次 `NativeOesDrawer.draw()` 进入 C 后，就能连续绑定 program 和纹理、设置 uniform、绑定 VAO 并绘制。这些 GL 调用在 C 内部完成，省去了对应的多次 Java GLES 包装调用。大量图层或高频状态更新时，可以按一遍渲染或一帧来设计 JNI 入口。当前版本将四个组件分别暴露给 Kotlin，便于逐个对照，也需要为绑定 FBO、绘制和输出分别跨过 JNI 边界。 [Android JNI 性能建议](https://developer.android.com/ndk/guides/jni-tips)

当前 C 版在初始化时上传静态顶点和贴纸，后续复用 GL 对象。每次 OES 绘制会用 `GetFloatArrayRegion` 把 ST、用户纹理矩阵和 MVP 复制到 C 的栈数组，共 `3 × 16 × 4 = 192` 字节。相机像素通过 OES 纹理参与渲染，JNI 接口传的是纹理 ID 和参数。若以后有原生解码或图像处理模块，动态像素数据可以留在 Native 内存或共享硬件缓冲中，再按需上传或导入 GPU。 [C 层矩阵传递](../native_opengl_core/src/main/cpp/oes_drawer.c) · [Android 硬件缓冲](https://developer.android.com/ndk/reference/group/a-hardware-buffer)

**GPU 开销取决于实际提交的工作。** 当 shader、分辨率、draw 次数、纹理采样和渲染目标相同，两种写法提交的是同类 GPU 工作。VAO/VBO 复用、减少全屏叠加和缩小特效覆盖区域，在两层都能实现。录制期间如果 GL 线程的长帧来自编码 `eglSwapBuffers` 等待，主要优化对象是输出队列和同步。当前的真机读回测试验证了绘制功能，Native 版的帧耗时收益需要 A/B 测量。 [Android 帧耗时分析](https://developer.android.com/agi/sys-trace/long)

做 A/B 时，固定同一设备、相机帧率、预览和编码尺寸、shader、图层覆盖范围及录制状态，并对齐两版的顶点缓冲和纹理配置。用 Perfetto 或 Android GPU Inspector 分别观察 GL 线程 CPU 活跃时间、GPU 工作量、两路 `eglSwapBuffers` 耗时，以及预览帧间隔的 P95 和掉帧数。围绕 `glDrawArrays` 计时主要反映 CPU 提交这次调用的耗时。 [Android 系统分析](https://developer.android.com/agi/sys-trace/system-profiler) · [CPU 和 GPU 帧耗时](https://developer.android.com/agi/sys-trace/long)

## 5. Native 层给扩展带来的空间

- **对接原生图像和媒体接口。** 需要引入 C/C++ 解码、图像处理或共享硬件缓冲时，可以直接在 C/C++ 层管理缓冲、格式和生命周期。例如把 `AHardwareBuffer` 导入 EGL 作为图像来源，再用 GPU 处理。具体能否共享取决于设备扩展、缓冲格式、usage 和同步方式。 [Android 硬件缓冲](https://developer.android.com/ndk/reference/group/a-hardware-buffer)
- **复用绘制组件。** 当前贴纸和普通纹理输出已经共用 2D quad 的 shader、VAO/VBO 初始化和绘制逻辑。增加水印或 GIF 时，可以复用这些绘制逻辑，分别管理纹理、位置和更新频率。Android 的资源加载和界面控制仍可通过 Kotlin 对接。 [共用的 C 绘制逻辑](../native_opengl_core/src/main/cpp/gl_common.c)
- **为不同图形后端保留接口。** 相机管理、界面和录制编排可以通过稳定的接口向渲染器传递帧与参数。以后接入 Vulkan 时，Native 模块便于共用构建系统和部分平台桥接代码，Vulkan 的图像导入、命令提交、同步与 Surface 输出仍需按它的 API 实现。 [Android NDK Vulkan 接口](https://developer.android.com/ndk/guides/stable_apis#vulkan)

## 6. 对照两份 Native 实现

| 对照点 | 集中版 `NativeCameraRenderer` | 四组件版 |
| --- | --- | --- |
| 一帧怎么合成 | Kotlin 调一次 `compose()`，C 绑定 FBO、清屏、画 OES 和贴纸。 | Kotlin 依次调用 `bind()`、OES `draw()`、贴纸 `draw()` 和 `unbind()`，并设置清屏和混合状态。 |
| 怎么输出 | 每个 Surface 调一次 `drawOutput()`，由 C 设置 viewport、清屏和绘制。 | Kotlin 设置 viewport 和清屏，调用 `NativeTexture2dDrawer.draw(fboTextureId)`。 |
| 资源归属 | 一个 C 结构体持有两套绘制状态、贴纸纹理和 FBO。 | 四个 C 结构体分别管理自己的资源，贴纸和 2D 输出共用绘制辅助函数。 |

两份源码编入同一个 `libnative_opengl_core.so`，相机页接的是四组件版。两路输出同时启用时，集中版每帧调用三次项目 JNI，四组件版调用六次。这只统计项目自己的 JNI 入口，CPU 耗时需要另行测量。 [集中版 C 实现](../native_opengl_core/src/main/cpp/combined/camera_renderer.c) · [集中版 Kotlin 入口](../native_opengl_core/src/main/java/com/example/native_opengl_core/combined/NativeCameraRenderer.kt) · [四组件版调用顺序](../camera_core/src/main/java/com/example/camera_core/view/CameraOesEngine.kt)
