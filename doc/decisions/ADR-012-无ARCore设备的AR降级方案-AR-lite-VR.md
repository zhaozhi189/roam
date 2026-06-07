# ADR-012 · 无 ARCore 设备的 AR 降级方案(AR-lite + VR-immersive)

> 状态:✅ Accepted(2026-05-31 zhi 拍板,Android 优先,AR-lite + VR 双 fallback 都做,iOS 留 Phase 3)
> 日期:2026-05-31
> 关联:[ADR-011 AR 集成方案](ADR-011-AR集成方案-PlayCanvas-WebXR-hit-test-anchor.md)、[ADR-005 WebView 套壳](ADR-005-应用形态-Android-WebView套壳.md)

---

## ⚠️ 2026-06-06 重大更新:本 ADR 的前提已被实测推翻

> **本 ADR(及 ADR-011 v0.2)的核心前提「Magic7 Pro 无 GMS / 无 ARCore,真 WebXR AR 走不通」在 2026-06-06 复测中被证伪。** 下方「背景 / 决策」按当时(2026-05-31)的认知保留作历史记录,**真实现状以本节为准**。

**2026-06-06 zhi 主测机 Magic7 Pro(PTP-AN10)实测**:

| 检查项 | 2026-05-31(v0.2/v0.3) | 2026-06-06(本次复测) |
|---|---|---|
| `com.google.ar.core`(ARCore) | ❌ sideload,不完整 | ✅ **已正规安装** |
| GMS + Play 商店(`com.google.android.gms` / `com.android.vending`) | ❌ 无 | ✅ **全套都有** |
| Chrome | sideload 149 | ✅ 149(正规) + WebView 147 |
| `isSessionSupported('immersive-ar')` | ✅ true | ✅ true |
| `requestSession('immersive-ar')` | ❌ **NotSupportedError**(5 种配置全 FAIL,设备白名单硬墙) | ✅ **成功启动**(官方 `immersive-ar-session` sample START AR 蓝色可点 → 进 session → 系统弹「已关闭 Immersive AR Session」= session 真建立过) |

**反转的根本原因(推断)**:5-31 测的是 **sideload** 的 ARCore 1.54 + Trichrome,过不了 Google 的设备认证(Play Integrity / ARCore 白名单);6-6 这台机已装上 **正规谷歌服务框架 + Play 商店分发的 ARCore**,设备通过了认证,`requestSession` 才放行。即「白名单硬墙」并非永久,补齐正规 GMS 即可解锁。

**取证**:`doc/screenshots/2026-06-06-webxr-ar-start-button.png`(START AR 蓝色可点)、`doc/screenshots/2026-06-06-webxr-ar-session-closed.jpg`(session 关闭系统通知)。

**对现状的影响**:

1. **真 WebXR AR 现在在 Magic7 Pro 的 Chrome 里可行** —— 不用再「借 Pixel/米/三星」(原 ADR-011 fallback 引导文案、本文档「白名单设备」假设作废)。
2. **但仅限 Chrome,不含 Roam WebView** —— 系统 WebView 默认不暴露 `navigator.xr`(本文档「组合策略」表最后一行的结论**依然成立**),所以真 AR 接进 Roam 必须走「Intent 跳系统 Chrome」或「Chrome Custom Tabs」,不能指望 Roam 套壳的 WebView 直接跑。详见下方决策延伸。
3. **AR-lite / VR-imm 双 fallback 仍有价值** —— 给「没补 GMS 的纯国行机」「老设备」兜底,不作废,降级为 fallback 而非唯一选项。

**落地方向(待 zhi 拍板,做的话补 v0.5 ADR)**:

