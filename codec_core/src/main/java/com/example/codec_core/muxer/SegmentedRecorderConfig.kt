package com.example.codec_core.muxer

import java.io.File

data class SegmentedRecorderConfig(
    val outputDirectory: File,
    val filePrefix: String = "record",
    val loopRecordingEnabled: Boolean = true,
    val segmentDurationMs: Long = 30_000L,
    val audioEnabled: Boolean = true,
    val audioDrainTimeoutMs: Long = 1_000L,
    val maxBufferedSamples: Int = 512,
    val orientationHint: Int = 0,
) {
    init {
        require(filePrefix.isNotBlank()) { "分段文件名前缀不能为空" }
        require(segmentDurationMs > 0) { "分段时长必须大于 0" }
        require(audioDrainTimeoutMs >= 0) { "音频排空超时不能小于 0" }
        require(maxBufferedSamples > 0) { "缓存样本数量必须大于 0" }
        require(orientationHint in setOf(0, 90, 180, 270)) { "视频方向只能是 0、90、180 或 270" }
    }
}

data class RecordedSegment(
    val index: Int,
    val file: File,
    val durationUs: Long,
    val successful: Boolean,
)

/** 回调可能来自分段线程或文件收尾线程，不保证位于主线程。 */
interface SegmentedRecorderListener {
    fun onSegmentStarted(index: Int, file: File) = Unit
    fun onSegmentCompleted(segment: RecordedSegment) = Unit
    fun onRecordingCompleted() = Unit
    fun onError(message: String, cause: Throwable?) = Unit
}
