# ADR-010 · 自扫场景数据源 — 「Magic7 Pro 拍 + Mac Brush 训」主路 + iPhone Scaniverse 备路 + 公开 sample 应急

> 状态:✅ Accepted(决定本地训为主、移动端端到端扫为备、公开 sample 当 fallback)
> 日期:2026-05-22
> 关联:[ADR-004 MVP 方向调整-本地优先](ADR-004-MVP-方向调整-本地优先.md)、[ADR-003 单场景文件大小上限](ADR-003-单场景文件大小上限.md)、[doc/09-自扫工具链调研.md](../09-自扫工具链调研.md)

---

## 背景

Roam 的核心承诺是「扫描真实空间 · 浏览器漫游 · 微信视频分享」。M0-M7 完整通过后,内置 6 个场景全是**公开 demo 数据**(PlayCanvas 官方 / 公开 splat dataset)— 还没有任何**真自扫**的样例场景。下一步需要 ship 至少 1 个 zhi 本人扫的 3DGS 场景作为 landing 演示 + App ⑧ 列表新增项。

但走到这一步遇到**数据源问题**:

| 工具 | 平台 | 类型 | 在 zhi 当前条件下可用性 |
|---|---|---|---|
| Scaniverse | iOS + Android | 端到端(拍+训+出文件 一气呵成) | ❌ 登录卡(Apple/Google 海外验证 / 网络) |
| KIRI Engine | iOS / Android / Web | 端到端 | ❌ CLAUDE.md 排除(不用) |
| Polycam | iOS / Android / Web | 端到端 | ❌ CLAUDE.md 排除(不用) |
| Luma AI | iOS / Android | 端到端 | ❌ CLAUDE.md 排除(不用) |

zhi 主力机:**Magic7 Pro(Android,Magic OS,无 GMS)** + **Mac M 芯片工作机**。Magic7 Pro 装 Scaniverse 即便 sideload 成功,登录环节也卡 — 与 iOS 同样问题。zhi 没有日常 iOS 设备。

### 关键认知(避免被「端到端工具」绑架)

3DGS 工作流可以**拆开成两件事**:

1. **采集**(capture):拍照片 / 视频。任何带相机的设备都行 — Android 手机、iPhone、相机、无人机。
2. **训练**(train):跑 SfM + Gaussian Splatting,产出 `.ply / .splat`。这才是真正消耗算力的环节。

Scaniverse / KIRI / Polycam 这类**端到端工具**把两步合在手机里,优点是用户体验好,缺点是必须用它们的扫描 App + 必须登录它们的账号 + 必须用它们能跑的设备。**对 zhi 当前局面全部不通**。

**解耦后**:zhi 用 Magic7 Pro 系统相机拍视频(0 门槛),Mac 上跑开源训练器,完全绕开账号 / 国别 / 平台问题。

## 决策

**三层数据源策略,按可控性 / 时效排序:**

### 1. 主路 · 「Magic7 Pro 拍 + Mac Brush 训」