| 方案 | 做法 | 改动量 | 代价 |
|---|---|---|---|
| **B. Intent 跳系统 Chrome**(推荐起步) | 点 AR → 拉起 Chrome 打开 AR 页(GitHub Pages landing) | 几乎为零,复用现有 VR 的 enterVR 跳 Chrome 套路 | 跳出 App,AR 页内无 JS↔Kotlin bridge(录屏/分享) |
| **A. Chrome Custom Tabs** | App 内叠一层系统 Chrome 跑 AR 页 | 加 `androidx.browser` 依赖 + 改入口 | 体验更连贯,但 Custom Tabs 不能注入 JsInterface,AR 页内仍无 bridge |
| **C. 维持 AR-lite/VR fallback** | 不动,纯 WebGL+陀螺仪 | 0 | 不是真 AR,给无 ARCore 机兜底 |

---

## 背景

ADR-011 锁定 PlayCanvas WebXR 路线作为 AR 主方案,但 **2026-05-31 M8 沙盘验证明确**:

- ✅ 白名单设备(Pixel / 三星国际 / 米国际)→ ADR-011 全套能跑
- ❌ Magic7 Pro(zhi 主测机,无 GMS 认证)→ `requestSession('immersive-ar')` 100% 被 Google ARCore 白名单拒绝(详见 ADR-011 v0.2 M8 章节)

这意味着 **zhi 自己** 在 Roam 里**永远看不到 AR 效果**,只能依赖借朋友手机或买二手白名单机。对个人项目体验**很挫**。

但同次验证 ALSO 发现 Magic7 上以下 3 个 API **完全可用**:

| API | Magic7 实测(2026-05-31) | 用途 |
|---|---|---|
| `navigator.xr.requestSession('immersive-vr')` | ✅ **可启动!** Chrome 进入 Cardboard 配对界面 | 真 WebXR VR session(陀螺仪 + 立体 stereo) |
| `navigator.mediaDevices.getUserMedia({facingMode:'environment'})` | ✅ 后摄完美,640×480,exposure/focus/zoom 可控 | 把现实相机画面叠在 splat 后面 |
| `DeviceOrientationEvent` | ✅ 不要权限,alpha/beta/gamma 实时返回 | 陀螺仪转视角看 splat |

这 3 项**完全绕开 ARCore 白名单**(VR session 用 Cardboard backend、getUserMedia 是浏览器原生、DeviceOrientation 是传感器 API,全跟 Google AR 服务无关)。

**结论**:Magic7 不能跑 WebXR AR,但能跑「**伪 AR**」/「**纯 VR**」—— 体验比真 AR 差一档,但**比完全没有强得多**,且**所有非白名单 Android 用户都能受益**(不只是 Magic7)。

## 决策

**给非 WebXR-AR 设备加 2 种 fallback 体验**,在 🥽 卡片探测后自动决定显示哪个按钮:

```
┌─────────────────────────────────────────────────────────┐
│ 🥽 在 AR 中查看                                          │
├─────────────────────────────────────────────────────────┤
│ Layer 1 检测: navigator.xr 存在?                         │
│   No  → 显示「设备不支持 WebXR」                          │
│   Yes →                                                  │
│     Layer 2 检测: requestSession('immersive-ar') 试启动? │
│       OK   → 真 AR 按钮(ADR-011 既有路径)               │
│       FAIL → 真 AR 不可用,显示 fallback 二选一:          │
│              [📱 AR-lite 模式(相机叠加)]                │
│              [🥽 VR 沉浸模式(陀螺仪环视)]              │
└─────────────────────────────────────────────────────────┘
```

### Mode A · AR-lite(相机叠加模式)

**核心**:不用任何 SLAM,把后摄画面拉成全屏背景,splat 透明渲染叠在前面,用手势控制位置 / 缩放 / 旋转。

