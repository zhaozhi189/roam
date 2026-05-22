# ADR-011 · AR 集成方案 — PlayCanvas WebXR(hit-test + plane-detection + anchor + scale + reset)

> 状态:✅ Accepted(决定走 WebXR 路线,接受 Magic7 Pro 可能不支持,留 fallback 文档)
> 日期:2026-05-22
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
| Magic7 Pro 🥽 探测 | ✗ 不支持(原因:无 ARCore) | 待测 |
| 朋友 Pixel 7 启动 AR session | < 5s 进入 AR | 待测 |
| 平面检测 reticle 显示 | 对准地面 2s 内出圆环 | 待测 |
| 单击放置 splat | 公寓 0.5m × 0.5m 出现在地面 | 待测 |
| 双指捏合缩放 | 0.1x ~ 3x 流畅 | 待测 |
| 单指拖动调位 | 重新 hit-test 移位 | 待测 |
| 反复退出 / 重进 | 不崩溃,anchor 重置 | 待测 |
| 视觉质量 | splat 在 AR camera feed 上不闪烁 | 待测 |
| Landing C 路径完整 | 朋友扫码 → landing → AR 全程 | 待测 |

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
