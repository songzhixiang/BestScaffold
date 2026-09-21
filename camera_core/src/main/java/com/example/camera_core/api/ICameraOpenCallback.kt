package com.example.camera_core.api

/** 在主线程接收相机打开和预览配置事件。 */
interface ICameraOpenCallback {

    /** [ICamera.openCamera] 查询可用摄像头后，在主线程回调查询结果。 */
    fun onCameraInventory(inventory: CameraInventory) = Unit

    /** 摄像头信息查询失败时调用。 */
    fun onCameraInventoryError(message: String) = Unit

    /** CameraX 实际可绑定的摄像头 ID，可能少于 [onCameraInventory] 返回的 Camera2 可见 ID。 */
    fun onCameraXAvailableCameraIds(cameraIds: List<String>) = Unit

    /** 捕获会话配置完成后，回报实际预览输出尺寸。 */
    fun onPreview(previewWidth: Int, previewHeight: Int) = Unit

    /** 持续预览请求成功启动后调用。 */
    fun onOpenSuccess() = Unit

    /** 打开相机或配置捕获会话失败时调用。 */
    fun onOpenError(message: String) = Unit
}