```
HTML 结构:
  <video id="cam-bg" autoplay playsinline muted>     ← 全屏后摄 feed
  <canvas id="pc-canvas">                            ← PlayCanvas 透明叠加
  <div id="ar-lite-ui">                              ← 控制条
    <button>↺ 复位</button>
    <button>✕ 退出</button>
    <div>👆 单指拖动 | 双指捏合 | 旋转</div>
  </div>

启动流程:
  1. getUserMedia({video:{facingMode:'environment',width:1920,height:1080}})
  2. 把 stream 灌到 <video>
  3. PlayCanvas 改 clearColor.a=0(透明背景)+ 隐藏 sky
  4. splat 缩到 1m³(屏幕中心)默认
  5. 触摸事件:
     - 单指拖 → splat.setPosition(基于屏幕坐标投影到 1m 远的虚拟平面)
     - 双指捏 → splat.scale 0.1x ~ 5x
     - 双指旋 → splat.rotation y 轴

退出流程:
  1. stream.getTracks().forEach(t=>t.stop())
  2. <video> 移除
  3. PlayCanvas 恢复 clearColor + sky
  4. 复位 splat
```

**体验差距 vs 真 AR**:
- ❌ splat 不会贴桌面(没 SLAM,没平面检测)
- ❌ splat 不会跟随手机移动(屏幕坐标系,不是世界坐标系)
- ✅ 但能「在现实背景上看 splat」—— 对 demo / 朋友圈截屏分享够用

### Mode B · VR-immersive(陀螺仪环视模式)

**核心**:进 WebXR `immersive-vr` session,用 Cardboard backend 启动立体 stereo + 陀螺仪 6DoF tracking,splat 在虚拟空间正中,用户转头环视。

```
启动流程:
  1. navigator.xr.requestSession('immersive-vr', {
       requiredFeatures: ['local'],
       optionalFeatures: ['local-floor']
     })
  2. PlayCanvas 接管 XR session(已内置,API 跟 immersive-ar 完全一致)
  3. splat 摆在原点,缩 1m³
  4. Cardboard 模式默认 stereo,但**用户不需要 Cardboard 盒子**
     —— 单手举着手机转,左右两个圆形画面会模糊但能看到 splat,陀螺仪让 splat 「钉」在虚拟空间不动
  5. 也可考虑加 "mono 模式"开关:用 CSS overlay 把右半屏 hide,只看左屏(单眼模式更清晰但失去 stereo)

退出:点屏幕任意位置触发 session.end()
```

**体验差距 vs 真 AR**:
- ❌ 没有相机 feed,背景是纯黑 / sky box(不混现实)
- ✅ splat 真 6DoF tracking(陀螺仪 + WebXR runtime 加速度计融合)
- ✅ 转头环视感觉「splat 在固定虚拟空间」,沉浸感比 AR-lite 强

**注意**:Magic7 实测 `immersive-vr` requestSession 直接 ✅ 工作(2026-05-31 验过),不卡白名单。

### 组合策略

| 用户机型 / 运行环境 | 默认 | 备选 |
|---|---|---|
| 白名单 GMS 设备 + Chrome 打开 landing | 🥽 真 AR(ADR-011) | — |
| Magic7 / Honor / 其它无 ARCore Android + **Chrome** | 📱 AR-lite | 🥽 VR-imm(已实测 Magic7 + Chrome 可启动 Cardboard) |
| iOS Safari(Phase 3 看)| 🥽 真 AR(看 Safari WebXR 进度) | 📱 AR-lite |
| **Roam App WebView 内**(任何设备) | 📱 **AR-lite 唯一可用** | navigator.xr 缺失,VR 不可用,enterVR 弹文案引导用户跳 Chrome |

**重要**:Chromium System WebView **默认不暴露 `navigator.xr`** —— 这是设计如此,非 Magic7 特例。任何 Roam WebView 应用都无法跑 WebXR(真 AR + VR-imm 都不行),AR-lite 是 Roam App 内唯一选项。

## 备选方案

