# 真 WebXR AR · Chrome viewer 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development(推荐)或 superpowers:executing-plans 按任务逐步实施。步骤用 checkbox(`- [ ]`)跟踪。
> **⚠️ 本项目不写测试代码**(CLAUDE.md:zhi 一人手动测够用)。故下方用 **adb 自动化桥 / 手动验证** 替代 TDD 的「写失败测试」步骤。其余 bite-sized / frequent commits / exact paths 保留。
> 关联 spec:[2026-06-07 真 AR Chrome viewer 落地设计](2026-06-07-真AR-Chrome-viewer-落地设计.md)

**Goal:** 让真 WebXR AR 在 Roam 落地 —— App 内点「真 AR」跳系统 Chrome 打开 `docs/ar.html` 看 splat 贴桌面,zhi 自己 + 朋友分享都能用。

**Architecture:** App(WebView 无 navigator.xr)→ Intent 跳 Chrome → 独立精简 `docs/ar.html`(零 RoamBridge)按 `?scene=` 加载 splat + detectAR + 真 AR/fallback。demo 资源走 jsdelivr 指 `assets/scenes/`,自扫 sample 走 `docs/scenes/` 同源。

**Tech Stack:** PlayCanvas Engine 2.18.1 UMD + @spz-loader 0.3.1 UMD + WebXR immersive-ar + Kotlin Intent.ACTION_VIEW。

---

## 文件结构

| 文件 | 责任 | 动作 |
|---|---|---|
| `client/app/src/main/java/com/roam/app/RoamBridge.kt` | 新增 `openInChrome(url)` 跳系统 Chrome | 改 |
| `client/app/src/main/assets/index.html` | 🥽 卡片加「真 AR」按钮 + ⑤ 二维码改 URL | 改 |
| `docs/vendor/playcanvas.min.js` | ar.html 渲染引擎 | 复制 |
| `docs/vendor/spz-loader-playcanvas.umd.cjs` | ar.html SPZ 解码 | 复制 |
| `docs/ar.html` | Chrome 专用 AR viewer(场景加载 + detectAR + 真AR + fallback) | 新建 |

---

## Task 1: RoamBridge.openInChrome + App「真 AR」按钮入口

**Files:**
- Modify: `client/app/src/main/java/com/roam/app/RoamBridge.kt`(仿 `openVideoExternal` line 162-186)
- Modify: `client/app/src/main/assets/index.html`(🥽 卡片 line 381-410 区域 + AR 按钮绑定 line 1027-1045 区域)

- [ ] **Step 1: RoamBridge 加 openInChrome**

在 `RoamBridge.kt` 的 `openDeepLink` 方法后(约 line 213)加:

```kotlin
/**
 * 打开系统 Chrome 看真 WebXR AR(Roam WebView 不暴露 navigator.xr,真 AR 必须经 Chrome)
 * 仿 openVideoExternal,强制 Chrome(WebXR 只 Chrome 稳),无 Chrome 回退 chooser
 */
@JavascriptInterface
fun openInChrome(url: String) {
    Log.d(TAG, "openInChrome() url=$url")
    activity.runOnUiThread {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // 没装 Chrome → 让系统选
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(Intent.createChooser(fallback, "用什么打开 AR?"))
        } catch (e: Exception) {
            Log.e(TAG, "openInChrome 失败", e)
            toast("打开 Chrome 失败:${e.message}")
        }
    }
}
```

确认顶部已 `import android.net.Uri`(若无则加)。

- [ ] **Step 2: index.html 🥽 卡片加「真 AR」按钮**

在 🥽 AR 卡片(line 381-410)的 fallback 按钮区,AR-lite/VR 按钮旁加:

```html
<button id="ar-real-chrome-btn" style="margin-top:6px;">🥽 真 AR(跳 Chrome)</button>
```

- [ ] **Step 3: index.html 绑定按钮 onclick**

在 detectAR 的按钮绑定区(line 1027-1045 附近)加(`currentSceneId` 取当前场景,复用现有场景状态变量;若无现成变量,用 `window.__currentSceneId` 或从 localStorage 读上次场景):

