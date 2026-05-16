# Roam Landing Page · GitHub Pages 部署

`site/` 是一个**单文件静态站点**,用于给微信生态内的接收方提供「点链接进 Roam」入口。

## 为什么需要它

微信内置 WebView 屏蔽 `roam://` 自定义 scheme(报错 `ERR_UNKNOWN_URL_SCHEME`),也屏蔽 `intent://` 兜底。所有自定义 scheme deep link 在微信内都会挂掉。

**实测可行的微信生态分享方式**:
1. 分享一个 `https://...` 链接(微信内可正常打开)
2. 链接落地页提示用户「右上角 ⋯ → 在浏览器打开」
3. 用户在系统浏览器内点击「在 Roam 中打开」→ Chrome 识别 `intent://` 调起 Roam

`site/index.html` 就是这个落地页。

## 路由

通过 URL 区分场景,两种方式都支持:

| URL 模式 | 示例 |
|---|---|
| query string | `/?scene=apartment` |
| path | `/scene/apartment/` |

JS 自动识别。微信检测后会显示「⚠️ 在微信内 → 右上角打开」提示。

## 部署到 GitHub Pages

### 一次性设置

1. push 当前 repo 到 GitHub(public 才能用 GitHub Pages 免费版)
   ```bash
   git remote add origin https://github.com/<你的用户名>/roam.git
   git push -u origin main
   ```
2. GitHub 仓库 → Settings → Pages
3. **Source**: Deploy from a branch
4. **Branch**: `main` / **Folder**: `/site`
5. Save · 等 1-2 分钟,显示 `Your site is live at https://<用户名>.github.io/roam/`

### 验证

打开:
```
https://<用户名>.github.io/roam/?scene=apartment
```
应该看到 landing page,场景 badge 显示「📍 🏠 公寓」。

### 修改后

`site/` 下文件改动 → push → GitHub Actions 自动重新部署(~1 分钟)。

## 落地页 → 二维码改造

把 ⑤ 卡片的二维码内容从 `roam://scene/apartment` 改成 `https://<用户名>.github.io/roam/?scene=apartment`,这样:
- 微信扫码 ✅ 在微信内可打开 landing page
- 系统相机扫码 ✅ 直接打开
- 任何地方点链接 ✅

修改位置:`client/app/src/main/assets/index.html` 内 `showQR()` 函数的 `url` 变量。

## 自定义域名(可选)

要用更短的 `https://roam.example.com/scene/apartment`:

1. 买域名(阿里云 / Cloudflare 都行)
2. DNS 加 CNAME 指向 `<用户名>.github.io`
3. site/ 加 `CNAME` 文件,内容是 `roam.example.com`
4. GitHub Pages Settings → Custom domain 填同样的域名

## Android App Links(可选,进阶)

当前是用户主动点击触发 `intent://`。如果想做到「点 https 链接自动调起 Roam(无需中转页)」:

1. 部署 `.well-known/assetlinks.json` 到根目录(GitHub Pages 自动支持)
2. `client/app/src/main/AndroidManifest.xml` 加 intent-filter:
   ```xml
   <intent-filter android:autoVerify="true">
     <action android:name="android.intent.action.VIEW" />
     <category android:name="android.intent.category.DEFAULT" />
     <category android:name="android.intent.category.BROWSABLE" />
     <data android:scheme="https" android:host="<用户名>.github.io" android:pathPrefix="/roam/" />
   </intent-filter>
   ```
3. Roam 签名 fingerprint 写到 assetlinks.json

效果:Android 系统验证通过后,微信内点这个 https 链接也会**直接**跳 Roam(绕过 WebView 内打开)。

但:
- 微信对 verified App Links 的处理不稳定(经常仍在 WebView 内打开)
- 国内大厂普遍用 landing page + 主动跳转方案
- App Links 需要 release 签名稳定

**结论**:MVP 用 landing page 就够,App Links 等 Phase 3 用户量上来再做。
