# ADR-005 · 应用形态选 Android WebView 套壳 + Share Intent + MediaProjection

> 状态:✅ Accepted
> 日期:2026-05-14
> 关联:[ADR-004 MVP 方向调整](ADR-004-MVP-方向调整-本地优先.md)、[03-技术架构.md](../03-技术架构.md)

---

## 背景 / 上下文

ADR-004 把 MVP 转向本地单机 + 视频微信分享后,产生新的关键决策:**用什么应用形态承载?**

zhi 已确认头号使用场景是 **Magic7 Pro**(主理人在手机上扫描 → 漫游 → 录视频 → 微信发),桌面方案(Tauri / Electron)直接排除。问题收窄到「Android 端用什么形态」。

讨论 B 路线(Android 原生 WebView 套壳 + 微信 OpenSDK)时,WebSearch 发现:**微信开放平台 OpenSDK 实际需要企业资质**(年费 300 元),个人开发者发布 OpenSDK 应用一直是灰色地带。这是 B 路线的核心风险。

## 决策

**B' 路线:Android 原生 WebView 套壳 + Android 系统 Share Intent + MediaProjection 录屏**。**不集成微信 OpenSDK**。

### 技术组件清单

| 组件 | 实现 |
|---|---|
| 应用主体 | Android Activity + WebView,加载 `assets/` 内本地 HTML/JS |
| 前端栈 | 复用 prototypes/ 已有 HTML + Tailwind CDN(后续 M1 可换 Vite + 框架) |
| 3D 引擎 | PlayCanvas Engine,跑在 WebView 内(Magic7 Pro WebView ≈ Chrome,H2 已验证流畅) |
| JS 与原生通信 | `WebView.addJavascriptInterface()` + Kotlin `@JavascriptInterface` 注解 |
| 微信分享 | **Android 系统 Share Intent**(`Intent.ACTION_SEND` + 视频文件 URI),弹系统分享面板,用户选「微信」 |
| 视频录制 | **MediaProjection API**(录全屏,包含 UI 操作和 PlayCanvas 画面) |
| 本地存储 | Android internal storage(`/data/data/<pkg>/files/`)+ SharedPreferences/Room DB 存场景元数据 |
| 文件导入 | Storage Access Framework(`ACTION_OPEN_DOCUMENT`)选 `.sog` 文件 |
| 分发 | 自签名 APK,zhi 自己装到 Magic7 Pro。**不上应用商店** |

## 备选方案

| 选项 | 评价 | 没选的原因 |
|---|---|---|
| **A · Android Chrome PWA** | 0 安装,工程量最小,prototypes 几乎不用动 | IndexedDB 在 iOS 7 天清理(虽然 iOS 已降级,但 Android 也偶有清理风险);只能录 canvas 不能录 UI 操作;首次必须联网 |
| **B · WebView 套壳 + 微信 OpenSDK 集成** | 微信分享体验最好(直接进微信选联系人,少 1 步) | **个人开发者无企业资质**,OpenSDK 走不通;企业认证 300 元/年是周末项目原则的违和 |
| **B' · WebView 套壳 + Share Intent + MediaProjection** ✅ | 保留 B 的本地存储 + 录全屏优势,丢掉微信 SDK 不可达的部分;0 微信平台风险 | (选择本项) |
| **C · 纯 Kotlin 原生 App** | 性能最高,深度集成 | ~3000 行 Kotlin,周末项目不现实 |
| **D · React Native / Capacitor / Tauri Mobile** | 跨平台 | iOS 在本项目降级,跨平台没价值;且 WebView 性能 ≤ Chrome,不如直接 WebView |

## 后果

### 好的影响

- **0 微信平台依赖**:不需要申请企业认证,不需要 AppID/AppSecret,不需要企业付费
- **真离线**:APK 装上后无网络可用,符合 ADR-004 的"不要互联网"
- **本地存储稳定**:Android internal storage 不会被系统清理,场景库不丢
- **能录全屏**:MediaProjection 包含 UI 操作和 PlayCanvas 画面,接收方看的视频更完整
- **WebView 体验等价 Chrome**:H2 验证 Magic7 Pro Chrome 流畅,WebView 同内核
- **复用 prototypes/**:前端代码大部分能直接搬到 `assets/`,省再设计成本
- **学习曲线可控**:Kotlin 主要写 ~600 行,核心是 WebView 配置 + JS Bridge + 几个原生 API

### 不好的影响 / 承担的代价

- **微信分享多一步**:用户得在系统分享面板选「微信」,而不是直接跳微信选联系人。**1 次额外点击**
- **Kotlin / Android Studio 学习曲线**:zhi 是 PM,不熟 Android 开发。决定让 Claude 写代码,zhi 跑测试 → **Bus factor = 1 + 1**(Claude + zhi)
- **Android Studio 安装包大**:~2GB 左右,加上 SDK 总占盘 5-10GB
- **签名 / APK 分发麻烦**:自签 APK 需要 keystore 管理;装 APK 需要"未知来源"权限
- **iOS 用户彻底没法用**:即便 ADR-004 说 iOS 降级,后续 Phase 2 想做 iOS 也得重写
- **审核 / 上架风险后置**:如果未来要上华为应用市场或国内 Android 商店,需要补企业认证
- **MediaProjection 需要敏感权限**:每次录屏都会弹「即将开始捕获屏幕上的所有内容」系统对话框,无法绕过

### 缓解措施

- **Bus factor 风险**:Claude 写的所有 Kotlin 代码会**强制带详细中文注释**,zhi 每次提交前 review;关键模块写最小可运行 demo 让 zhi 跑通验证
- **APK 分发**:M1 阶段就你 zhi 自己用,直接 `adb install`(已验证 ADB 工作);M2 才考虑分发渠道
- **MediaProjection 弹窗**:UI 文案明确告诉用户"接下来会出现系统提示,点允许即可",降低首次困惑

## 待决问题(后续 ADR / 文档解决)

| # | 问题 | 决策时点 |
|---|---|---|
| **Q3** | Roam 是否改名?当前 = 漫游,但接收方在微信看视频不漫游,有违和 | M1 实际能跑后再说,原型阶段暂用 Roam |
| **Q4** | 接收方回路:朋友看了视频后,有没有路径打开 Roam 自己漫游?二维码/短链? | M2 之前 |
| **新 Q5** | 视频规格:分辨率(1080p?720p?)、帧率(30/60)、码率上限(为微信 25MB 限制留余量) | M1 H7 实测决定 |
| **新 Q6** | Kotlin 项目结构 / Gradle 模块拆分 / 包名 | M1 第一次创建 Android 项目时决定 |

## 何时回头看

- **微信分享体验**:M2 阶段如果发现「弹系统面板再选微信」这一步用户怨念大,**重新评估是否值得为微信 SDK 申请企业认证**
- **iOS 用户呼声**:Phase 2 如果有 iOS 用户呼声大,考虑用 Capacitor / Tauri Mobile 做跨端
- **PWA 升级路径**:如果发现 WebView 套壳工程量过大,M1 中途可以**临时回退 PWA** 先验证产品价值

---

## 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| v0.1 | 2026-05-14 | 初版,沉淀 Q1(应用形态)+ Q2(视频录制)合并决策 |
