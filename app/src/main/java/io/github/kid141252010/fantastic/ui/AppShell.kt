package io.github.kid141252010.fantastic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.ui.player.MiniPlayer
import io.github.kid141252010.fantastic.ui.player.NowPlaying
import io.github.kid141252010.fantastic.ui.search.SearchScreen
import io.github.kid141252010.fantastic.ui.settings.SettingsScreen
import io.github.kid141252010.fantastic.ui.theme.TabAccent

enum class Tab(val label: String, val icon: ImageVector) {
    Home("主页", Icons.Rounded.Home),
    New("新发现", Icons.Rounded.GridView),
    Radio("广播", Icons.Rounded.Radio),
    Library("资料库", Icons.Rounded.LibraryMusic),
    Search("搜索", Icons.Rounded.Search),
}

/**
 * 外壳：页面铺满全屏（内容一直延伸到屏幕底部），底部悬浮玻璃迷你播放条 + 玻璃标签栏，
 * 玻璃取样页面内容做模糊。全屏播放页从迷你条展开，封面共享元素过渡。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppShell(app: FantasticApp) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val player by app.player.state.collectAsStateWithLifecycle()
    // 提前建好歌词控制器：切歌时就预取歌词，打开歌词时不用等
    remember { app.lyrics }
    val haze = remember { HazeState() }
    val hasTrack = player.mediaId != null

    val nav = WindowInsets.navigationBars.asPaddingValues()
    val barsHeight = Dimens.BarBottom + Dimens.TabBarHeight +
        (if (hasTrack) Dimens.BarGap + Dimens.MiniPlayerHeight else 0.dp) + Dimens.BarGap
    val barsPadding = PaddingValues(bottom = nav.calculateBottomPadding() + barsHeight)

    SharedTransitionLayout(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalSharedTransition provides this,
            LocalHaze provides haze,
            LocalBarsPadding provides barsPadding,
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().haze(haze).statusBarsPadding()) {
                    when (tab) {
                        Tab.Search -> SearchScreen(app)
                        else -> Placeholder(tab, onSettings = { settings = true })
                    }
                }

                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = Dimens.Page, end = Dimens.Page, bottom = Dimens.BarBottom),
                ) {
                    if (hasTrack) {
                        AnimatedVisibility(
                            visible = !nowPlaying,
                            enter = fadeIn(tween(Motion.Enter, easing = Motion.EmphasizedDecelerate)),
                            exit = fadeOut(tween(Motion.Exit, easing = Motion.EmphasizedAccelerate)),
                        ) {
                            Column {
                                MiniPlayer(player, app.player, visibility = this@AnimatedVisibility, onOpen = { nowPlaying = true })
                                Spacer(Modifier.height(Dimens.BarGap))
                            }
                        }
                        if (nowPlaying) Spacer(Modifier.height(Dimens.MiniPlayerHeight + Dimens.BarGap))
                    }
                    TabBar(tab, onSelect = { tab = it })
                }

                AnimatedVisibility(
                    visible = nowPlaying && hasTrack,
                    enter = nowPlayingEnter,
                    exit = nowPlayingExit,
                ) {
                    NowPlaying(player, app.player, app.lyrics, visibility = this, onClose = { nowPlaying = false })
                }
                if (nowPlaying) BackHandler { nowPlaying = false }

                if (settings) {
                    SettingsScreen(app.prefs, app.lyrics, onClose = { settings = false })
                    BackHandler { settings = false }
                }
            }
        }
    }
}

/** 悬浮玻璃标签栏：选中项用系统强调色（Material You 动态取色，录屏里是蓝色） */
@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Glass(CircleShape, Modifier.fillMaxWidth().height(Dimens.TabBarHeight)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { t ->
                val color = if (t == selected) TabAccent() else MaterialTheme.colorScheme.onSurface
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 32.dp),
                        ) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = color, modifier = Modifier.size(26.dp))
                    Text(t.label, color = color, fontSize = 10.sp, lineHeight = 12.sp)
                }
            }
        }
    }
}

/** 第一阶段还没做的页面：大标题 + 右上角设置入口。 */
@Composable
private fun Placeholder(tab: Tab, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            LargeTitle(tab.label)
            IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                Icon(Icons.Rounded.AccountCircle, contentDescription = "设置", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            "这一页还在做，先去「搜索」试试 QQ 音乐播放。",
            modifier = Modifier.padding(horizontal = Dimens.Page, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
