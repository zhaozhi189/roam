package com.roam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RoamLogic 纯逻辑单元测试 — JVM 直跑 `./gradlew test`,无需设备。
 *
 * 关键覆盖:
 *   - sanitizeFilename:防文件名注入
 *   - isPathInside:防 deleteVideo 越权(安全敏感)
 *   - mimeFromFilename:微信/系统图册 mime 推断(分享体验关键)
 *   - parseDeepLinkScene:roam://scene/<name> 路由(防接收方扫到恶意码)
 *   - buildEntryUrl:WebView 入口 URL 决策(默认场景 / deep link / extras 优先级)
 *   - extractSceneTagFromFilename:文件名 → 场景 tag(对回放体验有意义)
 */
class RoamLogicTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ===== sanitizeFilename =====

    @Test
    fun sanitize_normalFilename_unchanged() {
        assertEquals("roam-apartment-20260516.mp4",
            RoamLogic.sanitizeFilename("roam-apartment-20260516.mp4"))
    }

    @Test
    fun sanitize_chineseAndEmoji_replacedWithUnderscore() {
        // 关键属性:中文 + emoji 全部被去掉,只剩 ASCII。实际 _ 个数依赖 regex 对 surrogate pair 处理(实测 4 个 = emoji 算 1)
        val result = RoamLogic.sanitizeFilename("roam-🏠 公寓-20260516.mp4")
        // 严格:头尾正确 + 中间无非 ASCII
        assertTrue("应以 roam- 开头", result.startsWith("roam-"))
        assertTrue("应以 -20260516.mp4 结尾", result.endsWith("-20260516.mp4"))
        assertFalse("不应包含中文/emoji", result.any { it.code > 127 })
    }

    @Test
    fun sanitize_pathTraversal_dotsKeptSlashReplaced() {
        // ../../etc/passwd → . 和 - 合法,3 个 / 各替换 1 个 _
        // isPathInside 还会兜底,即使 sanitize 漏掉也安全
        assertEquals(".._.._etc_passwd",
            RoamLogic.sanitizeFilename("../../etc/passwd"))
    }

    @Test
    fun sanitize_specialChars_allReplaced() {
        assertEquals("a_b_c_d__e",
            RoamLogic.sanitizeFilename("a b c@d!#e"))
    }

    // ===== isPathInside(安全敏感)=====

    @Test
    fun isPathInside_targetUnderRoot_returnsTrue() {
        val root = tmp.newFolder("recordings")
        val target = File(root, "x.mp4").apply { createNewFile() }
        assertTrue(RoamLogic.isPathInside(target.absolutePath, root.absolutePath))
    }

    @Test
    fun isPathInside_targetEqualsRoot_returnsTrue() {
        val root = tmp.newFolder("recordings")
        assertTrue(RoamLogic.isPathInside(root.absolutePath, root.absolutePath))
    }

    @Test
    fun isPathInside_rejectsParentDirEscape() {
        val root = tmp.newFolder("recordings")
        val escape = File(root.parentFile, "evil.mp4").absolutePath
        assertFalse(RoamLogic.isPathInside(escape, root.absolutePath))
    }

    @Test
    fun isPathInside_rejectsDotDotTraversal() {
        val root = tmp.newFolder("recordings")
        val tricky = "${root.absolutePath}/../../../etc/passwd"
        assertFalse(RoamLogic.isPathInside(tricky, root.absolutePath))
    }

    @Test
    fun isPathInside_rejectsSiblingDirectory() {
        val parent = tmp.newFolder("parent")
        val root = File(parent, "recordings").apply { mkdirs() }
        val sibling = File(parent, "other/file.mp4")
        sibling.parentFile?.mkdirs()
        sibling.createNewFile()
        assertFalse(RoamLogic.isPathInside(sibling.absolutePath, root.absolutePath))
    }

    @Test
    fun isPathInside_recordingsPrefixCollision_rejectedNotAllowed() {
        // /tmp/recordings 不能被当成 /tmp/recordings-evil 的根
        val root = tmp.newFolder("recordings")
        val collide = File(root.parentFile, "recordings-evil/x.mp4")
        collide.parentFile?.mkdirs()
        collide.createNewFile()
        assertFalse(RoamLogic.isPathInside(collide.absolutePath, root.absolutePath))
    }

    // ===== mimeFromFilename =====

    @Test
    fun mime_mp4() = assertEquals("video/mp4", RoamLogic.mimeFromFilename("a.mp4"))

    @Test
    fun mime_webm() = assertEquals("video/webm", RoamLogic.mimeFromFilename("a.webm"))

    @Test
    fun mime_png() = assertEquals("image/png", RoamLogic.mimeFromFilename("a.png"))

    @Test
    fun mime_jpg_and_jpeg() {
        assertEquals("image/jpeg", RoamLogic.mimeFromFilename("a.jpg"))
        assertEquals("image/jpeg", RoamLogic.mimeFromFilename("a.jpeg"))
    }

    @Test
    fun mime_caseInsensitive() {
        assertEquals("video/mp4", RoamLogic.mimeFromFilename("a.MP4"))
        assertEquals("image/png", RoamLogic.mimeFromFilename("a.PnG"))
    }

    @Test
    fun mime_unknownExt_returnsWildcard() {
        assertEquals("*/*", RoamLogic.mimeFromFilename("a.xyz"))
        assertEquals("*/*", RoamLogic.mimeFromFilename("noext"))
        assertEquals("*/*", RoamLogic.mimeFromFilename(""))
    }

    // ===== parseDeepLinkScene =====

    @Test
    fun deepLink_validRoamScene_returnsName() {
        assertEquals("apartment",
            RoamLogic.parseDeepLinkScene("roam", "scene", "apartment"))
    }

    @Test
    fun deepLink_wrongScheme_returnsNull() {
        assertNull(RoamLogic.parseDeepLinkScene("http", "scene", "apartment"))
    }

    @Test
    fun deepLink_wrongHost_returnsNull() {
        assertNull(RoamLogic.parseDeepLinkScene("roam", "video", "apartment"))
    }

    @Test
    fun deepLink_emptyName_returnsNull() {
        assertNull(RoamLogic.parseDeepLinkScene("roam", "scene", ""))
        assertNull(RoamLogic.parseDeepLinkScene("roam", "scene", null))
        assertNull(RoamLogic.parseDeepLinkScene("roam", "scene", "   "))
    }

    @Test
    fun deepLink_invalidChars_rejectedNoInjection() {
        // 防恶意 deep link 注入 JS / path traversal
        assertNull(RoamLogic.parseDeepLinkScene("roam", "scene", "../../evil"))
        assertNull(RoamLogic.parseDeepLinkScene("roam", "scene", "apt;alert(1)"))
        assertNull(RoamLogic.parseDeepLinkScene("roam", "scene", "apt' or 1=1"))
        assertNull(RoamLogic.parseDeepLinkScene("roam", "scene", "apt name"))   // 空格不允许
        assertNull(RoamLogic.parseDeepLinkScene("roam", "scene", "公寓"))         // 非 ASCII
    }

    @Test
    fun deepLink_allowsHyphenUnderscoreDigits() {
        assertEquals("user-scene_42",
            RoamLogic.parseDeepLinkScene("roam", "scene", "user-scene_42"))
    }

    // ===== buildEntryUrl =====

    private val BASE = "https://appassets.androidplatform.net/assets/index.html"

    @Test
    fun entry_deepLinkWins_overExtras() {
        assertEquals("$BASE?auto=apartment",
            RoamLogic.buildEntryUrl(BASE, "apartment", "guitar"))
    }

    @Test
    fun entry_extrasIfNoDeepLink() {
        assertEquals("$BASE?auto=guitar",
            RoamLogic.buildEntryUrl(BASE, null, "guitar"))
    }

    @Test
    fun entry_noAuto_whenBothNull() {
        // M3-7:不传 ?auto=,JS 端读 localStorage 上次场景或 fallback 默认
        assertEquals(BASE,
            RoamLogic.buildEntryUrl(BASE, null, null))
    }

    @Test
    fun entry_noneBypassesAuto() {
        // --es auto none 跳过场景预加载(开发调试用)
        assertEquals(BASE,
            RoamLogic.buildEntryUrl(BASE, null, "none"))
    }

    // ===== extractSceneTagFromFilename =====

    @Test
    fun extractSceneTag_validMp4() {
        assertEquals("apartment",
            RoamLogic.extractSceneTagFromFilename("roam-apartment-20260516191234.mp4"))
    }

    @Test
    fun extractSceneTag_validPng() {
        assertEquals("lizard",
            RoamLogic.extractSceneTagFromFilename("roam-lizard-20260516.png"))
    }

    @Test
    fun extractSceneTag_validWebm() {
        assertEquals("cube",
            RoamLogic.extractSceneTagFromFilename("roam-cube-20260516191234.webm"))
    }

    @Test
    fun extractSceneTag_legacyNoTag_returnsNull() {
        // v0.7 之前的文件名格式 roam-{ts}.mp4 没 tag
        assertNull(RoamLogic.extractSceneTagFromFilename("roam-20260516191234.mp4"))
    }

    @Test
    fun extractSceneTag_unknownFormat_returnsNull() {
        assertNull(RoamLogic.extractSceneTagFromFilename("random.mp4"))
        assertNull(RoamLogic.extractSceneTagFromFilename("roam-apt-shortts.mp4"))
    }

    // ===== escapeJsonString + buildMediaItemJson =====

    @Test
    fun escapeJson_plain_unchanged() {
        assertEquals("abc 123", RoamLogic.escapeJsonString("abc 123"))
    }

    @Test
    fun escapeJson_doubleQuote() {
        assertEquals("""he said \"hi\"""", RoamLogic.escapeJsonString("""he said "hi""""))
    }

    @Test
    fun escapeJson_backslash() {
        assertEquals("""a\\b""", RoamLogic.escapeJsonString("""a\b"""))
    }

    @Test
    fun escapeJson_controlChars() {
        assertEquals("""line1\nline2\ttab\rret""",
            RoamLogic.escapeJsonString("line1\nline2\ttab\rret"))
    }

    @Test
    fun escapeJson_lowControlChar_unicodeEscaped() {
        // 0x01 控制字符应被 \\uXXXX escape
        val input = "a" + 0x01.toChar() + "b"
        assertEquals("a\\u0001b", RoamLogic.escapeJsonString(input))
    }

    @Test
    fun mediaItem_jsonValidWithSpecialPath() {
        // 防恶意/极端文件名破坏 JSON(SAF 选用户文件名可能有引号 / emoji / 控制符)
        val json = RoamLogic.buildMediaItemJson(
            absolutePath = """/tmp/with "quote" and \backslash.mp4""",
            name = "evil\"name.mp4",
            sizeBytes = 1234,
            mtimeMs = 9999999L
        )
        // 必须能被 JSONObject 解析(反向证明 escape 正确)
        val o = org.json.JSONObject(json)
        assertEquals("""/tmp/with "quote" and \backslash.mp4""", o.getString("path"))
        assertEquals("evil\"name.mp4", o.getString("name"))
        assertEquals(1234L, o.getLong("size"))
        assertEquals(9999999L, o.getLong("mtime"))
    }

    @Test
    fun mediaItem_normalFilename() {
        val json = RoamLogic.buildMediaItemJson(
            "/data/data/com.roam.app/files/recordings/roam-apartment-20260516.mp4",
            "roam-apartment-20260516.mp4",
            2_400_000L,
            1_700_000_000_000L
        )
        val o = org.json.JSONObject(json)
        assertEquals("roam-apartment-20260516.mp4", o.getString("name"))
        assertEquals(2_400_000L, o.getLong("size"))
    }

    // ===== safeScanDeepLink(扫码安全决策)=====

    @Test
    fun safeScan_validRoamScene_returnsCanonical() {
        assertEquals("roam://scene/apartment",
            RoamLogic.safeScanDeepLink("roam://scene/apartment"))
    }

    @Test
    fun safeScan_validWithQueryString_stripsAndReturns() {
        // 去掉 ?... 部分,只取 scene name
        assertEquals("roam://scene/apartment",
            RoamLogic.safeScanDeepLink("roam://scene/apartment?utm=qr"))
    }

    @Test
    fun safeScan_validWithFragment_stripsAndReturns() {
        assertEquals("roam://scene/apartment",
            RoamLogic.safeScanDeepLink("roam://scene/apartment#section"))
    }

    @Test
    fun safeScan_nullOrEmpty_returnsNull() {
        assertNull(RoamLogic.safeScanDeepLink(null))
        assertNull(RoamLogic.safeScanDeepLink(""))
        assertNull(RoamLogic.safeScanDeepLink("   "))
    }

    @Test
    fun safeScan_wrongScheme_returnsNull() {
        assertNull(RoamLogic.safeScanDeepLink("https://example.com/scene/apartment"))
        assertNull(RoamLogic.safeScanDeepLink("file:///etc/passwd"))
    }

    @Test
    fun safeScan_wrongHost_returnsNull() {
        // 防 roam://settings/reset / roam://exec/... 等恶意非 scene host
        assertNull(RoamLogic.safeScanDeepLink("roam://settings/reset"))
        assertNull(RoamLogic.safeScanDeepLink("roam://exec/rm"))
    }

    @Test
    fun safeScan_invalidSceneName_returnsNull() {
        // 防 JS / SQL / path traversal 注入
        assertNull(RoamLogic.safeScanDeepLink("roam://scene/../../evil"))
        assertNull(RoamLogic.safeScanDeepLink("roam://scene/apt';alert(1)"))
        assertNull(RoamLogic.safeScanDeepLink("roam://scene/公寓"))
        assertNull(RoamLogic.safeScanDeepLink("roam://scene/"))
    }

    @Test
    fun safeScan_arbitraryText_returnsNull() {
        // 扫到广告 / URL / 文本 → 不自动跳
        assertNull(RoamLogic.safeScanDeepLink("https://random-site.com"))
        assertNull(RoamLogic.safeScanDeepLink("just some text"))
        assertNull(RoamLogic.safeScanDeepLink("tel:10086"))
    }
}
