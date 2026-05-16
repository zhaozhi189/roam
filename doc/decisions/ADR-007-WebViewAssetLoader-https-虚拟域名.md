# ADR-007 · 用 WebViewAssetLoader 把 assets/ 暴露为 https:// 虚拟域名

> 状态:✅ Accepted(M1 第 0 周收尾验证通过,PlayCanvas GSplat 加载成功)
> 日期:2026-05-16
> 关联:[ADR-005 应用形态](ADR-005-应用形态-Android-WebView套壳.md)、[ADR-006 录屏方案](ADR-006-录屏方案-WebCodecs-mp4-muxer.md)

---

## 背景

ADR-005 选 WebView 套壳后,初版 MainActivity 用最直接的 `loadUrl("file:///android_asset/index.html")` 加载主页。M1 第 0 周收尾接入 PlayCanvas Engine 2.18.1 内置 GSplat handler 加载 vendor 在 `assets/scenes/` 的 .ply / .sog 文件时,JS 端报:

```
TypeError: Failed to fetch
  at fX.<anonymous> (file:///android_asset/index.html:337:47)
  at fX.fire (file:///android_asset/vendor/playcanvas.min.js…)
  at f0._onFailure …
```

根因:**`file://` scheme 下 `fetch()` 在 Android 11+ 被同源策略阻断**。即使 WebView 配置 `allowFileAccess=true` 和 `allowFileAccessFromFileURLs=true`,`fetch()` 仍受 CORS / scheme 安全限制(`<img src=file://>`、`<script src=>` 可以,但 XHR/fetch 不行)。PlayCanvas Engine 内部用 `fetch()` 加载 asset URL,因此挂掉。

## 决策

**用 `androidx.webkit:WebViewAssetLoader` 把 `assets/` 映射到 `https://appassets.androidplatform.net/assets/`**,主页和所有资源全部走这个虚拟 https 域名。

### 实现要点

```kotlin
val assetLoader = WebViewAssetLoader.Builder()
    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
    .build()
webViewClient = object : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest)
        = assetLoader.shouldInterceptRequest(request.url)
}
loadUrl("https://appassets.androidplatform.net/assets/index.html")
```

### 依赖

- `androidx.webkit:webkit:1.12.1`(libs.versions.toml 新增 `webkit` 版本号)

### `appassets.androidplatform.net` 是 Google 官方保留的虚拟域名

不会真的发起网络请求,所有 `https://appassets.androidplatform.net/*` 都被 WebView 拦截走 AssetsPathHandler 本地读 `assets/`,**零网络流量**,完全离线。

## 备选方案

| 选项 | 评价 | 没选的原因 |
|---|---|---|
| **Y · 保持 file:// + 各种 allowXXX 开关** | 改动最小 | Android 11+ 同源策略硬限制,`allowUniversalAccessFromFileURLs=true` 也常被 OEM/MagicOS 忽略;且 file:// 跨域行为各厂商不一致 |
| **X · 把所有 splat 文件转成 ArrayBuffer 走 JS Bridge 喂给 PlayCanvas** | 不改 scheme | 需要绕开 PlayCanvas Engine 内置 URL 加载链,API 复杂 + 性能折扣(base64 编码 vs 直接 fetch ArrayBuffer)|
| **W · 内嵌 Cronet / OkHttp 本地服务** | 灵活 | 引入大依赖,过度工程 |
| **Z · WebViewAssetLoader + https 虚拟域名** ✅ | 官方推荐,标准 | (本项) |

## 后果

### 好的影响

- **fetch / XHR / WebGL textureFromURL / Worker 全部正常** — 因为 https scheme 是浏览器一等公民 origin
- **PlayCanvas Engine 内置 asset 加载不需要任何改造**,标准用法即可
- **零网络流量**:虚拟域名被本地拦截
- **CORS 行为符合预期**:跨域请求按 https 标准 CORS 规则处理(本项目目前无跨域需求)
- **Service Worker / IndexedDB / localStorage 都正常工作**(以 origin 为 key,file:// 下行为异常)
- **Trace / DevTools 调试体验更好**:URL 在 chrome://inspect 里和正常网站一样

### 不好的影响 / 取舍

- 引入 `androidx.webkit:webkit` 1.12.1 依赖(~ 50KB AAR)
- 必须重写 `shouldInterceptRequest`,代码量增加 10 行(可接受)
- 注意:**`shouldInterceptRequest` 的拦截优先级高,如果未来加远程资源需要小心 path handler 不能误拦截**(目前只加 `/assets/`,远程 https 请求会直通)

## 实测数据(M1 第 0 周收尾验证)

| 测试 | 改 file:// 之前 | 改 https 之后 |
|---|---|---|
| PlayCanvas vendor 加载(2.2MB UMD) | ✅ 都正常(<script> 标签不受 fetch 限制) | ✅ |
| 立方体场景 | ✅ 119fps | ✅ 119fps |
| 公寓 SOG 8MB 加载 + 渲染 | ❌ Failed to fetch | ✅ **121fps** |
| 吉他 .compressed.ply 1.5MB 加载 + 渲染 | ❌ Failed to fetch | ✅ **123fps** |
| WebCodecs 录屏 + MP4 保存 | ✅ | ✅ |
| 微信分享后播放 | ✅ | ✅ |

## 何时回头看

- **加远程功能(在线下载 .ply / 云端协作)** → 远程 origin 需要正常 CORS 处理,代码中 `shouldInterceptRequest` 仅匹配 `appassets.androidplatform.net`,其它 origin 直通,目前安全
- **Service Worker 加 PWA 离线缓存** → 已支持(https origin 必备前提)

---

## 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| v0.1 | 2026-05-16 | 初版,M1 第 0 周收尾接 PlayCanvas GSplat 真渲染时踩坑后沉淀 |
