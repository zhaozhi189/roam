# 真 WebXR AR · Chrome viewer 落地设计

> 类型:设计 spec(brainstorming 产物) · 日期:2026-06-07
> 关联:[ADR-012 AR 降级方案](ADR-012-无ARCore设备的AR降级方案-AR-lite-VR.md)(本设计是其 v0.5 落地)、[ADR-011 AR 集成](ADR-011-AR集成方案-PlayCanvas-WebXR-hit-test-anchor.md)、[ADR-010 自扫数据源](ADR-010-自扫场景数据源-Magic7拍-Mac-Brush训.md)、[ADR-007 WebViewAssetLoader](ADR-007-WebViewAssetLoader-https-虚拟域名.md)
> 取证背景:2026-06-06 Magic7 Pro 补正规 GMS + ARCore 后,Chrome 149 实测 `requestSession('immersive-ar')` 成功(见 ADR-012 顶部「2026-06-06 重大更新」)

---

## 1. 目标与范围

**目标**:让真 WebXR AR 在 Roam 落地,**zhi 自己 + 收到分享的朋友**都能用。

**为什么走「跳 Chrome」而非 Roam 内嵌**:Roam 是 WebView 套壳([ADR-005](ADR-005-应用形态-Android-WebView套壳.md)),系统 WebView 默认不暴露 `navigator.xr`(Chromium 设计),真 AR 在 Roam WebView 内根本起不来。真 AR 必须经系统 Chrome。这与现有 VR fallback 的「引导跳 Chrome」一脉相承。

**确认的需求**(brainstorming 2026-06-07):
- 范围:zhi + 朋友分享路径都覆盖
- 场景:自扫 sample + 6 个内置 demo 都能 AR
- App 入口:🥽 卡片加「真 AR」按钮,与现有 📱 AR-lite / 🥽 VR **并存**
- viewer 实现:独立精简 `docs/ar.html`(方案 A,零 RoamBridge 依赖)

## 2. 架构

```
[Roam App · WebView]                      [系统 Chrome]
 index.html ⑥🥽 卡片                       docs/ar.html  (新建,零 RoamBridge)
   ├ 📱 AR-lite (App 内,保留)               ├ 1. 读 ?scene= 参数
   ├ 🥽 VR      (保留)                       ├ 2. PlayCanvas 加载对应 splat
   └ 🥽 真 AR (新增) ──Intent跳Chrome──►     ├ 3. detectAR (L1 isSessionSupported + L2 requestSession)
        RoamBridge.openInChrome(url)         ├ 4a. 有 ARCore → enterAR (hit-test/anchor/scale/reset)
                                             └ 4b. 无       → 自动降级 AR-lite / VR / 2D orbit
```

## 3. 组件

| # | 组件 | 类型 | 改动 |
|---|---|---|---|
| 1 | `docs/ar.html` | 新建 | Chrome 专用 AR viewer。从 `index.html` 抽 ADR-011 真 AR(detectAR/enterAR/hit-test/anchor/scale/reset)+ ADR-012 fallback(AR-lite/VR)代码,**去掉所有 RoamBridge 调用**,错误改用页面红字 + `alert`,不用 `toast` |
| 2 | `docs/vendor/` | 新增 2 文件 | 复制 `playcanvas.min.js` + `spz-loader-playcanvas.umd.cjs`(ar.html 同源加载,比 jsdelivr 稳;不需要 mp4-muxer/qrcode)|
| 3 | `RoamBridge.kt` | 新增 1 方法 | `openInChrome(url: String)`:`Intent.ACTION_VIEW` + `setPackage("com.android.chrome")`,无 Chrome 时回退 `createChooser`(仿现有 `openVideoExternal`)|
| 4 | `client/index.html` 🥽 卡片 | 改 | 加「🥽 真 AR」按钮,与 AR-lite/VR 并存;onclick 取当前 sceneId → `RoamBridge.openInChrome('https://zhaozhi189.github.io/roam/ar.html?scene='+id)` |
| 5 | ⑤ 二维码 URL | 改 | 分享生成的 URL 由现在的 landing 改指向 `ar.html?scene=xxx`,朋友扫码直达 AR |

## 4. 数据流

1. **zhi 自己**:App 内某场景 → 点「🥽 真 AR」→ Bridge 跳 Chrome 开 `ar.html?scene=<当前场景>` → Chrome detectAR → 真 AR(Magic7 有 ARCore)
2. **朋友分享**:收到二维码/链接 → 微信/Chrome 打开 `ar.html?scene=xxx` → detectAR → 有 ARCore 真 AR / 没有自动 fallback