- **采集**:Magic7 Pro 系统相机 App,拍 15-30 秒视频,绕目标物体 / 房间一圈,稳定速度移动,光照均匀
- **传输**:微信发自己 / USB / 华为分享 / AirDrop(Mac 端装华为分享插件) → Mac 本地视频文件
- **训练**:Mac 装 [Brush](https://github.com/ArthurBrussee/brush)(开源,Metal GPU 加速,5-30min 取决于视频长度) → 输出 `.ply`
- **后处理**:可选 [SuperSplat web 编辑器](https://playcanvas.com/supersplat/editor) 压缩 / 去噪 / 裁切
- **入库**:拷到 `docs/scenes/zhi-scan-NN.ply` 或 `client/app/src/main/assets/scenes/`,git push
- **优势**:完全本地、不需要任何账号、不依赖云服务、无国别封锁、输出标准 `.ply` 直接给 Roam 用、**前端采集设备就是 zhi 的主力机,零额外硬件**

### 2. 备路 · iPhone Scaniverse 无登录模式(有 iPhone 时)

- **关键发现**:Scaniverse 在 iOS 上首次开启可**「Skip account」**直接扫,不强制登录(只有云端备份才需要账号)
- **何时用**:借朋友 iPhone(已在 App Store 装好 Scaniverse) → 扫 → Files App 导出 .ply → AirDrop / 微信 转给 zhi
- **限制**:借机不便、扫完不能云存(无账号)、只对一次性高质量样例有意义
- **优势**:Scaniverse 模型质量 Niantic 商业训练,公认顶级 — 用一次拿个「最佳样例」演示

### 3. 应急 · 公开 sample 数据集

- **来源**:PlayCanvas 官方 demo splat / Mip-NeRF 360 dataset / Tanks and Temples / SuperSplat 示例
- **用途**:Brush 没装好 / 训练效果不达标时,先用公开数据**占位**保证 landing 演示完整(标注「公开数据集」非自扫)
- **限制**:不算「真自扫」,只是 ship 不卡进度的兜底

## 实现要点

### Phase 1(本会话 + 下个会话,我能独立做)

```
1. 下载 1 个公开 splat sample 落到 docs/scenes/sample-room.ply
   - 优选 PlayCanvas 官方 demo(版权清晰)或 Mip-NeRF 360 garden
   - 文件大小遵循 ADR-003 ≤ 30MB

2. landing 加「样例场景」入口
   - docs/index.html ?scene=sample 路由
   - 标注「公开数据集」徽章区分自扫

3. App ⑧ 场景管理加「拉远程 sample」按钮
   - 一键 fetch https://zhaozhi189.github.io/roam/scenes/sample-room.ply
   - 走 SAF 流程入库到 user-scenes
```

### Phase 2(zhi 配合,跨会话 — 主路)

```
4. zhi 在 Mac 装 Brush(从 GitHub release 下二进制或自编译)
5. zhi 用 Magic7 Pro 拍一段 15-30 秒视频(房间 / 物件,绕一圈,稳定)
   - 微信发自己 / USB 传到 Mac
6. zhi 在 Mac Brush 导入视频 → 训练得到 .ply
7. 我接力做后续:
   - 用 SuperSplat web 编辑器压缩 / 去噪 / 裁切
   - 落到 docs/scenes/zhi-scan-01.ply 或 client/assets/scenes/
   - 加到内置 6 场景 或者只 landing 演示 + ⑧ 列表
```

### Phase 3(机会主义,有 iPhone 时 — 备路)

```
8. 借 iPhone 装 Scaniverse,skip account,扫一个高质量样例
9. 同 Phase 2 步骤 7 接力入库
```

## 备选方案

| 选项 | 评价 | 没选的原因 |
|---|---|---|
| **A · 等 Scaniverse 国内登录修复** | 不动作 | 等了几个月没修,无 ETA |
| **B · Scaniverse Android sideload + 翻墙登录** | 工具对应 | Magic OS 无 GMS,登录链路仍卡;翻墙不稳定 |
| **C · Apple ID 海外区注册 + iOS App Store 装** | 可行 | 需要海外信用卡 / 礼品卡、注册成本高、zhi 没日常 iOS 设备 |
| **D · 用 KIRI/Polycam/Luma 任一** | 现成 | CLAUDE.md 项目级排除,不重新讨论 |
| **E · 用 Postshot(Windows)桌面训** | 训练质量高 | zhi 主力 Mac,Windows 部署成本高 |
| **F · 用 gsplat.studio 在线训** | 免登录 | 需上传照片到第三方、违 ADR-004「本地优先」精神 |
| **G · Magic7 Pro 拍 + Mac Brush 训 + iPhone 备路 + 公开 sample 应急** ✅ | 全本地可控 + 应急有 fallback + 不破坏 ADR-004 + 用 zhi 现有设备 | (本项) |

## 后果

### 好的影响

- **不依赖外部账号 / 国别封锁** — Brush 本地训、Magic7 Pro 系统相机拍、公开数据集 CDN 可达
- **延续 ADR-004 本地优先精神** — 训练在 Mac 本地完成,资源最终落到 GitHub Pages 自托管
- **三层 fallback** — 任一层失败不阻塞 ship landing 演示
- **不引入新依赖到 Roam App** — Brush 训练 / 系统相机拍摄都在 App 外完成,Roam App 代码零改动
- **零额外硬件** — 主路只用 zhi 现有的 Magic7 Pro + Mac,不需要 iPhone / Windows / 相机

### 不好的影响 / 取舍

- **Brush 学习曲线** — zhi 需要花 1-2 小时熟悉(读 README / 试拍 / 试训)
- **训练耗时** — 短视频 5min,长视频 30min,不是「按一下就出」
- **采集质量门槛** — Magic7 Pro 拍摄需注意稳定 / 光照 / 覆盖度,不像 Scaniverse 现场实时反馈
- **质量不一定打过 Scaniverse** — Brush 模型质量 vs Niantic 商业训练差距未知,要实测;不达标走备路
- **公开 sample 不算真自扫** — landing 演示需明确标注,否则误导用户对工具能力的预期
- **每次新增场景手动流程长** — 拍 → 传 → 训 → 后处理 → push,没有 1-click 体验

## 实测计划(下次会话或 zhi 配合时)

| 验证 | 期望 | 实测 |
|---|---|---|
| 公开 sample 加载到 Roam Magic7 Pro | 同公寓水准,> 60 fps | 待测 |
| 公开 sample 微信分享视频 | 接收方能播放 | 待测 |
| Magic7 Pro 拍 20s 视频,Brush 训耗时 | < 10min | 待测 |
| Brush 输出 .ply 直接给 Roam 加载 | 不需转格式 | 待测 |
| Brush 模型质量 vs 公寓 demo | 大致接近 | 待测 |
| iPhone Scaniverse skip account | 真的能跳过登录 | 待测(机会主义) |

## 何时回头看

- **Scaniverse 国内登录修复** → 切回 Scaniverse 主路(质量最稳),Brush 退备
- **PlayCanvas / Roam 出 App 内扫描功能** → 直接 App 内扫,跳过桌面训练流程
- **Apple Vision Pro / iPhone Pro LiDAR 普及** → 优先 LiDAR + Apple 原生扫(质量极高 + 设备普及)
- **zhi 入手 iPhone(Pro 系)** → 备路升主路:iPhone Scaniverse skip account 更便捷
- **WebGPU 3DGS 训练在浏览器跑通** → 用户端浏览器自训,不需要桌面工具
- **Magic OS 接入 GMS 或 ARCore** → 可重新评估 Android 端到端工具(KIRI / Polycam 仍受 CLAUDE.md 排除,但生态可重新审视)

---

## 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| v0.1 | 2026-05-22 | 初版,M7 后 C 路径 landing 完整闭环、需要 ship 真自扫样例场景时沉淀;锁定 Brush 主路 + iPhone Scaniverse 备 + 公开 sample 应急三层策略 |
| v0.2 | 2026-05-22 | **澄清重要误解**:Brush 是「只训不拍」工具,拍摄设备完全解耦 → 主路明确为「Magic7 Pro 系统相机拍 + Mac Brush 训」,iPhone 不再是主路必需。新增「关键认知」段强调采集 / 训练可分离。备选方案补充 Scaniverse Android sideload 路线及未选原因。 |
