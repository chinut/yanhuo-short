package com.chinut.yanhuo.data

/**
 * 首页分栏。
 *
 * 需求是"主页视频展示"要能一直往下看，同时"对热剧进行显示"。
 * 所以首页由两部分组成：
 *   1. **热剧榜** —— 一个有限的名次榜（[RANK]），横滑展示，卡片带名次角标
 *   2. **推荐流** —— 按分栏无限翻页的瀑布流（[RECOMMEND]）
 */
enum class HomeFeed(val label: String) {
    /** 热剧榜：条目带 [Drama.rank]。 */
    RANK("热剧榜"),

    /** 推荐流：一直往下翻。 */
    RECOMMEND("推荐"),
}

/**
 * 内容源。
 *
 * # 为什么留这一层抽象
 *
 * 当前只有**一个**实现（[QimaoSource]），看起来这层是多余的。留着它的理由很具体：
 *
 *   · 这个项目**已经因为换源改过一次主源了**（红果 → 七猫），当时界面一行没动，
 *     全靠这层接口兜住；
 *   · 要加源时只需要写一个新的实现类，界面/播放器/收藏/订阅全部无需修改；
 *   · 以后想把源做成外部配置（用户自己填接口地址）也必须先有这层。
 *
 * 所以这不是过度设计，而是一个**已经付过一次红利的接缝**。
 *
 * # 缓存约定
 *
 * [cached] **不允许联网** —— 它的职责是让界面第一帧就有东西（断网也成立）。
 * 联网的事全部交给 [page] / [search] / [detail]。
 */
interface Source {

    /** 源标识，同时用作 [Drama.sourceId]。 */
    val id: String

    /** 显示名，例如「七猫短剧」。 */
    val displayName: String

    /** 一句话说明这个源的能力与限制，显示在「我的 → 数据源」里。 */
    val note: String
        get() = ""

    /**
     * 立刻可用的内容（读本地库）。**不联网**。
     */
    suspend fun cached(): List<Drama>

    /**
     * 按分栏联网取内容。
     *
     * @param page 从 1 开始。返回空列表表示翻到底了。
     */
    suspend fun page(feed: HomeFeed, page: Int): List<Drama>

    /**
     * 通过源接口搜索。
     *
     * 需求明确要求"允许通过源接口搜索视频"，所以这里都是打源自己的搜索接口，
     * 不是拿本地库做模糊匹配。
     */
    suspend fun search(keyword: String, page: Int = 1): List<Drama>

    /**
     * 取一部剧的详情（**全部分集**）。
     *
     * 分集里会带上直链（如果源能一次给全），这样点播时不用再联网。
     */
    suspend fun detail(drama: Drama): DramaDetail?

    /**
     * 取某一集的播放直链。
     *
     * 返回 null 表示这一集**在这个源上拿不到地址**，调用方应如实提示用户。
     */
    suspend fun streamUrl(drama: Drama, episode: Episode): String?

    /**
     * 连通性自检，返回一句人话描述。
     *
     * 取源失败的用户可见表现都是"转圈/空白"，但病因可能是 DNS、证书、
     * 超时、接口改版、付费墙 —— 修法完全不同，所以要能自证。
     */
    suspend fun probe(): String
}
