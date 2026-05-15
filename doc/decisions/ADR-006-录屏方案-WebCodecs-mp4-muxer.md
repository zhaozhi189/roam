# ADR-006 · H7 录屏方案选 WebCodecs + mp4-muxer

> 状态:✅ Accepted(M1 第 0 周已真机验证跑通)
> 日期:2026-05-15
> 关联:[ADR-005 应用形态](ADR-005-应用形态-Android-WebView套壳.md)、[06-技术可行性验证.md](../06-技术可行性验证.md) v0.4 H7

---

## 背景

ADR-005 决定 Android WebView 套壳后,H7 视频录制原计划用 **MediaProjection + MediaRecorder**(Android 系统原生录屏,输出 H.264 MP4)。M1 第 0 周真机调试中,这一路径在 Magic7 Pro / MagicOS 10 / Android 16 上**实测体验破裂**:

1. **MagicOS 不尊重 `MediaProjectionConfig.createConfigForDefaultDisplay()`**:Android 14+ 标准 API 用来强制「整个屏幕」模式,跳过让用户选「单个应用 / 整个屏幕」的步骤。MagicOS 忽略这个 config,系统对话框还是带「单/整」选项,**默认是「单个应用」**
2. **「单个应用」模式让 Roam 切到 launcher**:用户允许后,系统让他「在最近任务里选要录的 App」,Roam 直接被最小化 → ScreenRecorderService 录到 launcher 画面 → 0 字节文件
3. **每次开始录屏都要重新授权**:Android 系统设计,Google 在 14+ 加强(防恶意应用偷录屏),**不能绕过**,所有录屏类 App(微信、抖音、AZ Recorder 等)行为一致

zhi 反馈这是「死循环」:点开始 → 切走 → 文件空 → 再点 → 又切走 → 永远录不到内容。

## 决策

**改用 WebCodecs + mp4-muxer 在 WebView 内直出 H.264 MP4**,完全跳出 MediaProjection 体系。

### 技术细节

```
canvas.captureStream(30fps)
  ↓
MediaStreamTrackProcessor → ReadableStream<VideoFrame>  (Chrome 94+)
  ↓
async loop: reader.read() → VideoFrame(自带正确 colorSpace)
  ↓
VideoEncoder.encode(frame, { keyFrame: idx % 30 === 0 })
  codec='avc1.42E01F' (H.264 Baseline 3.1) · bitrate=3Mbps · framerate=30
  → 系统硬件 H.264 编码器(Adreno 830 加速)
  ↓
output callback → Mp4Muxer.addVideoChunk(chunk, meta)
  ↓
encoder.flush() → muxer.finalize() → ArrayBuffer
  ↓
base64 → JS Bridge.saveVideoBase64 → internal storage *.mp4
  ↓
Share Intent + FileProvider → 系统分享面板 → 微信 → ✅ 播放
```

### 依赖

- **vendor/mp4-muxer.min.js**(31KB IIFE,挂 `window.Mp4Muxer`)— Vanilagy/mp4-muxer v5.2.2
- **WebCodecs API**:Android Chrome 102+ 原生,WebView 同 Chromium 内核,Magic7 Pro Chrome 147 完全支持
- **MediaStreamTrackProcessor**:Chrome 94+,WebView 同支持

## 备选方案

| 选项 | 评价 | 没选的原因 |
|---|---|---|
| **Y · MediaProjection 原生 MP4** | Android 标准方案,工业级稳定 | MagicOS 不尊重 createConfigForDefaultDisplay,App 被切走;每次都要授权(系统强制),用户体验劣化 |
| **B · canvas.captureStream + MediaRecorder(WebM 输出)** | 0 授权 + 0 切走 + 工程量小 | 输出 WebM(VP8/VP9),**微信不支持播放 WebM**(实测验证),只支持 MP4/H.264 |
| **X · ffmpeg.wasm 转码** | WebM → MP4 转码,微信兼容 | ffmpeg-core.wasm ~25MB(APK 翻倍),转码耗时(60s 视频 30-60s wasm 转码),用户体验差 |
| **Z · WebCodecs + mp4-muxer** ✅ | 0 授权 / 0 切走 / 标准 H.264 MP4 / 硬件加速实时编码 / mp4-muxer 31KB | (本项) |
| **C · 系统下拉通知栏录屏** | 体验最好,无需 App 内做 | system signature 权限,普通 App 拿不到;且产物文件分享/管理复杂 |