## 5. 场景映射与资源

ar.html 内嵌一份精简映射(6 场景,YAGNI 不抽共享;`index.html` 的 `SCENES` 在 App 内,跨目录无法直接共享):

```js
const AR_SCENES = {
  apartment: { url: <jsdelivr>/assets/scenes/apartment.sog,         kind:'gsplat' },
  guitar:    { url: <jsdelivr>/assets/scenes/guitar.compressed.ply, kind:'gsplat' },
  spz:       { url: <jsdelivr>/assets/scenes/hornedlizard.spz,      kind:'spz' },
  skull:     { url: <jsdelivr>/assets/scenes/skull.sog,             kind:'gsplat' },
  biker:     { url: <jsdelivr>/assets/scenes/biker.compressed.ply,  kind:'gsplat' },
  sample:    { url: 'scenes/sample.compressed.ply',                 kind:'gsplat' }, // 自扫,docs/ 同源
};
// cube 排除:程序生成无 splat,AR 放桌上无意义
```

**资源 URL 策略**(两类):
- **6 个 demo**:走 jsdelivr CDN 指向 repo 里已有的 `client/app/src/main/assets/scenes/`,格式沿用 ADR-010:
  `https://cdn.jsdelivr.net/gh/zhaozhi189/roam@main/client/app/src/main/assets/scenes/apartment.sog`
  → **不往仓库重复塞 25MB**,国内走 fastly 边缘也比 Pages 稳
- **自扫 sample**:`docs/scenes/sample.compressed.ply` 已在,ar.html 同源相对路径 `scenes/sample.compressed.ply`

**约束**:jsdelivr 对 `@main` 有缓存(最长 ~12h);资源更新没刷新时用 `@<commit-hash>` 精确或 purge。

## 6. 错误处理

ar.html 零 Bridge,错误全走页面 UI:

| 场景 | 处理 |
|---|---|
| `?scene=` 缺失/非法 | 默认加载 `sample` + 页面顶部黄字「未指定场景,已加载默认」 |
| splat 加载失败(CDN 超时/404) | 复用 index.html 已有的 `Promise.race` + 30s timeout(ADR-010 `c7ae6af`),失败 → 红字「场景加载失败,检查网络」+ 重试按钮 |
| `requestSession('immersive-ar')` 失败 | detectAR 已有逻辑:自动 fallback 到 AR-lite/VR 按钮(不报错,静默降级)|
| 设备完全不支持(无 xr + 无 getUserMedia) | 提示「设备不支持 AR,请用较新的 Chrome 打开」+ 仍可 **2D orbit 看 splat** 兜底 |

## 7. 测试(项目规范:不写测试代码,zhi 手测 + adb 桥)

| 层 | 方法 | 谁 |
|---|---|---|
| 能力探测(可自动化) | `adb` 跳 Chrome 开 `ar.html?scene=sample` → 截图看 detectAR 状态 + START AR 按钮 | adb |
| 真 AR 端到端 | zhi 手测:App「🥽 真 AR」→ Chrome → 进 AR → splat 贴桌面 / hit-test / 缩放 / 重置 / 退出 | zhi |
| fallback | 临时改 UA 或借无 ARCore 机 → 验证自动降级 | zhi(有备机时)|
| 分享路径 | 生成二维码 → 另一台手机/微信扫 → 打开 ar.html 进 AR | zhi |
| 资源 | 6 demo(jsdelivr)+ sample(docs/)各加载一次,确认 CDN 通 | adb + 手测 |

## 8. 范围边界(YAGNI — 第一版明确不做)

- ❌ 不抽共享 AR 模块(ar.html 复制 index.html 的 AR 代码,验证体验后再说)
- ❌ ar.html 内不做录屏 / 微信分享 / 场景管理(那些是 App 功能,AR 看完返回 App 做)
- ❌ cube 不进 AR(程序生成无 splat)
- ❌ 不碰 `build.gradle` 的 agp/kotlin/compose 版本
- ❌ 不动 App 内现有 AR-lite/VR 逻辑(只新增「真 AR」按钮并存)

## 9. 交付物

1. `docs/ar.html` + `docs/vendor/{playcanvas.min.js, spz-loader-playcanvas.umd.cjs}`
2. `RoamBridge.kt` 新增 `openInChrome(url)` + `index.html` 🥽 卡片加按钮 + ⑤ 二维码改 URL
3. ADR-012 v0.5(记录本落地决策,引用本 spec)
4. 装机验证(adb 能力探测 + zhi 手测真 AR)
</content>
</invoke>
