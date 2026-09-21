package com.example.camera_core.api

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder

/** 一个 Android Camera2 可见摄像头及其声明支持的输出尺寸。 */
data class CameraCapabilities(
    val cameraId: String,
    val lensFacing: Int?,
    val surfaceViewSizes: List<Size>,
    val textureViewSizes: List<Size>,
    val yuv420Sizes: List<Size>,
    val inspectionError: String? = null,
)

/** 查询时当前应用可见的 Camera2 摄像头快照。 */
data class CameraInventory(val cameras: List<CameraCapabilities>) {
    val count: Int get() = cameras.size
    val cameraIds: List<String> get() = cameras.map(CameraCapabilities::cameraId)
}

/**
 * 从设备实时查询，而非使用写死的摄像头配置。摄像头 ID 和输出尺寸可能因设备、用户、权限
 * 以及当前连接的外接摄像头而变化。
 */
object CameraInventoryQuery {
    @JvmStatic
    fun query(context: Context): CameraInventory {
        val manager = context.applicationContext
            .getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameras = manager.cameraIdList.map { id ->
            try {
                val characteristics = manager.getCameraCharacteristics(id)
                val streamMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                )
                CameraCapabilities(
                    cameraId = id,
                    lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING),
                    surfaceViewSizes = streamMap
                        ?.getOutputSizes(SurfaceHolder::class.java)
                        .toSortedSizes(),
                    textureViewSizes = streamMap
                        ?.getOutputSizes(SurfaceTexture::class.java)
                        .toSortedSizes(),
                    yuv420Sizes = streamMap
                        ?.getOutputSizes(ImageFormat.YUV_420_888)
                        .toSortedSizes(),
                )
            } catch (error: Exception) {
                CameraCapabilities(
                    cameraId = id,
                    lensFacing = null,
                    surfaceViewSizes = emptyList(),
                    textureViewSizes = emptyList(),
                    yuv420Sizes = emptyList(),
                    inspectionError = error.message ?: error.javaClass.simpleName,
                )
            }
        }
        return CameraInventory(cameras)
    }

    /** 将完整摄像头列表及各摄像头的分辨率列表写入日志。 */
    @JvmStatic
    fun log(inventory: CameraInventory, tag: String = "CameraInventory") {
        Log.i(tag, "Camera2 visible camera count=${inventory.count}, ids=${inventory.cameraIds}")
        inventory.cameras.forEach { camera ->
            Log.i(tag, "cameraId=${camera.cameraId}, lensFacing=${camera.lensFacing}")
            camera.inspectionError?.let { Log.w(tag, "cameraId=${camera.cameraId}: $it") }
            Log.i(tag, "cameraId=${camera.cameraId}, SurfaceView=${camera.surfaceViewSizes}")
            Log.i(tag, "cameraId=${camera.cameraId}, TextureView=${camera.textureViewSizes}")
            Log.i(tag, "cameraId=${camera.cameraId}, YUV_420_888=${camera.yuv420Sizes}")
        }
    }

    private fun Array<Size>?.toSortedSizes(): List<Size> = this
        ?.distinct()
        ?.sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }
            .thenByDescending { it.width })
        .orEmpty()
}