## 后果

### 好的影响

- **完全跳出 MediaProjection 死循环**:无任何系统授权弹窗,App 不会被切走
- **录制秒响应**:点击立即开始,无 1-2 秒系统对话框延迟
- **微信原生兼容**:H.264 MP4 是微信视频分享标准格式
- **硬件加速**:WebCodecs 底层就是 MediaCodec(跟 MediaProjection 共用硬件 H.264 编码器),性能等价
- **依赖轻**:mp4-muxer 31KB(对比 ffmpeg.wasm 25MB)
- **完全 JS 内闭环**:无 ForegroundService / 无通知栏录屏图标 / 无系统级介入
- **3 Mbps × 60s ≈ 22.5MB**:完美卡在微信 25MB 限制内

### 不好的影响 / 取舍

- **只录 canvas 内容,不录 UI 操作**:用户操作虚拟摇杆的手指不会出现在视频里
  - **但这其实是好事**:接收方在微信看到纯 3D 漫游(像 Vlog 短片),而不是「手指拖 UI」的录制感
- **依赖较新浏览器 API**(WebCodecs / MediaStreamTrackProcessor):Chrome 94+,Android WebView 通常跟 Chrome 同步更新,Magic7 Pro Chrome 147 OK,**老 Android 设备(Android 9 以下)可能不可用**
  - MVP 阶段不影响(Magic7 Pro 是主测设备),M2 如果发现老设备占比大,再加 MediaProjection fallback
- **VideoFrame 不能直接从 WebGL canvas 构造**:`new VideoFrame(canvas)` 在 PlayCanvas WebGL canvas 上抛 `Cannot read properties of null (reading 'colorSpace')`,**必须用 captureStream + MediaStreamTrackProcessor 间接拿 frame**(已在代码中实现)
- **第一次启动需要 PlayCanvas 已渲染**(captureStream 在 canvas 没渲染时给空 stream)

### Phase 2 退路保留

`ScreenRecorderService.kt` + `ShareHelper.kt` + AndroidManifest 里的 FOREGROUND_SERVICE_MEDIA_PROJECTION 权限**全部保留作 Phase 2 退路**。如果 M2 阶段:

- 发现 WebCodecs 在某些设备不可用 → 降级 MediaProjection
- 用户真需要录 UI 操作 → MediaProjection 全屏

直接用现有 Kotlin 代码即可。

## 实测数据(M1 第 0 周 H7 验证)

| 项 | 实测 |
|---|---|
| 录制启动延迟 | < 100ms(0 系统对话框) |
| App 切走 | ❌ 不会(完全在 WebView 内) |
| 录制 5-10s 文件大小 | 1.5-3 MB |
| 视频规格 | 1920×1080 等比缩 / 30fps / 3Mbps H.264 Baseline |
| 微信播放 | ✅ 正常,画质对比原片可接受 |
| 录制中 PlayCanvas 帧率影响 | 待测(立方体场景下未明显下降) |

## 何时回头看

- **M2 实测发现 WebView 内 MediaProjection 真的能正常工作**(MagicOS 后续修复),可以重新评估
- **3DGS 场景录制时帧率明显下降**(WebCodecs 编码 + PlayCanvas 渲染竞争 GPU),考虑硬件转码 fallback
- **微信兼容性出现新问题**(虽然 H.264 MP4 是标准,但万一)→ 加 ffmpeg.wasm 重新封装兜底

---

## 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| v0.1 | 2026-05-15 | 初版,真机验证后沉淀 |
