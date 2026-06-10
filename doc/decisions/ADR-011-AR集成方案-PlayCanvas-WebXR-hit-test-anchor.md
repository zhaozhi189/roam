# ADR-011 · AR 集成方案 — PlayCanvas WebXR(hit-test + plane-detection + anchor + scale + reset)

> 状态:✅ Accepted(WebXR 路线锁定;Magic7 Pro 实测**确认不支持**,fallback 路径就是正解)
> 日期:2026-05-22 起;2026-05-31 Magic7 Pro 沙盘验证完毕(v0.2)
> 关联:[ADR-004 本地优先](ADR-004-MVP-方向调整-本地优先.md)、[ADR-005 WebView 套壳](ADR-005-应用形态-Android-WebView套壳.md)、[ADR-007 https 虚拟域名](ADR-007-WebViewAssetLoader-https-虚拟域名.md)、ADR-010 自扫数据源

---

## 背景

M6-3 阶段只做了 WebXR 探测(🥽 卡片 badge 显示「✓ AR 支持 / ✗ AR 不支持」),没有真集成。zhi 希望做**全套**:WebXR session 启动 → hit-test 检测平面 → 单击放置 splat → 双指捏合缩放 → 拖动调位 → anchor 保持位置 → 一键 reset。

技术上 PlayCanvas Engine 2.18.1 **已经内置 WebXR API**:

```javascript
// 调研结论:PlayCanvas 原生支持
app.xr.start(camera, pc.XRTYPE_AR, pc.XRSPACE_LOCAL_FLOOR, {
  optionalFeatures: ['hit-test', 'plane-detection', 'anchors']
});
app.xr.hitTest.on('add', (source) => {
  source.on('result', (results) => {
    placedObject.setPosition(results[0].position);
  });
});
```

API 现成,不需要引入新依赖。

### 但有个硬约束:Magic7 Pro 大概率不支持

| 链路 | 状态 |
|---|---|
| Chrome WebXR `immersive-ar` | 依赖 **ARCore** |
| ARCore | 依赖 **Google Play Services for AR** |
| Google Play Services | 依赖 **GMS** |
| Magic7 Pro Magic OS(国行版) | **❌ 无 GMS** |

预期 M6-3a 实测会显示「✗ AR 不支持」(M6 验证清单已预测)。也就是说,**zhi 主测机大概率根本启不起 AR session**。

但这不是放弃理由,因为:
1. WebXR 是 W3C 标准,代码写一次,**任何带 ARCore/ARKit 的设备打开 landing 页都能用**(给朋友演示价值)
2. Magic7 Pro 失败有明确报错,不会崩溃
3. 朋友 / 其它 Android 设备(有 GMS)直接能用
4. iOS Chrome 18+ 也开始支持 WebXR(部分)

## 决策

**用 PlayCanvas 2.18 内置 WebXR API 实现全套 AR**,Magic7 Pro 不支持的情况下显式 fallback 到引导文案 + 引导用户在其它设备开 landing 试。

### 功能范围(全套)

1. **🥽 启动 AR session**(button → `app.xr.start`)
2. **hit-test 平面检测**(地板 + 桌面)
3. **指示器**(reticle):瞄准平面时显示一个圆环 / 十字
4. **单击放置**(WebXR `select` event → 当前 splat 摆到 reticle 位置 + anchor)
5. **双指捏合缩放**(touch gesture → splat localScale 0.1x ~ 3x)
6. **单指拖动调位**(在已放置 splat 上拖 → 重新 hit-test 移到新位置)
7. **重置 / 退出**(button → 删 anchor + 重新启动 session 或 退出)

### UI 改动(改 🥽 卡)

```
原 🥽 卡(M6-3):
  badge: ✓ AR 支持 / ✗ AR 不支持
  button: 启动 AR 试试(3s 自动结束)

新 🥽 卡(ADR-011):
  badge: ✓ AR 支持 / ✗ AR 不支持(原因:无 ARCore)
  button: 🥽 在 AR 中查看(支持时启用)
  AR 中浮动控件:
    ↺ 重置  ✕ 退出
  AR 中提示:
    第 1 步:对准地面/桌面,看到圆环表示找到平面
    第 2 步:单击屏幕放置场景
    第 3 步:双指捏合缩放,单指拖动调位
  失败提示(无 ARCore 设备):
    你的设备没有 ARCore 支持。Magic OS 国行版无 GMS,
    朋友的有 GMS 设备 / iPhone 打开同 landing 链接可体验。
```

