# Android OpenGL ES 矩阵与 MVP 学习指南

本文结合 BestScaffold 的 Camera2 → SurfaceTexture → OES Texture → OpenGL ES → 预览/MediaCodec 录制链路，说明缩放、旋转、平移、翻转、斜切和透视变形的实现方式，以及 MVP 矩阵各部分的含义。

## 1. 本次实现的位置

| 文件 | 作用 |
| --- | --- |
| `camera_core/.../opengl/MvpTransform.kt` | 矩阵参数模型和 M/V/P/MVP 构建 API |
| `camera_core/.../opengl/OesTextureEffect.kt` | 中心漩涡参数以及像素坐标到 UV 坐标的转换 |
| `camera_core/.../opengl/OesDrawer.kt` | 将 MVP 和 SurfaceTexture 矩阵传入顶点着色器 |
| `camera_core/.../view/CameraOesEngine.kt` | 在 GL 线程更新矩阵，并让预览和录像共用同一变换 |
| `app/.../ui/main/CameraActivity.kt` | 实时操作按钮和当前矩阵参数展示 |

页面目前提供以下实验按钮：

- 等比例放大、缩小；
- 每次旋转 90°；
- 水平、垂直翻转；
- 上下左右平移；
- 正负斜切变形；
- 正负 Y 轴 3D 倾斜和透视投影；
- 一键重置。

这些操作不仅影响屏幕预览，也会影响编码器输入 Surface，因此录制生成的 MP4 与预览保持一致。

## 2. 从一个顶点到屏幕像素

OpenGL ES 顶点通常依次经过这些空间：

```text
模型局部坐标
    │ Model
    ▼
世界坐标
    │ View
    ▼
观察/相机坐标
    │ Projection
    ▼
裁剪坐标
    │ 透视除法：x/w、y/w、z/w
    ▼
NDC 标准化设备坐标（通常为 -1..1）
    │ glViewport
    ▼
屏幕像素坐标
```

顶点着色器中的核心公式是：

```glsl
gl_Position = P * V * M * aPosition;
```

工程中先在 Kotlin 侧计算：

```text
MVP = P × V × M
```

再传给着色器：

```glsl
gl_Position = uMvpMatrix * aPosition;
```

虽然表达式从左向右写成 `P × V × M × 顶点`，但顶点实际先经过最右侧的 M，再经过 V，最后经过 P。

## 3. M、V、P 分别是什么

### 3.1 Model：模型矩阵

Model Matrix 描述“物体自己怎么变化以及放在哪里”。当前项目中的这些操作属于 Model：

- `scaleX/scaleY`：缩放；
- `rotationDegrees`：绕 Z 轴旋转；
- `translationX/translationY`：平移；
- `flipHorizontal/flipVertical`：负缩放形成翻转；
- `shearX/shearY`：斜切；
- `tiltXDegrees/tiltYDegrees`：绕 X/Y 轴倾斜。

例如二维齐次坐标下的平移矩阵可理解为：

```text
| 1  0  0  tx |
| 0  1  0  ty |
| 0  0  1  0  |
| 0  0  0  1  |
```

缩放矩阵：

```text
| sx  0   0  0 |
| 0   sy  0  0 |
| 0   0   1  0 |
| 0   0   0  1 |
```

水平翻转并不是特殊的 OpenGL 指令，本质上是 `sx = -1`；垂直翻转则是 `sy = -1`。

斜切使用：

```text
x' = x + shearX × y
y' = y + shearY × x
```

对应矩阵：

```text
| 1       shearX  0  0 |
| shearY  1       0  0 |
| 0       0       1  0 |
| 0       0       0  1 |
```

### 3.2 View：观察矩阵

View Matrix 可以理解为“虚拟摄像机站在哪里、看向哪里、头顶朝哪里”。

本项目通过 `Matrix.setLookAtM()` 设置：

- eye：`(0, 0, 3)`；
- center：`(0, 0, 0)`；
- up：`(0, 1, 0)`。

也就是虚拟摄像机位于 Z 轴正方向，朝坐标原点观察，屏幕上方对应 Y 轴正方向。

View Matrix 移动的数学效果可以看成移动整个世界的反方向。例如虚拟摄像机向右移动，最终画面看起来会向左移动。

### 3.3 Projection：投影矩阵

Projection Matrix 决定三维观察坐标如何投影到二维裁剪空间。

本项目支持两种模式：

| 模式 | Android API | 特征 |
| --- | --- | --- |
| 正交投影 | `Matrix.orthoM()` | 平行线仍平行，不产生近大远小 |
| 透视投影 | `Matrix.perspectiveM()` | 远处更小，X/Y 倾斜时产生梯形效果 |

普通缩放、旋转、翻转和斜切使用正交投影。点击“3D变形”时，会绕 Y 轴倾斜并切换为透视投影。

## 4. 为什么是 4×4 矩阵

三维顶点通常写成 `(x, y, z)`，但 OpenGL 使用齐次坐标 `(x, y, z, w)`。

多出来的 `w` 有两个重要作用：