```javascript
const realArBtn = document.getElementById('ar-real-chrome-btn');
if (realArBtn) {
  realArBtn.addEventListener('click', function () {
    const id = (typeof currentSceneId !== 'undefined' && currentSceneId) ? currentSceneId : 'sample';
    const url = 'https://zhaozhi189.github.io/roam/ar.html?scene=' + encodeURIComponent(id);
    if (window.RoamBridge && RoamBridge.openInChrome) {
      RoamBridge.openInChrome(url);
    } else {
      window.open(url, '_blank');  // 非 App 环境兜底
    }
  });
}
```

> 实施注意:先 grep `index.html` 确认「当前场景 id」的真实变量名(startPlayCanvasScene 调用处传的 id),用真实变量替换 `currentSceneId`。

- [ ] **Step 4: 构建装机**

```bash
cd client
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `BUILD SUCCESSFUL` + `Success`

- [ ] **Step 5: 验证按钮跳 Chrome(ar.html 还没建,预期 Chrome 报 404,只验跳转动作)**

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB shell am start -n com.roam.app/.MainActivity --es auto apartment
sleep 4
# 手动点 🥽 卡片「真 AR」按钮,或等 ar.html 建好后整体验
$ADB shell dumpsys activity activities | grep -i "mResumedActivity" 
```
Expected: 点按钮后前台变 `com.android.chrome`(此刻打开线上 ar.html,未部署则 404 —— 正常,Task 6 部署后才有内容)

- [ ] **Step 6: Commit**

```bash
git add client/app/src/main/java/com/roam/app/RoamBridge.kt client/app/src/main/assets/index.html
git commit -m "feat(AR): RoamBridge.openInChrome + 🥽 真 AR 按钮跳 Chrome"
```

---

## Task 2: docs/vendor + docs/ar.html 骨架(场景加载)

**Files:**
- Copy: `client/app/src/main/assets/vendor/{playcanvas.min.js,spz-loader-playcanvas.umd.cjs}` → `docs/vendor/`
- Create: `docs/ar.html`
- 参考(场景加载逻辑):`client/app/src/main/assets/index.html:1639`(startPlayCanvasScene)+ `:1872-1897`(GSplatResource.instantiate patch)

- [ ] **Step 1: 复制 vendor**

```bash
cd /Users/zhi/Documents/dev/private/roam
mkdir -p docs/vendor
cp client/app/src/main/assets/vendor/playcanvas.min.js docs/vendor/
cp client/app/src/main/assets/vendor/spz-loader-playcanvas.umd.cjs docs/vendor/
ls -la docs/vendor/
```
Expected: 两个文件在 docs/vendor/

- [ ] **Step 2: 新建 docs/ar.html 骨架**

创建 `docs/ar.html`,含:HTML 结构(canvas + 状态条 + 错误红字 div)、vendor 引入、PlayCanvas init、AR_SCENES 映射、`?scene=` 解析、场景加载。骨架:

```html
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>Roam · 真 AR</title>
<style>
  html,body{margin:0;height:100%;background:#0b0b14;overflow:hidden;font-family:system-ui}
  #cv{width:100vw;height:100vh;display:block;touch-action:none}
  #status{position:fixed;top:8px;left:8px;color:#9af;font-size:12px;z-index:10}
  #err{position:fixed;top:40px;left:8px;right:8px;color:#f66;font-size:13px;z-index:11;display:none}
  .arbtn{position:fixed;z-index:12;padding:10px 16px;border-radius:8px;border:1px solid #6cf;
         background:rgba(20,30,60,.85);color:#cdf;font-size:15px}
</style>
</head>
<body>
<canvas id="cv"></canvas>
<div id="status">初始化…</div>
<div id="err"></div>
<script src="vendor/playcanvas.min.js"></script>
<script src="vendor/spz-loader-playcanvas.umd.cjs"></script>
<script>
const JSD = 'https://cdn.jsdelivr.net/gh/zhaozhi189/roam@main/client/app/src/main/assets/scenes/';
const AR_SCENES = {
  apartment: { url: JSD+'apartment.sog',         kind:'gsplat' },
  guitar:    { url: JSD+'guitar.compressed.ply', kind:'gsplat' },
  spz:       { url: JSD+'hornedlizard.spz',       kind:'spz' },
  skull:     { url: JSD+'skull.sog',              kind:'gsplat' },
  biker:     { url: JSD+'biker.compressed.ply',   kind:'gsplat' },
  sample:    { url: 'scenes/sample.compressed.ply', kind:'gsplat' },
};
function setStatus(s){ document.getElementById('status').textContent = s; }
function showErr(s){ const e=document.getElementById('err'); e.textContent=s; e.style.display='block'; }

// ?scene= 解析(缺失/非法 → sample)
const qScene = (new URLSearchParams(location.search)).get('scene');
let sceneId = (qScene && AR_SCENES[qScene]) ? qScene : 'sample';
if (qScene && !AR_SCENES[qScene]) setStatus('未知场景 '+qScene+',已载默认 sample');

// PlayCanvas init(preserveDrawingBuffer 防黑屏,见 CLAUDE.md 已知坑)
const canvas = document.getElementById('cv');
const app = new pc.Application(canvas, {
  graphicsDeviceOptions: { preserveDrawingBuffer: true, alpha: true }
});
app.setCanvasFillMode(pc.FILLMODE_FILL_WINDOW);
app.setCanvasResolution(pc.RESOLUTION_AUTO);
window.addEventListener('resize', ()=>app.resizeCanvas());

// 相机 + 光(orbit 默认视角,2D 兜底也用它)
const camera = new pc.Entity('camera');
camera.addComponent('camera', { clearColor: new pc.Color(0.04,0.04,0.08,1) });
camera.setPosition(0,1,3);
app.root.addChild(camera);
const light = new pc.Entity('light'); light.addComponent('light'); 
light.setEulerAngles(45,30,0); app.root.addChild(light);
app.start();
</script>
</body>
</html>
```

- [ ] **Step 3: ar.html 加场景加载逻辑(gsplat + spz)**

在 `app.start()` 后追加场景加载。**参考 index.html:1639 startPlayCanvasScene + 1872-1897 patch**,去掉 RoamBridge,精简成单场景加载:

```javascript
let splatEntity = null;
function loadScene() {
  const sc = AR_SCENES[sceneId];
  setStatus('加载 '+sceneId+'…');
  // gsplat .sog/.ply + spz 统一走 asset,spz 需先确保 window.SpzWasmPlayCanvas patch(抄 index.html 的 GSplatResource.instantiate 强制 patch)
  applyGSplatPatch();  // 抄自 index.html:1878-1897
  const assetType = (sc.kind === 'spz') ? 'gsplat' : 'gsplat';  // 两类都进 gsplat 管线(index.html 同款)
  const asset = new pc.Asset(sceneId, 'gsplat', { url: sc.url });
  // 加载超时 30s(抄 ADR-010 c7ae6af Promise.race 思路)
  const timeout = setTimeout(()=>showErr('场景加载超时,检查网络'), 30000);
  asset.once('load', ()=>{ clearTimeout(timeout); onSplatLoaded(asset); });
  asset.once('error', (e)=>{ clearTimeout(timeout); showErr('加载失败:'+e); });
  app.assets.add(asset); app.assets.load(asset);
}
function onSplatLoaded(asset) {
  splatEntity = new pc.Entity(sceneId);
  splatEntity.addComponent('gsplat', { asset });
  splatEntity.setLocalScale(1,1,1);
  app.root.addChild(splatEntity);
  setStatus(sceneId+' 就绪');
}
loadScene();
```

> 实施关键:SPZ 加载的真实 API 以 index.html startPlayCanvasScene 内 spz 分支为准(createGSplatEntityFromSpzAsync / SpzWasmPlayCanvas),照抄改造。Step 2-3 的骨架是结构,具体 gsplat/spz API 调用务必比对 index.html 现有可跑代码。

- [ ] **Step 4: 本地起 server + adb reverse 验证场景渲染**

