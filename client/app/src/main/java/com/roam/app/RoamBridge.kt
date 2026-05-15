package com.roam.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import java.io.File

/**
 * JS Bridge 暴露给 WebView 内 JavaScript 的原生能力
 *
 * 在 JS 端通过 `window.RoamBridge.xxx()` 调用。
 *
 * 注意:`@JavascriptInterface` 方法在 WebView 的非 UI 线程执行,涉及 UI 操作必须
 * `activity.runOnUiThread { ... }` 包一层,否则崩溃。
 */
class RoamBridge(
    private val activity: Activity,
    private val spzPickerLauncher: ActivityResultLauncher<Array<String>>,
    private val mediaProjectionLauncher: ActivityResultLauncher<Intent>
) {
    /** SAF 选 .spz 文件 */
    @JavascriptInterface
    fun pickSpzFile() {
        Log.d(TAG, "pickSpzFile() 被 JS 调用")
        activity.runOnUiThread {
            spzPickerLauncher.launch(arrayOf("*/*"))
        }
    }

    /**
     * H7 录屏 — 启动 MediaProjection 权限请求。
     * 用户允许后,MainActivity.mediaProjectionLauncher callback 会启动 ScreenRecorderService。
     */
    @JavascriptInterface
    fun startRecording() {
        Log.d(TAG, "startRecording() 被 JS 调用")
        activity.runOnUiThread {
            val mpm = activity.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    /** H7 录屏 — 停止 ForegroundService */
    @JavascriptInterface
    fun stopRecording() {
        Log.d(TAG, "stopRecording() 被 JS 调用")
        activity.runOnUiThread {
            activity.stopService(Intent(activity, ScreenRecorderService::class.java))
        }
    }

    /** 列出本地 recordings/ 目录的 mp4 文件 — JS 端做视频列表用 */
    @JavascriptInterface
    fun listRecordings(): String {
        val dir = File(activity.filesDir, "recordings")
        if (!dir.exists()) return "[]"
        val items = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                """{"path":"${f.absolutePath}","name":"${f.name}","size":${f.length()},"mtime":${f.lastModified()}}"""
            }
            ?: emptyList()
        return "[" + items.joinToString(",") + "]"
    }

    /** H8 微信分享 — 把 internal storage 内 mp4 发到系统分享面板,用户选「微信」 */
    @JavascriptInterface
    fun shareVideoToWeChat(filePath: String) {
        Log.d(TAG, "shareVideoToWeChat() filePath=$filePath")
        activity.runOnUiThread {
            val ok = ShareHelper.shareVideo(activity, filePath, "分享漫游视频到")
            if (!ok) {
                Toast.makeText(activity, "分享失败:文件不存在或权限错", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun toast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun log(tag: String, message: String) {
        Log.d("Roam/$tag", message)
    }

    @JavascriptInterface
    fun nativeInfo(): String {
        return """{"version":"M1-week0","platform":"android","sdk":${Build.VERSION.SDK_INT},"abi":"${Build.SUPPORTED_ABIS.joinToString(",")}","hasRecording":true,"hasShare":true}"""
    }

    companion object {
        private const val TAG = "RoamBridge"
        const val JS_INTERFACE_NAME = "RoamBridge"
    }
}
