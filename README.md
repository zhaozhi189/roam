# Roam · 即境

> 个人周末项目 · 起源 2026-05-12 · **MVP 端到端验证通过 2026-05-16**

**用手机扫真实空间,在 App 内漫游,微信视频/二维码分享给朋友。** 完全本地运行,不上云,不收集数据,不需要登录。

---

## 当前能力(M1 + M2 收尾)

- ✅ **6 个内置 3DGS 场景**:🎲 立方体 / 🏠 公寓 SOG 8MB / 🎸 吉他 PLY 1.5MB / 🦎 蜥蜴 SPZ 17MB / 💀 骷髅 SOG 5MB / 🏍 摩托手 PLY 2.4MB
- ✅ **三种 3DGS 格式全支持**:`.ply`(PlayCanvas 原生) · `.sog`(PlayCanvas 原生) · `.spz`(Niantic,@spz-loader WASM 解码)
- ✅ **触摸漫游**:单指拖 yaw/pitch · 双指捏 zoom · 双击复位 · 3s 无操作恢复自动旋转
- ✅ **沉浸模式**:⛶ 一键全屏(0 Android fullscreen API 依赖,纯 CSS)
- ✅ **录屏 → 微信分享**:WebCodecs + mp4-muxer 硬件 H.264 直出 MP4(实测朋友收到能播放)
- ✅ **截屏 → 微信分享**:canvas.toDataURL → PNG(快门 flash 效果)
- ✅ **App 内置扫码**(ZXing):扫 `roam://scene/<name>` deep link 直跳场景
- ✅ **二维码生成**(qrcode-generator):为任意场景生成 deep link QR
- ✅ **Deep link**:`roam://scene/apartment` 等可调起 Roam 任意场景
- ✅ **GitHub Pages landing page** 备用:`site/` 静态站点解决微信内 ERR_UNKNOWN_URL_SCHEME 问题
- ✅ **SAF 选自己文件**:.ply/.sog 直接渲染,.spz WASM 解码

## 快速试用

```bash
cd client
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # JDK 21
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.roam.app/.MainActivity
```

启动后默认加载 🏠 公寓场景。**单指拖 = 转视角,双指捏 = 缩放,双击 canvas = 复位,⛶ = 沉浸,Back = 退沉浸**。

## 单元测试

```bash
cd client
./gradlew testDebugUnitTest        # JVM 直跑,无需设备(~3s,46 个测试)
```

测试覆盖 `RoamLogic` 内的纯逻辑:
- 路径越权检查(`deleteVideo` 安全 — 防 `../../etc/passwd`)
- 文件名 sanitize(防注入)
- mime 推断(微信分享 + 系统图库)
- Deep link 解析(`roam://scene/<name>` 防 JS/SQL/Unicode 注入)
- WebView 入口 URL 决策(deep link > extras > 默认 apartment)
- 文件名 → 场景 tag 解析

不测的(可接受):JS UI 交互、WebCodecs 编码、ZXing 扫码 — 设备-only 验证。

## 端到端自动化(adb,需设备)

```bash
./scripts/e2e-test.sh [out-dir]     # 默认 /tmp/roam-e2e/
```

8 段:冷启动 / 切 6 场景 / 沉浸 / 二维码 / 截屏 / 录屏 / deep link / no-auto,每步截屏归档供回看。

## 项目结构

```
roam/
├── client/                            Android Compose + WebView 工程
│   └── app/src/main/
│       ├── assets/
│       │   ├── index.html             ① 在线 demo / ② 6 场景 / ③ SAF 选文件 / ④ 录屏 / ⑤ 二维码 / ⑥ 扫码 / ⑦ 截屏
│       │   ├── vendor/
│       │   │   ├── playcanvas.min.js              v2.18.1 UMD (2.2MB)
│       │   │   ├── mp4-muxer.min.js               v5.2.2 IIFE (31KB)
│       │   │   ├── qrcode.min.js                  qrcode-generator v1.4.4 (20KB)
│       │   │   └── spz-loader-playcanvas.umd.cjs  @spz-loader/playcanvas v0.3.1 (242KB,内嵌 WASM)
│       │   └── scenes/                            6 个内置 3DGS demo,~33MB
│       ├── java/com/roam/app/
│       │   ├── MainActivity.kt                    Compose + WebView + WebViewAssetLoader + Deep link + Auto extras
│       │   ├── RoamBridge.kt                      JS Bridge:pickSpzFile / 录屏 / 分享 / 扫码 / 截屏保存 / openDeepLink
│       │   ├── ScreenRecorderService.kt          (Phase 2 退路:MediaProjection 路径,保留)
│       │   └── ShareHelper.kt                     Intent.ACTION_SEND + FileProvider
│       └── res/drawable/ic_launcher_{bg,fg}.xml   3DGS 点云抽象图标
├── doc/                               需求 / 架构 / ADR / 验证 / M0+M1 总结
│   ├── 01-需求说明.md                 v0.2.1(本地化重写)
│   ├── 03-技术架构.md                 v0.3.1(2 层架构)
│   ├── 06-技术可行性验证.md            v0.9(M0+M1+M2 收尾)
│   ├── 07-M0总结-与-M1开胃菜任务清单.md v0.3
│   └── decisions/                     ADR
│       ├── ADR-004-MVP-方向调整-本地优先.md
│       ├── ADR-005-应用形态-Android-WebView套壳.md
│       ├── ADR-006-录屏方案-WebCodecs-mp4-muxer.md
│       └── ADR-007-WebViewAssetLoader-https-虚拟域名.md
├── site/                              GitHub Pages 静态 landing page(微信 scheme 解决方案)
├── prototypes/                        早期 HTML 产品原型
└── README.md                          本文件
```