```bash
cd /Users/zhi/Documents/dev/private/roam/docs
python3 -m http.server 8123 &
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB reverse tcp:8123 tcp:8123
$ADB shell am start -a android.intent.action.VIEW -d "http://localhost:8123/ar.html?scene=sample" com.android.chrome
sleep 6
$ADB exec-out screencap -p > /tmp/ar-html-sample.png
```
Expected: 截图看到 sample splat 渲染(非黑屏)+ 状态条「sample 就绪」。再换 `?scene=apartment` 验 jsdelivr demo。

- [ ] **Step 5: Commit**

```bash
git add docs/vendor docs/ar.html
git commit -m "feat(AR): docs/ar.html 骨架 + vendor + 场景加载(gsplat/spz)"
```

---

## Task 3: ar.html detectAR + enterAR(真 AR 全套)

**Files:**
- Modify: `docs/ar.html`
- 参考(照抄改造):`client/app/src/main/assets/index.html:956-1130`(detectAR / enterAR / exitAR + hit-test/anchor/scale/reset)

- [ ] **Step 1: 抄 detectAR + enterAR/exitAR 到 ar.html**

把 index.html `956-1130` 的 detectAR + enterAR + exitAR + hit-test/plane/anchor/scale/reset 逻辑抄进 ar.html `</script>` 前。**改造点**:
- 删除所有 `RoamBridge.toast(...)` → 改 `setStatus(...)` 或 `alert(...)`
- 删除所有 `RoamBridge.log(...)` → 改 `console.log(...)`
- AR session 的目标 entity 用本页 `splatEntity`(Task 2)
- START AR 按钮:`<button id="ar-start" class="arbtn" style="left:50%;transform:translateX(-50%);bottom:32px;">进入 AR</button>`,detectAR 探测到 immersive-ar L1=true 时显示并绑定 enterAR

- [ ] **Step 2: detectAR 自动跑 + 显示入口**

ar.html 加载后自动 detectAR(抄 index.html:994 的 async IIFE),探测 `navigator.xr` → `isSessionSupported('immersive-ar')`:
- L1 true → 显示「进入 AR」按钮 → 点击 enterAR(真 requestSession + hit-test 放置 splatEntity)
- L1 false → 进 Task 4 fallback