1. 让平移也能用矩阵乘法表达；
2. 让透视投影可以在最后执行 `x/w`、`y/w`、`z/w`，形成近大远小。

因此 Model、View、Projection 和最终 MVP 都是包含 16 个 `Float` 的 4×4 矩阵。

## 5. `android.opengl.Matrix` 常用 API

### 单位矩阵

```kotlin
val model = FloatArray(16)
Matrix.setIdentityM(model, 0)
```

单位矩阵相当于数字乘法中的 1，不改变顶点。

### 平移

```kotlin
Matrix.translateM(model, 0, tx, ty, tz)
```

### 旋转

```kotlin
// 绕 Z 轴旋转，适合二维画面旋转。
Matrix.rotateM(model, 0, degrees, 0f, 0f, 1f)

// 绕 X/Y 轴旋转，搭配透视投影观察 3D 变形。
Matrix.rotateM(model, 0, tiltX, 1f, 0f, 0f)
Matrix.rotateM(model, 0, tiltY, 0f, 1f, 0f)
```

`android.opengl.Matrix.rotateM()` 使用角度制，不需要手动转换为弧度。

### 缩放和翻转

```kotlin
val directionX = if (flipHorizontal) -1f else 1f
val directionY = if (flipVertical) -1f else 1f
Matrix.scaleM(model, 0, scaleX * directionX, scaleY * directionY, 1f)
```

### 矩阵相乘

```kotlin
val modelView = FloatArray(16)
val mvp = FloatArray(16)

Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
```

这里得到：

```text
modelView = V × M
mvp = P × modelView = P × V × M
```

### View 矩阵

```kotlin
Matrix.setLookAtM(
    view, 0,
    0f, 0f, 3f, // eye
    0f, 0f, 0f, // center
    0f, 1f, 0f, // up
)
```

### 正交投影

```kotlin
Matrix.orthoM(
    projection, 0,
    -1f, 1f,
    -1f, 1f,
    1f, 10f,
)
```

### 透视投影

```kotlin
Matrix.perspectiveM(
    projection, 0,
    36.87f, // Y 方向视野角
    1f,     // 宽高比
    1f,     // near，必须大于 0
    10f,    // far，必须大于 near
)
```

## 6. 矩阵操作顺序为什么重要

矩阵乘法不满足交换律：

```text
Translation × Rotation ≠ Rotation × Translation
```

例如一个点先绕原点旋转再向右移动，与先向右移动再绕原点旋转，最终位置完全不同。

Android `Matrix.translateM()`、`rotateM()`、`scaleM()` 会将新变换乘入现有矩阵。当前工厂中的调用顺序构造出：

```text
M = Translation × RotationZ × RotationX × RotationY × Shear × Scale
```

当它乘以列向量时，实际生效顺序是从右向左：

```text
Scale/Flip
→ Shear
→ RotateY/RotateX
→ RotateZ
→ Translation
```

这种顺序的直观结果是：画面先围绕自己的中心缩放、翻转和旋转，最后再移动到目标位置。

## 7. MVP Matrix 与 SurfaceTexture ST Matrix 的区别

Camera2 画面通过 `SurfaceTexture` 进入 `GL_TEXTURE_EXTERNAL_OES`。这里同时存在两套完全不同的矩阵。

### MVP Matrix

改变四边形顶点的位置：

```glsl
gl_Position = uMvpMatrix * aPosition;
```

它决定画面在屏幕上的大小、位置、旋转、翻转和形状。

### ST Matrix

`SurfaceTexture.getTransformMatrix()` 返回纹理采样矩阵：

```glsl
vTexCoord = (uSTMatrix * aTexCoord).xy;
```

它处理生产者提供的纹理裁剪、坐标方向以及半 texel 边界等信息。每次 `updateTexImage()` 后都应该重新获取，不能假定它永远是单位矩阵。

简化理解：

```text
MVP：移动“画布”
ST：调整“画布上采样哪一块相机纹理”
```

不要用 MVP 替代 ST，也不要直接丢弃 SurfaceTexture 提供的 ST Matrix。

## 8. 当前矩阵 API 的使用方式

只进行二维变化：

```kotlin
engine.setRenderTransform(
    OesRenderTransform(
        scaleX = 0.8f,
        scaleY = 0.8f,
        rotationDegrees = 90f,
        translationX = 0.2f,
        flipHorizontal = true,
        shearX = 0.25f,
    ),
)
```

进行透视变形：

```kotlin
engine.setRenderTransform(
    OesRenderTransform(
        tiltXDegrees = 15f,
        tiltYDegrees = 30f,
        projectionMode = ProjectionMode.PERSPECTIVE,
    ),
)
```

恢复默认状态：

```kotlin
engine.resetRenderTransform()
```

单独检查 M、V、P 和 MVP：

```kotlin
val matrices = MvpMatrixFactory.create(
    OesRenderTransform(rotationDegrees = 90f),
)

val model = matrices.model
val view = matrices.view
val projection = matrices.projection
val mvp = matrices.mvp
```