| 选项 | 评价 | 没选的原因 |
|---|---|---|
| **A · 只做 AR-lite,不做 VR** | 实现量小,1 天搞定 | 浪费 Magic7 上 `immersive-vr` 现成可用的能力,且 VR 沉浸感更高 |
| **B · 只做 VR-imm,不做 AR-lite** | 真沉浸 | 不混现实背景,失去「在我家桌上看 splat」的演示效果 |
| **C · 自研陀螺仪 + 相机 feed + splat 混合**(D mode 升级版) | 真酷,有「相机背景 + splat 跟陀螺仪转」组合体验 | 需自己 fuse DeviceOrientation 与 splat 渲染,可能漂移严重;先做 A + B 验证体验,再考虑 C |
| **D · 接 HUAWEI AR Engine(native)** | 真 AR(理论上) | 需 native JNI + Magic OS 不一定有 HMS,且违 ADR-005 WebView 套壳精神;工程量 = ADR-011 重做 |
| **E · 集成 8th Wall(纯 WASM SLAM)** | 不卡白名单,所有设备真 AR | 商业 $99/月起免费版 25 view/月,违 ADR-004 不引付费服务 |
| **F · 不做 fallback,Magic7 用户直接看不到 AR** | 0 工作量 | zhi 本人体验差,不能 demo,违项目「自己玩得爽」初衷 |
| **G · AR-lite + VR-imm 双 fallback(本项)** ✅ | 1-2 天工作量,Magic7 + 所有非白名单 Android 都能用 | (本项) |

## 实现要点

### Phase 1 · AR-lite 模式(优先,~1 天)

```
代码改动:
1. client/app/src/main/assets/index.html
   - 🥽 卡片探测逻辑增强:Layer 1 + Layer 2 两步
   - 加 #ar-lite-container(z-index 高于 .card,低于 ar-overlay)
     - <video id="ar-lite-video"> 全屏
     - <div id="ar-lite-controls"> 顶部控制条
   - 加按钮「📱 AR-lite」(在 fallback 显示时)
   - 加函数 enterARLite(scene) / exitARLite()
   - 加 PlayCanvas 透明背景切换(clearColor.a 来回切)
   - 加单指 / 双指手势(已有 indoor 模式手势,可复用)

2. RoamBridge.kt
   - getUserMedia 权限处理(Android WebView 需 onPermissionRequest 回调放行)
   - WebChromeClient.onPermissionRequest({"android.webkit.resource.VIDEO_CAPTURE"})
     → grant

3. AndroidManifest.xml
   - <uses-permission android:name="android.permission.CAMERA">
   - <uses-feature android:name="android.hardware.camera" android:required="false">

4. 自动化测试桥
   - runAuto cmd 加 ar-lite-start / ar-lite-end

UI 细节:
- AR-lite 状态条字号小,贴右上角,不挡 splat
- 单击 splat 不触发任何操作(避免误触);双击复位
- 多个场景共用一个进出口
```

### Phase 2 · VR-immersive 模式(实验,~半天)

```
代码改动:
1. index.html
   - 加按钮「🥽 VR」(在 fallback 时)
   - 加 enterVR(scene):pcApp.xr.start(cam, pc.XRTYPE_VR, pc.XRSPACE_LOCAL, {...})
   - 加退出处理(任意触摸 → session.end())

2. 实测:
   - Magic7 上启动 VR session 看效果
   - 如果默认 stereo 模式手持体验差,加「mono」CSS hack
   - 评估是否值得 ship vs 仅做 demo
```

### Phase 3 · 真 AR 路径不变(ADR-011)

- 白名单设备依然走原 ADR-011 全套
- 探测到真 AR 可用 → 不显示 AR-lite/VR 按钮(避免选项过多)
- 探测到真 AR 不可用 → 提示「真 AR 仅白名单设备可用,以下 fallback:」

## 后果

### 好的影响

