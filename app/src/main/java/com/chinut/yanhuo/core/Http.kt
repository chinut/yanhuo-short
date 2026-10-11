package com.chinut.yanhuo.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * HTTP 层。
 *
 * 全 App 只有**一个** OkHttpClient：多个 client 会各建一套线程池和连接池，
 * 在低端手机上纯属浪费内存。
 *
 * 超时为什么是 12s / 20s：
 * 短剧站的图片 CDN 和网页接口在弱网下确实慢，但超过 20 秒用户早就走了。
 * 这两个值是电视端实测下来"既能成功又不至于让用户干等"的折中。
 */
object Http {

    /**
     * 桌面 UA。
     *
     * 当前主源（七猫）用 [UA_MOBILE]，这个常量暂时没有调用点 ——
     * 留给以后接入"按 UA 分流"的网页源（桌面 UA 常见于服务端渲染站点）。
     */
    const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /** 手机 UA：七猫接口按手机 UA 返回更全的字段。 */
    const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var client: OkHttpClient? = null

    fun init(context: Context) {
        // context 目前只在未来要加磁盘缓存时用得上，先接收住，避免以后再改调用方
        @Suppress("UNUSED_EXPRESSION") context
    }

    val ok: OkHttpClient
        get() = client ?: synchronized(this) {
            client ?: OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(35, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .build()
                .also { client = it }
        }

    /**
     * GET 文本。
     *
     * 失败一律返回 null（网络异常、超时、4xx/5xx、解码失败都算）。
     * ⚠️ 这是刻意的：调用方要的是"有没有内容"，不是"为什么没有"。
     * 但排查阶段需要原因时，请用 [getOrThrow] 或看日志里打的异常类名。
     */
    fun get(url: String, ua: String = UA_DESKTOP, referer: String? = null): String? =
        runCatching { getOrThrow(url, ua, referer) }.getOrElse {
            Diag.warn("Http", "GET 失败 $url → ${it.javaClass.simpleName} ${it.message?.take(80)}")
            null
        }

    /** 同 [get]，但把异常抛出来。取源链路排查时用。 */
    fun getOrThrow(url: String, ua: String = UA_DESKTOP, referer: String? = null): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply { referer?.let { header("Referer", it) } }
            .build()
        return ok.newCall(req).execute().use { r: Response ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body?.string() ?: error("响应体为空")
        }
    }

    /**
     * 只探测响应头，不下载正文。
     *
     * 用途：确认某个播放地址**是不是真的能播**（而不是只看字符串非空）。
     * 只取前 1 字节，所以哪怕是个几百 MB 的文件也瞬间返回。
     */
    fun probe(url: String, referer: String? = null): ProbeResult =
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA_MOBILE)
                .header("Range", "bytes=0-0")
                .apply { referer?.let { header("Referer", it) } }
                .build()
            ok.newCall(req).execute().use { r ->
                ProbeResult(
                    ok = r.isSuccessful || r.code == 206,
                    code = r.code,
                    contentType = r.header("Content-Type").orEmpty(),
                )
            }
        }.getOrElse {
            ProbeResult(ok = false, code = -1, contentType = it.javaClass.simpleName)
        }

    /** 是否处于联网状态。 */
    fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    /** 是否在用蜂窝网络（用于"仅 WiFi 播放"这类开关）。 */
    fun onCellular(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }.getOrDefault(false)

    data class ProbeResult(val ok: Boolean, val code: Int, val contentType: String)
}
