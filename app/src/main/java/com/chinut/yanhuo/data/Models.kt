package com.chinut.yanhuo.data

/**
 * 一部短剧（列表 / 详情共用的统一模型）。
 *
 * 界面（首页、搜索、收藏、订阅、播放器）只认这一个类型，所以换源时界面零改动。
 * 源的差异收在 [sourceId] + [targetId] 两个字段里。
 */
data class Drama(
    /** 数据源标识，见 [Source.id]（当前是 `qimao`）。 */
    val sourceId: String,
    /** 在该源里回源取详情用的 id（七猫是剧集数字 id）。 */
    val targetId: String,
    val title: String,
    val cover: String = "",
    /**
     * 总集数。
     *
     * **这个值是需求里明确要显示在首页卡片上的**（"主页视频展示界面要注明集数"）。
     * 列表页拿不到时是 0，进详情页后会被补齐。
     */
    val episodeCount: Int = 0,
    /** 题材标签，例如「战神」。 */
    val tags: List<String> = emptyList(),
    /** 热度文案，例如「209万热度」。 */
    val hot: String = "",
    /**
     * 热度数值（用于排序做榜单）。
     *
     * 为什么除文案外还要一个数值：`hot` 形如「5525万热度」，直接按字符串排序是错的
     * （"9万" 会排在 "1000万" 前面）。所以源侧解析时就把"多少万"折算成数字存下来
     * （见 [parseHot]），榜单直接按它排。拿不到时为 0。
     */
    val hotScore: Long = 0L,
    /** 简介（详情页才有）。 */
    val intro: String = "",
    /**
     * 榜单位置（1 起）。0 表示不在榜上。
     *
     * 首页「热剧榜」用名次角标把它和普通列表区分开。
     */
    val rank: Int = 0,
) {
    /**
     * 本地唯一键。
     *
     * 用 `sourceId:targetId` 而不是单独 targetId —— 即使以后只有一个源，
     * 带上源前缀也能让"换源后旧收藏不会串到新源的同名 id 上"。
     */
    val key: String get() = "$sourceId:$targetId"

    val hasCover: Boolean get() = cover.startsWith("http")

    /** 卡片上显示的集数文案。列表页没集数时退回"连载中"。 */
    val episodeLabel: String
        get() = if (episodeCount > 0) "全 $episodeCount 集" else "连载中"
}

/**
 * 把「5525万热度 / 6721万 / 9.5万」这类文案折算成可比较的数值。
 *
 * 为什么要折算：直接按字符串排，"9万" 会排在 "1000万" 前面（字典序）。
 * 榜单必须按数值排才对。
 *
 * 单位按中文习惯：万 = 1e4，亿 = 1e8。拿不到数字时返回 0。
 */
fun parseHot(text: String): Long {
    if (text.isBlank()) return 0L
    val m = Regex("""(\d+(?:\.\d+)?)""").find(text) ?: return 0L
    val n = m.groupValues[1].toDoubleOrNull() ?: return 0L
    val unit = when {
        text.contains('亿') -> 100_000_000.0
        text.contains('万') -> 10_000.0
        else -> 1.0
    }
    return (n * unit).toLong()
}

/**
 * 一集。
 *
 * [url] 是详情接口**一次性带回来的全集直链**，所以点播时通常不需要再联网。
 * 地址带时效，所以：
 *   · 详情响应只在内存里缓存（进程重启后重新取）
 *   · **不落盘**，避免存下一堆过期地址
 */
data class Episode(
    /** 在源里的集标识（七猫是 `video_id`）。 */
    val targetId: String,
    /** 显示名，例如「第 12 集」。 */
    val name: String,
    /** 集号，从 1 开始 —— 自动联播靠它算下一集。 */
    val index: Int,
    /** 直链。空表示这一集这个源给不出地址。 */
    val url: String = "",
    /**
     * 这一集是否需要会员。
     *
     * 七猫是全集免费，所以正常情况下恒为 false。保留这个字段是为了
     * 以后接入有付费墙的源时，界面能照实标出来（而不是假装都能看）。
     */
    val locked: Boolean = false,
) {
    val playable: Boolean get() = url.isNotBlank()
}

/**
 * 一部剧的全部分集。
 */
data class DramaDetail(
    val drama: Drama,
    val episodes: List<Episode>,
    /** 该源明确告诉我们的免费集数；0 表示源没提供这个信息。 */
    val freeCount: Int = 0,
) {
    val total: Int get() = episodes.size
}

/**
 * 播放地址解析结果。
 */
data class ResolvedStream(
    val url: String,
    /** 地址来自哪个源（界面会标出来，让用户知道内容出处）。 */
    val source: Source,
)