### 默认 splat 缩放策略

进 AR 时 splat 默认缩到 **物理 0.5m × 0.5m × 0.5m** 体积(放桌面合适),不缩成原始尺寸(公寓 8m × 6m 桌上没法看)。

## 备选方案

| 选项 | 评价 | 没选的原因 |
|---|---|---|
| **A · 8th Wall / Niantic Lightship**(商业 WebAR SDK) | SLAM 自带,**不依赖 ARCore** → Magic7 Pro 可能跑通 | 商业 $$($3000/年起),违 ADR-004 个人项目本地优先 + 不引第三方付费服务 |
| **B · Native ARCore via JS Bridge** | App 内调原生 ARCore,SurfaceTexture 喂回 WebView | Magic OS 仍无 ARCore,且违 ADR-005 「WebView 套壳为主」轻量级精神 |
| **C · AR.js / mind-AR(marker-based)** | 不需 ARCore,纯 CV 跟踪图片标记 | 需要打印二维码 / 物体表面贴 marker,体验差;不能用平面检测;splat 渲染不合适 |
| **D · Unity AR Foundation 重写 App** | 跨平台 ARCore + ARKit 一套 | 违 ADR-005,放弃 WebView 套壳整个返工,代价过大 |
| **E · 等 Magic OS 接入 ARCore** | 不动作 | 国行 Honor 估计永远没有,等不到 |
| **F · 只做 probe,不做真集成** | 当前 M6-3 状态 | 不满足 zhi 要求的「全套」 |
| **G · PlayCanvas 内置 WebXR(全套 hit-test/plane/anchor)+ 文档化 fallback** ✅ | 标准 W3C / 零依赖 / 跨设备 / 失败有明确报错 | (本项) |

## 实现要点

### Phase 1(代码改动,我能独立做)

```
1. 改 client/app/src/main/assets/index.html:
   - 🥽 卡片重写(原 M6-3 探测改成支持 / 启动二态)
   - 加 AR session 启动 / 结束 hook
   - 加 hit-test source 订阅 + reticle entity 渲染
   - 加 select event → place anchor 逻辑
   - 加 touch gesture 处理(单指拖 / 双指捏)
   - 加 AR mode 内 floating UI(↺ ✕)

2. PlayCanvas 启动代码加 XR camera:
   - 当前 currentCam.entity.addComponent('camera') 已有
   - 加 app.xr.input 事件订阅

3. 全局 error handler 捕获 AR start 失败:
   - "NotSupportedError" → "无 ARCore"
   - "SecurityError" → "需 https"(实际 appassets 是 https,应该没事)
   - "InvalidStateError" → "已经在 session 中"

4. 自动化测试桥加 cmd:
   - "ar-start" / "ar-end" / "ar-reset"
```

### Phase 2(zhi 配合测试,跨会话)

```
5. Magic7 Pro 跑 M6-3a → 确认「✗ 无 ARCore」(预期)
6. 借朋友有 GMS 的 Android(米/Pixel)→ 跑全流程
   - landing https URL 在朋友手机 Chrome 打开
   - 启动 AR → 找平面 → 放公寓 → 缩放 → 退出
   - 录全程视频回传给 zhi
7. zhi 朋友圈分享:朋友点 landing → 直接 AR 看吉他 splat
```

### Phase 3(可选,未来优化)

```
8. 加 light estimation(WebXR optionalFeature)→ splat 自动适应环境光
9. 加 image-tracking(WebXR)→ 扫指定图片 marker 自动放置 splat
10. 加 hand-tracking(WebXR Hand API)→ 手势更自然,无需触屏
```

## 后果

### 好的影响

- **零新依赖** — PlayCanvas 2.18 已内置,不引第三方
- **W3C 标准** — 长期演进,各浏览器跟进,代码无锁定风险
- **跨设备** — 朋友的有 GMS 设备 / iPhone 直接能用 → 增强分享价值
- **失败优雅** — 不支持设备明确报错,引导用户
- **延续 ADR-005 WebView 套壳精神** — AR 在 WebView 内跑,App 层零改动
- **C 路径加分** — landing https URL 内只要有 ARCore 设备打开就能 AR,无需装 App

### 不好的影响 / 取舍