- **Magic7 用户(包括 zhi 本人)有 AR 体验** — 不是真 AR,但能 demo splat 在现实背景上,价值远大于 0
- **所有非白名单 Android 受益** — Honor / 华为系 / 任何无 GMS 设备都能跑 AR-lite
- **零新依赖** — `getUserMedia` / `DeviceOrientationEvent` / PlayCanvas WebXR 都是浏览器 / 现有引擎能力
- **延续 ADR-005 WebView 套壳精神** — 全部在 WebView 内做,App 层只加一个相机权限
- **代码量小** — Phase 1 估 ~300 行 HTML/JS + 30 行 Kotlin,1 天能做完
- **强化 C 路径**(landing 分享)— 朋友收到 landing 链接,不论手机 GMS 与否都能在 Roam / Chrome 里看到 splat

### 不好的影响 / 取舍

- **「真 AR」和「AR-lite」的命名区分要小心** — UI 上不能让用户误以为 AR-lite 是「真 AR 但效果差」,得说清「相机叠加 demo 模式」
- **没有 SLAM 的「AR」打引号** — 严格说 AR-lite 不算 AR(splat 不贴桌面),但用户视觉上看起来像 AR,沟通要诚实
- **getUserMedia 触发系统级相机权限** — 第一次用 Roam 会弹「允许相机」,可能吓到用户 → 进 AR-lite 前先弹一个 Roam 自己的解释 modal「需相机权限来生成混合现实背景」
- **AR-lite 中 splat 不跟随手机移动** — 用户走两步,splat 还在屏幕同样位置,「飘」的感觉,体验廉价。要在文案里说清「demo 模式,不是真 AR」
- **VR-imm 默认 stereo 不适合裸眼** — 没 Cardboard 盒子的话双眼图像模糊,可能要做 mono fallback,工程量 +0.5 天
- **维护负担 +1 个分支** — Roam 现在的 AR 代码从「单分支(WebXR 真 AR)」变成「三分支(真 AR / AR-lite / VR-imm)」,后续 bug 排查复杂度上升
- **iOS 上 AR-lite 模式的相机权限申请逻辑不同** — Safari `getUserMedia` 需 https + 用户激活,在 in-app browser 行为多变;先只保 Android 工作,iOS Phase 3 再说

## 何时回头看

- **GMS 在 Magic7 / Honor 系国行机普及** → 真 AR 优先,AR-lite 降级为 demo
- **iOS Safari WebXR 完整支持** → 扩展真 AR 到 iOS
- **8th Wall / Niantic Lightship 出真免费版** → 替换 AR-lite,所有设备都能真 AR
- **PlayCanvas 出 native getUserMedia → splat 合成 pipeline 优化** → AR-lite 视觉质量升级
- **用户反馈 AR-lite 体验确实廉价没人用** → 砍 Phase 1 只留 VR(或全砍)

## zhi review 待定项

1. **VR-immersive 模式真的要做吗?**(裸眼 stereo 体验可能很糟,要不要先只做 AR-lite,VR 看效果再加?)
2. **AR-lite 中 splat 跟陀螺仪转?**(Mode C 备选,真做了会怎样?)—— 我倾向第一版不做,Phase 3 看反馈
3. **iOS 优先级?**(我建议 Phase 1 只做 Android,iOS 留 Phase 3)
4. **UI 命名建议**:「📱 AR-lite」可改成「📷 现实背景」「🪟 透视模式」等更通俗;「🥽 VR」可改成「🌐 360° 环视」

---

