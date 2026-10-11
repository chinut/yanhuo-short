# 友商 App（各种果）逆向分析结论

分析对象：`G:\百度下载神器\各种果\base.apk`（30.6 MB，包名 `com.duanju.duanju_app`，
Flutter + Go 混合）。目的只有一个 —— **它有没有比红果官网更好的源**。

分析脚本：`tools/extract_duanju.py`、`tools/extract_duanju_cn.py`、
`tools/extract_hongguo_api.py`（全部只读，只抽字符串）。

---

## 一、它确实是个成熟的聚合器

Java 层只有 987 个文件且**没有任何接口代码**（全是 Flutter 壳 + 混淆），
真正的数据层在 `lib/arm64-v8a/libduanju_core.so`（14.2 MB，Go 编译产物），
而且 **Go 的字符串表是明文的**，所以 `.go` 源文件名直接暴露了架构。

从二进制里抽出的**平台接入模块清单**（`provider_*.go`）：

```
provider_hongguo.go                  provider_hongguo_app.go
provider_hongguo_catalog.go          provider_hongguo_detail.go
provider_hongguo_playback.go         provider_hongguo_search.go
provider_hongguo_search_seasons.go   provider_hongguo_recommendations.go
provider_hongguo_sign.go             provider_hongguo_comment_sign.go
provider_hongguo_native_media.go     provider_hongguo_suggestions.go
provider_huangguo.go                 provider_huangguo_cards.go
provider_yeguo.go / _client.go       provider_huangju.go / _client.go
provider_huangdou.go / _detail / _vip
provider_duanju*.go（heguo / yaguo / maccms / api / client / dispatch / sources）
provider_dsd_catalog.go / _playback.go
provider_rankings*.go / _sort_details.go / _sort_metadata.go
```

界面里出现的中文平台名（从错误文案反推）：

> 红果、黄果、野果、帝果、猫果、芽果、饭果、观果、河果、星果、花果、
> 牛果、网果、发果、皮果、伍果、剧果、黄豆、芽果

**共 19 个平台。** 也就是说，用户说的"好多 X 果集成在一起"是真的。

---

## 二、它的红果源，和我做的**是同一个**

红果相关的字符串里，有一条最关键：

```
(?s)player_aaaa\s*=\s*(\{.*?\})\s*</script>
https://hongguoduanju.com/detail?series_id=
https://hongguoduanju.com/category/
https://hongguoduanju.com/player/
https://hongguoduanju.com/search/
https://hongguoduanju.com/incent_resource/suggestion
```

`player_aaaa` 是**苹果CMS**的播放器变量 —— 说明它**也在爬红果官网的 HTML**，
和本项目 `HongguoSource` 走的是完全一样的三级链路
（分类页 → 详情页 → 播放页 → `main_url`）。

它比我多做的是**第二条路**：`provider_hongguo_app.go` 走红果 **App 接口**，
需要字节跳动的签名头。从字符串里能读出完整请求形态：

| 类别 | 内容 |
|---|---|
| 签名头 | `X-Gorgon`、`X-Khronos`、`X-Argus`、`X-Ladon`、`X-SS-STUB`、`X-XS-From-Web` |
| 设备/会话 | `device_id`、`deviceId`、`device_name`、`device_type`、`oaid`、`session_id`、`req_scene`、`req_type` |
| App 信息 | `app_name`、`app_id`、`app-version`、`Sdk-Version`、`bundleId`、`oauth_id`、`client_id` |
| 内容字段 | `series_id`、`vid_list`、`vid_index`、`main_url`、`url_list`、`episode_cnt`、`total_episode` |
| 试看相关 | **`free_episodes`**、`isPreview`、**`/preview.m3u8`**、**`&jump=trysee`** |

### ⚠️ 结论：它同样受"只免费前 3 集"的限制

上面那一行最后四个字段是决定性的：

- `free_episodes` —— 它专门读这个字段，而**红果官网实测该值恒为 3**
  （`accessible_episode_cnt = 3`，本项目已实测确认）
- `/preview.m3u8` + `isPreview` —— 预览（试看）专用的播放地址
- `&jump=trysee` —— `trysee` 就是**"试看"**。非会员点付费集时，
  红果是跳到 App 里"试看/开通会员"，它把这个跳转也解析了出来

也就是说：**这个成熟商业聚合器也没绕开红果的付费墙**，
它只是比我多解析了"试看"的边界，并把会员引导跳转透传出去。

（App 接口还需要实现字节的 X-Gorgon/X-Argus 签名算法，
以及 `provider_hongguo_native_media.go` 里的媒体密钥解密 ——
那是 native 机器码，要逆向出等价实现工作量极大，且随 App 更新即失效。
本项目**不打算**走这条路：那不是"接入公开源"，而是绕过付费内容保护。）

---

## 三、它集成的其它平台，实测大多是死的

逐个探测结果（2026-10-09）：

| 平台 | 域名 | 实测 |
|---|---|---|
| 黄果 | `huangguo.video` | ❌ 连接被直接关闭 |
| 黄果 | `huangguoai.com` | ⚠️ 活的，但整站 JS 混淆（`_gf1f6` 之类），要扒它的私有接口 |
| 黄果 | `api.huangju.net` / `huangju.net` | ❌ 404 |
| 野果 | `ygdj7.com` | ⚠️ 活的，页面自带 Base64 混淆 |
| 野果 | `dsd.com.se` | ⚠️ 活的，需自己解析站型 |
| 芽果 | `zzzznnn.lkkwip.cn` | ❌ 404 |
| 帝果 | `app.whjzjx.cn` | ❌ 503 |
| 帝果 | `ptt.red` | ⚠️ 活的（繁体站） |
| 猫果 | `duanju2.com` / `duanju55.com` | ⚠️ 活的，站点结构未知 |
| 饭果 | `xzyx168.com` | ❌ 连接被直接关闭 |
| 观果 | `kuaikaw.cn` | ⚠️ 活的（河马剧场） |
| 星果 | `api.drama.9ddm.com` | ❌ 404（根路径只是个 nginx 欢迎页） |
| 果 | `www.zywest263.com` | ⚠️ 活的，返回 gzip 流 |
| 果 | `djapi.999888456.xyz` | ❌ 返回**它自己的加密格式**：<br>`v2.api_<64位hex>.<base64>`（AES 风格），第三方解不开 |

**能当备用源的候选**：黄果（huangguoai.com）、野果（ygdj7.com / dsd.com.se）、
猫果、观果 —— 但每一个都要单独写适配器去解析它自己的站点结构
（有的还带 JS/Base64 混淆），**维护成本高、随时会挂**。

对比之下，本项目的七猫源（`xiaoqi.icofun.cn`）：
纯 JSON、无需混淆解析、**一次返回全集直链**、带网微剧备案号。
仍然是性价比最高的"看全集"方案。

---

## 四、对本项目的结论

**不换源。** 理由：

1. 红果源我们已经用了**和它一样**的那一条路（官网抓取），覆盖面相同；
2. 它的红果 App 接口同样只能免费看 3 集，没有额外收益；
3. 其它 19 个平台里，可用的那几个都要重度定制解析，且稳定性远不如现有两源；
4. 要突破 3 集限制需要逆向字节的 native 签名 + 密钥交换 ——
   这是绕过付费内容保护，不做。

**保持现状**：红果（主）+ 七猫（备用，全集直链）。

如果以后要提高"付费集回退"的命中率，最小代价的方向是
**再找一个和红果库重叠度高、且公开 JSON 接口的源** ——
只需要新增一个 `Source` 实现（约 200 行），界面零改动。
本次分析没有找到这样的源。
