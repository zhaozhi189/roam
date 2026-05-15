package com.roam.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import java.io.File
import java.io.FileOutputStream

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
     *
     * Android 14+(API 34)用 MediaProjectionConfig.createConfigForDefaultDisplay() 强制
     * 「整个屏幕」模式,避免 MagicOS / 部分 OEM 默认「单个应用」让用户去 launcher 选 app
     * 导致 Roam 最小化录不到自己内容。
     *
     * 用户允许后,MainActivity.mediaProjectionLauncher callback 会启动 ScreenRecorderService。
     */
    @JavascriptInterface
    fun startRecording() {
        Log.d(TAG, "startRecording() 被 JS 调用")
        activity.runOnUiThread {
            val mpm = activity.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+:强制全屏录制(规避 MagicOS 默认单 app 让用户切换的问题)
                val config = android.media.projection.MediaProjectionConfig
                    .createConfigForDefaultDisplay()
                mpm.createScreenCaptureIntent(config)
            } else {
                mpm.createScreenCaptureIntent()
            }
            mediaProjectionLauncher.launch(intent)
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

    /** 列出本地 recordings/ 目录的视频文件(mp4 + webm)— JS 端做视频列表用 */
    @JavascriptInterface
    fun listRecordings(): String {
        val dir = File(activity.filesDir, "recordings")
        if (!dir.exists()) return "[]"
        val items = dir.listFiles { f ->
                f.isFile && (f.name.endsWith(".mp4") || f.name.endsWith(".webm"))
            }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                """{"path":"${f.absolutePath}","name":"${f.name}","size":${f.length()},"mtime":${f.lastModified()}}"""
            }
            ?: emptyList()
        return "[" + items.joinToString(",") + "]"
    }

    /**
     * B 路径 H7(已退役但保留兼容):JS 端 canvas.captureStream + MediaRecorder 录到 Blob → base64,
     * 通过这个方法落盘到 internal storage filesDir/recordings/
     *
     * @return 文件绝对路径(失败返回空字符串)
     */
    @JavascriptInterface
    fun saveVideoBase64(filename: String, base64: String): String {
        return try {
            val dir = File(activity.filesDir, "recordings").apply { mkdirs() }
            val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val out = File(dir, safeName)
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            FileOutputStream(out).use { it.write(bytes) }
            Log.d(TAG, "saveVideoBase64 写入 ${out.absolutePath} (${bytes.size} bytes)")
            out.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveVideoBase64 失败", e)
            ""
        }
    }

    /**
     * 删除一个录制视频文件。
     *
     * @param filePath 必须在 App private storage(filesDir/recordings/)下,防止误删系统文件
     * @return 删除成功返回 "true",失败返回 "false"(JS bridge 不支持原生 Boolean)
     */
    @JavascriptInterface
    fun deleteVideo(filePath: String): Boolean {
        return try {
            val recordingsDir = File(activity.filesDir, "recordings").canonicalPath
            val target = File(filePath).canonicalFile
            // 安全检查:必须在 recordings 目录下,不允许 ../../ 之类越权
            if (!target.absolutePath.startsWith(recordingsDir)) {
                Log.w(TAG, "deleteVideo 拒绝越权路径: $filePath")
                return false
            }
            val ok = target.delete()
            Log.d(TAG, "deleteVideo $filePath -> $ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "deleteVideo 失败", e)
            false
        }
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