## 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| v0.1 | 2026-05-31 | 初版,M8 沙盘验证完毕 + 确认 Magic7 上 immersive-vr / getUserMedia / DeviceOrientation 三件套可用;提议为非 ARCore 白名单设备加 AR-lite + VR-imm 双 fallback;锁定 Phase 1 优先做 AR-lite,Phase 2 看体验决定 VR-imm 是否 ship |
| v0.2 | 2026-05-31 | 状态 Proposed → Accepted。zhi review 答复:① Phase 1 AR-lite + Phase 2 VR 都做(不等 AR-lite 体验回头);② Android 优先,iOS 留 Phase 3 不在本 ADR 范围;③ AR-lite 中 splat 跟陀螺仪转(D-mode 升级)第一版**不做**;④ UI 命名按默认「📱 AR-lite」「🥽 VR」 |
| v0.3 | 2026-05-31 | Phase 1 + 2 实施完毕。**改动**:`MainActivity.kt` onPermissionRequest 加 `RESOURCE_VIDEO_CAPTURE` 放行 + `index.html` 新增 detectAR 三能力探测、enterARLite/exitARLite/enterVR/exitVR、arLiteState/vrState、ar-lite-controls + ar-lite-video DOM、runAuto 加 `ar-lite-start/end + vr-start/end`、onAndroidBack 优先退 AR/AR-lite/VR、onXRStart/onXREnd 按 `pcApp.xr.type` 分流(VR 不走 AR 的 hit-test/overlay)。**踩坑**:① ar-lite-controls 在 `.wrap` 内 position:fixed 被父级 stacking context 困住,移到 `body` 末尾 + inline `!important` style 才生效;② Magic OS uiautomator dump 在 WebView WebGL fullscreen 下完全看不到 DOM,只能截屏判断;③ 装新 APK 后 Magic OS 弹「应用扫描」UI 挡屏,需手动 dismiss。**重大发现**:Roam WebView **navigator.xr 完全缺失**(Chromium WebView 设计不暴露 WebXR Device API)→ VR 模式在 Roam App 里**根本启不起来**,只能在 Chrome 等真浏览器打开 Roam landing 才能用;AR-lite 是 Roam App 内唯一可用的「类 AR」体验。已在 enterVR 加 navigator.xr 检查 + 友好引导文案 |
| v0.4 | 2026-06-06 | **⚠️ 前提反转**(详见文档顶部「2026-06-06 重大更新」)。Magic7 Pro(PTP-AN10)复测:ARCore + GMS + Play 商店现已全部正规安装,Chrome 149 实测 `requestSession('immersive-ar')` **成功启动** session(官方 immersive-ar sample START AR 蓝色可点 → 进 session → 系统弹「已关闭 Immersive AR Session」),**推翻 v0.2/v0.3 及 ADR-011 v0.2「Magic7 真 AR 走不通(白名单硬墙)」结论**。根因:5-31 测的是 sideload ARCore/Trichrome(过不了 Google 设备认证),6-6 已补正规 GMS → 认证通过。取证:`doc/screenshots/2026-06-06-webxr-ar-{start-button.png,session-closed.jpg}`。**影响**:真 AR 在本机 Chrome 可行,无需借机;但 Roam WebView 仍不暴露 navigator.xr(组合策略表末行结论不变),真 AR 接 Roam 须走 Intent 跳 Chrome / Custom Tabs(方案待 zhi 拍板,做则补 v0.5);AR-lite/VR 双 fallback 不作废,降级为无 GMS 机兜底 |
| v0.5 | 2026-06-07 | **真 AR 落地决策(方案 B + viewer 方案 A)**。经 brainstorming 定:① 目标 zhi + 朋友分享路径都覆盖;② App 🥽 卡片加「🥽 真 AR」按钮(与 AR-lite/VR 并存)→ `RoamBridge.openInChrome(url)` Intent 跳系统 Chrome;③ Chrome 端独立精简 `docs/ar.html`(零 RoamBridge,从 index.html 抽 AR 代码)按 `?scene=` 加载 splat + detectAR + 真AR/fallback;④ 资源:6 demo 走 jsdelivr 指 `assets/scenes/`,自扫 sample 走 `docs/scenes/` 同源;⑤ ⑤ 二维码改指 ar.html。完整设计见 [2026-06-07 真 AR Chrome viewer 落地设计](2026-06-07-真AR-Chrome-viewer-落地设计.md)。**待实施** |
