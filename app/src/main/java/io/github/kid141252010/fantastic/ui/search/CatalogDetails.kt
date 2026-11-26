package io.github.kid141252010.fantastic.ui.search

import android.text.Html
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.AppleCatalogApi
import io.github.kid141252010.fantastic.data.AppleCatalogHttpException
import io.github.kid141252010.fantastic.data.AppleCatalogPage
import io.github.kid141252010.fantastic.data.AppleCatalogResource
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.LibraryAlbumMetadata
import io.github.kid141252010.fantastic.data.NeteaseAlbumList
import io.github.kid141252010.fantastic.data.NeteaseAlbum
import io.github.kid141252010.fantastic.data.NeteaseAlbumSummary
import io.github.kid141252010.fantastic.data.NeteaseArtistCredit
import io.github.kid141252010.fantastic.data.NeteaseArtistPage
import io.github.kid141252010.fantastic.data.NeteasePlaylistPage
import io.github.kid141252010.fantastic.data.NeteaseQuality
import io.github.kid141252010.fantastic.data.NeteaseSong
import io.github.kid141252010.fantastic.data.NeteaseSongInfo
import io.github.kid141252010.fantastic.data.NeteaseSongPage
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.QqSong
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.catalogCountry
import io.github.kid141252010.fantastic.data.albumMetadataRevision
import io.github.kid141252010.fantastic.data.albumArtistHits
import io.github.kid141252010.fantastic.data.libraryTrackCount
import io.github.kid141252010.fantastic.data.normalizedAppleCountry
import io.github.kid141252010.fantastic.data.toTrack
import io.github.kid141252010.fantastic.data.toLibraryTracks
import io.github.kid141252010.fantastic.ui.Dimens
import io.github.kid141252010.fantastic.ui.GlassSheet
import io.github.kid141252010.fantastic.ui.MediaActionContent
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import io.github.kid141252010.fantastic.ui.Md3BackButton
import io.github.kid141252010.fantastic.ui.Md3EmptyState
import io.github.kid141252010.fantastic.ui.Md3MediaArtwork
import io.github.kid141252010.fantastic.ui.Md3MediaRow
import io.github.kid141252010.fantastic.ui.Md3MediaSectionHeader
import io.github.kid141252010.fantastic.ui.formatTime
import io.github.kid141252010.fantastic.playback.DownloadStatus
import io.github.kid141252010.fantastic.playback.mediaId
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

