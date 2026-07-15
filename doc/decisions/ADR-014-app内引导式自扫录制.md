# ADR-014 · App 内引导式自扫录制 — 把「采集质量门槛」从看运气变成照着拍

> 状态:🟢 已实施(v1 真机验证通过;v2 引导强化已 ship;v3 推进规则修正已 ship 待真机复验)
> 日期:2026-06-13(v0.3 修订 2026-07-15)
> 关联:[ADR-010 自扫数据源-Magic7拍-Mac训](ADR-010-自扫场景数据源-Magic7拍-Mac-Brush训.md)、[ADR-012 无ARCore降级](ADR-012-无ARCore设备的AR降级方案-AR-lite-VR.md)、`scripts/scan-train.sh`(commit 8b1312c exhaustive matching)

---

## 背景

ADR-010 定下「Magic7 Pro 系统相机拍 + Mac Brush 训」的自扫主路,并在「不好的影响」里**明确预言了一个缺陷**:

> 采集质量门槛 — Magic7 Pro 拍摄需注意稳定/光照/覆盖度,**不像 Scaniverse 现场实时反馈**

2026-06-13 实测印证了这点:zhi 昨晚随手拍的房间视频(`VID_20260612_203604.mp4`,48s/1080p)能跑出 133/143 帧(93%)注册率,**是拍得好,但没有引导时这事看运气**——普通拍法(太快/覆盖不全/不闭环)大概率重建失败或漂移。

同日 `scan-train.sh` 把 COLMAP matching 从 `sequential` 改成 `exhaustive`(commit 8b1312c),让**绕圈拍的首尾闭环**成为重建不漂的关键。但 exhaustive 能不能发力,前提是**用户真的绕了一圈、首尾拍到同一片墙**——这恰恰是采集手法问题,光改脚本管不到。

技术上,底座已经在 App 里:
- `index.html` 的 AR-lite 模式已在用 `navigator.mediaDevices.getUserMedia` 开后摄(约第 1233 行)
- `RoamBridge.saveVideoBase64()` 已能把视频存到 `Movies/Roam/`

ADR-010 的「何时回头看」里也写了:「**Roam 出 App 内扫描功能 → 直接 App 内扫**」。本 ADR 即沿这条演进路径走第一步——**不做端到端扫描(仍不在手机训练),只补「引导式采集」**。

## 决策

在 Roam App 内新增**引导式录制**功能,用脚本式分段引导 + 陀螺仪辅助,引导用户拍出适合 3DGS 重建的房间视频;录完存相册并提示传 Mac 走 `scan-train.sh`。**采集与训练仍解耦(ADR-010 精神),手机只负责出片。**

### 边界(明确不做)

- ❌ 手机端训练(算力不够,违 ADR-010 解耦)
- ❌ SLAM / 空间覆盖感知(Magic7 无 ARCore,ADR-012 已确认拉不起)
- ❌ 自动把视频传到 Mac / 远程触发训练(YAGNI,adb pull / 微信传足够)
- ❌ 物体(object) preset(先只做房间,代码留扩展位)
- ❌ App 内「自扫草稿」状态列表(一人用,over-engineering)

## 设计

### 1. 录制流程脚本(房间 preset,总约 45s,可提前停)

| 段 | 时长 | 指令(顶部大字) | 陀螺仪辅助 | 对重建的作用 |
|---|---|---|---|---|
| 准备 | 3s | 竖握手机,站房间一角,离墙 1.5m+ | — | 起手姿势 |
| ① 水平绕 | 20s | 缓慢转/沿墙走一圈 | 角速度过快 → 红字「慢一点」 | 主覆盖 + 相邻帧重叠 |
| ② 抬头 | 10s | 抬头 30°,再扫上半圈 | 俯仰到位 → ▣天花板 打勾 | 补天花板(3DGS 怕顶部空) |
| ③ 低头 | 8s | 低头 30°,扫地面+家具底 | 俯仰到位 → ▣地板 打勾 | 补地板 |
| ④ 闭环 | 4s | 转回最开始那面墙,停 2 秒 | — | 喂饱 exhaustive 的闭环 |

流程脚本是一个**数组**(每段:指令文案 / 时长 / 目标俯仰角 / 是否检测角速度),物体 preset 以后加第二个数组即可。

### 2. 录制界面(竖屏 HUD)

```
┌───────────────────────────┐
│        [后摄实时画面]         │  getUserMedia 全屏 <video>
│                           │
│   ① 缓慢向左转一圈           │  当前指令(顶部大字)
│                           │
│         ◖ 62% ◗            │  进度环(按总时长/分段填充)
│      ⚠ 转太快,慢一点         │  陀螺仪反馈(红字,临时)
│                           │
│   覆盖: ▣天花板  □地板       │  俯仰覆盖打勾
│   ● REC  00:24 / 00:45     │  录制计时
│        [ ■ 停止 ]          │
└───────────────────────────┘
```

### 3. 技术实现要点