- **zhi 主测机不支持** — Magic7 Pro 无 GMS 是硬伤,zhi 本地开发体验差(只能 mock 或借机)
- **联调依赖朋友手机** — 没有有 GMS 设备实测前,代码质量不能保证
- **AR session 启动慢** — Chrome 第一次启 ARCore 装包要 30s+,体验门槛
- **splat 在 AR 中渲染未实测** — 3DGS 半透明 + gaussian 混合在 AR camera feed 上的视觉效果不确定,可能需调参
- **anchor 持久化跨 session 不做** — WebXR persistent anchor API 各家实现不一,只做 session 内 anchor,退出即丢
- **iOS 支持有限** — Chrome iOS 不支持 WebXR(WebKit 限制),Safari WebXR 部分支持但 ARKit 接入不全;Roam 主测 Android,iOS 是 Phase 3 候选

## 实测计划(下次会话或 zhi 配合)

| 验证 | 期望 | 实测 |
|---|---|---|
| Magic7 Pro 🥽 探测(Roam WebView) | ✗ 不支持(原因:无 ARCore) | ✅ **2026-05-31 实测确认**:WebView `navigator.xr` 缺失 |
| Magic7 Pro + sideload ARCore + Chrome | 期望尝试绕过 | ❌ **2026-05-31 实测验完不行,详见下方 M8 章节** |
| 朋友 Pixel 7 启动 AR session | < 5s 进入 AR | 待测(等借机) |
| 平面检测 reticle 显示 | 对准地面 2s 内出圆环 | 待测(等借机) |
| 单击放置 splat | 公寓 0.5m × 0.5m 出现在地面 | 待测(等借机) |
| 双指捏合缩放 | 0.1x ~ 3x 流畅 | 待测(等借机) |
| 单指拖动调位 | 重新 hit-test 移位 | 待测(等借机) |
| 反复退出 / 重进 | 不崩溃,anchor 重置 | 待测(等借机) |
| 视觉质量 | splat 在 AR camera feed 上不闪烁 | 待测(等借机) |
| Landing C 路径完整 | 朋友扫码 → landing → AR 全程 | 待测(等借机) |

## M8 沙盘验证(2026-05-31 Magic7 Pro · sideload 路径)

> **背景**:M8 收尾时 zhi 想试「能不能在 Magic7 上 sideload Chrome + ARCore + Trichrome 绕过 GMS 限制实测 AR」,顺便看「能不能集成进 Roam」。本节是这次验证的**完整数据 + 明确结论**,避免下次再起同样念头浪费时间。

### 验证环境

| 项 | 值 |
|---|---|
| 设备 | Honor Magic7 Pro · PTP-AN10 · Android 16 · 骁龙 8 Elite · Adreno 830 |
| GMS | `com.google.android.gms` 26.15.33(国行 Honor **居然预装完整 GMS**,意外发现) |
| Play 商店 | `com.android.vending` 在,但 ARCore 详情页明示 **「您的设备与此版本不兼容」**(Google 服务器判定 Magic7 不在白名单) |
| sideload ARCore | `com.google.ar.core` 1.54.260890493(arm64-v8a + armeabi-v7a)从 APKMirror 装 |
| sideload Trichrome Library | `com.google.android.trichromelibrary` 149.0.7827.22 · versionCode 782702233(arm64-v8a)从 APKMirror 装 |
| sideload Chrome | `com.android.chrome` 149.0.7827.22 · versionCode 782702233(arm64-v8a,Android 12L+ bundle,split apks)从 APKMirror 装 |

### 验证步骤 + 数据

| 步 | 测试 | 结果 |
|---|---|---|
| 1 | Roam WebView 内 `navigator.xr` 探测 | ❌ 缺失(WebView 不暴露 WebXR Device API,Chromium 设计如此) |
| 2 | Chrome 用 `data:text/html,…` 探测 | ❌ `navigator.xr` 缺失 —— 但**这是 false negative**,原因:`data:` URL 是 unique opaque origin,`isSecureContext = false`,Chrome 在非 secure context 下不暴露 WebXR(同 getUserMedia 限制) |
| 3 | Chrome 用 `http://localhost:8080`(Mac Python http.server + `adb reverse tcp:8080`,loopback 算 secure context)探测 | ✅ `navigator.xr` 存在,`isSecureContext: true` |
| 4 | `isSessionSupported('immersive-ar')` | ✅ **返回 `true`** |
| 4b | `isSessionSupported('immersive-vr')` | ✅ 返回 `true` |
| 4c | `isSessionSupported('inline')` | ✅ 返回 `true` |
| 5 | Chrome 弹出**官方 AR 权限对话框**「`http://localhost:8080` 想为您的周边环境创建 3D 地图并跟踪摄像头位置」→ 点「仅这次允许」 | ✅ 授权成功,Chrome 进入「准备启动 AR」状态(URL 栏出现 AR 图标) |
| 6 | `requestSession('immersive-ar')` 裸调,**无 features** | ❌ `NotSupportedError: The specified session configuration is not supported.` |
| 6b | `requestSession('immersive-ar', { requiredFeatures: ['local'] })` | ❌ 同上 |
| 6c | `requiredFeatures: ['local-floor']` | ❌ 同上 |
| 6d | `required: ['local'], optional: ['hit-test']` | ❌ 同上 |
| 6e | `required: ['local-floor'], optional: ['hit-test', 'dom-overlay'], domOverlay: { root: body }` | ❌ 同上 |

