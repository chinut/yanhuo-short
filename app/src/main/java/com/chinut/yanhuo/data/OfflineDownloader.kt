package com.chinut.yanhuo.data

import com.chinut.yanhuo.YanhuoApp
import com.chinut.yanhuo.core.Diag
import com.chinut.yanhuo.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 离线下载 —— 把一集做成**自包含的本地 HLS 目录**。
 *
 * # 为什么不能"把 m3u8 和分片直接存下来"就完事
 *
 * 实测这个源的 m3u8 是这样的：
 *
 * ```
 * #EXT-X-KEY:METHOD=AES-128,URI="https://api-gw.wtzw.com/.../token-deal?asset_id=...",IV=0x...
 * #EXTINF:2.000000,
 * 2ad0828e..._0.ts        ← 分片是**相对路径**
 * ```
 *
 * 两个坑：
 *   1. **AES-128 加密**，密钥在 `api-gw.wtzw.com`。断网后这个请求发不出去，
 *      播放器拿不到密钥 → 分片就算全下好了也解不开，**离线直接播不了**；
 *   2. 分片是相对路径，播放器会去 m3u8 所在位置找 —— 所以**必须整目录一起存**。
 *
 * 所以下载流程是"**取密钥 + 下分片 + 改写 m3u8**"：
 *
 * ```
 * filesDir/offline/qimao_4576/1/
 *   ├── index.m3u8     ← 重写过：#EXT-X-KEY 指向本地 key.bin，分片保持相对名
 *   ├── key.bin        ← 16 字节裸密钥（实测不绑 Referer/UA，可以直接用）
 *   └── xxx_0.ts ...   ← 分片
 * ```
 *
 * 实测确认的三点（都在 tools/probe_hls_key.py 里验证过）：
 *   · 密钥响应就是 **16 字节裸内容**，不是 JSON 包装；
 *   · 密钥请求**不绑 Referer**，也不绑 UA 特征；
 *   · 分片可以在不知道密钥的情况下下载（加密在内容层，不影响传输）。
 *
 * # 为什么不用系统的 DownloadManager
 *
 * DownloadManager 只能"存一个 URL 到一个文件"。这里要的是**多文件 + 改写清单 +
 * 存密钥**，它做不了。而且它没法把下载状态回传给界面做进度。
 * 所以自己写，用 OkHttp（项目已有依赖）。
 */
object OfflineDownloader {

    private const val TAG = "Offline"

    /** 离线文件的根目录。放 filesDir 下，卸载即清，不用申请存储权限。 */
    private fun root(): File = File(YanhuoApp.ctx().filesDir, "offline").apply { mkdirs() }

    /** 一部剧的目录名：源 id + 剧 id，避免不同源同 id 撞车。 */
    private fun dramaDir(drama: Drama): File =
        File(root(), "${drama.sourceId}_${drama.targetId}")

    /** 一集的目录：按集号。 */
    fun episodeDir(drama: Drama, episodeIndex: Int): File =
        File(dramaDir(drama), episodeIndex.toString())

    /** 本地 m3u8 路径（播放器直接吃这个文件）。 */
    fun localPlaylist(drama: Drama, episodeIndex: Int): File =
        File(episodeDir(drama, episodeIndex), "index.m3u8")

    fun isDownloaded(drama: Drama, episodeIndex: Int): Boolean =
        localPlaylist(drama, episodeIndex).let { it.isFile && it.length() > 0 }

