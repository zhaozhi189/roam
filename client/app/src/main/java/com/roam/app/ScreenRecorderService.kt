package com.roam.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MediaProjection 全屏录制 ForegroundService(M1 第 0 周 H7 验证用)
 *
 * 调用方式:
 *   通过 Intent extras 传入 resultCode + data(MediaProjection 权限请求结果)
 *
 *   start:
 *     val intent = Intent(context, ScreenRecorderService::class.java)
 *       .putExtra(EXTRA_RESULT_CODE, resultCode)
 *       .putExtra(EXTRA_RESULT_DATA, data)
 *     context.startForegroundService(intent)
 *
 *   stop:
 *     context.stopService(Intent(context, ScreenRecorderService::class.java))
 *
 * 录制产物:internal storage 的 files/recordings/ 目录,文件名 roam-{timestamp}.mp4
 *
 * TODO M1 真机调试:
 *   - 视频码率 / 分辨率根据 Magic7 Pro 实测调(目标 60s ≤ 25MB,即 ~3.3 Mbps)
 *   - 录制完成后通过广播 / EventBus 把文件路径通知 MainActivity → JS
 *   - 处理 MediaProjection.Callback onStop 异常(系统中断录制)
 *   - 加录制时长上限保护(60s 自动停)
 */
class ScreenRecorderService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentOutputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.w(TAG, "onStartCommand received null intent")
            stopSelf()
            return START_NOT_STICKY
        }

        // 必须先 startForeground 才能调 MediaProjection.getMediaProjection(Android 14+ 强制)
        startForegroundCompat()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null || resultCode == 0) {
            Log.e(TAG, "Missing resultCode or data, cannot start recording")
            stopSelf()
            return START_NOT_STICKY
        }

        startRecording(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null, user may have denied")
            stopSelf()
            return
        }

        // 屏幕尺寸
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // 输出文件
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        currentOutputFile = File(dir, "roam-$ts.mp4")

        // MediaRecorder 配置
        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(VIDEO_FPS)
            setVideoEncodingBitRate(VIDEO_BITRATE)
            setOutputFile(currentOutputFile!!.absolutePath)
            try {
                prepare()
            } catch (e: Exception) {
                Log.e(TAG, "MediaRecorder prepare failed", e)
                stopSelf()
                return
            }
        }

        // VirtualDisplay 把屏幕画到 MediaRecorder 的 surface
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "RoamScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder!!.surface,
            null, null
        )

        try {
            mediaRecorder!!.start()
            Log.d(TAG, "Recording started: ${currentOutputFile?.absolutePath} (${width}x${height} @ ${VIDEO_FPS}fps, ${VIDEO_BITRATE / 1024} kbps)")
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder start failed", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder stop failed", e)
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
        mediaRecorder = null
        virtualDisplay = null
        mediaProjection = null
        Log.d(TAG, "Recording stopped, file=${currentOutputFile?.absolutePath}")
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notif: Notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Roam 录制中")
            .setContentText("正在录制屏幕,完成后会自动保存")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Roam 录屏",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "RoamRecorder"
        private const val NOTIF_CHANNEL_ID = "roam_recording"
        private const val NOTIF_ID = 1001
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        // M1 实测调:1080p@30fps,~3.3 Mbps 给微信 60s ≤ 25MB 留足余量
        const val VIDEO_FPS = 30
        const val VIDEO_BITRATE = 3_500_000  // ~3.5 Mbps
    }
}
