package io.github.kid141252010.fantastic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.ui.player.MiniPlayer
import io.github.kid141252010.fantastic.ui.player.NowPlaying
import io.github.kid141252010.fantastic.ui.search.SearchScreen
import io.github.kid141252010.fantastic.ui.settings.SettingsScreen

enum class Tab(val label: String, val icon: ImageVector) {
    Home("主页", Icons.Rounded.Home),
    New("新发现", Icons.Rounded.GridView),
    Radio("广播", Icons.Rounded.Radio),
    Library("资料库", Icons.Rounded.LibraryMusic),
    Search("搜索", Icons.Rounded.Search),
}

@Composable
fun AppShell(app: FantasticApp) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val player by app.player.state.collectAsStateWithLifecycle()
    // 提前建好歌词控制器：切歌时就预取歌词，打开歌词时不用等
    remember { app.lyrics }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    if (player.mediaId != null) {
                        MiniPlayer(player, app.player, onOpen = { nowPlaying = true })
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f)) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = Color.Transparent,
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    Tab.Search -> SearchScreen(app)
                    else -> Placeholder(tab, onSettings = { settings = true })
                }
            }
        }

        AnimatedVisibility(
            visible = nowPlaying && player.mediaId != null,
            enter = slideInVertically(tween(380)) { it },
            exit = slideOutVertically(tween(300)) { it },
        ) {
            NowPlaying(player, app.player, app.lyrics, onClose = { nowPlaying = false })
        }
        if (nowPlaying) BackHandler { nowPlaying = false }

        if (settings) {
            SettingsScreen(app.prefs, onClose = { settings = false })
            BackHandler { settings = false }
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
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