## 9. 将矩阵传给 GLSL

Kotlin 侧：

```kotlin
GLES20.glUniformMatrix4fv(
    uMvpMatrixHandle,
    1,
    false,
    mvpMatrix,
    0,
)
```

参数含义：

| 参数 | 含义 |
| --- | --- |
| location | shader 中 uniform 的位置 |
| count | 上传的矩阵数量，这里是 1 |
| transpose | OpenGL ES 必须使用 `false` |
| value | 16 个 Float 的列主序矩阵 |
| offset | 数组起始位置 |

GLSL 侧：

```glsl
uniform mat4 uMvpMatrix;
attribute vec4 aPosition;

void main() {
    gl_Position = uMvpMatrix * aPosition;
}
```

## 10. `android.graphics.Matrix` 与 `android.opengl.Matrix`

Android 有两个容易混淆的 Matrix：

| 类 | 常见尺寸 | 主要用途 |
| --- | --- | --- |
| `android.graphics.Matrix` | 3×3 | Canvas、Bitmap、View 的二维变换 |
| `android.opengl.Matrix` | 4×4 | OpenGL ES 三维变换和 MVP |

当前相机渲染使用的是：

```kotlin
import android.opengl.Matrix
```

## 11. 常见问题

### 缩放后画面被裁掉

顶点超出 NDC 的 `-1..1` 区域后会被裁剪，这是预期行为。缩小会露出清屏颜色，放大会裁掉边缘。

### 旋转 90° 后比例看起来变化

MVP 工作在标准化坐标中，最后还会经过 `glViewport()` 映射到实际像素。如果预览窗口不是正方形，旋转后的 X/Y 会受到窗口宽高比影响。需要保持内容比例时，可以把 viewport 宽高比纳入投影矩阵，或使用 letterbox/pillarbox viewport。

### 先平移再旋转为什么绕屏幕中心转

检查矩阵乘法顺序。代码调用顺序不等同于顶点的实际执行顺序，列向量会从表达式最右侧开始变换。

### 透视画面消失或穿过屏幕

检查以下条件：

- near 必须大于 0；
- far 必须大于 near；
- 物体必须位于虚拟摄像机前方并处于 near/far 之间；
- 倾斜角度不要一开始就设得过大。

### 为什么矩阵更新要交给 GL 线程

矩阵本身可以在普通线程计算，但绘制状态和 EGL/GL 调用属于创建上下文的 GL 线程。本项目在调用线程完成参数校验和矩阵计算，再把不可变结果提交到 GL 线程，避免一帧中读到一半新、一半旧的矩阵。

## 12. 建议学习顺序

1. 单独使用单位矩阵，确认画面与原始版本一致；
2. 只改变 `scaleX/scaleY`；
3. 只改变 `translationX/translationY`；
4. 只做 Z 轴旋转；
5. 用负缩放理解翻转；
6. 修改操作顺序，观察矩阵不可交换；
7. 增加斜切矩阵；
8. 最后学习 View、Perspective 和 `w` 分量。

## 13. 为什么漩涡不能只靠 `uUserTextureMatrix`

现在的纹理坐标链路是：

```glsl
vTexCoord = (uSTMatrix * uUserTextureMatrix * aTexCoord).xy;
```

`uUserTextureMatrix` 可以完成纹理的平移、缩放、旋转、翻转和斜切，但矩阵对所有点使用同一组线性系数。漩涡要求中心附近旋转更多、越靠近半径边缘旋转越少，旋转角度依赖每个像素到中心的距离，因此属于非线性变换。

本项目在片段着色器中计算：

```text
distance = length(currentUv - centerUv)
falloff = (radius - distance) / radius
angle = strength × falloff²
```

随后让当前纹理坐标绕中心旋转 `angle`。1280×720 画面的中心像素 `(640, 360)` 会转换为 UV 中心 `(0.5, 0.5)`。

```kotlin
val swirl = OesSwirlEffect.fromPixels(
    enabled = true,
    centerX = 640f,
    centerY = 360f,
    radiusPixels = 260f,
    imageWidth = 1280,
    imageHeight = 720,
)
engine.setSwirlEffect(swirl)
```

因为 1280×720 不是正方形，片段着色器在计算距离前会把 X 方向乘以 `1280 / 720`，旋转完成后再除回来。否则相同 UV 半径在屏幕上会表现成椭圆漩涡。

当前示例启动时默认开启中心漩涡，页面上的“中心漩涡”按钮可以实时关闭或重新开启。漩涡发生在 OES 采样之前，因此预览和编码视频都会包含同样的效果。

## 14. 官方参考

- [Android `android.opengl.Matrix`](https://developer.android.com/reference/android/opengl/Matrix)
- [Android `SurfaceTexture.getTransformMatrix`](https://developer.android.com/reference/android/graphics/SurfaceTexture#getTransformMatrix(float[]))
- [Android `GLES20.glUniformMatrix4fv`](https://developer.android.com/reference/android/opengl/GLES20#glUniformMatrix4fv(int,%20int,%20boolean,%20float[],%20int))

