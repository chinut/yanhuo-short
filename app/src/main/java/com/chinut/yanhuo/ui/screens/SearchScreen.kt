package com.chinut.yanhuo.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinut.yanhuo.core.Diag
import com.chinut.yanhuo.data.Drama
import com.chinut.yanhuo.data.SourceRegistry
import com.chinut.yanhuo.ui.components.DramaCard
import com.chinut.yanhuo.ui.components.EmptyState
import com.chinut.yanhuo.ui.components.TagChip
import com.chinut.yanhuo.ui.theme.Dim
import com.chinut.yanhuo.ui.theme.Ink
import com.chinut.yanhuo.ui.theme.Txt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 搜索页。
 *
 * # 走的是**源接口搜索**（需求明确要求）
 *
 * 打的是源自己的搜索接口，返回该源的实时结果 ——
 * 不是拿本地已经加载过的片库做模糊匹配（那只能搜到"首页恰好抓过的"，
 * 用户搜什么都可能空手而归）。
 *
 * # 翻页规则
 *
 * 一页 10 条。滚动到底自动加载下一页，直到接口返回空
 * （接口不返回总数，只能靠"空了就是到底"来判断）。
 */
@Composable
fun SearchScreen(
    /** 预填关键词。正常从首页点搜索图标进来是空串。 */
    initialKeyword: String = "",
    onBack: () -> Unit,
    onOpenDrama: (Drama) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var keyword by remember { mutableStateOf(initialKeyword) }
    /** 已提交搜索的词（和输入框里的 [keyword] 分开，避免边打字边发请求）。 */
    var submitted by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Drama>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }

    /** 搜索历史（本次会话内，用于"最近搜索"）。 */
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }

    /**
     * 执行一次搜索。
     *
     * 写成 suspend 而不是"内部 launch"：调用点有两处（提交按钮、滚到底翻页），
     * 后者本身就在 `collect` 里，如果内部再 launch，快速滚动会并发起好几次
     * 同一页的请求，结果是列表里出现重复项。
     */
    suspend fun runSearch(kw: String, reset: Boolean) {
        val q = kw.trim()
        if (q.isEmpty()) return
        if (reset) {
            submitted = q
            results = emptyList()
            ended = false
            page = 0
            message = ""
            loading = true
            recent = (listOf(q) + recent.filterNot { it == q }).take(10)
        } else {
            if (loadingMore || ended) return
            loadingMore = true
        }

        val src = SourceRegistry.primary()
        val next = page + 1
        val list = runCatching { src.search(q, next) }.getOrElse {
            Diag.warn("Search", "搜索「$q」异常：${it.javaClass.simpleName} ${it.message?.take(60)}")
            emptyList()
        }
        if (list.isEmpty()) {
            ended = true
            if (reset && results.isEmpty()) {
                message = "没有找到「$q」相关的短剧。\n换个关键词试试，或者确认网络是否正常。"
            }
        } else {
            page = next
            // 同一部剧可能出现在多页（接口去重没那么严），按 key 去重
            results = (results + list).distinctBy { it.key }
        }
        loading = false
        loadingMore = false
    }

    // 进页面自动聚焦输入框并弹键盘 —— 搜索页的核心动作就是打字
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
        // 带词进来（调试广播 / 从别的界面跳转）就直接搜，不用用户再按一次
        if (initialKeyword.isNotBlank()) {
            keyboard?.hide()
            runSearch(initialKeyword, reset = true)
        }
    }

    BackHandler(enabled = true) { onBack() }

    // 滚到底自动翻页
    LaunchedEffect(gridState, submitted) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                val total = gridState.layoutInfo.totalItemsCount
                if (submitted.isNotEmpty() && total > 0 && last >= total - 3) {
                    runSearch(submitted, reset = false)
                }
            }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Deep)
            .statusBarsPadding(),
    ) {
        // ==================== 搜索框 ====================
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Dim.PageH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(Dim.Radius))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = Ink.Text,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(Dim.Gap8))

            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(Dim.RadiusXl))
                    .background(Ink.Card)
                    .padding(horizontal = Dim.Gap12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Ink.TextFaint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Dim.Gap8))
                Box(Modifier.weight(1f)) {
                    if (keyword.isEmpty()) {
                        Text("搜索剧名，比如「总裁」「战神」", color = Ink.TextFaint, fontSize = Txt.Body)
                    }
                    BasicTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Ink.Text, fontSize = Txt.Body),
                        cursorBrush = SolidColor(Ink.Brand),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                keyboard?.hide()
                                scope.launch { runSearch(keyword, reset = true) }
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }
                if (keyword.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "清空",
                        tint = Ink.TextFaint,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable {
                                keyword = ""
                                results = emptyList()
                                submitted = ""
                                message = ""
                                ended = false
                                page = 0
                            },
                    )
                }
            }

            Spacer(Modifier.width(Dim.Gap8))

            Text(
                text = "搜索",
                color = Ink.Brand,
                fontSize = Txt.Body,
                modifier = Modifier.clickable {
                    keyboard?.hide()
                    scope.launch { runSearch(keyword, reset = true) }
                },
            )
        }

        // ==================== 结果 ====================
        when {
            loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Ink.Brand, strokeWidth = 2.dp)
            }

            message.isNotBlank() && results.isEmpty() -> EmptyState(
                text = message,
                hint = "也可以直接用剧名全称试试，匹配更准",
            )

            results.isEmpty() -> Column(Modifier.padding(Dim.PageH)) {
                Text("试试这些", color = Ink.TextDim, fontSize = Txt.Caption)
                Spacer(Modifier.height(Dim.Gap8))
                Row(horizontalArrangement = Arrangement.spacedBy(Dim.Gap8)) {
                    listOf("总裁", "战神", "重生", "甜宠").forEach { w ->
                        TagChip(w) {
                            keyword = w
                            keyboard?.hide()
                            scope.launch { runSearch(w, reset = true) }
                        }
                    }
                }

                if (recent.isNotEmpty()) {
                    Spacer(Modifier.height(Dim.Gap20))
                    Text("最近搜索", color = Ink.TextDim, fontSize = Txt.Caption)
                    Spacer(Modifier.height(Dim.Gap8))
                    Column {
                        recent.forEach { r ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        keyword = r
                                        keyboard?.hide()
                                        scope.launch { runSearch(r, reset = true) }
                                    }
                                    .padding(vertical = Dim.Gap8),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = Ink.TextFaint,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(Dim.Gap8))
                                Text(r, color = Ink.TextDim, fontSize = Txt.Body)
                            }
                        }
                    }
                }
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                contentPadding = PaddingValues(
                    start = Dim.PageH,
                    end = Dim.PageH,
                    bottom = Dim.BottomBar,
                ),
                horizontalArrangement = Arrangement.spacedBy(Dim.Gap12),
                verticalArrangement = Arrangement.spacedBy(Dim.Gap16),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hint") {
                    Text(
                        text = "「$submitted」找到 ${results.size} 部" +
                            if (ended) "（已全部加载）" else "",
                        color = Ink.TextFaint,
                        fontSize = Txt.Tiny,
                        modifier = Modifier.padding(bottom = Dim.Gap4),
                    )
                }

                items(results, key = { it.key }) { d ->
                    DramaCard(drama = d, onClick = { onOpenDrama(d) })
                }

                if (loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
                        Box(
                            Modifier.fillMaxWidth().padding(Dim.Gap16),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = Ink.TextFaint,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
