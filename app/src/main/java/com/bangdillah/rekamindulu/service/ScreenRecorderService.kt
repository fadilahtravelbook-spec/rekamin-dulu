package com.bangdillah.rekamindulu.service

/**
 * ScreenRecorderService - Backward-compatible subclass of ScreenRecordingService.
 * Inherits MediaProjection lifecycle, virtual display initialization, and microphone audio capture.
 */
class ScreenRecorderService : ScreenRecordingService() {

    companion object {
        const val TAG = ScreenRecordingService.TAG
        const val ACTION_START = ScreenRecordingService.ACTION_START
        const val ACTION_STOP = ScreenRecordingService.ACTION_STOP
        const val ACTION_PAUSE = ScreenRecordingService.ACTION_PAUSE
        const val ACTION_RESUME = ScreenRecordingService.ACTION_RESUME
        const val ACTION_TOGGLE_MIC = ScreenRecordingService.ACTION_TOGGLE_MIC
        const val ACTION_SET_MIC_MUTED = ScreenRecordingService.ACTION_SET_MIC_MUTED
        const val ACTION_CAPTURE_SCREENSHOT = ScreenRecordingService.ACTION_CAPTURE_SCREENSHOT
        const val ACTION_REFRESH_FOREGROUND_TYPE = ScreenRecordingService.ACTION_REFRESH_FOREGROUND_TYPE
        const val ACTION_RECORDING_STOPPED = ScreenRecordingService.ACTION_RECORDING_STOPPED
        const val ACTION_RECORDING_STATUS_CHANGED = ScreenRecordingService.ACTION_RECORDING_STATUS_CHANGED
        const val ACTION_RECORDING_ERROR = ScreenRecordingService.ACTION_RECORDING_ERROR
        const val ACTION_SCREENSHOT_RESULT = ScreenRecordingService.ACTION_SCREENSHOT_RESULT
        const val EXTRA_FILE_PATH = ScreenRecordingService.EXTRA_FILE_PATH
        const val EXTRA_ERROR_MESSAGE = ScreenRecordingService.EXTRA_ERROR_MESSAGE
        const val EXTRA_MIC_MUTED = ScreenRecordingService.EXTRA_MIC_MUTED
        const val EXTRA_SCREENSHOT_SUCCESS = ScreenRecordingService.EXTRA_SCREENSHOT_SUCCESS
        const val EXTRA_SCREENSHOT_URI = ScreenRecordingService.EXTRA_SCREENSHOT_URI
        const val EXTRA_RESULT_CODE = ScreenRecordingService.EXTRA_RESULT_CODE
        const val EXTRA_RESULT_DATA = ScreenRecordingService.EXTRA_RESULT_DATA

        val isRecording: Boolean
            get() = ScreenRecordingService.isRecording

        val isPaused: Boolean
            get() = ScreenRecordingService.isPaused

        val isMicMuted: Boolean
            get() = ScreenRecordingService.isMicMuted

        val startTimeMillis: Long
            get() = ScreenRecordingService.startTimeMillis

        val elapsedSeconds: Int
            get() = ScreenRecordingService.elapsedSeconds

        val lastRecordedFilePath: String?
            get() = ScreenRecordingService.lastRecordedFilePath

        internal fun syncRecordingState(
            recording: Boolean,
            paused: Boolean,
            startMillis: Long,
            elapsedSec: Int,
            lastPath: String?
        ) {
            // Automatically synchronized through ScreenRecordingService companion getters
        }
    }
}