## 关键决策(必读 ADR)

| ADR | 主题 | 为什么 |
|---|---|---|
| [004](doc/decisions/ADR-004-MVP-方向调整-本地优先.md) | MVP 从 Web SaaS 转本地 Android | 个人项目 zhi 不想运维后端,微信分享视频是用户首选传播路径 |
| [005](doc/decisions/ADR-005-应用形态-Android-WebView套壳.md) | Android WebView 套壳 | 跨平台代码量最少,iOS 后续再说 |
| [006](doc/decisions/ADR-006-录屏方案-WebCodecs-mp4-muxer.md) | WebCodecs 录屏 | MagicOS MediaProjection 死循环,WebCodecs 完全 JS 闭环 |
| [007](doc/decisions/ADR-007-WebViewAssetLoader-https-虚拟域名.md) | WebViewAssetLoader | `file://` scheme 下 PlayCanvas fetch GSplat 被 Android 11+ 同源策略阻断 |

## 实测性能(Magic7 Pro / 骁龙 8 Gen4 / Adreno 830)

| 项 | 实测 |
|---|---|
| 立方体 | 119-121 fps |
| 公寓 SOG 8MB 加载 | 178-345 ms · 渲染 120 fps |
| 吉他 PLY 1.5MB 加载 | < 50 ms · 渲染 123 fps |
| 蜥蜴 SPZ 17MB | WASM 解码 1-3s · 渲染(待测) |
| 录 10s 1920×1080@30fps H.264 | 1.4-3 MB MP4 |
| 微信视频通话期间 visibilitychange | ✅ 自动停录保存 |
| 微信发给朋友 | ✅ 接收方正常播放(2026-05-16 实测) |

## adb 自动化测试(debug build)

```bash
# Deep link 进场景
adb shell am start -W -a android.intent.action.VIEW -d "roam://scene/apartment"
adb shell am start -W -a android.intent.action.VIEW -d "roam://scene/spz"      # SPZ 蜥蜴

# Activity extras
adb shell am start -n com.roam.app/.MainActivity --es auto apartment
adb shell am start -n com.roam.app/.MainActivity --es auto none                 # 跳过默认场景

# 运行中触发 UI(broadcast)
adb shell am broadcast -a com.roam.app.AUTO --es cmd guitar
adb shell am broadcast -a com.roam.app.AUTO --es cmd record
adb shell am broadcast -a com.roam.app.AUTO --es cmd stop
adb shell am broadcast -a com.roam.app.AUTO --es cmd qr-apt
```

支持的 cmd:
- 场景:`apartment / guitar / cube / spz / skull / biker`
- 录屏 / 截屏 / 扫码:`record / stop / list / snap / scan / pick`
- 二维码:`qr-apt / qr-guitar / qr-cube`
- 沉浸 / 复位:`immersive / reset-view`

## 已知限制 / M2+ TODO

- ⏸ **自扫工具链** — Scaniverse 国内登录卡(候选:Apple ID 绕 / 代理首登 / KIRI 重试 / Polycam Web)
- ⏸ **微信内 deep link** — 微信屏蔽自定义 scheme;**workaround**:用 ⑥ Roam 内扫码 / 系统相机扫码 / 部署 site/ GitHub Pages 跳转
- ⏸ **App Links** — 需 release 签名稳定 + domain verify,M2+
- ⏸ **接收方 Roam 安装引导** — 朋友扫码后,App 没装怎么办?现在只能看视频
- ⏸ **场景元数据** — 未来场景可能成百上千,UI 需 grid/搜索/收藏

## 不在计划内(明确不做)

- ❌ 后端 / 云存储 / 用户系统(ADR-004)
- ❌ Web SaaS / 公开 URL(ADR-004)
- ❌ iOS(ADR-005,Phase 3 再说)
- ❌ 微信 OpenSDK 深度集成(个人开发者无企业资质)
- ❌ AI / LLM(MVP-Phase 2 都不需要)
- ❌ 多场景拼接 / 大空间扫描(Phase 3)

## License

MIT(代码) · 各 vendor 库保留原作者声明:
- PlayCanvas Engine — MIT
- mp4-muxer — MIT (Vanilagy)
- qrcode-generator — MIT (Kazuhiko Arase)
- @spz-loader/playcanvas — Apache-2.0 (drumath2237)
- ZXing Android Embedded — Apache-2.0 (journeyapps)
- Demo splat 资源 — PlayCanvas examples (MIT) / Niantic spz samples
