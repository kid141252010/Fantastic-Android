package io.github.kid141252010.fantastic.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.AppleCatalogResource
import io.github.kid141252010.fantastic.data.AppleCatalogResponse
import io.github.kid141252010.fantastic.data.AppleCatalogApi
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.SearchCatalog
import io.github.kid141252010.fantastic.data.catalogCountry
import io.github.kid141252010.fantastic.ui.Dimens
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import io.github.kid141252010.fantastic.ui.Md3BackButton
import io.github.kid141252010.fantastic.ui.Md3MediaSectionHeader
import io.github.kid141252010.fantastic.ui.theme.FontWeightRole
import io.github.kid141252010.fantastic.ui.theme.withUserFontWeight
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.util.Locale

/** Native Apple editorial catalogue, using only the configured Fantastic server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleDiscoveryScreen(app: FantasticApp, radio: Boolean, rootNavigationRequest: Int, onOpen: (CatalogHit) -> Unit, searchLanding: Boolean = false, modifier: Modifier = Modifier) {
    val storefront = app.prefs.storefront
    val initialArea = if (searchLanding) AREA_CATEGORIES else if (radio) AREA_RADIO else AREA_NEW
    var area by rememberSaveable(initialArea) { mutableStateOf(initialArea) }
    var stack by remember(initialArea) { mutableStateOf(emptyList<BrowseTarget>()) }
    var chartKind by remember { mutableStateOf<String?>(null) }
    var chartCountry by remember { mutableStateOf<String?>(null) }
    var genreId by rememberSaveable { mutableStateOf(ALL_GENRES) }
    var retry by remember { mutableIntStateOf(0) }
    var model by remember { mutableStateOf<DiscoveryModel?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var moreJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    var handledRootNavigationRequest by rememberSaveable { mutableStateOf(rootNavigationRequest) }
    LaunchedEffect(rootNavigationRequest) {
        if (handledRootNavigationRequest != rootNavigationRequest) {
            if (stack.isNotEmpty() || area != initialArea || chartKind != null || genreId != ALL_GENRES) {
                moreJob?.cancel()
                moreJob = null
                loadingMore = false
                stack = emptyList()
                area = initialArea
                chartKind = null
                chartCountry = null
                genreId = ALL_GENRES
            }
            handledRootNavigationRequest = rootNavigationRequest
        }
    }
    val target = stack.lastOrNull()

    BackHandler(enabled = stack.isNotEmpty() || chartKind != null || area != initialArea) {
        if (stack.isNotEmpty()) stack = stack.dropLast(1)
        else if (chartKind != null) {
            chartKind = null
            chartCountry = null
            genreId = ALL_GENRES
        } else {
            area = initialArea
            chartCountry = null
            genreId = ALL_GENRES
        }
    }

    val requestCountry = target?.country ?: chartCountry ?: storefront
    LaunchedEffect(radio, requestCountry, area, target, chartKind, genreId, retry) {
        moreJob?.cancel()
        loading = true
        loadingMore = false
        error = null
        model = null
        try {
            model = loadDiscovery(app.apple, requestCountry, radio, area, target, chartKind, genreId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message?.takeIf { it.isNotBlank() } ?: "无法加载 Apple Music 内容"
        } finally {
            loading = false
        }
    }



    fun openTarget(destination: BrowseTarget, artwork: String? = null) {
        if (destination.kind == "charts") {
            area = AREA_CHARTS
            chartKind = null
            chartCountry = destination.country
            genreId = ALL_GENRES
            return
        }
        if (destination.kind in CATALOG_TARGETS) {
            onOpen(CatalogHit(
                source = Source.Apple,
                kind = destination.kind,
                id = destination.id,
                title = destination.title,
                artwork = artwork,
                detailOnly = true,
                country = destination.country,
            ))
        } else {
            stack = stack + destination
            chartKind = null
            chartCountry = null
        }
    }

    fun openResource(resource: AppleCatalogResource, country: String?) {
        val editorialTarget = resource.toDiscoveryTarget(country)
        if (editorialTarget != null) {
            openTarget(editorialTarget, resource.artworkUrl(500))
            return
        }
        onOpen(SearchCatalog.appleHit(resource, country))
    }

    val title = when {
        target != null -> target.title
        radio -> "广播"
        area == AREA_CHARTS && chartKind != null -> chartLabel(chartKind!!)
        area == AREA_CHARTS -> "排行榜"
        area == AREA_CATEGORIES -> "类别浏览"
        else -> "新发现"
    }
    val showBricks = searchLanding && area == AREA_CATEGORIES && target == null
    val bricks = remember(model, showBricks) {
        if (showBricks) model?.sections.orEmpty().flatMap { section -> section.entries.mapNotNull(::categoryBrick) }
        else emptyList()
    }
    val bottom = if (searchLanding) LocalBarsPadding.current.calculateBottomPadding() + 20.dp else 20.dp

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottom),
    ) {
        if (target != null) item(key = "discovery-detail-heading") {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { Md3BackButton { stack = stack.dropLast(1) } },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        } else if (!showBricks) item(key = "discovery-heading") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Page, vertical = 10.dp)) {
                Text(title, style = LocalTextStyle.current.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    .withUserFontWeight(FontWeightRole.Title))
                if (!radio && !searchLanding) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DiscoveryTab("新发现", area == AREA_NEW) {
                            area = AREA_NEW; chartKind = null; chartCountry = null; genreId = ALL_GENRES
                        }
                        DiscoveryTab("排行榜", area == AREA_CHARTS) {
                            area = AREA_CHARTS; chartKind = null; chartCountry = null; genreId = ALL_GENRES
                        }
                        DiscoveryTab("类别", area == AREA_CATEGORIES) {
                            area = AREA_CATEGORIES; chartKind = null; chartCountry = null; genreId = ALL_GENRES
                        }
                    }
                }
            }
        }

        if (loading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (error != null) {
            item(key = "error") {
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Page, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("暂时无法加载", style = LocalTextStyle.current.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        .withUserFontWeight(FontWeightRole.Title))
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { retry++ }) { Text("重试") }
                }
            }
        } else {
            val current = model
            if (current == null || current.sections.isEmpty() || (showBricks && bricks.isEmpty())) {
                item(key = "empty") {
                    Text(
                        if (radio) "此地区暂时没有广播内容。" else "此地区暂时没有可展示的内容。",
                        Modifier.fillMaxWidth().padding(horizontal = Dimens.Page, vertical = 32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (showBricks) {
                item(key = "category-heading") { Md3MediaSectionHeader("浏览类别") }
                items((bricks.size + 1) / 2, key = { "category-row:$it" }) { row ->
                    Row(Modifier.fillMaxWidth().padding(start = Dimens.Page, end = Dimens.Page, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val first = bricks[row * 2]
                        CategoryBrick(first, Modifier.weight(1f)) { openTarget(first.target) }
                        val second = bricks.getOrNull(row * 2 + 1)
                        if (second == null) Spacer(Modifier.weight(1f))
                        else CategoryBrick(second, Modifier.weight(1f)) { openTarget(second.target) }
                    }
                }
            } else {
                if (chartKind != null && area == AREA_CHARTS && current.genres.isNotEmpty()) {
                    item(key = "genres") {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(start = Dimens.Page, end = Dimens.Page, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            current.genres.forEach { genre ->
                                FilterChip(
                                    selected = genre.id == genreId,
                                    onClick = { genreId = genre.id },
                                    label = { Text(genre.name, maxLines = 1) },
                                )
                            }
                        }
                    }
                }
                itemsIndexed(current.sections, key = { index, section -> "${section.key}-$index" }) { _, section ->
                    DiscoveryShelf(
                        section = section,
                        onSelect = { entry ->
                            entry.resource?.let { openResource(it, entry.country) } ?: entry.target?.let(::openTarget)
                        },
                        onSeeAll = { section.seeAll?.let(::openTarget) },
                        onChart = { kind ->
                            area = AREA_CHARTS
                            chartKind = kind
                            chartCountry = section.country ?: current.country
                            genreId = ALL_GENRES
                        },
                    )
                }
                if (!current.next.isNullOrBlank()) {
                    item(key = "load-more") {
                        TextButton(
                            onClick = {
                                if (!loadingMore) {
                                    loadingMore = true
                                    moreJob = scope.launch {
                                        val pageModel = model
                                        val cursor = pageModel?.next
                                        if (pageModel == null || cursor == null) {
                                            loadingMore = false
                                            return@launch
                                        }
                                        try {
                                            val response = app.apple.page(cursor, params = TRACK_PAGE_PARAMS)
                                            currentCoroutineContext().ensureActive()
                                            val chart = pageModel.nextChartKind
                                            val page = if (chart != null) response.charts(chart).firstOrNull() else null
                                            val more = page?.data ?: response.data
                                            val first = pageModel.sections.firstOrNull()
                                            val next = page?.next ?: response.next
                                            if (first == null) model = pageModel.copy(next = next, country = response.requestStorefront ?: pageModel.country)
                                            else {
                                                val appended = (first.entries + more.mapIndexed { index, resource ->
                                                    resource.toEntry(index + first.entries.size, response.requestStorefront ?: pageModel.country)
                                                }).distinctBy { it.key }
                                                model = pageModel.copy(
                                                    sections = pageModel.sections.toMutableList().also { sections ->
                                                        sections[0] = first.copy(entries = appended)
                                                    },
                                                    next = next,
                                                    country = response.requestStorefront ?: pageModel.country,
                                                )
                                            }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            error = failure.message?.takeIf { it.isNotBlank() } ?: "无法加载下一页"
                                        } finally {
                                            loadingMore = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) { Text(if (loadingMore) "正在加载…" else "加载更多") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryTab(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun DiscoveryShelf(
    section: DiscoverySection,
    onSelect: (DiscoveryEntry) -> Unit,
    onSeeAll: () -> Unit,
    onChart: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = Dimens.Page, end = Dimens.Page, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(section.title, Modifier.weight(1f),
                style = LocalTextStyle.current.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold).withUserFontWeight(FontWeightRole.Title))
            section.seeAll?.let { TextButton(onClick = onSeeAll) { Text("查看全部") } }
            section.chartKind?.let { TextButton(onClick = { onChart(it) }) { Text("查看全部") } }
        }
        section.description?.let {
            Text(it, Modifier.padding(horizontal = Dimens.Page, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (section.entries.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.Page),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(section.entries, key = { index, entry -> "${entry.key}-$index" }) { index, entry ->
                    DiscoveryCard(entry, index, onClick = { onSelect(entry) })
                }
            }
        }
    }
}

@Composable
private fun DiscoveryCard(entry: DiscoveryEntry, index: Int, onClick: () -> Unit) {
    Column(
        Modifier.width(152.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            AsyncImage(
                model = entry.artwork,
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(152.dp).clip(RoundedCornerShape(8.dp)),
            )
            if (entry.rank > 0) {
                Text(
                    entry.rank.toString(),
                    Modifier.align(Alignment.TopStart).padding(8.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold).withUserFontWeight(FontWeightRole.Title),
                )
            }
        }
        Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = LocalTextStyle.current.copy(fontWeight = FontWeight.Medium).withUserFontWeight(FontWeightRole.Title))
        if (entry.subtitle.isNotBlank()) {
            Text(entry.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

private data class CategoryBrick(val target: BrowseTarget, val art: String, val color: Color?)

/** Apple SearchLandingPageIntent: real 16:9 subscriptionHero thumbnails, never generic genre placeholders. */
private fun categoryBrick(entry: DiscoveryEntry): CategoryBrick? {
    val target = entry.target ?: return null
    val resource = entry.resource ?: return null
    val attrs = resource.attributes
    val hero = attrs.obj("editorialArtwork")?.obj("subscriptionHero") ?: return null
    val slug = hero.text("url").takeIf { it.startsWith("https://") } ?: return null
    val art = slug.replace("{w}", "520").replace("{h}", "293").replace("{c}", "sr").replace("{f}", "webp")
    val title = when (resource.type) {
        "apple-curators" -> attrs.text("shortName")
        "editorial-items" -> attrs.obj("editorialNotes")?.text("name").orEmpty()
        else -> entry.title
    }.ifBlank { entry.title }.ifBlank { return null }
    val tint = hero.text("bgColor").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color((it or 0xff000000).toInt()) }
    return CategoryBrick(target.copy(title = title), art, tint)
}

