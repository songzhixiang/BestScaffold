package com.example.camera_core.api

import android.media.Image

/**
 * 在相机图像线程接收 YUV 预览帧。
 *
 * [Image] 仅在 [onPreviewBufferFrame] 执行期间有效。如需异步处理，必须在回调返回前复制数据。
 * 回调返回后，相机实现会关闭该图像。
 */
interface ICameraPreviewDataCallback {

    /** 连续无效帧过多时调用。 */
    fun onFrameErrorCallback() = Unit

    /** 收到有效帧时调用。 */
    fun onFrameSuccessCallback() = Unit

    /** 同步接收一帧 YUV_420_888 预览图像。 */
    fun onPreviewBufferFrame(image: Image)
}
