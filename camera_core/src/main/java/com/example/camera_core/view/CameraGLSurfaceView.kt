package com.example.camera_core.view

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.View.MeasureSpec
import kotlin.math.roundToInt

/**
 * 用于 OpenGL 相机预览的等比例 SurfaceView。
 *
 * 它只约束界面显示比例，不负责创建 GL 线程或持有相机；默认按照 1280:720 显示。
 */
class CameraGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr) {

    private var aspectWidth = DEFAULT_ASPECT_WIDTH
    private var aspectHeight = DEFAULT_ASPECT_HEIGHT

    /** 设置预览宽高比，例如 setAspectRatio(1280, 720)。 */
    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "预览宽高必须大于 0" }
        if (aspectWidth == width && aspectHeight == height) return
        aspectWidth = width
        aspectHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        val ratio = aspectWidth.toFloat() / aspectHeight

        val measuredWidth: Int
        val measuredHeight: Int
        when {
            widthMode == MeasureSpec.UNSPECIFIED && heightMode == MeasureSpec.UNSPECIFIED -> {
                measuredWidth = aspectWidth
                measuredHeight = aspectHeight
            }

            widthMode == MeasureSpec.UNSPECIFIED -> {
                measuredHeight = maxHeight
                measuredWidth = (measuredHeight * ratio).roundToInt()
            }

            heightMode == MeasureSpec.UNSPECIFIED -> {
                measuredWidth = maxWidth
                measuredHeight = (measuredWidth / ratio).roundToInt()
            }

            maxWidth / ratio <= maxHeight -> {
                measuredWidth = maxWidth
                measuredHeight = (maxWidth / ratio).roundToInt()
            }

            else -> {
                measuredHeight = maxHeight
                measuredWidth = (maxHeight * ratio).roundToInt()
            }
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    private companion object {
        private const val DEFAULT_ASPECT_WIDTH = 1280
        private const val DEFAULT_ASPECT_HEIGHT = 720
    }
}