@Composable
private fun CategoryBrick(brick: CategoryBrick, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )) {
        AsyncImage(brick.art, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .background(brick.color ?: MaterialTheme.colorScheme.surfaceContainerHigh), contentScale = ContentScale.Crop)
        Text(
            brick.target.title,
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class BrowseTarget(val kind: String, val id: String, val title: String, val country: String? = null)
private data class DiscoveryEntry(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val artwork: String? = null,
    val resource: AppleCatalogResource? = null,
    val target: BrowseTarget? = null,
    val rank: Int = 0,
    val country: String? = null,
)
private data class DiscoverySection(
    val key: String,
    val title: String,
    val entries: List<DiscoveryEntry> = emptyList(),
    val description: String? = null,
    val seeAll: BrowseTarget? = null,
    val chartKind: String? = null,
    val country: String? = null,
)
private data class DiscoveryModel(
    val sections: List<DiscoverySection>,
    val next: String? = null,
    val nextChartKind: String? = null,
    val genres: List<DiscoveryGenre> = emptyList(),
    val country: String? = null,
)
private data class DiscoveryGenre(val id: String, val name: String)

private suspend fun loadDiscovery(
    api: AppleCatalogApi,
    storefront: String,
    radio: Boolean,
    area: String,
    target: BrowseTarget?,
    chartKind: String?,
    genreId: String,
): DiscoveryModel {
    if (target != null) return loadEditorialTarget(api, storefront, target)
    if (radio) return loadGrouping(api.radio(storefront), "广播", storefront)
    return when (area) {
        AREA_CHARTS -> {
            if (chartKind == null) loadChartShelves(api.charts(storefront = storefront), storefront)
            else {
                val response = api.charts(genre = genreId, limit = 50, storefront = storefront)
                val country = response.requestStorefront ?: storefront
                val page = response.charts(chartKind).firstOrNull() ?: return DiscoveryModel(emptyList(), country = country)
                val genres = if (chartKind in GENRE_CHARTS) {
                    val available = try {
                        api.genres(country).data.mapNotNull { resource ->
                            resource.attributes.text("name").takeIf { it.isNotBlank() }?.let { DiscoveryGenre(resource.id, it) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                    listOf(DiscoveryGenre(ALL_GENRES, "所有类型")) + available.filterNot { it.id == ALL_GENRES }
                } else emptyList()
                DiscoveryModel(
                    sections = listOf(DiscoverySection(
                        key = "chart-$chartKind", title = page.name ?: chartLabel(chartKind),
                        entries = page.data.mapIndexed { index, resource -> resource.toEntry(index + 1, country) },
                        country = country,
                    )), next = page.next, nextChartKind = chartKind, genres = genres, country = country,
                )
            }
        }
        AREA_CATEGORIES -> loadCategories(api.categories(storefront), storefront)
        else -> loadGrouping(api.browse(storefront), "新发现", storefront)
    }
}

private fun loadGrouping(response: AppleCatalogResponse, fallbackTitle: String, requestCountry: String): DiscoveryModel {
    val grouping = response.data.firstOrNull() ?: return DiscoveryModel(emptyList(), country = response.requestStorefront ?: requestCountry)
    val country = grouping.catalogCountry(response.requestStorefront ?: requestCountry)
    val title = grouping.attributes.text("name").ifBlank { fallbackTitle }
    val tab = grouping.relationships["tabs"]?.data?.firstOrNull()
    val elements = tab?.relationships?.get("children")?.data.orEmpty()
    return DiscoveryModel(elements.mapIndexedNotNull { index, element -> editorialSection(element, index, country) }, country = country)
}

private fun editorialSection(element: AppleCatalogResource, index: Int, fallbackCountry: String?): DiscoverySection? {
    val country = element.catalogCountry(fallbackCountry)
    val attrs = element.attributes
    val kind = attrs.text("editorialElementKind")
    val sectionTitle = attrs.text("name").ifBlank { attrs.text("title") }
    val sectionKey = "editorial-$index-${element.id}"
    val entries = when (kind) {
        "316", "385" -> element.relationships["children"]?.data.orEmpty().mapNotNull { child ->
            val content = child.relationships["contents"]?.data?.firstOrNull()
            if (content != null) content.toEntry(0, country)
            else child.toEntry(fallbackCountry = country, attrs = child.attributes, target = child.attributes.obj("link")?.text("url")?.toDiscoveryTarget(), fallbackTitle = child.attributes.text("designTag").ifBlank { child.attributes.text("name") })
        }
        "326", "327", "345", "387" -> element.relationships["contents"]?.data.orEmpty().mapIndexed { i, res -> res.toEntry(i + 1, country) }
        "488" -> element.relationships["children"]?.data.orEmpty().mapNotNull { child ->
            child.relationships["contents"]?.data?.firstOrNull()?.toEntry(0, country)
        }
        else -> emptyList()
    }
    val links = when (kind) {
        "391", "405" -> (attrs["links"] as? JsonArray).orEmpty().mapNotNull { link ->
            val value = link as? JsonObject ?: return@mapNotNull null
            val label = value.text("label")
            val destination = value.text("url").toDiscoveryTarget() ?: return@mapNotNull null
            DiscoveryEntry("link-${destination.kind}-${destination.id}", label.ifBlank { destination.title }, target = destination, country = destination.country)
        }
        else -> emptyList()
    }
    val allEntries = entries + links
    val room = element.relationships["room"]?.data?.firstOrNull()?.let {
        BrowseTarget("room", it.id, it.attributes.text("title").ifBlank { it.attributes.text("name") }.ifBlank { sectionTitle }, it.catalogCountry(country))
    }?.takeIf { allEntries.size >= 6 }
    val description = if (kind == "404") attrs.text("description").plainText() else null
    if (allEntries.isEmpty() && description.isNullOrBlank()) return null
    return DiscoverySection(
        key = sectionKey,
        title = sectionTitle.ifBlank { if (kind == "488") "直播电台" else "" },
        entries = allEntries,
        description = description,
        seeAll = room,
        country = country,
    )
}

private suspend fun loadCategories(response: AppleCatalogResponse, requestCountry: String): DiscoveryModel {
    val country = response.requestStorefront ?: requestCountry
    val seen = HashSet<String>()
    val sections = response.data.mapIndexedNotNull { index, recommendation ->
        val attrs = recommendation.attributes
        val title = attrs.obj("title")?.text("stringForDisplay").orEmpty()
        val entries = recommendation.relationships["contents"]?.data.orEmpty().mapNotNull { resource ->
            if (!seen.add("${resource.type}:${resource.id}")) return@mapNotNull null
            resource.toEntry(0, recommendation.catalogCountry(country))
        }
        if (entries.isEmpty()) null else DiscoverySection("category-$index-${recommendation.id}", title.ifBlank { "为你推荐" }, entries)
    }
    return DiscoveryModel(sections, country = country)
}

private suspend fun loadEditorialTarget(api: AppleCatalogApi, storefront: String, target: BrowseTarget): DiscoveryModel {
    return when (target.kind) {
        "charts" -> loadChartShelves(api.charts(storefront = storefront), storefront)
        "grouping" -> loadGrouping(api.editorial(AppleCatalogApi.Editorial.Grouping, target.id, storefront), target.title, storefront)
        "room" -> {
            val response = api.editorial(AppleCatalogApi.Editorial.Room, target.id, storefront)
            val room = response.data.firstOrNull()
                ?: return DiscoveryModel(emptyList())
            val country = room.catalogCountry(response.requestStorefront ?: storefront)
            val contents = room.relationships["contents"]
            DiscoveryModel(listOf(DiscoverySection(
                "room-${target.id}", target.title.ifBlank { room.attributes.text("title").ifBlank { room.attributes.text("name") } },
                contents?.data.orEmpty().mapIndexed { index, resource -> resource.toEntry(index + 1, country) },
            )), contents?.next, country = country)
        }
        "multi-room" -> {
            val response = api.editorial(AppleCatalogApi.Editorial.MultiRoom, target.id, storefront)
            val multi = response.data.firstOrNull()
                ?: return DiscoveryModel(emptyList())
            val country = multi.catalogCountry(response.requestStorefront ?: storefront)
            DiscoveryModel(multi.relationships["children"]?.data.orEmpty().mapIndexedNotNull { index, element -> editorialSection(element, index, country) }, country = country)
        }
        "curator" -> {
            val response = api.curator(target.id, storefront)
            val curator = response.data.firstOrNull() ?: return DiscoveryModel(emptyList())
            val country = curator.catalogCountry(response.requestStorefront ?: storefront)
            val grouping = curator.relationships["grouping"]?.data?.firstOrNull()
            if (grouping != null) {
                val tab = grouping.relationships["tabs"]?.data?.firstOrNull()
                val sections = tab?.relationships?.get("children")?.data.orEmpty().mapIndexedNotNull { index, element -> editorialSection(element, index, country) }
                if (sections.isNotEmpty()) DiscoveryModel(sections, country = country) else curatorPlaylists(curator, target, country)
            } else curatorPlaylists(curator, target, country)
        }
        else -> DiscoveryModel(emptyList())
    }
}

private fun curatorPlaylists(curator: AppleCatalogResource, target: BrowseTarget, country: String?): DiscoveryModel {
    val page = curator.relationships["playlists"]
    return DiscoveryModel(
        listOf(DiscoverySection("curator-${target.id}", target.title.ifBlank { curator.name }, page?.data.orEmpty().map { it.toEntry(0, country) })),
        next = page?.next,
        country = country,
    )
}

private fun loadChartShelves(response: AppleCatalogResponse, requestCountry: String): DiscoveryModel {
    val country = response.requestStorefront ?: requestCountry
    val order = listOf("songs", "cityCharts", "dailyGlobalTopCharts", "playlists", "albums", "music-videos")
    val sections = order.mapNotNull { kind ->
        val page = response.charts(kind).firstOrNull() ?: return@mapNotNull null
        if (page.data.isEmpty()) return@mapNotNull null
        DiscoverySection(
            key = "chart-$kind", title = page.name ?: chartLabel(kind),
            entries = page.data.mapIndexed { index, resource -> resource.toEntry(if (kind in RANKED_CHARTS) index + 1 else 0, country) },
            chartKind = kind.takeIf { kind in RANKED_CHARTS }, country = country,
        )
    }
    return DiscoveryModel(sections, country = country)
}

private fun AppleCatalogResource.toEntry(
    rank: Int = 0,
    fallbackCountry: String? = null,
    attrs: JsonObject = attributes,
    target: BrowseTarget? = toDiscoveryTarget(fallbackCountry),
    fallbackTitle: String = "",
): DiscoveryEntry {
    val art = artworkUrl(500) ?: attrs.obj("editorialArtwork")?.let { editorial ->
        sequenceOf("subscriptionHero", "subscriptionCover", "brandLogo").mapNotNull { editorial.obj(it) }
            .firstNotNullOfOrNull { it.artUrl(500, 500) }
    } ?: attrs.obj("artwork")?.artUrl(500, 500)
    return DiscoveryEntry(
        key = "$type:$id",
        title = attrs.text("name").ifBlank { attrs.text("title") }.ifBlank { fallbackTitle }.ifBlank { name },
        subtitle = attrs.text("artistName").ifBlank { attrs.text("curatorName") }.ifBlank {
            attrs.obj("plainEditorialNotes")?.text("short")?.plainText().orEmpty()
        },
        artwork = art,
        resource = takeIf { type != "editorial-elements" },
        target = target,
        rank = rank,
        country = catalogCountry(fallbackCountry),
    )
}


private fun AppleCatalogResource.toDiscoveryTarget(fallbackCountry: String?): BrowseTarget? = when (type) {
    "groupings" -> BrowseTarget("grouping", id, name, catalogCountry(fallbackCountry))
    "rooms" -> BrowseTarget("room", id, name, catalogCountry(fallbackCountry))
    "multirooms" -> BrowseTarget("multi-room", id, name, catalogCountry(fallbackCountry))
    "apple-curators", "curators" -> BrowseTarget("curator", id, name, catalogCountry(fallbackCountry))
    "editorial-items", "activities" -> attributes.text("url").ifBlank {
        attributes.obj("link")?.text("url").orEmpty()
    }.toDiscoveryTarget()
    else -> null
}

private fun String.toDiscoveryTarget(): BrowseTarget? {
    if (isBlank()) return null
    val uri = try {
        URI(if (startsWith("/")) "https://music.apple.com$this" else this)
    } catch (_: Exception) {
        return null
    }
    if (uri.scheme != "https" || uri.host?.lowercase(Locale.ROOT) !in setOf("music.apple.com", "itunes.apple.com") || uri.userInfo != null) return null
    val parts = uri.path.split('/').filter(String::isNotBlank)
    val cc = parts.firstOrNull()?.takeIf { it.matches(Regex("[a-zA-Z]{2}")) }?.lowercase(Locale.ROOT) ?: return null
    if (cc.length != 2) return null
    val route = parts.drop(1)
    if (route.size < 2) return null
    val (kind, id) = when (route[0].lowercase(Locale.ROOT)) {
        "grouping" -> "grouping" to route.getOrNull(1)
        "room" -> "room" to route.getOrNull(1)
        "multi-room" -> "multi-room" to route.getOrNull(1)
        "curator" -> "curator" to route.lastOrNull()
        "song", "album", "artist", "playlist", "music-video", "station", "post" -> route[0].lowercase(Locale.ROOT) to route.lastOrNull()
        "new" -> if (route.getOrNull(1) == "top-charts") "charts" to "" else return null
        else -> return null
    }
    val validId = id.orEmpty()
    if (kind != "charts" && !validId.matches(Regex("[A-Za-z0-9._-]+"))) return null
    return BrowseTarget(kind, validId, when (kind) {
        "charts" -> "排行榜"
        "room" -> "专题"
        "grouping" -> "浏览"
        "multi-room" -> "专题"
        else -> "策展人"
    }, cc)
}

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.artUrl(width: Int, height: Int): String? = text("url").takeIf { it.isNotBlank() }
    ?.replace("{w}", width.toString())?.replace("{h}", height.toString())?.replace("{c}", "bb")?.replace("{f}", "jpg")
private fun String.plainText(): String = replace(Regex("<[^>]*>"), " ").replace("&amp;", "&").replace("&quot;", "\"").trim()

private fun chartLabel(kind: String): String = when (kind) {
    "songs" -> "歌曲榜"
    "cityCharts" -> "城市榜"
    "dailyGlobalTopCharts" -> "每日全球热门"
    "playlists" -> "热门歌单"
    "albums" -> "热门专辑"
    "music-videos" -> "热门音乐视频"
    else -> kind
}

private const val AREA_NEW = "new"
private const val AREA_CHARTS = "charts"
private const val AREA_CATEGORIES = "categories"
private const val AREA_RADIO = "radio"
private const val ALL_GENRES = "34"
private val GENRE_CHARTS = setOf("songs", "albums", "music-videos")
private val RANKED_CHARTS = setOf("songs", "playlists", "albums", "music-videos")
private val CATALOG_TARGETS = setOf("song", "album", "artist", "playlist", "music-video", "station", "post")
private val TRACK_PAGE_PARAMS = mapOf("include[songs]" to "artists", "relate[songs]" to "albums")