/** Native catalogue details are stacked under the common mini-player and tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogDetails(
    app: FantasticApp,
    source: Source,
    kind: String,
    id: String,
    country: String?,
    openRequest: Int,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    onClose: () -> Unit,
) {
    val root = remember(source, kind, id, country) {
        DetailTarget(source, normalizedKind(kind), id,
            if (source == Source.Apple) country.normalizedAppleCountry() ?: app.prefs.storefront.normalizedAppleCountry() else null)
    }
    var history by remember { mutableStateOf(listOf(root)) }
    var handledOpenRequest by remember { mutableStateOf(openRequest) }
    if (handledOpenRequest != openRequest) {
        handledOpenRequest = openRequest
        if (root != history.last()) history = history + root
    }
    val current = history.last()
    val player by app.player.state.collectAsStateWithLifecycle()
    var qualityTrack by remember(current, history.size) { mutableStateOf<Track?>(null) }
    var qualityExpanded by remember(current, history.size) { mutableStateOf(false) }
    var playbackError by remember(current, history.size) { mutableStateOf<String?>(null) }
    val moreActionState = remember(current, history.size) { mutableStateOf<(() -> Unit)?>(null) }
    val registerMore = remember(moreActionState) { { action: (() -> Unit)? -> moreActionState.value = action } }
    val play: (List<Track>, Int, String?) -> Unit = { tracks, index, quality ->
        if (index in tracks.indices) {
            try {
                playbackError = null
                app.player.playQueue(tracks, index, quality)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                playbackError = "无法启动播放，请检查播放器连接后重试。"
            }
        }
    }
    val playOrdered: (List<Track>, Int, String?, Boolean) -> Unit = { tracks, index, quality, shuffle ->
        if (index in tracks.indices) {
            try {
                playbackError = null
                app.player.playQueue(tracks, index, quality, shuffle)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                playbackError = "无法启动播放，请检查播放器连接后重试。"
            }
        }
    }
    val back: () -> Unit = {
        if (qualityExpanded) qualityExpanded = false
        else if (history.size > 1) history = history.dropLast(1) else onClose()
    }
    BackHandler(enabled = interactive, onBack = back)
    Surface(modifier.then(if (!interactive) Modifier.clearAndSetSemantics {} else Modifier), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
            Column(Modifier.fillMaxSize().statusBarsPadding().nestedScroll(scrollBehavior.nestedScrollConnection)) {
                TopAppBar(
                    title = { Text(kindLabel(current.kind), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { Md3BackButton(back) },
                    actions = {
                        IconButton(enabled = interactive && moreActionState.value != null,
                            onClick = { if (interactive) moreActionState.value?.invoke() }) {
                            Icon(Icons.Rounded.MoreHoriz, "${kindLabel(current.kind)}更多操作")
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    scrollBehavior = scrollBehavior,
                )
                key(current, history.size) {
                    DetailBody(app, current, play, playOrdered, playbackError, registerMore,
                        onQualityOpen = { track -> qualityTrack = track; qualityExpanded = true },
                        navigate = { target -> if (target != current) history = history + target })
                }
            }
            qualityTrack?.let { track ->
                NeteaseSongControls(app, track, player.mediaId, play,
                    expanded = qualityExpanded, onDismiss = { qualityExpanded = false }, playbackError = playbackError)
            }
            BackHandler(enabled = interactive && qualityExpanded) { qualityExpanded = false }
        }
    }
}

private data class DetailTarget(val source: Source, val kind: String, val id: String, val country: String? = null)
private data class DetailEntry(
    val target: DetailTarget,
    val title: String,
    val subtitle: String = "",
    val artwork: String? = null,
    val track: Track? = null,
)
private sealed interface DetailCursor {
    data class Apple(val next: String, val tracks: Boolean, val visited: Set<String> = emptySet()) : DetailCursor
    data class Adapter(val kind: String, val offset: Int, val limit: Int) : DetailCursor
}
private data class DetailSection(
    val key: String,
    val title: String,
    val entries: List<DetailEntry>,
    val next: DetailCursor? = null,
    val notice: String? = null,
    val truncated: Boolean = false,
)
private data class DetailPage(
    val title: String,
    val subtitle: String = "",
    val artwork: String? = null,
    val description: String = "",
    val facts: List<Pair<String, String>> = emptyList(),
    val song: Track? = null,
    val sections: List<DetailSection> = emptyList(),
    val notice: String? = null,
    val country: String? = null,
)
private class DetailUnavailable(message: String) : IOException(message)

@Composable
private fun DetailBody(
    app: FantasticApp,
    target: DetailTarget,
    play: (List<Track>, Int, String?) -> Unit,
    playOrdered: (List<Track>, Int, String?, Boolean) -> Unit,
    playbackError: String?,
    onMoreActionChange: ((() -> Unit)?) -> Unit,
    onQualityOpen: (Track) -> Unit,
    navigate: (DetailTarget) -> Unit,
) {
    val repository = remember(app, target) { DetailRepository(app, target) }
    var frameActive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { frameActive = false } }
    var page by remember { mutableStateOf<DetailPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var busySections by remember { mutableStateOf(emptySet<String>()) }
    var sectionErrors by remember { mutableStateOf(emptyMap<String, String>()) }
    val scope = rememberCoroutineScope()
    var moreEntry by remember { mutableStateOf<DetailEntry?>(null) }
    var moreSection by remember { mutableStateOf<DetailSection?>(null) }
    var moreIndex by remember { mutableStateOf(-1) }
    val player by app.player.state.collectAsStateWithLifecycle()
    val attemptedArtists = remember(repository) { mutableSetOf<DetailTarget>() }
    LaunchedEffect(repository, retry) {
        error = null
        attemptedArtists.clear()
        try {
            page = repository.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = detailError(e)
        }
    }
    val loaded = page
    DisposableEffect(target, loaded, onMoreActionChange) {
        onMoreActionChange(loaded?.let { model -> {
            moreSection = null
            moreIndex = -1
            moreEntry = DetailEntry(target, model.title, model.subtitle, model.artwork, model.song)
        } })
        onDispose { onMoreActionChange(null) }
    }
    if (loaded == null) {
        val message = error
        if (message == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        else Md3EmptyState("无法加载${kindLabel(target.kind)}", message, icon = Icons.Rounded.ErrorOutline) {
            FilledTonalButton(onClick = { retry++ }) { Text("重新加载") }
        }
        return
    }
    val playableSection = remember(loaded) {
        loaded.sections.firstOrNull { section -> section.entries.any { it.track != null } }
    }
    val playable = remember(loaded) {
        loaded.song?.let(::listOf) ?: playableSection?.entries?.mapNotNull { it.track }.orEmpty()
    }
    var collectionPlayBusy by remember { mutableStateOf<String?>(null) }
    var collectionPlayError by remember { mutableStateOf<String?>(null) }
    var collectionPlayRequest by remember { mutableStateOf(0) }
    fun loadCollection(section: DetailSection, action: (List<Track>) -> Unit) {
        val request = collectionPlayRequest + 1
        collectionPlayRequest = request
        collectionPlayBusy = section.key
        collectionPlayError = null
        scope.launch {
            try {
                val tracks = repository.completeTracks(section)
                if (tracks.isEmpty()) throw DetailUnavailable("服务器未返回歌曲。")
                if (request == collectionPlayRequest) action(tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request == collectionPlayRequest) collectionPlayError = "无法加载完整曲目列表：${detailError(e)}"
            } finally {
                if (request == collectionPlayRequest) collectionPlayBusy = null
            }
        }
    }
    val playCollection: (DetailSection, Int, Boolean) -> Unit = playCollection@{ section, entryIndex, shuffle ->
        val clickedTrack = section.entries.getOrNull(entryIndex)?.track ?: return@playCollection
        app.player.play(clickedTrack)
        val clickedIndex = section.entries.take(entryIndex).count { it.track != null }
        loadCollection(section) { songs ->
            if (songs.getOrNull(clickedIndex)?.mediaId != clickedTrack.mediaId) {
                throw DetailUnavailable("服务器未返回所选歌曲。")
            }
            playOrdered(songs, clickedIndex, null, shuffle)
        }
    }
    val artists = remember(loaded) { loaded.sections.flatMap { it.entries }.filter { it.target.kind == "artist" }.distinctBy { it.target } }
    val artistTargets = remember(artists) { artists.map { it.target } }
    LaunchedEffect(repository, artistTargets, retry) {
        if (target.kind != "album") return@LaunchedEffect
        for (entry in artists) {
            if (!entry.artwork.isNullOrBlank() || !attemptedArtists.add(entry.target)) continue
            try {
                val artwork = repository.artistArtwork(entry.target.id, entry.target.country)
                    ?.takeIf { it.isNotBlank() } ?: continue
                ensureActive()
                page = page?.let { latest -> latest.copy(sections = latest.sections.map { section ->
                    section.copy(entries = section.entries.map { current ->
                        if (current.target == entry.target) current.copy(artwork = artwork) else current
                    })
                }) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                Log.w("FantasticCatalog", "${entry.target.source} artist artwork failed for ${entry.target.id}", failure)
            }
        }
    }
    val artistPage = target.kind == "artist"
    val firstPlayable = playableSection?.entries?.indexOfFirst { it.track != null } ?: -1
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().then(if (moreEntry != null) Modifier.clearAndSetSemantics {} else Modifier),
        contentPadding = PaddingValues(bottom = LocalBarsPadding.current.calculateBottomPadding() + 24.dp)) {
        item("header") {
            DetailHero(
                page = loaded,
                artist = artistPage,
                video = target.kind == "music-video",
                busy = collectionPlayBusy != null,
                onArtist = artists.singleOrNull()?.takeUnless { artistPage }?.let { entry -> { navigate(entry.target) } },
                onPlay = if (playable.isNotEmpty()) ({
                    if (loaded.song != null) play(playable, 0, null)
                    else if (playableSection != null) playCollection(playableSection, firstPlayable, false)
                }) else null,
                onShuffle = playableSection?.takeUnless { artistPage || loaded.song != null }?.let { section ->
                    { playCollection(section, firstPlayable, true) }
                },
                onDownload = if (!artistPage && playable.isNotEmpty()) ({
                    if (playableSection != null && loaded.song == null) loadCollection(playableSection) { app.downloads.enqueue(it) }
                    else app.downloads.enqueue(playable)
                }) else null,
            )
            if (!artistPage && loaded.description.isNotBlank()) {
                var expanded by remember { mutableStateOf(false) }
                var clipped by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Page, vertical = 8.dp)) {
                    Text(
                        loaded.description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!expanded) clipped = it.hasVisualOverflow },
                    )
                    if (expanded || clipped) TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起" else "展开简介")
                    }
                }
            }
            loaded.notice?.let { Text(it, Modifier.padding(horizontal = Dimens.Page, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            playbackError?.let { Text(it, Modifier.padding(horizontal = Dimens.Page), color = MaterialTheme.colorScheme.error) }
            collectionPlayError?.let { Text(it, Modifier.padding(horizontal = Dimens.Page), color = MaterialTheme.colorScheme.error) }
            if (player.error != null && player.mediaId?.startsWith("${target.source.mediaPrefix}:") == true) {
                Text("播放器报告播放失败，请重试或检查账号与音质设置。", Modifier.padding(horizontal = Dimens.Page), color = MaterialTheme.colorScheme.error)
            }
        }
        if (target.source == Source.Netease && loaded.song != null) {
            item("qualities") {
                FilledTonalButton(onClick = { onQualityOpen(loaded.song) }, modifier = Modifier.padding(horizontal = Dimens.Page)) {
                    Text("音质与下载")
                }
            }
        }
        if (loaded.sections.none { it.entries.any { entry -> entry.track != null } } && loaded.song == null &&
            target.kind in setOf("album", "artist", "playlist")) {
            item("no-songs") { Text("当前没有可播放的音频曲目；可继续浏览下方关联内容。",
                Modifier.padding(Dimens.Page), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        loaded.sections.forEach { section ->
            val multipleDiscs = target.kind == "album" && section.entries.any { (it.track?.discNumber ?: 0) > 1 }
            val firstSong = section.entries.indexOfFirst { it.track != null }
            item("${section.key}:heading") {
                Md3MediaSectionHeader(section.title) {
                    if (firstSong >= 0 && artistPage) TextButton(enabled = collectionPlayBusy == null,
                        onClick = { playCollection(section, firstSong, false) }) { Text("播放") }
                }
                if (section.entries.isEmpty()) Text("暂无内容", Modifier.padding(horizontal = Dimens.Page),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(section.entries, key = { index, entry ->
                "${section.key}:$index:${entry.target.kind}:${entry.target.id}"
            }) { index, entry ->
                val track = entry.track
                if (track != null) {
                    val number = track.trackNumber.takeIf { target.kind == "album" && it > 0 }?.let {
                        if (multipleDiscs && track.discNumber > 0) "${track.discNumber}-$it" else it.toString()
                    }
                    Md3MediaRow(
                        title = track.title,
                        subtitle = if (number == null) track.artist else "",
                        playing = player.mediaId == track.mediaId,
                        onClick = { playCollection(section, index, false) },
                        leading = {
                            if (number != null) Text(number, Modifier.padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Md3MediaArtwork(entry.artwork ?: track.artwork(120), Modifier.size(48.dp), shape = MaterialTheme.shapes.small)
                        },
                        trailing = {
                            IconButton(onClick = { moreEntry = entry; moreSection = section; moreIndex = index }) {
                                Icon(Icons.Rounded.MoreHoriz, "${entry.title}更多操作")
                            }
                        },
                    )
                } else {
                    DetailRelationRow(entry) { navigate(entry.target) }
                }
            }
            item("${section.key}:footer") {
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Page, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    section.notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall) }
                    sectionErrors[section.key]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val cursor = section.next
                    if (cursor != null) {
                        FilledTonalButton(enabled = section.key !in busySections, onClick = {
                            if (section.key !in busySections) {
                                busySections = busySections + section.key
                                sectionErrors = sectionErrors - section.key
                                scope.launch {
                                    try {
                                        val addition = repository.more(cursor)
                                        page = page?.let { current -> current.copy(sections = current.sections.map {
                                            if (it.key == section.key) it.copy(entries = it.entries + addition.entries,
                                                next = addition.next, notice = addition.notice, truncated = addition.truncated) else it
                                        }) }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        sectionErrors = sectionErrors + (section.key to detailError(e))
                                    } finally {
                                        busySections = busySections - section.key
                                    }
                                }
                            }
                        }) {
                            if (section.key in busySections) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(if (section.key in sectionErrors) "重试加载更多" else "加载更多")
                        }
                    }
                }
            }
        }
        val showBio = artistPage && loaded.description.isNotBlank()
        val hasFacts = loaded.facts.any { (label, value) -> value.isNotBlank() && (artistPage || label !in heroFactNames) }
        if (showBio || hasFacts) item("info") {
            Md3MediaSectionHeader(if (artistPage) "关于 ${loaded.title}" else "详细信息")
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.Page),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showBio) Text(loaded.description, style = MaterialTheme.typography.bodyMedium)
                    loaded.facts.forEach { (label, value) ->
                        if (value.isNotBlank() && (artistPage || label !in heroFactNames)) Text(
                            if (label == "版权") value else "$label：$value",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    moreEntry?.let { entry ->
        val hit = CatalogHit(entry.target.source, entry.target.kind, entry.target.id, entry.title, entry.subtitle, entry.artwork, entry.track,
            country = if (entry.target == target) loaded.country else entry.track?.country ?: entry.target.country)
        val queue = if (entry.target == target) playable else listOfNotNull(entry.track)
        val resolverSection = (if (target.kind == "album") loaded.sections.firstOrNull {
            it.key == "tracks" || it.key == "relationship:tracks"
        } else playableSection)?.takeIf { entry.target == target && loaded.song == null }
        val sectionForSong = moreSection
        val indexForSong = moreIndex
        val quality = entry.track?.takeIf { it.source == Source.Netease }
        val closeMore = { moreEntry = null; moreSection = null; moreIndex = -1 }
        BackHandler { closeMore() }
        GlassSheet(entry.title, onDismissRequest = closeMore, showHeader = false) {
            MediaActionContent(hit, app.library, queue,
                onPlay = if (queue.isNotEmpty()) ({
                    closeMore()
                    when {
                        sectionForSong != null && indexForSong >= 0 -> playCollection(sectionForSong, indexForSong, false)
                        resolverSection != null -> playCollection(resolverSection, resolverSection.entries.indexOfFirst { it.track != null }, false)
                        else -> play(queue, 0, null)
                    }
                }) else null,
                onView = if (entry.target != target) ({ closeMore(); navigate(entry.target) }) else null,
                onQuality = quality?.let { song -> { closeMore(); onQualityOpen(song) } },
                resolveTracks = resolverSection?.let { section -> { repository.completeTracks(section) } },
                saveParentAlbum = sectionForSong?.takeIf { target.kind == "album" && entry.track?.albumId == target.id }?.let { section ->
                    {
                        val album = CatalogHit(target.source, "album", target.id, loaded.title, loaded.subtitle, loaded.artwork, country = loaded.country)
                        app.library.add(album, repository.completeTracks(section))
                    }
                },
                onOpenRelated = if (entry.target.kind == "song") ({ related ->
                    if (frameActive && moreEntry == entry) {
                        closeMore()
                        navigate(DetailTarget(related.source, related.kind, related.id, related.country))
                    }
                }) else null,
                onDismiss = closeMore)
        }
    }
    }
}

private val heroFactNames = setOf("流派", "发行日期", "音频规格", "内容分级")

@Composable
private fun DetailHero(
    page: DetailPage,
    artist: Boolean,
    video: Boolean,
    busy: Boolean,
    onArtist: (() -> Unit)?,
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    onDownload: (() -> Unit)?,
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.Page, vertical = 12.dp), Alignment.Center) {
        Surface(
            Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (artist) {
                    Md3MediaArtwork(page.artwork, Modifier.fillMaxWidth().aspectRatio(1f),
                        shape = MaterialTheme.shapes.extraLarge, icon = Icons.Rounded.Person)
                } else {
                    Md3MediaArtwork(
                        page.artwork,
                        Modifier.padding(top = 20.dp).widthIn(max = 260.dp).fillMaxWidth(.72f)
                            .aspectRatio(if (video) 16f / 9f else 1f),
                        shape = MaterialTheme.shapes.large,
                    )
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(page.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    if (page.subtitle.isNotBlank()) {
                        if (onArtist != null) TextButton(onClick = onArtist) {
                            Text(page.subtitle, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        } else Text(page.subtitle, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    if (!artist) {
                        val caption = remember(page.facts) {
                            page.facts.filter { it.first in heroFactNames && it.second.isNotBlank() }.joinToString(" · ") { it.second }
                        }
                        if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onPlay != null) Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onShuffle != null) FilledTonalIconButton(onClick = onShuffle, enabled = !busy) {
                            Icon(Icons.Rounded.Shuffle, "随机播放")
                        }
                        Button(onClick = onPlay, enabled = !busy) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("播放", Modifier.padding(start = 8.dp))
                        }
                        if (onDownload != null) FilledTonalIconButton(onClick = onDownload, enabled = !busy) {
                            Icon(Icons.Rounded.Download, "下载")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRelationRow(entry: DetailEntry, onClick: () -> Unit) {
    Md3MediaRow(
        title = entry.title,
        subtitle = listOf(kindLabel(entry.target.kind), entry.subtitle).filter { it.isNotBlank() }.distinct().joinToString(" · "),
        onClick = onClick,
        leading = {
            val artist = entry.target.kind == "artist"
            Md3MediaArtwork(entry.artwork, Modifier.size(48.dp),
                shape = if (artist) CircleShape else MaterialTheme.shapes.small,
                icon = if (artist) Icons.Rounded.Person else Icons.Rounded.MusicNote)
        },
        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
    )
}

private val neteaseQualityLabels = Prefs.NETEASE_QUALITIES.asReversed()

@Composable
private fun NeteaseSongControls(
    app: FantasticApp,
    track: Track,
    mediaId: String?,
    play: (List<Track>, Int, String?) -> Unit,
    expanded: Boolean,
    onDismiss: () -> Unit,
    playbackError: String?,
) {
    val downloads by app.downloads.entries.collectAsStateWithLifecycle()
    val download = downloads.firstOrNull { it.key == track.mediaId }
    var qualities by remember(track.id) { mutableStateOf<List<NeteaseQuality>?>(null) }
    var selected by remember(track.id) { mutableStateOf<String?>(null) }
    var qualityError by remember(track.id) { mutableStateOf<String?>(null) }
    var retry by remember(track.id) { mutableStateOf(0) }
    LaunchedEffect(app, track.id, retry) {
        qualityError = null
        try {
            val result = app.netease.qualities(track.id).qualities
            qualities = result
            val available = result.filter { it.available }.map { it.quality }
            selected = selected?.takeIf { it in available }
                ?: app.prefs.neteaseQuality.takeIf { it in available }
                ?: neteaseQualityLabels.firstOrNull { it.first in available }?.first
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            qualityError = detailError(e)
        }
    }
    if (!expanded) return
    GlassSheet("音质与下载", onDismiss) {
        val rows = qualities?.associateBy { it.quality }.orEmpty()
        Column(Modifier.selectableGroup()) {
        neteaseQualityLabels.forEach { (quality, label) ->
            val info = rows[quality]
            val available = info?.available == true
            val select: () -> Unit = {
                selected = quality
                if (mediaId == track.mediaId) app.player.selectQuality(quality)
            }
            Row(Modifier.fillMaxWidth().selectable(selected = selected == quality, enabled = available,
                role = Role.RadioButton, onClick = select).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RadioButton(selected = selected == quality, enabled = available, onClick = null)
                Column(Modifier.weight(1f)) {
                    Text(label, color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    val specs = when {
                        qualities == null && qualityError == null -> "正在查询…"
                        available -> info?.sizeBytes?.let(::formatBytes).orEmpty()
                        qualities != null -> "不可用"
                        else -> ""
                    }
                    if (specs.isNotEmpty()) Text(specs, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        }
        playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        qualityError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (qualityError != null) TextButton(onClick = { retry++ }) { Text("重新查询音质") }
        if (qualities != null && selected == null) Text("此歌曲当前没有可用音质，无法播放或下载。",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val selectedInfo = rows[selected]?.takeIf { it.available }
        Button(enabled = selectedInfo != null, onClick = { play(listOf(track), 0, selectedInfo?.quality) }) {
            Text("播放所选音质")
        }
        val busy = download?.status in setOf(DownloadStatus.Downloading, DownloadStatus.Queued)
        OutlinedButton(enabled = selectedInfo != null && !busy && download?.status != DownloadStatus.Ready,
            onClick = { app.downloads.enqueue(listOf(track), selectedInfo?.quality) }) {
            Text(if (download?.status == DownloadStatus.Ready) "已下载" else "下载所选音质")
        }
        if (busy) Text(download?.percent?.let { "下载进度 $it%" } ?: "正在准备下载…",
            style = MaterialTheme.typography.bodySmall)
        if (download?.status == DownloadStatus.Failed) Text(download.error.orEmpty(), color = MaterialTheme.colorScheme.error)
    }
}


private class DetailRepository(private val app: FantasticApp, private val target: DetailTarget) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    // Keep a consistent storefront while navigating this resource's opaque pagination links.
    private val storefront = target.country.normalizedAppleCountry() ?: app.prefs.storefront
    private var resourceCountry: String? = storefront.normalizedAppleCountry()
    private var parentAlbum: AppleCatalogResource? = null

    suspend fun load(): DetailPage {
        if (target.id.isBlank()) throw DetailUnavailable("缺少资源 ID，请返回搜索重新选择。")
        return when (target.source) {
            Source.Apple -> apple()
            Source.Netease, Source.QQ -> adapter()
        }
    }

    suspend fun more(cursor: DetailCursor): DetailSection = when (cursor) {
        is DetailCursor.Apple -> {
            if (cursor.next in cursor.visited) throw DetailUnavailable("服务器重复返回同一分页链接，无法继续加载。")
            val response = if (cursor.tracks) app.apple.trackPage(cursor.next) else app.apple.page(cursor.next)
            val next = response.next?.takeIf { it.isNotBlank() }
            if (next == cursor.next || next in cursor.visited) throw DetailUnavailable("服务器分页没有前进，请稍后重试。")
            val entries = response.data.mapNotNull { appleEntry(it, parentAlbum.takeIf { cursor.tracks }, response.requestStorefront ?: resourceCountry) }
            DetailSection("", "", entries,
                next?.let { DetailCursor.Apple(it, cursor.tracks, cursor.visited + cursor.next) },
                if (response.data.any { !isSupportedKind(it.type) }) "部分非音频资源不支持在此页面打开。" else null)
        }
        is DetailCursor.Adapter -> {
            require(cursor.offset in 0..10_000)
            when (cursor.kind) {
                "songs" -> {
                    val result = artistSongs(cursor.offset, cursor.limit)
                    adapterContinuation(cursor, songEntries(result.songs), result.songs.size, result.more)
                }
                "albums" -> {
                    val result = artistAlbums(cursor.offset, cursor.limit)
                    adapterContinuation(cursor, albumEntries(result.albums), result.albums.size, result.more)
                }
                "playlist" -> {
                    val result = playlist(cursor.offset, cursor.limit)
                    adapterContinuation(cursor, songEntries(result.songs), result.songs.size, result.more)
                }
                else -> throw DetailUnavailable("此资源不支持分页。")
            }
        }
    }
    suspend fun completeTracks(section: DetailSection): List<Track> {
        if (section.truncated) throw DetailUnavailable(section.notice ?: "服务器未返回完整曲目列表。")
        val entries = section.entries.toMutableList()
        var cursor = section.next
        while (cursor != null) {
            val addition = more(cursor)
            if (addition.truncated) throw DetailUnavailable(addition.notice ?: "服务器未返回完整曲目列表。")
            entries += addition.entries
            cursor = addition.next
        }
        return entries.mapNotNull { it.track }
    }

    private fun adapterContinuation(cursor: DetailCursor.Adapter, entries: List<DetailEntry>, count: Int,
        more: Boolean): DetailSection {
        if (more && count == 0) throw DetailUnavailable("服务器没有返回下一页内容，请稍后重试。")
        val offset = cursor.offset + count
        val capped = more && offset > 10_000
        return DetailSection("", "", entries,
            if (more && !capped) cursor.copy(offset = offset) else null,
            if (capped) "已达到此接口支持的分页上限（offset 10000），不能继续加载。" else null,
            truncated = capped)
    }

    private suspend fun apple(): DetailPage {
        val entity = when (target.kind) {
            "song" -> AppleCatalogApi.Entity.Song
            "album" -> AppleCatalogApi.Entity.Album
            "artist" -> AppleCatalogApi.Entity.Artist
            "playlist" -> AppleCatalogApi.Entity.Playlist
            "music-video" -> AppleCatalogApi.Entity.MusicVideo
            else -> throw DetailUnavailable("Apple Music 暂不支持此类资源详情。")
        }
        val resource = app.apple.entity(entity, target.id, storefront = storefront,
            params = if (entity == AppleCatalogApi.Entity.Album) mapOf("include" to "tracks,artists,record-labels") else emptyMap())
        if (resource.id != target.id || resource.type != entity.route) throw DetailUnavailable("服务器返回了其他资源。")
        resourceCountry = resource.catalogCountry(storefront)
        parentAlbum = resource.takeIf { target.kind == "album" }
        val facts = buildList {
            val attrs = resource.attributes
            listOf("releaseDate" to "发行日期", "recordLabel" to "唱片公司", "composerName" to "作曲",
                "origin" to "来自", "bornOrFormed" to "出生 / 成立", "copyright" to "版权").forEach { (key, label) ->
                attrs.detailString(key).takeIf { it.isNotBlank() }?.let { add(label to it) }
            }
            (attrs["genreNames"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.joinToString(" / ")?.takeIf { it.isNotBlank() }?.let { add("流派" to it) }
            (attrs["audioTraits"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.joinToString(" · ")?.takeIf { it.isNotBlank() }?.let { add("音频规格" to it) }
            attrs.detailString("trackCount").takeIf { it.isNotBlank() }?.let { add("曲目数" to it) }
            if (resource.durationMs > 0) add("时长" to formatTime(resource.durationMs))
            if (resource.explicit) add("内容分级" to "Explicit")
        }
        val sections = buildList {
            resource.relationships.forEach { (name, relation) -> appleSection("relationship:$name", name, relation, resource)?.let { add(it) } }
            resource.views.forEach { (name, view) -> appleSection("view:$name", name, view, resource)?.let { add(it) } }
        }.sortedBy { if (it.key == "relationship:tracks" || it.key == "view:top-songs") 0 else 1 }
        val description = listOf("description", "plainEditorialNotes", "editorialNotes", "artistBio")
            .firstNotNullOfOrNull { key -> detailPlainText(resource.attributes[key]).takeIf { it.isNotBlank() } }.orEmpty()
        return DetailPage(resource.name, listOf(resource.artist, resource.album).filter { it.isNotBlank() }.distinct().joinToString(" · "),
            resource.artworkUrl(600), description, facts, resource.toTrack(resourceCountry).takeIf { target.kind == "song" }, sections,
            if (target.kind == "music-video") "这是音乐视频。当前播放器队列只接受音频歌曲；此处不将视频冒充音频，也不解析 DRM 视频地址。可浏览下方关联艺人和专辑。" else null,
            country = resourceCountry)
    }

    private fun appleSection(key: String, name: String, page: AppleCatalogPage, parent: AppleCatalogResource): DetailSection? {
        val entries = page.data.mapNotNull { appleEntry(it, parent.takeIf { name == "tracks" && target.kind == "album" }) }
        if (entries.isEmpty() && page.next.isNullOrBlank()) return null
        // Do not send unsupported relationship types (credits/labels/etc.) to a fake detail route.
        if (entries.isEmpty() && name !in setOf("tracks", "top-songs", "albums", "artists", "playlists", "music-videos")) return null
        return DetailSection(key, page.name?.takeIf { it.isNotBlank() } ?: sectionLabel(name), entries,
            page.next?.takeIf { it.isNotBlank() }?.let { DetailCursor.Apple(it, name == "tracks" || name == "top-songs") },
            if (page.data.any { !isSupportedKind(it.type) }) "部分非音频资源不支持在此页面打开。" else null)
    }

    private fun appleEntry(resource: AppleCatalogResource, album: AppleCatalogResource? = null, country: String? = resourceCountry): DetailEntry? {
        if (!isSupportedKind(resource.type)) return null
        val entryCountry = if (resource.type == "artists" || resource.type == "albums")
            resource.catalogCountry(null) ?: country.normalizedAppleCountry() else resource.catalogCountry(country)
        val track = resource.toTrack(entryCountry).takeIf { normalizedKind(resource.type) == "song" }?.let { song -> if (album == null) song else song.copy(
            album = song.album.ifBlank { album.name },
            artist = song.artist.ifBlank { album.artist },
            artworkTemplate = song.artworkTemplate ?: album.artwork?.detailString("url")?.replace("{c}", "bb"),
            albumId = album.id,
            albumCountry = if (song.albumId == album.id && song.albumId != "0") song.albumCountry ?: album.catalogCountry(null)
                else album.catalogCountry(null),
            albumArtist = song.albumArtist.ifBlank { album.artist },
        ) }
        return DetailEntry(DetailTarget(Source.Apple, normalizedKind(resource.type), resource.id, entryCountry),
            resource.name, resource.artist, resource.artworkUrl(160) ?: track?.artwork(160), track)
    }

    private suspend fun adapter(): DetailPage = when (target.kind) {
        "song" -> {
            val qqSong = if (target.source == Source.QQ) qq("song") else null
            val info = if (qqSong == null) app.netease.song(target.id)
                else NeteaseSongInfo(json.decodeFromJsonElement<NeteaseSong>(qqSong), qqSong["meta"])
            val song = info.song
            if (song.id != target.id) throw DetailUnavailable("服务器返回了其他歌曲。")
            val metadata = info.meta as? JsonObject
            val track = if (qqSong == null) song.toTrack() else json.decodeFromJsonElement<QqSong>(qqSong).toTrack()
            DetailPage(song.name, song.artists.joinToString(" / ") { it.name }, artwork(song.cover),
                detailPlainText(metadata?.get("intro")), buildList {
                    if (song.album.isNotBlank()) add("专辑" to song.album)
                    if (song.durationMs > 0) add("时长" to formatTime(song.durationMs))
                    if (song.vip) add("账号要求" to "VIP")
                    listOf("publishDate" to "发行日期", "genre" to "流派", "language" to "语言", "company" to "唱片公司").forEach { (key, label) ->
                        metadata?.detailString(key)?.takeIf { it.isNotBlank() }?.let { add(label to it) }
                    }
                }, track, buildList {
                    artistRelations(song.artists)?.let { add(it) }
                    if (song.albumMid.isNotBlank()) add(DetailSection("album", "所属专辑", listOf(
                        DetailEntry(DetailTarget(target.source, "album", song.albumMid), song.album.ifBlank { "专辑" }, artwork = artwork(song.cover)))))
                }, if (song.mv.isNotBlank()) "此歌曲有音乐视频；当前音频队列不支持视频播放。" else null)
        }
        "album" -> {
            val expectedRevision = app.library.snapshot.value.albumMetadataRevision(target.source, target.id)
            val qqAlbum = if (target.source == Source.QQ) qq("album") else null
            val neteaseAlbum = if (qqAlbum == null) app.netease.album(target.id) else null
            val album = neteaseAlbum?.album ?: json.decodeFromJsonElement<NeteaseAlbum>(qqAlbum!!.getValue("album"))
            val albumArtist = album.artists.joinToString(" / ") { it.name }
            val tracks = neteaseAlbum?.toLibraryTracks(target.id)
                ?: (qqAlbum?.get("songs") as? JsonArray).orEmpty().map { json.decodeFromJsonElement<QqSong>(it).toTrack() }
            if (album.mid != target.id) throw DetailUnavailable("服务器返回了其他专辑。")
            val albumTracks = tracks.filter { it.albumId.isBlank() || it.albumId == target.id }.map { song ->
                song.copy(albumId = target.id, album = song.album.ifBlank { album.name },
                    albumArtist = song.albumArtist.ifBlank { albumArtist },
                    artworkTemplate = song.artworkTemplate?.takeIf { it.isNotBlank() } ?: album.cover.ifBlank { null })
            }
            album.size.libraryTrackCount(tracks.size)?.let { count ->
                app.library.recordAlbumMetadata(LibraryAlbumMetadata(target.source, target.id, albumArtist, albumTracks, count,
                    artists = albumArtistHits(target.source, album.artists)), expectedRevision)
            }
            DetailPage(album.name, albumArtist, artwork(album.cover), detailPlainText(JsonPrimitive(album.description)),
                buildList {
                    album.size?.takeIf { it >= 0 }?.let { add("曲目数" to it.toString()) }
                    detailDate(album.publishTime)?.let { add("发行日期" to it) }
                    if (album.language.isNotBlank()) add("语言" to album.language)
                    if (album.subType.isNotBlank()) add("类型" to album.subType)
                }, sections = buildList {
                    add(DetailSection("tracks", "曲目", albumTracks.filter { it.id.isNotBlank() }.map(::songEntry)))
                    artistRelations(album.artists)?.let { add(it) }
                })
        }
        "artist" -> {
            val result = artist()
            val artist = result.artist
            if (artist.mid != target.id) throw DetailUnavailable("服务器返回了其他艺人。")
            DetailPage(artist.name, artist.transNames.joinToString(" / "), artwork(artist.avatar.ifBlank { artist.cover }),
                listOf(artist.briefDesc, artist.description).filter { it.isNotBlank() }.distinct()
                    .joinToString("\n\n").let { detailPlainText(JsonPrimitive(it)) },
                listOf("歌曲数" to artist.musicSize.toString(), "专辑数" to artist.albumSize.toString()),
                sections = listOf(
                    DetailSection("songs", "歌曲", songEntries(result.songs),
                        if (result.moreSongs) DetailCursor.Adapter("songs", result.songs.size, 50) else null),
                    DetailSection("albums", "专辑", albumEntries(result.albums),
                        if (result.moreAlbums) DetailCursor.Adapter("albums", result.albums.size, 30) else null),
                ), notice = if (result.mvs.isNotEmpty() || result.moreMvs) "当前音频详情不提供艺人音乐视频播放。" else null)
        }
        "playlist" -> {
            val result = playlist(0, 100)
            val playlist = result.playlist
            DetailPage(playlist?.name?.ifBlank { "歌单 ${target.id}" } ?: "歌单 ${target.id}",
                playlist?.creator.orEmpty(), artwork(playlist?.cover.orEmpty()),
                detailPlainText(playlist?.description?.let { JsonPrimitive(it) }),
                buildList {
                    playlist?.let {
                        add("曲目数" to it.trackCount.toString())
                        detailDate(it.updateTime)?.let { date -> add("更新时间" to date) }
                    }
                }, sections = listOf(DetailSection("tracks", "曲目", songEntries(result.songs),
                    if (result.more) DetailCursor.Adapter("playlist", result.songs.size, 100) else null)),
                notice = if (playlist == null) "服务器未返回歌单介绍，已保留可用曲目。" else null)
        }
        "music-video" -> if (target.source == Source.QQ) qqVideo() else
            throw DetailUnavailable("网易云当前没有此类视频详情接口，请返回歌曲或艺人详情。")
        else -> throw DetailUnavailable("当前不支持此类资源详情，请返回搜索。")
    }

    private fun songEntries(songs: List<NeteaseSong>): List<DetailEntry> = songs.filter { it.id.isNotBlank() }.map { song ->
        val track = if (target.source == Source.QQ) QqSong(song.id, song.name,
            song.artists.map { io.github.kid141252010.fantastic.data.QqArtist(it.name, it.mid, it.avatar) },
            song.album, song.albumMid, song.durationMs, song.cover).toTrack() else song.toTrack()
        songEntry(track)
    }

    private fun songEntry(track: Track) = DetailEntry(DetailTarget(track.source, "song", track.id, track.country),
        track.title, track.artist, track.artwork(160), track)

    private fun albumEntries(albums: List<NeteaseAlbumSummary>): List<DetailEntry> = albums.filter { it.mid.isNotBlank() }.map {
        DetailEntry(DetailTarget(target.source, "album", it.mid), it.name,
            it.artists.joinToString(" / ") { artist -> artist.name }, artwork(it.cover))
    }

    private fun artistRelations(artists: List<NeteaseArtistCredit>): DetailSection? {
        val entries = artists.filter { it.mid.isNotBlank() }.distinctBy { it.mid }.map {
            DetailEntry(DetailTarget(target.source, "artist", it.mid), it.name, artwork = artwork(it.avatar))
        }
        return entries.takeIf { it.isNotEmpty() }?.let { DetailSection("artists", "艺人", it) }
    }

    suspend fun artistArtwork(artistId: String, country: String?): String? {
        if (artistId.isBlank() || artistId == "0") return null
        return when (target.source) {
            Source.Apple -> app.apple.entity(AppleCatalogApi.Entity.Artist, artistId,
                storefront = country.normalizedAppleCountry() ?: storefront)
                .takeIf { it.id == artistId && it.type == "artists" }?.artworkUrl(160)
            Source.Netease -> app.netease.artist(artistId).artist.takeIf { it.mid == artistId }
                ?.let { artwork(it.avatar.ifBlank { it.cover }) }
            Source.QQ -> json.decodeFromJsonElement<NeteaseArtistPage>(app.qq.artist(artistId)).artist
                .takeIf { it.mid == artistId }?.let { artwork(it.avatar.ifBlank { it.cover }) }
        }
    }

    private suspend fun artist(): NeteaseArtistPage = if (target.source == Source.Netease) app.netease.artist(target.id)
        else json.decodeFromJsonElement(qq("artist"))
    private suspend fun artistSongs(offset: Int, limit: Int): NeteaseSongPage = if (target.source == Source.Netease)
        app.netease.artistSongs(target.id, limit, offset) else json.decodeFromJsonElement(qq("artist", "/songs?limit=$limit&offset=$offset"))
    private suspend fun artistAlbums(offset: Int, limit: Int): NeteaseAlbumList = if (target.source == Source.Netease)
        app.netease.artistAlbums(target.id, limit, offset) else json.decodeFromJsonElement(qq("artist", "/albums?limit=$limit&offset=$offset"))
    private suspend fun playlist(offset: Int, limit: Int): NeteasePlaylistPage = if (target.source == Source.Netease)
        app.netease.playlist(target.id, limit, offset) else json.decodeFromJsonElement(qq("playlist", "?limit=$limit&offset=$offset"))

    private suspend fun qqVideo(): DetailPage {
        val body = qq("mv")
        val artists = (body["artists"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .map { NeteaseArtistCredit(it.detailString("name"), it.detailString("mid")) }
        return DetailPage(body.detailString("name"), artists.joinToString(" / ") { it.name }, artwork(body.detailString("cover")),
            detailPlainText(body["description"]), listOf("发行日期" to body.detailString("publishDate")),
            sections = listOfNotNull(artistRelations(artists)),
            notice = "这是音乐视频。当前播放器队列只接受音频歌曲，此处仅展示真实元数据与艺人关联。")
    }

    /** QQ and NetEase's pinned adapter DTOs intentionally share these album/artist/song shapes. */
    private suspend fun qq(route: String, suffix: String = ""): JsonObject {
        require(target.id.length in 1..32 && target.id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' })
        val url = app.api.url("/qq/$route/${target.id}$suffix")
        val body = suspendCancellableCoroutine<String> { continuation ->
            val call = app.api.client.newCall(Request.Builder().url(url).get().build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            if (!it.isSuccessful) throw DetailUnavailable("QQ 音乐请求失败（HTTP ${it.code}），请检查服务器及账号设置。")
                            it.body?.string().orEmpty()
                        }
                        if (!continuation.isCancelled) continuation.resume(value)
                    } catch (e: Exception) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }
                }
            })
        }
        return json.parseToJsonElement(body) as? JsonObject ?: throw IOException("Invalid detail response")
    }
}