**5 种 session 配置全部 FAIL,同一个 `NotSupportedError`** —— 包括「裸调无 features」最弱配置。

### 关键发现 · Google 的「两层防御」

| 层 | 检查内容 | Magic7 表现 |
|---|---|---|
| **Layer 1 · `isSessionSupported`** | 软检查:ARCore 装了吗?Chrome 版本够吗?有摄像头吗? | ✅ 全过 → **返回 true** |
| **Layer 2 · `requestSession`** | 硬检查:**设备指纹 vs ARCore 白名单**(hardcode 在 ARCore 二进制 + 服务端校验) | ❌ Magic7 不在表 → **拒绝创建 session** |

**「isSessionSupported = true」会骗人** —— Google 故意这样设计:让网站知道「这台机原则上可以做 AR」(用于显示按钮 UI),但实际创建 session 时严卡白名单。如果只看 `isSessionSupported` 就会错误地认为设备 OK。

### 结论 · sideload 路径死透,设备白名单是硬墙

> **⚠️ 2026-06-06 已被推翻(见 [v0.3 修订记录](#修订记录))**:本节「死透/硬墙」结论仅对 **2026-05-31 当时的 sideload 配置**成立。后来 Magic7 Pro 补装**正规 GMS + Play 商店分发的 ARCore**,设备通过 Google 认证,`requestSession('immersive-ar')` 实测**成功启动** session(Chrome 149)。即「白名单」并非永久硬墙,**正规 GMS 是钥匙,sideload 才是死路**。下表按当时认知保留作历史。


| 路径 | 验证结果 |
|---|---|
| Magic7 + sideload(ARCore + Chrome + Trichrome) | ❌ **NOT POSSIBLE** —— Layer 2 白名单拒绝,与是否 sideload 无关 |
| Magic7 + Roam APK 集成 ARCore(把 ARCore 打包进 Roam) | ❌ 同样卡 Layer 2,**集成做了也没用** |
| Magic7 + Roam APK 集成 8th Wall(纯 WASM SLAM,不依赖 ARCore) | ⚠️ 唯一**绕过白名单**的路径,但 8th Wall 商业 $99/月起,违 ADR-004 个人项目不引付费服务 |
| **白名单设备(Pixel / 米国际 / 三星国际 / 一加国际)+ 现有 ADR-011 路线** | ✅ **保持不变,这就是正解** |

### 给未来 zhi 的提醒(避免重蹈覆辙)

1. **不要再花时间 sideload ARCore 绕白名单** —— 实测明确死透,2026-05-31 用了 2 小时验完,文档化在此
2. **不要把 ARCore 打包进 Roam APK** —— 白名单是硬墙,集成做了 Magic7 用户仍然用不上
3. **「Roam 一键 AR」集成方案不值得做** —— 除非将来在 Roam 用户群里**白名单设备占比 >50%**,做集成才有 ROI
4. **要真测 AR,只能借 GMS 白名单机** —— 现 Roam landing https URL 已就绪,白名单机用 Chrome 直接打开即可,无需 sideload 任何东西
5. **要破白名单,只有两条路**:① root + Magisk 改 device fingerprint(Magic7 没 root 渠道,死);② 8th Wall 等商业 WASM SLAM(要钱)

### 现 ADR-011 决策 100% 正确,不需要修改

实测结果**完全符合**原 ADR 中「Magic7 Pro 大概率不支持」的预期。原 fallback 文档(🥽 卡片显示「✗ 无 ARCore,借机用 Chrome」)就是正确处理。
本次验证只是把「大概率」升级为「**100% 确认**」+ 把绕过路径试了一遍封闭掉。

## 何时回头看

- **Magic OS 集成 ARCore / 华为系出 AR 框架** → 重新评估 Magic7 Pro 可用性
- **WebXR Persistent Anchor API 稳定** → 加 session 间 anchor 持久
- **PlayCanvas 出 3DGS in-AR 渲染优化** → 升级配套渲染参数
- **iOS Safari WebXR 完整支持** → 扩展到 iOS
- **8th Wall 个人开源版** → 重新评估 SLAM 路线
- **zhi 入手有 GMS 的备机(Pixel / 米)** → 主测体验改善,迭代速度提升

---

## 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| v0.1 | 2026-05-22 | 初版,M7 完成 + ADR-010 自扫数据源决策后,启动 AR 真集成方案;锁定 PlayCanvas WebXR 路线 + 全套功能范围;承认 Magic7 Pro 大概率不支持 + 文档化 fallback |
| v0.2 | 2026-05-31 | 加 M8 沙盘验证章节:Magic7 + sideload(ARCore 1.54 + Chrome 149 + Trichrome 149)实测,Layer 1 `isSessionSupported = true` 但 Layer 2 `requestSession` 全部 `NotSupportedError`(5 种配置都 FAIL),证明设备白名单是硬墙;明确「集成 ARCore 进 Roam 没用」「sideload 绕不过」「8th Wall 是唯一非商业绕过路径但要钱」,给未来 zhi 留 5 条避坑提醒;原 ADR-011 决策不变,fallback 路线即正解 |
| v0.3 | 2026-06-06 | **⚠️ v0.2「白名单硬墙」结论被推翻**(详见 [ADR-012](ADR-012-无ARCore设备的AR降级方案-AR-lite-VR.md) 顶部「2026-06-06 重大更新」)。Magic7 Pro(PTP-AN10)复测:已补正规 GMS + Play 商店分发的 ARCore,Chrome 149 实测 `requestSession('immersive-ar')` **成功启动** session。根因:v0.2 测的是 **sideload** ARCore/Trichrome,过不了 Google 设备认证;正规 GMS 装上后认证通过,白名单即放行(并非永久硬墙)。⇒「借 Pixel/米/三星」引导可作废,真 AR 在本机 Chrome 即可跑。**但 Roam WebView 仍不暴露 navigator.xr**,真 AR 接 Roam 须经系统 Chrome(Intent / Custom Tabs)。取证:`doc/screenshots/2026-06-06-webxr-ar-{start-button.png,session-closed.jpg}` |
| v0.4 | 2026-06-10 | **线上 https 端到端验证 + 再次反转:Magic7 真 AR 被「ARCore 版本墙」重新挡死,fallback 双路实测可用**。① **6-6「已正规安装」是误判**:Play 商店实查显示本机 ARCore 1.54 仍是 sideload(「并非来自 Google Play」),且 Play 判 Magic7 Pro「已不再与您的设备兼容」**只给卸载不给更新**;Chrome 149 现要求更新版 ARCore,`requestSession` 一律 `NotSupportedError`(CDP 裸调零 features 同样失败,与 session 配置无关)。6-6 能进 session 推断是当时 Chrome 小版本尚接受 1.54。② **新坑:MagicOS 把 ARCore force-stop + 禁止关联启动**(`stopped=true, lastStoppedCaller=android`),Chrome 根本拉不起 ARCore 进程;已在「应用启动管理」对 ARCore 关自动管理 + 手动三开关全开(自启动/关联启动/后台活动),此步永久生效。③ **修 domOverlay 传法 bug**(commit `2fb8123`):PlayCanvas 须 start 前设 `xr.domOverlay.root`,塞 `start()` options 无效(Chrome 实锤警告),否则真 AR session 内重置/退出浮动条不显示——影响所有扫码进真 AR 的认证设备用户。④ **fallback 双路在线上 ar.html 实测可用**:AR-lite 相机叠加 ✅(zhi:「效果挺好」)、VR 沉浸 ✅。⑤ 本机真 AR 出路:sideload 新版 ARCore(APKMirror),或维持 fallback、真 AR 留给扫码朋友。⑥ **调试新知:系统 Chrome 的 DevTools 可经 `adb forward localabstract:chrome_devtools_remote` 接通**(MagicOS 只屏蔽 App WebView 调试),console/Runtime.evaluate(userGesture)全可用,本次定位全靠它 |
