package com.example.bestscaffold

import android.app.Application
import com.example.camera_core.view.CameraOesEngine

class App : Application() {

    private var cameraEngine: CameraOesEngine? = null

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
                    if (cameraEngine === created) cameraEngine = null
                }
            }
            created.startEngine(cameraId)
        }
    }

    @Synchronized
    fun releaseCameraEngine() {
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
