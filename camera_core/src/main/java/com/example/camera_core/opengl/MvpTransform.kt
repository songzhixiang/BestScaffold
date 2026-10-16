package com.example.camera_core.opengl

import android.opengl.Matrix

/** 投影方式：正交投影保持平行线，透视投影会产生近大远小的效果。 */
enum class ProjectionMode {
    ORTHOGRAPHIC,
    PERSPECTIVE,
}

/**
 * OES 画面的几何变换参数。平移量和缩放量均位于 OpenGL 的标准化坐标空间。
 *
 * - translationX/Y：可见区域通常为 -1..1。
 * - rotationDegrees：绕 Z 轴旋转。
 * - tiltX/YDegrees：绕 X/Y 轴旋转，搭配透视投影形成梯形/透视变形。
 * - shearX/Y：仿射斜切，x' = x + shearX * y，y' = y + shearY * x。
 */
data class OesRenderTransform(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val shearX: Float = 0f,
    val shearY: Float = 0f,
    val tiltXDegrees: Float = 0f,
    val tiltYDegrees: Float = 0f,
    val projectionMode: ProjectionMode = ProjectionMode.ORTHOGRAPHIC,
)

/** 单独暴露 M、V、P 与最终 MVP，便于调试和学习每一级矩阵的职责。 */
data class MvpMatrices(
    val model: FloatArray,
    val view: FloatArray,
    val projection: FloatArray,
    val mvp: FloatArray,
)

/** 使用 Android [Matrix] API 创建 OpenGL ES 使用的列主序 4x4 矩阵。 */
object MvpMatrixFactory {

    @JvmStatic
    fun create(transform: OesRenderTransform): MvpMatrices {
        validate(transform)

        val model = FloatArray(MATRIX_SIZE)
        Matrix.setIdentityM(model, 0)

        // Matrix.translateM/rotateM/scaleM 会右乘新矩阵。对顶点实际生效顺序与调用顺序相反：
        // 缩放/翻转 -> 斜切 -> X/Y 倾斜 -> Z 轴旋转 -> 平移。
        Matrix.translateM(model, 0, transform.translationX, transform.translationY, 0f)
        Matrix.rotateM(model, 0, transform.rotationDegrees, 0f, 0f, 1f)
        Matrix.rotateM(model, 0, transform.tiltXDegrees, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, transform.tiltYDegrees, 0f, 1f, 0f)

        val shear = identityMatrix().apply {
            this[4] = transform.shearX
            this[1] = transform.shearY
        }
        val modelWithShear = FloatArray(MATRIX_SIZE)
        Matrix.multiplyMM(modelWithShear, 0, model, 0, shear, 0)

        val horizontalDirection = if (transform.flipHorizontal) -1f else 1f
        val verticalDirection = if (transform.flipVertical) -1f else 1f
        Matrix.scaleM(
            modelWithShear,
            0,
            transform.scaleX * horizontalDirection,
            transform.scaleY * verticalDirection,
            1f,
        )

        val view = FloatArray(MATRIX_SIZE)
        Matrix.setLookAtM(
            view,
            0,
            0f,
            0f,
            CAMERA_DISTANCE,
            0f,
            0f,
            0f,
            0f,
            1f,
            0f,
        )

        val projection = FloatArray(MATRIX_SIZE)
        when (transform.projectionMode) {
            ProjectionMode.ORTHOGRAPHIC -> Matrix.orthoM(
                projection,
                0,
                -1f,
                1f,
                -1f,
                1f,
                NEAR_PLANE,
                FAR_PLANE,
            )

            ProjectionMode.PERSPECTIVE -> Matrix.perspectiveM(
                projection,
                0,
                PERSPECTIVE_FOV_Y_DEGREES,
                1f,
                NEAR_PLANE,
                FAR_PLANE,
            )
        }

        val modelView = FloatArray(MATRIX_SIZE)
        val mvp = FloatArray(MATRIX_SIZE)
        Matrix.multiplyMM(modelView, 0, view, 0, modelWithShear, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        return MvpMatrices(modelWithShear, view, projection, mvp)
    }

    @JvmStatic
    fun identityMatrix(): FloatArray = FloatArray(MATRIX_SIZE).also {
        Matrix.setIdentityM(it, 0)
    }

    private fun validate(transform: OesRenderTransform) {
        require(transform.scaleX.isFinite() && transform.scaleX > 0f) {
            "scaleX 必须是大于 0 的有限值"
        }
        require(transform.scaleY.isFinite() && transform.scaleY > 0f) {
            "scaleY 必须是大于 0 的有限值"
        }
        val values = floatArrayOf(
            transform.rotationDegrees,
            transform.translationX,
            transform.translationY,
            transform.shearX,
            transform.shearY,
            transform.tiltXDegrees,
            transform.tiltYDegrees,
        )
        require(values.all(Float::isFinite)) { "矩阵变换参数必须是有限值" }
    }

    private const val MATRIX_SIZE = 16
    private const val CAMERA_DISTANCE = 3f
    private const val NEAR_PLANE = 1f
    private const val FAR_PLANE = 10f

    // tan(fov / 2) = 1 / CAMERA_DISTANCE，使未变形的 [-1, 1] 四边形仍铺满画面。
    private const val PERSPECTIVE_FOV_Y_DEGREES = 36.869896f
}
