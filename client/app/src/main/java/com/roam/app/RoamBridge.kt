package com.roam.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
    private val mediaProjectionLauncher: ActivityResultLauncher<Intent>,
    private val qrScanLauncher: ActivityResultLauncher<com.journeyapps.barcodescanner.ScanOptions>
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

    /** 列出本地 recordings/ 目录的媒体文件(mp4 + webm + png 截屏)— JS 端做媒体列表用 */
    @JavascriptInterface
    fun listRecordings(): String {
        val dir = File(activity.filesDir, "recordings")
        if (!dir.exists()) return "[]"
        val items = dir.listFiles { f ->
                f.isFile && (f.name.endsWith(".mp4") || f.name.endsWith(".webm") || f.name.endsWith(".png"))
            }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                // 通过 RoamLogic 安全 escape — 用户 SAF 选的文件名可能含 " 或控制符,inline 拼接会破坏 JSON
                RoamLogic.buildMediaItemJson(f.absolutePath, f.name, f.length(), f.lastModified())
            }
            ?: emptyList()
        return "[" + items.joinToString(",") + "]"
    }

    /**
     * 接收 JS 端 base64 编码的 blob 落盘:
     * 1) internal storage filesDir/recordings/(App 内列表用,▶/分享/🗑 调这个路径)
     * 2) MediaStore 公共目录 Movies/Roam/ 或 Pictures/Roam/(系统图库/相册直接看到,用户友好)
     *
     * 文件名后缀决定 collection:
     *   .mp4/.webm → MediaStore.Video.Media (Movies/Roam/)
     *   .png       → MediaStore.Images.Media (Pictures/Roam/)
     *
     * @return internal storage 绝对路径
     */
    @JavascriptInterface
    fun saveVideoBase64(filename: String, base64: String): String {
        return try {
            val dir = File(activity.filesDir, "recordings").apply { mkdirs() }
            val safeName = RoamLogic.sanitizeFilename(filename)
            val out = File(dir, safeName)
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            FileOutputStream(out).use { it.write(bytes) }
            Log.d(TAG, "saveVideoBase64 写入 ${out.absolutePath} (${bytes.size} bytes)")
            // 同步导一份到系统图库(失败不影响 App 内功能)
            try { exportToMediaStore(safeName, bytes) }
            catch (e: Exception) { Log.w(TAG, "exportToMediaStore 失败(忽略): ${e.message}") }
            out.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveVideoBase64 失败", e)
            ""
        }
    }

    /**
     * 把 bytes 写到 MediaStore 公共目录,用户在系统「图库 / 相册」直接看到。
     * Android 10+(API 29+)用 scoped storage 友好的 MediaStore API,无需 WRITE_EXTERNAL_STORAGE。
     * Android 9 及以下跳过(legacy 路径要 WRITE 权限,不值得)。
     */
    private fun exportToMediaStore(filename: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val isImage = filename.endsWith(".png")
        val isVideo = filename.endsWith(".mp4") || filename.endsWith(".webm")
        if (!isImage && !isVideo) return

        val collection = if (isImage)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = if (isImage) "${Environment.DIRECTORY_PICTURES}/Roam"
                           else         "${Environment.DIRECTORY_MOVIES}/Roam"
        val mime = RoamLogic.mimeFromFilename(filename)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = activity.contentResolver.insert(collection, values) ?: return
        activity.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        activity.contentResolver.update(uri, values, null, null)
        Log.d(TAG, "MediaStore 导出 $relativePath/$filename → uri=$uri")
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
            // 安全检查:必须在 recordings 目录下(防 ../../ 越权,RoamLogic 单元测试覆盖)
            if (!RoamLogic.isPathInside(filePath, recordingsDir)) {
                Log.w(TAG, "deleteVideo 拒绝越权路径: $filePath")
                return false
            }
            val ok = File(filePath).delete()
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

    /**
     * 用系统视频播放器打开本地视频(WebView <video> 标签直接读 internal storage 路径有限制,
     * 用 Intent.ACTION_VIEW + FileProvider 跳到系统视频 App 最直接)
     */
    @JavascriptInterface
    fun openVideoExternal(filePath: String) {
        Log.d(TAG, "openVideoExternal() filePath=$filePath")
        activity.runOnUiThread {
            try {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    Toast.makeText(activity, "文件不存在", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val authority = "${activity.packageName}.fileprovider"
                val uri = androidx.core.content.FileProvider.getUriForFile(activity, authority, file)
                val mime = RoamLogic.mimeFromFilename(filePath)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(Intent.createChooser(intent, "用什么打开?"))
            } catch (e: Exception) {
                Log.e(TAG, "openVideoExternal 失败", e)
                Toast.makeText(activity, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 测试 deep link:直接调起系统 Intent.ACTION_VIEW 打开 roam://scene/<name>
     * 用于 zhi 验证 deep link 是否真能调起 Roam(不依赖扫码或微信)。
     * 也可用于扫码 App 内部 scan 完后直接跳。
     */
    @JavascriptInterface
    fun openDeepLink(sceneOrUrl: String) {
        Log.d(TAG, "openDeepLink: $sceneOrUrl")
        activity.runOnUiThread {
            try {
                val uri = if (sceneOrUrl.contains("://")) android.net.Uri.parse(sceneOrUrl)
                          else android.net.Uri.parse("roam://scene/$sceneOrUrl")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "openDeepLink 失败", e)
                Toast.makeText(activity, "deep link 失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * B 路径:Roam 内置扫码(ZXing 调相机)
     * 扫到 roam:// scheme → MainActivity 自动 startActivity 跳同 App
     * 扫到其他 URL → 回调 JS window.onScanResult(url) 显示
     */
    @JavascriptInterface
    fun scanQrCode() {
        Log.d(TAG, "scanQrCode() 被 JS 调用")
        activity.runOnUiThread {
            val opts = com.journeyapps.barcodescanner.ScanOptions()
                .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                .setPrompt("对准二维码")
                .setBeepEnabled(true)
                .setOrientationLocked(false)
                .setCameraId(0)  // back camera
            qrScanLauncher.launch(opts)
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
