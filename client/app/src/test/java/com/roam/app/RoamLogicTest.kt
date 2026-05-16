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
    fun entry_defaultApartment_whenBothNull() {
        // 默认开屏看公寓(wow factor)
        assertEquals("$BASE?auto=apartment",
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
}
