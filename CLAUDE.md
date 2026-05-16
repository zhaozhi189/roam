# Roam 项目级 instructions(给 Claude)

> 全局 instructions 见 `~/.claude/CLAUDE.md`(语言/通用编码原则/JDK 切换)。本文件是 Roam 项目专属补充。

## 项目定位(不要变)

- **本地 Android 单机 + 微信视频/二维码分享**(ADR-004)
- **WebView 套壳 + PlayCanvas Engine 渲染**(ADR-005)
- **个人周末项目**,不追求商业化
- MVP 端到端已验证通过(2026-05-16)

## 默认技术栈(讨论时按这个说,不要建议替代)

| 层 | 选型 | 锁定理由 |
|---|---|---|
| App 壳 | Android Compose + WebView | ADR-005;iOS 后续再说 |
| 3D 引擎 | PlayCanvas Engine 2.18.1 UMD | vendor 在 `assets/vendor/`,不要换 Three.js |
| 3DGS 加载 | PlayCanvas 内置 gsplat(.ply/.sog) + @spz-loader(.spz) | UMD 共存,Engine 实例只能一个 |
| 录屏 | WebCodecs + mp4-muxer 直出 H.264 MP4 | ADR-006;**不要用 MediaProjection**(MagicOS 死循环) |
| 分享 | Intent.ACTION_SEND + FileProvider | 不要装 微信 OpenSDK(个人无企业资质) |
| 资源加载 | WebViewAssetLoader → https://appassets.androidplatform.net/ | ADR-007;**不要用 file://** scheme |
| 扫码 | ZXing Android Embedded | 不要换 ML Kit(国行机无 GMS) |
| 二维码 | qrcode-generator UMD | vendor 在 `assets/vendor/qrcode.min.js` |

## 明确不做(用户提了也要先确认)

- ❌ 后端 / 云存储 / 用户系统(已 ADR-004 决定)
- ❌ Web SaaS / 公开 URL
- ❌ iOS(Phase 3 才考虑)
- ❌ 微信 OpenSDK / 小程序
- ❌ AI / LLM 任何接入(MVP-Phase 2)
- ❌ React Native / Flutter / Unity
- ❌ KIRI / Polycam / Luma AI(自扫只用 Scaniverse,即使它现在国内登录卡)

## 工程规范

### 重大决策走 ADR
任何「方向调整 / 新依赖 / 替代既有方案」**先写 ADR** 到 `doc/decisions/ADR-NNN-标题.md`,再实施。模板参考 ADR-004~007。

### Commit 风格
- 中文 commit message,带 emoji 和段落结构
- 类型前缀:`feat() / fix() / docs() / chore() / refactor()`
- 写「为什么」不仅是「做了什么」
- 多行 commit 用 HEREDOC

### 文档版本号
- `doc/06-技术可行性验证.md` 维护一个 v0.X 版本号 + 修订记录表(最新在最下)
- 重大改动加版本号 + 修订内容

### 不要写
- 测试代码(zhi 一人测,手动够用)
- 多语言(中文 only)
- 过度抽象/工厂模式(场景才 6 个,直接写 6 个 button)

## 装机 / 测试 / 调试

### 构建
```bash
cd client
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 自动化测试桥(debug build 内置)
```bash
adb shell am start -n com.roam.app/.MainActivity --es auto apartment   # 直接进场景
adb shell am broadcast -a com.roam.app.AUTO --es cmd guitar            # 运行中切换
./scripts/e2e-test.sh                                                  # 一键 8 段端到端
```
cmd 列表(运行中):
- 场景:`apartment / guitar / cube / spz / skull / biker`
- 操作:`record / stop / list / snap / scan / pick`
- 二维码:`qr-apt / qr-guitar / qr-cube`
- 视图:`immersive / reset-view`

### 单元测试(JVM,无设备)
```bash
cd client && ./gradlew testDebugUnitTest
```
46 个测试覆盖 `RoamLogic`(纯逻辑层),约 3 秒。改 RoamBridge/ShareHelper/MainActivity 内的对应逻辑前先看 `RoamLogicTest`,确保契约不变。

### Deep link 测试
```bash
adb shell am start -W -a android.intent.action.VIEW -d "roam://scene/apartment"
```

### 调试日志
- **MagicOS 屏蔽第三方 App logcat + 屏蔽进程 PID**(实测)→ Chrome remote devtools 也接不上
- 错误用 `console.error` 写到 #pc-status 红字 + `RoamBridge.toast` 弹窗
- 截屏诊断:`adb exec-out screencap -p > /tmp/x.png`

## 已知坑(踩过的)

| 坑 | 原因 | 解 |
|---|---|---|
| `Failed to fetch` 加载 .ply/.sog | Android 11+ file:// 同源策略 | WebViewAssetLoader + https 虚拟域名(ADR-007) |
| MediaProjection 录屏死循环 | MagicOS 不尊重 createConfigForDefaultDisplay | WebCodecs(ADR-006) |
| 微信视频不能播放 | 微信不支持 WebM | 用 WebCodecs avc1.42E01F MP4 |
| `new VideoFrame(canvas)` 失败 | WebGL canvas 没 colorSpace | 走 captureStream + MediaStreamTrackProcessor |
| canvas.toDataURL 黑屏 | WebGL 默认不保留 drawing buffer | `graphicsDeviceOptions: { preserveDrawingBuffer: true }` |
| 微信扫码 ERR_UNKNOWN_URL_SCHEME | 微信屏蔽自定义 scheme | 用 Roam 内扫码(ZXing) / 系统相机扫 / GitHub Pages landing |
| ZXing scanQrCode 弹相机权限 | Magic7 Pro 需手动确认 | 首次提示,后续记忆 |

## 切勿擅自做的事

- 不要 `git push`(zhi 决定何时 push,即使代码已 commit)
- 不要 `git push --force` / `git reset --hard`(zhi 没明确授权)
- 不要修改 `client/app/build.gradle.kts` 的 `agp` / `kotlin` / `composeBom` 版本(冷启动很慢)
- 不要写 release 签名密钥
- 不要把 vendor 文件 minified 后再压缩(diff 看不清)

## 加新功能 checklist

1. 是否在 MVP 范围?对照 ADR-004 五大功能(扫描/上传/漫游/录屏/分享)
2. 用现有 vendor 库能不能搞定?**优先复用**
3. 引入新依赖前问 zhi(任何 implementation libs.xxx)
4. UI 改动放 `client/app/src/main/assets/index.html`(Web UI,改完即生效不用重编)
5. Native 能力放 `RoamBridge.kt`(@JavascriptInterface 暴露给 JS)
6. 加自动化测试桥(runAuto map 加新 cmd)
7. 重大改动写 ADR + 更新 06 文档版本
