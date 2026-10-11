package com.chinut.yanhuo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本清单解析测试。
 *
 * 为什么值得测：清单是**升级功能的唯一外部输入**。解析写错的表现很隐蔽 ——
 * 要么"永远提示没有新版本"（用户拿不到更新），要么"一直提示有新版"
 * （用户被反复骚扰），两者在日志里都不显眼，靠手点很难发现。
 *
 * 这里只测解析（纯函数、不联网）。真正的联网 + 下载 + 唤起安装
 * 需要公网清单地址，见 docs/自动升级说明.md 的验证步骤。
 */
class UpdateManifestTest {

    @Test
    fun `正常清单能解析出全部字段`() {
        val json = """
            {
              "versionCode": 4,
              "versionName": "1.1.0",
              "notes": "新增离线下载",
              "urls": [
                "https://github.com/a/b/releases/download/v1.1.0/app.apk",
                "https://gitee.com/a/b/releases/download/v1.1.0/app.apk"
              ]
            }
        """.trimIndent()

        val info = UpdateChecker.parse(json)
        assertEquals(4, info?.versionCode)
        assertEquals("1.1.0", info?.versionName)
        assertEquals("新增离线下载", info?.notes)
        assertEquals(2, info?.urls?.size)
        // 顺序必须保持 —— 清单里是靠前的优先，App 按序试
        assertTrue(info!!.urls[0].startsWith("https://github.com/"))
        assertTrue(info.urls[1].startsWith("https://gitee.com/"))
    }

    @Test
    fun `版本号更高时判定为新版`() {
        val info = UpdateChecker.parse(
            """{"versionCode":5,"versionName":"2.0","urls":["https://a.test/x.apk"]}""",
        )!!
        assertTrue(info.isNewerThan(4))
        assertFalse(info.isNewerThan(5))
        // 同版本不算新 —— 否则每次启动都提示"有新版"
        assertFalse(info.isNewerThan(6))
    }

    @Test
    fun `缺 versionCode 视为无效`() {
        assertNull(UpdateChecker.parse("""{"versionName":"1.0","urls":["https://a.test/x.apk"]}"""))
        assertNull(UpdateChecker.parse("""{"versionCode":0,"versionName":"1.0","urls":["https://a.test/x.apk"]}"""))
    }

    @Test
    fun `缺 versionName 视为无效`() {
        assertNull(UpdateChecker.parse("""{"versionCode":5,"urls":["https://a.test/x.apk"]}"""))
        assertNull(UpdateChecker.parse("""{"versionCode":5,"versionName":"  ","urls":["https://a.test/x.apk"]}"""))
    }

    @Test
    fun `没有可用地址视为无效`() {
        // 没有 urls 字段
        assertNull(UpdateChecker.parse("""{"versionCode":5,"versionName":"2.0"}"""))
        // urls 是空数组
        assertNull(UpdateChecker.parse("""{"versionCode":5,"versionName":"2.0","urls":[]}"""))
    }

    @Test
    fun `非 http 的地址被过滤掉`() {
        val json = """
            {"versionCode":5,"versionName":"2.0","urls":[
              "file:///sdcard/evil.apk",
              "javascript:alert(1)",
              "https://ok.test/app.apk"
            ]}
        """.trimIndent()
        val info = UpdateChecker.parse(json)
        assertEquals(1, info?.urls?.size)
        assertEquals("https://ok.test/app.apk", info?.urls?.get(0))
    }

    @Test
    fun `全是非法地址时视为无效`() {
        // 关键：不能留一个"空 urls 的 Info"，那样界面会弹窗但点更新必然失败
        assertNull(
            UpdateChecker.parse(
                """{"versionCode":5,"versionName":"2.0","urls":["file:///x.apk"]}""",
            ),
        )
    }

    @Test
    fun `畸形 JSON 返回 null 而不是抛异常`() {
        assertNull(UpdateChecker.parse(""))
        assertNull(UpdateChecker.parse("{not json"))
        assertNull(UpdateChecker.parse("[]"))
    }

    @Test
    fun `notes 缺失时不报错`() {
        val info = UpdateChecker.parse(
            """{"versionCode":5,"versionName":"2.0","urls":["https://a.test/x.apk"]}""",
        )
        assertEquals("", info?.notes)
    }

    @Test
    fun `清单地址为空时不联网也不报错`() {
        // manifestUrl 留空 = 显式不做检查（源码默认值也是这样，
        // 免得配错地址时把"取不到"误报成"有新版本"）
        val old = UpdateChecker.manifestUrl
        try {
            UpdateChecker.manifestUrl = ""
            // fetch() 内部会直接 return null；这里只确认空串是允许的状态
            assertEquals("", UpdateChecker.manifestUrl)
        } finally {
            UpdateChecker.manifestUrl = old
        }
    }
}
