package com.roam.app

import android.app.Activity
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher

/**
 * JS Bridge 暴露给 WebView 内 JavaScript 的原生能力
 *
 * 在 JS 端通过 `window.RoamBridge.xxx()` 调用。
 *
 * 注意:`@JavascriptInterface` 方法在 WebView 的非 UI 线程执行,涉及 UI 操作必须
 * `activity.runOnUiThread { ... }` 包一层,否则崩溃。
 *
 * M1 第 0 周首版:只暴露 SPZ 文件选择 + Toast + Log,后续按需扩展
 * (录屏 / 分享 / 微信等放到独立 Bridge 类避免单一 God class)。
 */
class RoamBridge(
    private val activity: Activity,
    private val spzPickerLauncher: ActivityResultLauncher<Array<String>>
) {
    /**
     * 唤起系统 Storage Access Framework 选 .spz 文件
     *
     * 用户选完后,MainActivity 的 ActivityResult callback 会通过
     * `webView.evaluateJavascript("window.onSpzPicked('content://...')")` 把 URI 传回 JS。
     *
     * 注:.spz 没有标准 mime type,系统选择器允许任意 mime,JS 端拿到 URI 后再判扩展名。
     */
    @JavascriptInterface
    fun pickSpzFile() {
        Log.d(TAG, "pickSpzFile() 被 JS 调用,启动 SAF")
        activity.runOnUiThread {
            spzPickerLauncher.launch(arrayOf("*/*"))
        }
    }

    /**
     * 给 JS 一个简单的 Toast 通道(调试用)
     */
    @JavascriptInterface
    fun toast(message: String) {
        Log.d(TAG, "toast: $message")
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * JS console.log 桥接到 Android logcat,便于 adb logcat 抓
     * tag 会拼到 `Roam/<tag>` 方便过滤
     */
    @JavascriptInterface
    fun log(tag: String, message: String) {
        Log.d("Roam/$tag", message)
    }

    /**
     * JS 查询 native 的能力探针(后续可扩展返回 MediaProjection 是否可用等)
     */
    @JavascriptInterface
    fun nativeInfo(): String {
        return """{"version":"M1-week0","platform":"android","abi":"${android.os.Build.SUPPORTED_ABIS.joinToString(",")}"}"""
    }

    companion object {
        private const val TAG = "RoamBridge"
        const val JS_INTERFACE_NAME = "RoamBridge"
    }
}
