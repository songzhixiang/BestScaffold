package com.example.camera_core.opengl

/**
 * 以归一化纹理坐标描述的漩涡效果。半径以画面高度为基准，内部会按宽高比校正为圆形。
 */
data class OesSwirlEffect(
    val enabled: Boolean = false,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radius: Float = 0.35f,
    val strengthRadians: Float = TWO_PI,
    val textureAspectRatio: Float = 16f / 9f,
) {
    init {
        require(centerX.isFinite() && centerY.isFinite()) { "漩涡中心必须是有限值" }
        require(radius.isFinite() && radius > 0f) { "漩涡半径必须大于 0" }
        require(strengthRadians.isFinite()) { "漩涡强度必须是有限值" }
        require(textureAspectRatio.isFinite() && textureAspectRatio > 0f) {
            "纹理宽高比必须大于 0"
        }
    }

    companion object {
        /** 使用图像像素坐标创建效果，适合直接传入检测框中心或指定分辨率中心。 */
        @JvmStatic
        fun fromPixels(
            enabled: Boolean,
            centerX: Float,
            centerY: Float,
            radiusPixels: Float,
            imageWidth: Int,
            imageHeight: Int,
            strengthRadians: Float = TWO_PI,
        ): OesSwirlEffect {
            require(imageWidth > 0 && imageHeight > 0) { "图像尺寸必须大于 0" }
            require(radiusPixels > 0f) { "漩涡像素半径必须大于 0" }
            return OesSwirlEffect(
                enabled = enabled,
                centerX = centerX / imageWidth,
                centerY = centerY / imageHeight,
                radius = radiusPixels / imageHeight,
                strengthRadians = strengthRadians,
                textureAspectRatio = imageWidth.toFloat() / imageHeight,
            )
        }

        private const val TWO_PI = 6.2831855f
    }
}
