package io.github.kid141252010.fantastic.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.Role
import io.github.kid141252010.fantastic.data.SongRelations
import io.github.kid141252010.fantastic.data.songRelations
import io.github.kid141252010.fantastic.data.resolveSongRelations
import io.github.kid141252010.fantastic.data.validCatalogId
import kotlinx.coroutines.ensureActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.MusicLibrary
import io.github.kid141252010.fantastic.data.SearchCatalog
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.librarySongHit
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import io.github.kid141252010.fantastic.playback.DownloadStatus
import io.github.kid141252010.fantastic.playback.mediaId
import io.github.kid141252010.fantastic.playback.validSongId
import io.github.kid141252010.fantastic.ui.theme.FontWeightRole
import io.github.kid141252010.fantastic.ui.theme.withUserFontWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** C3469t.h/b/d/f/g/a ordering, with local-library actions explicitly distinguished from Apple sync. */
@Composable
internal fun MediaActionContent(
    hit: CatalogHit,
    library: MusicLibrary,
    tracks: List<Track>,
    nowPlaying: Boolean = false,
    hasLyrics: Boolean = false,
    shareText: String? = catalogShareText(hit),
    onPlay: (() -> Unit)? = null,
    onView: (() -> Unit)? = null,
    onQuality: (() -> Unit)? = null,
    pronunciation: Boolean = false,
    onPronunciation: (() -> Unit)? = null,
    translation: Boolean = false,
    onTranslation: (() -> Unit)? = null,
    resolveTracks: (suspend () -> List<Track>)? = null,
    saveParentAlbum: (suspend () -> Unit)? = null,
    onOpenRelated: ((CatalogHit) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FantasticApp
    val downloads by app.downloads.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var resolving by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val playable = tracks.isNotEmpty() || resolveTracks != null
    fun runAction(action: suspend () -> Unit) {
        if (resolving) return
        resolving = true
        actionError = null
        scope.launch {
            try {
                action()
                onDismiss()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { actionError = error.message ?: "无法载入歌曲" }
            finally { resolving = false }
        }
    }
    fun withSongs(action: (List<Track>) -> Unit) = runAction {
        val songs = resolveTracks?.invoke() ?: tracks
        if (songs.isEmpty()) error("没有可用的歌曲")
        action(songs)
    }
    val snapshot by library.snapshot.collectAsStateWithLifecycle()
    val saved = snapshot.items.firstOrNull { it.key.source == hit.source && it.key.kind == hit.kind && it.key.id == hit.id }
    val media = hit.kind in setOf("song", "album", "playlist", "music-video")
    val favoriteEntity = media || hit.kind == "artist"
    var playlists by remember(hit.source, hit.kind, hit.id) { mutableStateOf(false) }
    var showCredits by remember(hit.source, hit.kind, hit.id) { mutableStateOf(false) }
    val storefront by app.prefs.storefrontState.collectAsStateWithLifecycle()
    val relationSeed = remember(hit, tracks, onOpenRelated != null) {
        if (onOpenRelated == null || hit.kind != "song") null else
            (hit.track ?: tracks.singleOrNull())?.takeIf { it.source == hit.source && it.id == hit.id }?.let { song ->
                if (song.source == Source.Apple && song.country.normalizedAppleCountry() == null && hit.country.normalizedAppleCountry() != null)
                    song.copy(country = hit.country.normalizedAppleCountry()) else song
            }
    }
    val relationServer = app.prefs.baseUrl
    val relationCountry = if (relationSeed?.source == Source.Apple)
        relationSeed.country.normalizedAppleCountry() ?: storefront.normalizedAppleCountry() else null
    val relationIdentity = remember(relationSeed, relationServer, relationCountry) { Any() }
    var relationActive by remember(relationIdentity) { mutableStateOf(true) }
    var relationResult by remember(relationIdentity) { mutableStateOf<SongRelations?>(null) }
    var relationIntent by remember(relationIdentity) { mutableStateOf<String?>(null) }
    var relationPending by remember(relationIdentity) { mutableStateOf<Any?>(null) }
    var relationError by remember(relationIdentity) { mutableStateOf<String?>(null) }
    var relationAttempt by remember(relationIdentity) { mutableStateOf(0) }
    val latestSeed by rememberUpdatedState(relationSeed)
    val latestRelated by rememberUpdatedState(onOpenRelated)
    val relations = relationResult ?: relationSeed?.songRelations(relationCountry) ?: SongRelations()
    val canQueryRelations = relationSeed?.let { it.id.isNotBlank() && it.id != "0" && validCatalogId(it.source, "song", it.id) } == true
    fun relationContextCurrent(): Boolean = relationActive && !playlists && !showCredits && latestSeed == relationSeed &&
        latestRelated != null && app.prefs.baseUrl == relationServer &&
        (relationSeed?.source != Source.Apple ||
            (relationSeed.country.normalizedAppleCountry() ?: app.prefs.storefront.normalizedAppleCountry()) == relationCountry)
    fun targetFor(result: SongRelations, intent: String): CatalogHit? = if (intent == "artist") result.artist else result.album
    fun openRelation(intent: String) {
        if (!relationContextCurrent()) return
        val target = targetFor(relations, intent)
        if (target != null) {
            relationPending = null
            latestRelated?.invoke(target)
        } else if (relationPending == null || relationIntent != intent) {
            relationIntent = intent
            if (relationResult == null && relationError == null && canQueryRelations) relationPending = Any()
        }
    }
    DisposableEffect(relationIdentity, playlists, showCredits) {
        relationActive = true
        onDispose { relationActive = false; relationPending = null }
    }
    LaunchedEffect(relationIdentity, relationPending, relationAttempt, playlists, showCredits) {
        val token = relationPending ?: return@LaunchedEffect
        val intent = relationIntent ?: return@LaunchedEffect
        val seed = relationSeed ?: return@LaunchedEffect
        if (!relationContextCurrent()) return@LaunchedEffect
        try {
            val result = app.resolveSongRelations(seed, relationCountry)
            ensureActive()
            if (!relationContextCurrent() || relationPending !== token || relationIntent != intent) return@LaunchedEffect
            relationResult = result
            relationPending = null
            targetFor(result, intent)?.let { latestRelated?.invoke(it) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            ensureActive()
            if (relationContextCurrent() && relationPending === token && relationIntent == intent) {
                relationPending = null
                relationError = failure.message ?: "读取失败"
            }
        }
    }
    val albumLink: (() -> Unit)? = if (relationSeed != null && (relations.album != null || canQueryRelations)) ({ openRelation("album") }) else null
    val artistLink: (() -> Unit)? = if (relationSeed != null && (relations.artist != null || canQueryRelations)) ({ openRelation("artist") }) else null
    if (playlists) {
        BackHandler { playlists = false }
        SheetAction("返回${hit.title}", onClick = { playlists = false })
        snapshot.playlists.forEach { playlist ->
            SheetAction(playlist.name, Icons.AutoMirrored.Outlined.PlaylistAdd, detail = "本地播放列表", onClick = {
                if (hit.kind == "song" || hit.kind == "music-video") library.addToPlaylist(playlist.id, hit)
                else tracks.forEach { track -> library.addToPlaylist(playlist.id, SearchCatalog.trackHit(track)) }
                onDismiss()
            })
        }
        return
    }
    val palette = actionSheetColors()
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(hit.artwork, 80.dp, 5.dp)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(hit.title, modifier = if (albumLink != null) Modifier.clickable(role = Role.Button, onClickLabel = "前往专辑", onClick = albumLink) else Modifier, color = palette.label,
                style = LocalTextStyle.current.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold).withUserFontWeight(FontWeightRole.Title),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hit.subtitle.isNotBlank()) Text(hit.subtitle, Modifier.padding(top = 4.dp).then(
                if (artistLink != null) Modifier.clickable(role = Role.Button, onClickLabel = "前往艺人", onClick = artistLink) else Modifier),
                color = palette.secondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (resolving) Text("正在加载歌曲…", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = palette.secondary)
    actionError?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
    when {
        relationPending != null -> Text("正在读取关系信息…", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = palette.secondary)
        relationError != null -> {
            Text("无法读取关系信息：$relationError", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = palette.secondary)
            TextButton(onClick = {
                if (relationContextCurrent() && relationIntent != null && relationPending == null) {
                    relationError = null; relationAttempt++; relationPending = Any()
                }
            }) { Text("重试", color = palette.label) }
        }
        relationResult != null && relationIntent != null && targetFor(relations, relationIntent!!) == null ->
            Text(if (relationIntent == "artist") "暂无可跳转的艺人信息" else "暂无可跳转的专辑信息",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = palette.secondary)
    }
    if (media) {
        if (saved != null && hit.kind != "song") SheetAction("置顶${catalogKindLabel(hit.kind)}", Icons.Outlined.PushPin,
            detail = "Fantastic 未提供目录置顶")
        if (saved == null || (hit.kind == "album" && resolveTracks != null)) SheetAction(
            if (saved == null) "添加到资料库" else "更新资料库专辑", Icons.Outlined.LibraryAdd,
            detail = "仅保存到本机，不同步 Apple 账号", onClick = if (resolving) null else ({
                when {
                    hit.kind == "album" && resolveTracks != null -> runAction { library.add(hit, resolveTracks.invoke()) }
                    hit.kind == "song" -> runAction {
                        library.add(app.librarySongHit(hit))
                        saveParentAlbum?.invoke()
                    }
                    else -> { library.add(hit); onDismiss() }
                }
            }))
        if (saved != null) {
            SheetAction("从资料库中删除", Icons.Outlined.Delete, detail = "仅移除本机项目", destructive = true,
                onClick = { library.remove(hit); onDismiss() })
        }
        val canAdd = snapshot.playlists.isNotEmpty() && (hit.kind in setOf("song", "music-video") || tracks.isNotEmpty())
        SheetAction("添加到播放列表…", Icons.AutoMirrored.Outlined.PlaylistAdd,
            detail = when {
                snapshot.playlists.isEmpty() -> "请先在资料库新建本地播放列表"
                hit.kind !in setOf("song", "music-video") -> "添加当前已载入的 ${tracks.size} 首歌曲"
                else -> "本地播放列表，不同步 Apple 账号"
            }, onClick = if (canAdd) ({ relationPending = null; playlists = true }) else null)
    }
    if (playable && hit.kind != "music-video") {
        val single = if (hit.kind == "song") tracks.singleOrNull() else null
        val download = single?.let { track -> downloads.firstOrNull {
            it.key == track.mediaId && (track.source != Source.Apple || track.country.normalizedAppleCountry() == null ||
                it.storefront == track.country.normalizedAppleCountry())
        } }
        when (download?.status) {
            DownloadStatus.Ready -> SheetAction("移除下载", Icons.Outlined.Delete, onClick = {
                app.downloads.remove(download.key); onDismiss()
            })
            DownloadStatus.Downloading, DownloadStatus.Queued -> SheetAction("取消下载", Icons.Outlined.Download, onClick = {
                app.downloads.cancel(download.key); onDismiss()
            })
            DownloadStatus.Failed, DownloadStatus.Cancelled -> SheetAction("重新下载", Icons.Outlined.Download, onClick = {
                app.downloads.retry(download.key); onDismiss()
            })
            null -> SheetAction("下载", Icons.Outlined.Download,
                onClick = if (resolving) null else ({ withSongs { app.downloads.enqueue(it) } }))
        }
        if (!nowPlaying) {
            SheetDivider()
            SheetAction("插播", Icons.AutoMirrored.Outlined.PlaylistPlay,
                onClick = if (resolving) null else ({ withSongs { app.player.enqueue(it, next = true) } }))
            SheetAction("添加到队列", Icons.AutoMirrored.Outlined.PlaylistAdd,
                onClick = if (resolving) null else ({ withSongs { app.player.enqueue(it, next = false) } }))
        }
    }
    SheetDivider()
    SheetAction("分享${catalogKindLabel(hit.kind)}", Icons.Outlined.Share,
        detail = if (shareText == null) "缺少已验证的资源链接" else null,
        onClick = shareText?.let { text -> {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
            }, null))
            onDismiss()
        } })
    if (hit.kind == "song") {
        if (hasLyrics) SheetAction("分享歌词", Icons.Outlined.Lyrics)
        val validId = hit.source.validSongId(hit.id)
        SheetAction(
            "查看歌曲制作信息",
            Icons.Outlined.Info,
            detail = if (validId) null else "缺少有效的歌曲 ID",
            onClick = if (validId) ({ relationPending = null; showCredits = true }) else null,
        )
        if (onPronunciation != null) SheetAction(if (pronunciation) "隐藏音译" else "显示音译", Icons.Outlined.RecordVoiceOver, onClick = onPronunciation)
        if (onTranslation != null) SheetAction(if (translation) "隐藏翻译" else "显示翻译", Icons.Outlined.Translate, onClick = onTranslation)
        if (nowPlaying) SheetAction("睡眠定时器", Icons.Outlined.Timer, detail = "当前播放器未提供定时停止")
    }
    if (favoriteEntity) {
        SheetDivider()
        SheetAction(if (saved?.isFavorite == true) "取消喜爱" else "喜爱",
            if (saved?.isFavorite == true) Icons.Outlined.Star else Icons.Outlined.StarBorder,
            detail = "仅保存到本机资料库", onClick = if (resolving) null else ({
                when {
                    hit.kind == "album" && resolveTracks != null && saved?.isFavorite != true ->
                        runAction { library.toggleFavorite(hit, resolveTracks.invoke()) }
                    hit.kind == "song" && saved?.isFavorite != true -> runAction {
                        library.toggleFavorite(app.librarySongHit(hit))
                        saveParentAlbum?.invoke()
                    }
                    hit.kind == "song" && saved?.isFavorite == true -> { library.toggleFavorite(saved.hit); onDismiss() }
                    else -> { library.toggleFavorite(hit); onDismiss() }
                }
            }))
        if (saved?.isFavorite != true) SheetAction("减少推荐", Icons.Outlined.ThumbDownOffAlt,
            detail = "Fantastic 未提供推荐偏好反馈")
    }
    if (onPlay != null || onView != null || onQuality != null || hit.kind == "artist") {
        SheetDivider()
        if (onPlay != null) SheetAction("播放", Icons.Outlined.PlayArrow, onClick = onPlay)
        if (onView != null) SheetAction("查看详情", Icons.Outlined.Info, onClick = onView)
        if (onQuality != null) SheetAction("播放音质", Icons.Outlined.GraphicEq, onClick = onQuality)
        if (hit.kind == "artist") SheetAction(if (saved == null) "添加到本机资料库" else "从本机资料库移除",
            Icons.Outlined.LibraryMusic, onClick = { if (saved == null) library.add(hit) else library.remove(hit); onDismiss() })
    }
    if (showCredits) SongCreditsDialog(app, hit) { showCredits = false }
}

internal fun catalogKindLabel(kind: String): String = when (kind) {
    "song" -> "歌曲"; "album" -> "专辑"; "artist" -> "艺人"; "playlist" -> "播放列表"
    "music-video" -> "视频"; "station" -> "电台"; "post" -> "动态"; else -> "内容"
}

internal fun catalogShareText(hit: CatalogHit): String {
    val id = Uri.encode(hit.id)
    val link = when (hit.source) {
        Source.Apple -> "https://music.apple.com/${hit.country.normalizedAppleCountry()?.let { "$it/" }.orEmpty()}${hit.kind}/$id"
        Source.QQ -> "https://y.qq.com/n/ryqq/${when (hit.kind) {
            "song" -> "songDetail"; "artist" -> "singer"; "music-video" -> "mv"; else -> hit.kind
        }}/$id"
        Source.Netease -> "https://music.163.com/${hit.kind}?id=$id"
    }
    return "${hit.title}${hit.subtitle.takeIf { it.isNotBlank() }?.let { " – $it" }.orEmpty()}\n$link"
}
