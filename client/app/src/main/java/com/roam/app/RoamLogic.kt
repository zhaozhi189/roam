package com.roam.app

import java.io.File

/**
 * 纯逻辑 utility(无 Android API 依赖,可 JVM 单元测试)
 *
 * 从 RoamBridge / ShareHelper / MainActivity 内提取的关键逻辑:
 *   - filename sanitize(防路径注入)
 *   - 路径越权检查(deleteVideo 安全 — 防 ../../ 删系统文件)
 *   - mime 推断(按后缀)
 *   - deep link URL 解析(roam://scene/<name>)
 *   - 入口 URL 拼接(MainActivity 决定 WebView 首页)
 *
 * 单元测试见 src/test/java/com/roam/app/RoamLogicTest.kt,
 * 跑 `./gradlew test`(无需设备,JVM 直跑)。
 */
object RoamLogic {

    private val SAFE_FILENAME_REGEX = Regex("[^a-zA-Z0-9._-]")

    /** 把任意字符串清洗成安全文件名,非法字符替换 _ */
    fun sanitizeFilename(name: String): String =
        name.replace(SAFE_FILENAME_REGEX, "_")

    /**
     * 检查 targetPath 是否在 rootDir 之内(canonical path 比较,防 ../../ 越权)
     * 用于 RoamBridge.deleteVideo 不让 JS 端删 internal storage 外的文件
     */
    fun isPathInside(targetPath: String, rootDir: String): Boolean {
        return try {
            val target = File(targetPath).canonicalPath
            val root = File(rootDir).canonicalPath
            target.startsWith(root + File.separator) || target == root
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 按文件名后缀推 MIME type
     * 用于 ShareHelper.shareVideo + RoamBridge.openVideoExternal
     */
    fun mimeFromFilename(path: String): String = when {
        path.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        path.endsWith(".webm", ignoreCase = true) -> "video/webm"
        path.endsWith(".png", ignoreCase = true) -> "image/png"
        path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        else -> "*/*"
    }

    /**
     * 解析 deep link `roam://scene/<name>` 拿到 scene name(不含 path 时返回 null)
     * 用于 MainActivity 接 deep link intent → loadUrl ?auto=<name>
     */
    fun parseDeepLinkScene(scheme: String?, host: String?, lastPathSegment: String?): String? {
        if (scheme != "roam") return null
        if (host != "scene") return null
        val name = lastPathSegment?.trim()
        if (name.isNullOrBlank()) return null
        // 同 sanitize 规则,允许 a-z 0-9 _ -
        if (!name.matches(Regex("^[a-zA-Z0-9_-]+$"))) return null
        return name
    }

    /**
     * 构造 WebView 入口 URL
     * 优先级:deep link scene > extras auto > 默认 apartment
     * "none" 跳过 ?auto=,加载纯主页(给开发调试用)
     */
    fun buildEntryUrl(
        baseUrl: String,
        deepLinkScene: String?,
        autoExtra: String?
    ): String {
        val auto = deepLinkScene ?: autoExtra ?: "apartment"
        return if (auto == "none") baseUrl else "$baseUrl?auto=$auto"
    }

    /**
     * 从 internal storage 录屏文件名解出 scene tag(无 tag 返回 null)
     * 文件名约定:roam-{tag}-{ts}.{ext},如 roam-apartment-20260516.mp4
     */
    fun extractSceneTagFromFilename(filename: String): String? {
        val m = Regex("^roam-([a-zA-Z0-9_-]+)-\\d{8,14}\\.(mp4|webm|png)$").matchEntire(filename)
        return m?.groupValues?.get(1)
    }
}
