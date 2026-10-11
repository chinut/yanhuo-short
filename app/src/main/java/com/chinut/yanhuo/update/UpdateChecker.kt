package com.chinut.yanhuo.update

import com.chinut.yanhuo.core.Diag
import com.chinut.yanhuo.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 自动升级 —— 检查远端版本。
 *
 * # 为什么不用 GitHub / Gitee 的官方 Release API
 *
 * 一开始想直接读 `https://api.github.com/repos/<owner>/<repo>/releases/latest`，
 * 但这样做有三个问题：
 *
 *   1. **总有一个源在国内打不开** —— GitHub API 在部分网络下不通，
 *      Gitee 又要求在请求里带 token 才能读私有仓库（公开仓库也要限流）；
 *   2. **两个源的响应格式不同**，要写两套解析，还要处理"哪个先成功"；
 *   3. **发布 APK 的体积走 GitHub/Gitee 的下载通道**，国内速度很不稳定，
 *      更新包下载失败率高。
 *
 * 所以改成：**读一份自己维护的小 JSON 清单**。
 * 清单里同时写上 GitHub 与 Gitee 的下载地址，App 按顺序试，
 * 哪个通用哪个 —— 这才是"通过 GitHub 和 Gitee 源升级"的稳妥做法。
 *
 * # 清单格式（发布时手工/脚本更新）
 *
 * ```json
 * {
 *   "versionCode": 4,
 *   "versionName": "1.0.3",
 *   "notes": "修了横屏裁切；新增离线下载",
 *   "urls": [
 *     "https://github.com/<owner>/<repo>/releases/download/v1.0.3/app-release.apk",
 *     "https://gitee.com/<owner>/<repo>/releases/download/v1.0.3/app-release.apk",
 *     "https://<自己的站点>/yanhuo_dl.php?app=short"
 *   ]
 * }
 * ```
 *
 * 自己站点那条放最后当兜底 —— 前两个都慢/不通时它最可靠。
 */
object UpdateChecker {

    private const val TAG = "Update"

    /**
     * 版本清单地址。
     *
     * # 为什么放在仓库的 raw 上，而不是自己站点
     *
     * 本站只有内网主机名（`web-server`），**没有公网域名** —— 手机在公网
     * 根本解析不到，所以清单必须放在能被公网访问的地方。GitHub raw 正好满足，
     * 而且和"源码在 GitHub、发布在 Gitee"这套分工一致：改清单只改仓库一个文件。
     *
     * # 为什么是 raw.githubusercontent 而不是 GitHub API
     *
     * API 有**限流**（未认证 60 次/小时/IP），而 raw 是纯静态文件、无限流，
     * 也不会因为限流失败被误判成"没有新版本"。
     *
     * # 国内可达性
     *
     * raw.githubusercontent.com 在国内**可能不稳**。所以清单里的 `urls`
     * 同时列了 Gitee 与自建站点两条下载地址，App 会按顺序试 ——
     * 清单本身取不到时只是"这次不检查更新"，不会误报。
     *
     * 源文件：仓库里的 `docs/update-manifest.json`（改完提交即生效）
     */
    var manifestUrl: String =
        "https://raw.githubusercontent.com/chinut/yanhuo-short/main/docs/update-manifest.json"

    /** 一次检查的结果。 */
    data class Info(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        /** 候选下载地址，按优先级排列。 */
        val urls: List<String>,
    ) {
        /** 是否比当前安装的版本新。 */
        fun isNewerThan(currentVersionCode: Int) = versionCode > currentVersionCode
    }

    /**
     * 拉取清单。
     *
     * @return 失败返回 null —— 调用方**不要**据此提示"已是最新"，
     *   网络失败与"确实没有新版本"是两件事，混在一起会骗用户。
     */
    suspend fun fetch(): Info? = withContext(Dispatchers.IO) {
        if (manifestUrl.isBlank()) {
            Diag.info(TAG, "未配置版本清单地址，跳过检查")
            return@withContext null
        }
        runCatching {
            // 加时间戳绕过 CDN/代理缓存 —— 否则发布新版后可能还读到旧的清单
            val url = manifestUrl + if (manifestUrl.contains('?')) "&" else "?" +
                "t=" + System.currentTimeMillis()
            val text = Http.getOrThrow(url, ua = Http.UA_MOBILE)
            parse(text)
        }.onFailure {
            Diag.warn(TAG, "检查更新失败：${it.javaClass.simpleName} ${it.message?.take(60)}")
        }.getOrNull()
    }

    /** 解析清单。抽出来是为了能单元测试（不联网）。 */
    fun parse(json: String): Info? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val code = o.optInt("versionCode", 0)
        val name = o.optString("versionName").trim()
        if (code <= 0 || name.isBlank()) return null

        val arr = o.optJSONArray("urls")
        val urls = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i).trim()
                    // 只收 http(s)，挡掉清单被写坏时把奇怪的 scheme 传给下载器
                    if (u.startsWith("http://") || u.startsWith("https://")) add(u)
                }
            }
        }
        if (urls.isEmpty()) return null

        return Info(
            versionCode = code,
            versionName = name,
            notes = o.optString("notes").trim(),
            urls = urls,
        )
    }
}
