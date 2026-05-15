package com.roam.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.roam.app.ui.theme.RoamTheme

/**
 * MVP 第 0 周入口:WebView 加载 assets/index.html
 * 验证 WebView 在 Compose 里能跑 + JS 启用 + 硬件加速
 * 后续 H9 加 PlayCanvas + @spz-loader/playcanvas
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoamTheme {
                RoamWebView(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RoamWebView(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // WebView 基础配置
                settings.apply {
                    javaScriptEnabled = true               // PlayCanvas 需要
                    domStorageEnabled = true               // localStorage / IndexedDB
                    allowFileAccess = true                 // 读 internal storage 的 .spz
                    allowContentAccess = true              // 读 content:// URI(SAF)
                    mediaPlaybackRequiresUserGesture = false
                    setSupportZoom(false)                  // 3D 漫游不需要双指缩放
                    builtInZoomControls = false
                    displayZoomControls = false
                }
                webViewClient = WebViewClient()             // 默认拦截外部跳转
                webChromeClient = WebChromeClient()         // console.log 透传 / 全屏支持
                // 加载 assets 内的本地 HTML
                loadUrl("file:///android_asset/index.html")
            }
        }
    )
}