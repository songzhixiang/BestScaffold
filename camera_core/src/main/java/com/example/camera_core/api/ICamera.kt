package com.example.camera_core.api

import android.view.Surface

/**
 * 相机实现的通用接口。
 *
 * 相机操作是异步的，结果通过 [ICameraOpenCallback] 返回。调用 [releaseCamera] 后，该实例不可再使用。
 * 传入 [openCamera] 的 [Surface] 由调用方持有，并由调用方负责释放。
 */
interface ICamera {

    /** 获取当前 Camera2 可见的摄像头 ID 及其声明支持的输出分辨率。 */
    fun getCameraInventory(): CameraInventory

    /** 打开相机，并向 [surface] 输出预览画面。 */
    fun openCamera(surface: Surface)

    /** 停止相机工作并释放内部资源；重复调用不会产生额外影响。 */
    fun releaseCamera()

    /** 在 [pauseCamera] 后重新创建预览会话。 */
    fun resumeCamera()

    /** 暂停预览，但不关闭底层相机设备。 */
    fun pauseCamera()

    /** 返回当前是否正在执行持续预览请求。 */
    fun isPreviewOn(): Boolean

    /** 注册相机打开及状态回调；仅保留最近一次注册的回调。 */
    fun addCameraOpenCallback(callback: ICameraOpenCallback)

    /** 注册 YUV 预览数据回调；仅保留最近一次注册的回调。 */
    fun addCameraPreviewDataCallback(callback: ICameraPreviewDataCallback)
}
