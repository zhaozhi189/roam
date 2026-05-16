package com.roam.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.roam.app.ui.theme.RoamTheme
import org.json.JSONObject

/**
 * MVP 入口:WebView 加载 assets/index.html
 *
 * 架构:
 *   MainActivity
 *     ├ webView(私有引用,供 ActivityResult callback 用)
 *     ├ spzPickerLauncher(SAF 文件选择,回调时把 URI 通过 evaluateJavascript 传回 JS)
 *     └ RoamWebView (Composable) → AndroidView → WebView
 *           └ addJavascriptInterface(RoamBridge, "RoamBridge")
 *
 * JS 调用流程:
 *   1. JS:  window.RoamBridge.pickSpzFile()
 *   2. Kotlin RoamBridge.pickSpzFile() 在 UI 线程启动 SAF launcher
 *   3. 系统 SAF 弹出,用户选文件
 *   4. MainActivity.spzPickerLauncher callback 拿到 Uri
 *   5. webView.evaluateJavascript 调 window.onSpzPicked(uri) 把 URI 传回 JS
 */
class MainActivity : ComponentActivity() {

    private var webView: WebView? = null

    /**
     * SAF Open Document launcher。结果通过 evaluateJavascript 异步传回 JS。
     * 注册必须在 onCreate 里、setContent 之前(activity registry 要求)。
     */
    private val spzPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val js = if (uri != null) {
            // JSONObject.quote 自动加引号 + 转义,防止 URI 里特殊字符破坏 JS 语法
            "window.onSpzPicked && window.onSpzPicked(${JSONObject.quote(uri.toString())})"
        } else {
            "window.onSpzPicked && window.onSpzPicked(null)"
        }
        Log.d(TAG, "SAF returned uri=$uri,calling JS: $js")
        webView?.post { webView?.evaluateJavascript(js, null) }
    }

    /**
     * H7 MediaProjection 权限请求结果。允许后启动 ScreenRecorderService 开始录制。
     * 用户拒绝时 resultCode = RESULT_CANCELED,不启动 service。
     */
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d(TAG, "MediaProjection 权限已允许,启动 ScreenRecorderService")
            val svcIntent = Intent(this, ScreenRecorderService::class.java)
                .putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ScreenRecorderService.EXTRA_RESULT_DATA, result.data)
            startForegroundService(svcIntent)
            webView?.post {
                webView?.evaluateJavascript("window.onRecordingStarted && window.onRecordingStarted()", null)
            }
        } else {
            Log.w(TAG, "MediaProjection 权限被拒绝")
            webView?.post {
                webView?.evaluateJavascript("window.onRecordingDenied && window.onRecordingDenied()", null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 开 WebView 远程 DevTools 调试,Mac Chrome 输入 chrome://inspect/#devices 即可看到
        // (debug build 才开;release 默认关闭防泄露)
        WebView.setWebContentsDebuggingEnabled(true)
        enableEdgeToEdge()
        setContent {
            RoamTheme {
                RoamWebView(
                    modifier = Modifier.fillMaxSize(),
                    onWebViewCreated = { wv ->
                        webView = wv
                        wv.addJavascriptInterface(
                            RoamBridge(this@MainActivity, spzPickerLauncher, mediaProjectionLauncher),
                            RoamBridge.JS_INTERFACE_NAME
                        )
                        Log.d(TAG, "WebView created and RoamBridge injected")
                    }
                )
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RoamWebView(
    modifier: Modifier = Modifier,
    onWebViewCreated: (WebView) -> Unit = {}
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true               // PlayCanvas 需要
                    domStorageEnabled = true               // localStorage / IndexedDB
                    allowFileAccess = true                 // 读 internal storage 的 .spz
                    allowContentAccess = true              // 读 content:// URI(SAF 返回的)
                    mediaPlaybackRequiresUserGesture = false
                    setSupportZoom(false)                  // 3D 漫游不需要双指缩放
                    builtInZoomControls = false
                    displayZoomControls = false
                }

                // WebViewAssetLoader 把 assets/ 暴露到 https://appassets.androidplatform.net/assets/
                // 关键:用 https:// scheme 让 WebView 把 page 当成普通网页 → fetch() 才能正常工作
                // file:// scheme 下 fetch() 在 Android 11+ 受同源策略限制,PlayCanvas 加载 .ply/.sog 会 Failed to fetch
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
                }
                webChromeClient = object : WebChromeClient() {
                    // 把 JS console.log/warn/error 桥到 Android logcat,tag = Roam/WebView
                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                        val level = when (msg.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> "E"
                            ConsoleMessage.MessageLevel.WARNING -> "W"
                            ConsoleMessage.MessageLevel.LOG -> "I"
                            ConsoleMessage.MessageLevel.DEBUG -> "D"
                            else -> "V"
                        }
                        Log.i("Roam/WebView", "[$level ${msg.lineNumber()}] ${msg.message()}")
                        return true
                    }
                }
                onWebViewCreated(this)
                loadUrl("https://appassets.androidplatform.net/assets/index.html")
            }
        }
    )
}
