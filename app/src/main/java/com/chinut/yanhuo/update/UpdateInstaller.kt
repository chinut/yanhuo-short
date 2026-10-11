package com.chinut.yanhuo.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.chinut.yanhuo.core.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 下载更新包并唤起系统安装。
 *
 * # 为什么这里用【自己写的 HttpURLConnection】而不是 OkHttp 或 DownloadManager
 *
 *   · **不能用内部的 OkHttp 单例**：它带 `network_security_config` 与自签 CA 信任，
 *     更新包可能来自 GitHub/Gitee（公开证书），走那套配置没必要且会受站点策略影响；
 *   · **不用 DownloadManager**：它把"下载到哪"交给系统，
 *     而且**唤不起安装界面**（要自己监听广播，还得处理各种 ROM 的差异）。
 *     自己下到 filesDir 再用 FileProvider 交给系统安装器，路径完全可控。
 *
 * # 为什么下到 filesDir 而不是外部存储
 *
 * filesDir 是应用私有目录，**不需要任何存储权限**。FileProvider 可以把它
 * 以 content:// 的形式授权给系统安装器 —— 这样从 Android 7 到 16 都不用
 * 申请 `REQUEST_INSTALL_PACKAGES` 之外的权限（那个是安装时系统自己弹的）。
 */
object UpdateInstaller {

    private const val TAG = "Update"
    private const val APK_NAME = "update.apk"

    /** 更新包落在私有目录，装完即删。 */
    fun apkFile(ctx: Context): File = File(ctx.filesDir, APK_NAME)

    /**
     * 下载更新包到私有目录。
     *
     * @param onProgress 0f..1f（总长度拿不到时回传 -1f，界面显示"下载中"）
     * @return 成功返回 APK 文件
     */
    suspend fun download(
        ctx: Context,
        urls: List<String>,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        val out = apkFile(ctx)
        var lastErr: Throwable? = null

        // 依次试每个地址 —— manifest 里同时给了 GitHub / Gitee / 自己站点，
        // 国内网络下 GitHub 常常超时，直接换下一个比让用户干等好
        for ((idx, u) in urls.withIndex()) {
            try {
                Diag.info(TAG, "开始下载更新包（第 ${idx + 1}/${urls.size} 个源）")
                fetchTo(u, out, onProgress)
                // 体积太小几乎肯定不是 APK（多半是错误页/被劫持的 HTML）
                if (out.length() < 1024 * 512) {
                    throw IOException("下载内容过小（${out.length()} 字节），可能不是安装包")
                }
                Diag.info(TAG, "更新包下载完成：${out.length() / 1024} KB")
                return@withContext Result.success(out)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                lastErr = t
                Diag.warn(TAG, "第 ${idx + 1} 个源失败：${t.message?.take(60)}，换下一个")
                runCatching { out.delete() }
            }
        }
        Result.failure(lastErr ?: IOException("没有可用的下载地址"))
    }

    private fun fetchTo(url: String, out: File, onProgress: (Float) -> Unit) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                // GitHub Release 会 302 到 objects.githubusercontent.com，
                // 必须允许重定向，否则拿到的是 302 响应体（HTML）
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) YanhuoUpdater")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        onProgress(if (total > 0) read.toFloat() / total else -1f)
                    }
                }
            }
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 唤起系统安装界面。
     *
     * 从 Android 8 起需要 `REQUEST_INSTALL_PACKAGES` + 用户在系统里允许
     * "安装未知应用"。**Android 16 收紧了权限上限**，如果系统拒绝，
     * 这里会抛 SecurityException —— 捕获后引导用户去设置页手动开启，
     * 而不是让界面毫无反应。
     */
    fun install(ctx: Context): Result<Unit> = runCatching {
        val f = apkFile(ctx)
        if (!f.isFile || f.length() <= 0) throw IOException("安装包不存在，请重新下载")

        val uri: Uri = FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.fileprovider",
            f,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }.onFailure {
        Diag.error(TAG, "唤起安装失败", it)
    }

    /**
     * 是否有"安装未知应用"的权限。
     *
     * Android 8+ 才有这个概念；低版本直接 true（那时安装不需要这个开关）。
     */
    fun canInstall(ctx: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * 跳到系统的"安装未知应用"设置页。
     *
     * 拿不到这个页面的 ROM 上退回应用详情页 —— 至少能让用户手动找到开关。
     */
    fun openInstallSettings(ctx: Context) {
        val pkgUri = Uri.parse("package:${ctx.packageName}")
        val intents = listOf(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
        )
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return
        }
        Diag.warn(TAG, "无法打开安装设置页（该 ROM 不支持）")
    }

    /** 清掉下载残留（用户取消、安装完成后）。 */
    fun clear(ctx: Context) {
        runCatching { apkFile(ctx).delete() }
        // 顺带清一下系统下载缓存目录里可能残留的同名文件（有则删，无则忽略）
        runCatching {
            val ext = File(
                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                APK_NAME,
            )
            if (ext.isFile) ext.delete()
        }
    }
}