private fun normalizedKind(kind: String): String = when (val value = kind.lowercase(Locale.ROOT)) {
    "songs", "song", "track", "tracks" -> "song"
    "albums", "album" -> "album"
    "artists", "artist" -> "artist"
    "playlists", "playlist" -> "playlist"
    "music-videos", "music-video", "mv", "mvs" -> "music-video"
    else -> value
}
private fun isSupportedKind(kind: String) = normalizedKind(kind) in setOf("song", "album", "artist", "playlist", "music-video")
private fun kindLabel(kind: String): String = when (normalizedKind(kind)) {
    "song" -> "歌曲"; "album" -> "专辑"; "artist" -> "艺人"; "playlist" -> "歌单"; "music-video" -> "音乐视频"; else -> "详情"
}
private fun sourceLabel(source: Source): String = when (source) {
    Source.Apple -> "Apple Music"; Source.Netease -> "网易云"; Source.QQ -> "QQ 音乐"
}
private fun sectionLabel(key: String): String = when (key) {
    "tracks" -> "曲目"; "top-songs" -> "热门歌曲"; "albums", "full-albums" -> "专辑"
    "artists", "featured-artists" -> "艺人"; "playlists" -> "歌单"; "music-videos", "related-videos" -> "音乐视频"
    "more-by-artist" -> "艺人的更多作品"; "more-by-curator" -> "策展人的更多歌单"
    "similar-artists" -> "相似艺人"; "other-versions" -> "其他版本"; "you-might-also-like" -> "你可能也喜欢"
    "appears-on", "appears-on-albums", "featured-on-albums" -> "参与作品"; "singles" -> "单曲与 EP"
    "latest-release", "featured-release" -> "最新发行"; "featured-albums" -> "精选专辑"
    "live-albums" -> "现场专辑"; "compilation-albums" -> "合辑"; "audio-extras" -> "更多音频"
    "video-extras" -> "更多视频"; else -> key.replace('-', ' ')
}
private fun JsonObject.detailString(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun detailPlainText(value: JsonElement?): String {
    val text = when (value) {
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonObject -> listOf("standard", "short", "text").firstNotNullOfOrNull { key ->
            value.detailString(key).takeIf { it.isNotBlank() }
        }.orEmpty()
        else -> ""
    }
    return if ('<' in text) Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT).toString().trim() else text.trim()
}
private fun artwork(template: String?): String? = template?.takeIf { it.isNotBlank() }
    ?.replace("{w}", "500")?.replace("{h}", "500")?.replace("{c}", "bb")?.replace("{f}", "jpg")
private fun detailDate(value: Long): String? = if (value <= 0) null else try {
    val millis = if (value < 100_000_000_000L) value * 1000 else value
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
} catch (_: Exception) { null }
private fun formatBytes(value: Long): String = if (value >= 1024 * 1024)
    String.format(Locale.getDefault(), "%.1f MB", value.toDouble() / (1024 * 1024)) else "${value / 1024} KB"
private fun detailError(error: Exception): String = when (error) {
    is DetailUnavailable -> error.message ?: "资源不可用，请返回搜索。"
    is AppleCatalogHttpException -> when (error.statusCode) {
        401, 403 -> "Apple Music 拒绝访问，请检查服务器账号和店面设置。"
        404 -> "当前店面中找不到此资源。请返回搜索，或更换店面后重新打开。"
        else -> "Apple Music 加载失败（HTTP ${error.statusCode}），请稍后重试。"
    }
    is IllegalArgumentException -> "资源 ID 或服务器配置无效，请返回搜索并检查设置。"
    else -> "加载失败。请检查网络、Fantastic 服务器和来源账号配置，然后重试。"
}