    /**
     * 下载一集。
     *
     * @param onProgress 回调 (已完成分片数, 总片数)。调用方负责切回主线程更新界面。
     * @return 成功返回本地 m3u8 的绝对路径。
     */
    suspend fun downloadEpisode(
        drama: Drama,
        episode: Episode,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<String> = withContext(Dispatchers.IO) {
        val dir = episodeDir(drama, episode.index)
        try {
            dir.mkdirs()

            // ---------- 1. 取远端 m3u8 ----------
            val playlist = Http.getOrThrow(episode.url, ua = Http.UA_MOBILE)
            val lines = playlist.lines()

            // ---------- 2. 把密钥取下来存成本地文件 ----------
            //
            // 这一步是"离线可用"的关键：不改写的话，本地 m3u8 里仍然指向
            // api-gw.wtzw.com，断网时播放器会卡在取密钥上，表现是"一直转圈"。
            val keyLine = lines.firstOrNull { it.startsWith("#EXT-X-KEY") }
            var rewrittenKeyLine: String? = null
            if (keyLine != null) {
                val uri = Regex("""URI="([^"]+)"""").find(keyLine)?.groupValues?.get(1)
                if (!uri.isNullOrBlank()) {
                    val keyBytes = fetchBytes(uri)
                    if (keyBytes.size != 16) {
                        // 不是 16 字节就不要硬写 —— 写进去播放器会解密失败，
                        // 表现是花屏/黑屏，比"明确失败"更难排查
                        throw IOException("密钥长度异常：${keyBytes.size} 字节（期望 16）")
                    }
                    File(dir, KEY_NAME).writeBytes(keyBytes)
                    // 换成本地相对文件名，并把 IV 原样保留（IV 是明文，不用改）
                    val iv = Regex("""IV=(0x[0-9a-fA-F]+)""").find(keyLine)
                        ?.groupValues?.get(1)
                    rewrittenKeyLine = buildString {
                        append("#EXT-X-KEY:METHOD=AES-128,URI=\"$KEY_NAME\"")
                        if (iv != null) append(",IV=$iv")
                    }
                    Diag.info(TAG, "第 ${episode.index} 集密钥已本地化（16 字节）")
                }
            }

            // ---------- 3. 下分片 ----------
            val segs = lines.filter { it.isNotBlank() && !it.startsWith("#") }
            if (segs.isEmpty()) throw IOException("播放列表里没有分片")
            val base = episode.url.substringBeforeLast('/') + "/"

            var done = 0
            for (name in segs) {
                val out = File(dir, name)
                // 断点续传：已经下过且非空的跳过（重试时不用从头来）
                if (out.isFile && out.length() > 0) {
                    done++
                    onProgress(done, segs.size)
                    continue
                }
                out.writeBytes(fetchBytes(base + name))
                done++
                onProgress(done, segs.size)
            }

            // ---------- 4. 写改写后的 m3u8 ----------
            //
            // 分片名保持**相对**，并且和 m3u8 同目录 —— 播放器按 m3u8 位置解析，
            // 这样整个目录可以整体搬走/删除，不依赖任何绝对路径。
            val outLines = lines.map { line ->
                if (line === keyLine && rewrittenKeyLine != null) rewrittenKeyLine else line
            }
            val playlistFile = File(dir, "index.m3u8")
            playlistFile.writeText(outLines.joinToString("\n"))

            val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            Diag.info(
                TAG,
                "第 ${episode.index} 集下载完成：${segs.size} 片，" +
                    "${bytes / 1024} KB → ${dir.name}/",
            )
            Result.success(playlistFile.absolutePath)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Diag.error(TAG, "第 ${episode.index} 集下载失败", t)
            // 不清理半成品：分片留着，下次重试能跳过已下好的（断点续传）。
            // 只有 m3u8 不存在（最后一步没跑到），所以不会被误判成"已下载"。
            Result.failure(t)
        }
    }

    /** 取二进制内容。HLS 的密钥和分片都是二进制，不能用 [Http.get]（那个返回 String）。 */
    private fun fetchBytes(url: String): ByteArray {
        val req = okhttp3.Request.Builder()
            .url(url)
            // 分片 CDN 对 UA 不敏感，但带上手机 UA 更稳（和播放时一致）
            .header("User-Agent", Http.UA_MOBILE)
            .build()
        Http.ok.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.bytes() ?: throw IOException("响应体为空")
        }
    }

    /** 删掉一集。 */
    fun deleteEpisode(drama: Drama, episodeIndex: Int): Boolean =
        episodeDir(drama, episodeIndex).deleteRecursively()

    /** 删掉一部剧的全部离线内容。 */
    fun deleteDrama(drama: Drama): Boolean = dramaDir(drama).deleteRecursively()

    /** 清空所有离线内容。 */
    fun clearAll(): Boolean = root().deleteRecursively().also { root() }

    /** 一集占用的字节数。 */
    fun episodeBytes(drama: Drama, episodeIndex: Int): Long =
        episodeDir(drama, episodeIndex).walkTopDown()
            .filter { it.isFile }.sumOf { it.length() }

    /** 离线内容总占用。 */
    fun totalBytes(): Long =
        root().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private const val KEY_NAME = "key.bin"
}
