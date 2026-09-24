package com.example.bestscaffold

import android.app.Application
import com.example.bestscaffold.recording.CameraRecordingController
import com.example.camera_core.view.CameraOesEngine

class App : Application() {

    private var cameraEngine: CameraOesEngine? = null
    private var recordingController: CameraRecordingController? = null

    /** 引擎由进程持有；Activity 只负责挂载和卸载自己的预览窗口。 */
    @Synchronized
    fun getOrStartCameraEngine(cameraId: String): CameraOesEngine {
        cameraEngine?.let { current ->
            if (!current.isReleased()) return current
            check(current.isReleaseComplete()) { "摄像头资源仍在释放中，请稍后重试" }
        }
        return CameraOesEngine(this).also { created ->
            cameraEngine = created
            created.setReleaseListener {
                synchronized(this) {
                    if (cameraEngine === created) {
                        recordingController?.release()
                        recordingController = null
                        cameraEngine = null
                    }
                }
            }
            created.startEngine(cameraId)
        }
    }

    /** 录制控制器同样由进程持有，页面暂时不可见时录制不会被中断。 */
    @Synchronized
    fun getOrCreateRecordingController(cameraId: String): CameraRecordingController {
        val currentEngine = getOrStartCameraEngine(cameraId)
        recordingController?.let { return it }
        return CameraRecordingController(this, currentEngine).also {
            recordingController = it
        }
    }

    @Synchronized
    fun releaseCameraEngine() {
        recordingController?.release()
        recordingController = null
        cameraEngine?.release()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