- [ ] **Step 3: 本地验证 detectAR 状态 + zhi 手测真 AR**

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB shell am start -a android.intent.action.VIEW -d "http://localhost:8123/ar.html?scene=sample" com.android.chrome
sleep 6
$ADB exec-out screencap -p > /tmp/ar-html-detect.png
```
Expected: 截图见「进入 AR」按钮(蓝色可点)。**zhi 手测**:点「进入 AR」→ 授权相机 → 举手机看 splat 贴桌面 → hit-test 移动 → pinch 缩放 → 重置 → 退出。

- [ ] **Step 4: Commit**

```bash
git add docs/ar.html
git commit -m "feat(AR): ar.html detectAR + enterAR 真 AR 全套(hit-test/anchor/scale/reset)"
```

---

## Task 4: ar.html fallback(AR-lite/VR)+ 错误处理

**Files:**
- Modify: `docs/ar.html`
- 参考:`client/app/src/main/assets/index.html:1132-1290`(enterARLite/exitARLite/enterVR/exitVR)

- [ ] **Step 1: 抄 AR-lite + VR fallback**

把 index.html `1132-1290` 的 enterARLite/exitARLite/enterVR/exitVR 抄进 ar.html,改造点同 Task 3(去 RoamBridge,target=splatEntity)。detectAR 分支:
- immersive-ar L1 false 但 getUserMedia 可用 → 显示「📱 AR-lite」按钮
- immersive-vr 可用 → 显示「🥽 VR」按钮
- 都不可用 → 不显示 AR 按钮,仅 2D orbit 看 splat + `setStatus('设备不支持 AR,可旋转查看')`

- [ ] **Step 2: 错误处理收尾**

确认四类错误(spec §6)都有 UI:
- scene 非法 → 已在 Task 2 Step 2(默认 sample + 提示)
- 加载失败/超时 → 已在 Task 2 Step 3(showErr + 重试)。补一个重试按钮:`showErr` 时加 `<button onclick="location.reload()">重试</button>`
- requestSession 失败 → enterAR 的 catch 里 `setStatus('真 AR 启动失败,试试 AR-lite')` + 显示 fallback 按钮
- 完全不支持 → Step 1 已覆盖(2D orbit)

- [ ] **Step 3: 手测 fallback(可选,需无 ARCore 环境)**

Magic7 有 ARCore 跑不到 fallback。验证方式:临时在 ar.html detectAR 顶部 `const FORCE_FALLBACK=true` 强制走 fallback 分支 → 手测 AR-lite 相机叠加 + VR 立体视,验完删掉强制开关。

```bash
$ADB exec-out screencap -p > /tmp/ar-html-fallback.png
```

- [ ] **Step 4: Commit**

```bash
git add docs/ar.html
git commit -m "feat(AR): ar.html AR-lite/VR fallback + 错误处理(超时/重试/2D 兜底)"
```

---

## Task 5: 二维码 URL 改指 ar.html(分享路径)

**Files:**
- Modify: `client/app/src/main/assets/index.html:2760`(二维码 URL 生成)

- [ ] **Step 1: 改二维码 URL**

`index.html:2760` 现为:
```javascript
const url = 'https://zhaozhi189.github.io/roam/?scene=' + encodeURIComponent(sceneName);
```
改为指向 ar.html(朋友扫码直达真 AR/fallback):
```javascript
const url = 'https://zhaozhi189.github.io/roam/ar.html?scene=' + encodeURIComponent(sceneName);
```
同步更新 line 312 的说明文案(landing → ar.html AR)。

- [ ] **Step 2: 重建装机 + 验证二维码**

```bash
cd client && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb shell am broadcast -a com.roam.app.AUTO --es cmd qr-apt
sleep 2
~/Library/Android/sdk/platform-tools/adb exec-out screencap -p > /tmp/ar-qr.png
```
Expected: 二维码生成,下方 URL 含 `ar.html?scene=`

- [ ] **Step 3: Commit**

```bash
git add client/app/src/main/assets/index.html
git commit -m "feat(AR): ⑤ 二维码 URL 改指 ar.html,朋友扫码直达真 AR"
```

---

## Task 6: 部署 + 端到端验证(zhi 操作 push)

**Files:** 无代码改动,部署 + 验收

- [ ] **Step 1: zhi push 到 GitHub Pages**

> ⚠️ Claude 不 push(CLAUDE.md)。由 zhi 执行:
```bash
git push origin <分支>   # 或合并到 main 后 push,GitHub Pages 部署 docs/
```
等 Pages 部署完成(~1min)。

- [ ] **Step 2: 线上端到端(zhi 手测)**

- App 内某场景 → 点「🥽 真 AR」→ Chrome 打开线上 `ar.html?scene=xxx` → 进真 AR
- 6 demo + sample 各开一次,确认 jsdelivr/docs 资源都通
- 生成二维码 → 另一台手机/微信扫 → 打开 ar.html 进 AR(分享路径)

- [ ] **Step 3: 回填文档**

- ADR-012 v0.5「待实施」→「已 ship」+ 实测结论
- doc/10 加真 AR 端到端验证条目(M8-11g~l 在 ar.html 环境逐条验)
- doc/06 版本号 +1 + 修订记录

```bash
git add doc/
git commit -m "docs(AR): 真 AR Chrome viewer ship + 端到端验证回填"
```

---

## 自检清单(实施完核对)

- [ ] spec §3 五个组件都有对应 task(1:Bridge+按钮 / 2:ar.html+vendor / 3-4:AR逻辑 / 5:二维码)✅
- [ ] 无 placeholder:抽取类步骤给了源行号 + 改造点,骨架代码给了结构(具体 gsplat/spz API 明确要求比对 index.html 现有可跑代码)
- [ ] 命名一致:`openInChrome` / `AR_SCENES` / `splatEntity` / `setStatus` / `showErr` 全计划统一
- [ ] 资源策略一致:demo=jsdelivr 指 assets/scenes/,sample=docs/ 同源(Task 2 AR_SCENES 与 spec §5 一致)
</content>