- **采集**:`getUserMedia({ video: { facingMode: 'environment' } })` 复用 AR-lite 那套 → `<video>` 全屏预览
- **录制**:`MediaRecorder` 录 webm(VP8/VP9)。**容器无所谓**——`scan-train.sh` 第一步 ffmpeg 抽帧,不挑容器(不是分享给微信,不受 ADR-006「微信不支持 WebM」约束)
- **陀螺仪**:`DeviceOrientationEvent.beta`(俯仰角,判抬头/低头到位)+ `DeviceMotionEvent.rotationRate`(角速度,判转太快)。WebView 在 https 虚拟域名(ADR-007)下应可拿,Android 不需 iOS 那种 `requestPermission`
- **落地**:`MediaRecorder` Blob → base64 → `RoamBridge.saveVideoBase64('roam-scan-<时间>.mp4', base64)` → `Movies/Roam/`。复用现有接口,RoamBridge 可零改动(文件名扩展名按实际容器)
- **提示**:录完弹 `RoamBridge.toast` +#pc-status:「已存 <文件名>,传 Mac 跑 `scan-train.sh <文件> scene`」
- **入口**:`index.html` 加一个「📷 自扫录制」按钮(具体放场景列表还是主界面,实现时按现有 UI 结构定)
- **自动化测试桥**:`runAuto` map 加 `scan-rec` cmd(对应录制入口按钮),便于 e2e

### 4. 降级(关键风险兜底)

`DeviceOrientation` / `DeviceMotion` 在 Magic7 WebView **拿不到** → 自动隐藏陀螺仪反馈(速度警告 + 俯仰打勾),退化为**纯脚本式分段引导**(计时 + 文案 + 进度环)。功能不依赖陀螺仪存活。**实施第一步先真机验证陀螺仪可用性**,据此决定是否实现辅助层。

## 备选方案

| 选项 | 评价 | 没选的原因 |
|---|---|---|
| A · 维持现状,系统相机随手拍 | 0 改动 | 采集质量看运气,重建失败率高(本 ADR 要解决的) |
| B · 录制前/中弹图文 checklist 卡片 | 最轻 | 没解决「实时」引导的核心诉求 |
| C · 真 SLAM 覆盖感知引导 | 体验最好 | Magic7 无 ARCore(ADR-012),做不到 |
| D · 手机端端到端扫(拍+训) | 一气呵成 | 手机算力不够,违 ADR-010 解耦 |
| **E · 脚本分段 + 陀螺仪辅助引导,纯采集** ✅ | 零新依赖、复用 getUserMedia/saveVideoBase64、不破坏 ADR-010、闭环段直接喂 exhaustive | (本项) |

## 后果

### 好的影响

- 把 ADR-010 的「采集质量门槛」从**看运气**变成**照着拍**,直接拉高自扫重建成功率
- **闭环段**与 commit 8b1312c 的 exhaustive matching 配套——前端引导用户拍出闭环,后端 matching 吃到闭环,首尾对齐
- **零新依赖**:复用 getUserMedia(AR-lite 已用)+ saveVideoBase64(已有),RoamBridge 基本零改动
- 延续 ADR-010 采集/训练解耦,不碰 Mac 训练链路

### 不好的影响 / 取舍

- 陀螺仪在 Magic7 WebView 的可用性**未实测**,是头号风险(有纯脚本降级兜底)
- 引导流程的时长/分段是**经验值**,需真机拍几次调参(像 scan-train.sh 的 FPS/steps 一样迭代)
- 仍需手动把视频从手机传到 Mac(本 ADR 不做自动传输)

## 何时回头看

- 陀螺仪真机不可用 → 砍掉辅助层,只留纯脚本引导
- 以后要扫物体 → 加 object preset(第二个流程数组 + 入口选择)
- Magic OS 接入 ARCore → 可重新评估真 SLAM 覆盖引导(ADR-012 回头看条目联动)

---

## 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| v0.1 | 2026-06-13 | 初版,与 zhi brainstorm 敲定:纯采集引导 / 脚本分段+陀螺仪辅助 / 存相册+提示 / 只房间 preset。配套 commit 8b1312c exhaustive matching 的闭环诉求 |
| v0.2 | 2026-07-02 | **v1 实施完成并真机验证**(zhi 实拍录制成功,commit b971f12)。实施中踩坑:toggleFullscreen 把场景 canvas 移到 body 末尾盖住相机层,video/hud 需同样 appendChild 到 body(AR-lite 同款坑)。**v2 引导强化**(zhi 实拍反馈「指引不明显、不知道拍没拍全、拍摄难度大」):① 语音播报每段指令(RoamBridge.speak/TTS)+ 换段震动 —— 举着拍看不清屏幕字,语音才是主指引;② 方位覆盖环 —— alpha 切 36 个 10° 扇区转到哪亮到哪,把「拍没拍全」可视化(零依赖伪 SLAM 覆盖感知);③ 分段推进从「掐秒数」改「完成度驱动」—— 覆盖达标才换段,maxSec 兜底,手慢不再等于拍废。陀螺仪不可用整链自动回退纯时间驱动。待真机复验:TTS 中文引擎可用性 / 覆盖环方向感 / up/down 段俯仰阈值 |
| v0.3 | 2026-07-15 | **v3 推进规则修正**(zhi 实拍反馈「时间太短,还没拍完就结束了」)。根因两条:① 覆盖段 maxSec 硬切(orbit 45s / up/down 30s)——语音让「缓慢转一圈」,转得慢就被拦腰截断;② 陀螺仪降级路径直接按最短秒数走(总 39s)根本拍不完。修正:覆盖段废弃 maxSec,改**停滞检测**——覆盖达标才换段;没达标但环还在点亮新扇区(=人还在转)就一直等;连续 12s(SCAN_REC_STALL_SEC)没新扇区(卡住/俯仰一直不达标)才兜底推进,120s(SCAN_REC_STEP_CAP_SEC)防挂死。降级路径新增 noGyroSec 按慢速转一圈标定(orbit 40s / up、down 各 25s,总 98s)。node 仿真验证三场景:慢转 64s 不被截断、卡住 12s 兜底、降级 98s 完整走完;**待真机复验** |
