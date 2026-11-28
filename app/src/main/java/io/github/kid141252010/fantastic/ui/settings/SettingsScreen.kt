package io.github.kid141252010.fantastic.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.BuildConfig
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.AppleLyricsOptions
import io.github.kid141252010.fantastic.data.NeteaseCapabilities
import io.github.kid141252010.fantastic.data.FontWeights
import io.github.kid141252010.fantastic.data.PlaybackCacheLimit
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.SourceStatus
import io.github.kid141252010.fantastic.data.isValidAppleLyricsTag
import io.github.kid141252010.fantastic.lyrics.LyricsController
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import io.github.kid141252010.fantastic.ui.Md3PreferenceRow
import io.github.kid141252010.fantastic.ui.Md3SectionHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: FantasticApp,
    lyrics: LyricsController,
    showLibrarySources: Boolean,
    onShowLibrarySourcesChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val prefs = app.prefs
    val diag by lyrics.diag.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf(prefs.baseUrl) }
    val savedStorefront by prefs.storefrontState.collectAsStateWithLifecycle()
    val fontWeights by prefs.fontWeightsState.collectAsStateWithLifecycle()
    val regions by app.appleRegions.state.collectAsStateWithLifecycle()
    var storefrontDraft by remember { mutableStateOf(prefs.storefront) }
    var storefrontEdited by remember { mutableStateOf(false) }
    val storefront = if (storefrontEdited) storefrontDraft else savedStorefront
    val savedServer = SourceStatus.normalizeBaseUrl(prefs.baseUrl)
    val regionEntry = regions.entries[savedServer]
    val cachedWrapper = regionEntry?.wrapperRegions
    val selectedStorefront = storefront.trim().lowercase()
    val storefrontValidationError = when {
        !selectedStorefront.matches(Regex("[a-z]{2}")) ->
            "Apple Music 地区必须是两位小写英文字母代码，例如 ca、tr 或 jp。"
        cachedWrapper != null && selectedStorefront !in cachedWrapper ->
            "地区 $selectedStorefront 不在此服务器缓存的 wrapper 支持地区中。"
        else -> null
    }
    var qqQuality by remember { mutableStateOf(prefs.qqQuality) }
    var neteaseQuality by remember { mutableStateOf(prefs.neteaseQuality) }
    var appleDownloadQuality by remember { mutableStateOf(prefs.appleQuality) }
    var appleWifiQuality by remember { mutableStateOf(prefs.appleWifiQuality) }
    var appleCellularQuality by remember { mutableStateOf(prefs.appleCellularQuality) }
    var appleSpatial by remember { mutableStateOf(prefs.appleSpatial) }
    var appleTransition by remember { mutableStateOf(prefs.appleTransition) }
    var appleCrossfade by remember { mutableStateOf(prefs.appleCrossfade) }
    var playbackCacheLimit by remember { mutableStateOf(prefs.playbackCacheLimit) }
    val initialLyricsOptions = remember { prefs.appleLyricsOptions }
    val defaultLyricsOptions = AppleLyricsOptions.DEFAULT
    var appleLyricsLanguage by remember { mutableStateOf(initialLyricsOptions.language) }
    var appleLyricsScript by remember { mutableStateOf(initialLyricsOptions.script) }
    var lyricsLanguageError by remember { mutableStateOf(false) }
    var lyricsScriptError by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var wrapperStatus by remember { mutableStateOf<SourceStatus.Wrapper?>(null) }
    var wrapperError by remember { mutableStateOf<String?>(null) }
    var checkedAddress by remember { mutableStateOf<String?>(null) }
    var neteaseCapabilities by remember { mutableStateOf<NeteaseCapabilities?>(null) }
    var neteaseError by remember { mutableStateOf<String?>(null) }
    var choicePanel by remember { mutableStateOf<SettingsChoicePanel?>(null) }
    val serverRequestBusy = checking || regions.refreshingServer != null

    fun checkServer() {
        if (serverRequestBusy) return
        val normalized = SourceStatus.normalizeBaseUrl(baseUrl)
        if (normalized == null) {
            saveError = "服务器地址无效。请输入完整的 HTTP(S) 地址，不要包含账号、查询参数或片段。"
            return
        }
        saveError = null
        wrapperStatus = null
        wrapperError = null
        neteaseCapabilities = null
        neteaseError = null
        checkedAddress = null
        checking = true
        scope.launch {
            try {
                val result = app.appleRegions.refresh(normalized)
                val status = result.wrapper.getOrNull()
                wrapperStatus = status
                if (status == null) {
                    wrapperError = result.wrapper.exceptionOrNull()?.message ?: "无法读取 /status"
                    return@launch
                }
                if (!status.serverReachable || status.httpCode !in 200..299) {
                    wrapperError = "此地址未返回有效的 Fantastic /status。"
                    return@launch
                }
                prefs.baseUrl = normalized
                checkedAddress = normalized
                sourceResult { app.netease.capabilities(normalized) }.fold(
                    onSuccess = { neteaseCapabilities = it },
                    onFailure = { neteaseError = it.message ?: "无法读取 NetEase capabilities" },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                saveError = error.message ?: "服务器检查失败，未保存设置。"
            } finally {
                checking = false
            }
        }
    }

    val panel = choicePanel
    if (panel == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "关闭设置")
                        }
                    },
                    title = { Text("设置") },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
            ) {
                item {
                    Md3SectionHeader("服务器")
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            saveError = if (SourceStatus.normalizeBaseUrl(it) == null) {
                                "服务器地址无效。请输入完整的 HTTP(S) 地址，不要包含账号、查询参数或片段。"
                            } else null
                        },
                        label = { Text("Fantastic 服务器地址") },
                        singleLine = true,
                        enabled = !checking,
                        isError = saveError != null,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        supportingText = if (baseUrl.trim().startsWith("http://", ignoreCase = true)) {
                            { Text("HTTP 是明文传输，只建议在局域网内使用。检查会验证此地址的 /status 并读取网易能力。") }
                        } else null,
                    )
                    OutlinedTextField(
                        value = storefront,
                        onValueChange = {
                            storefrontDraft = it
                            storefrontEdited = true
                            val normalized = it.trim().lowercase()
                            if (normalized.matches(Regex("[a-z]{2}")) &&
                                (cachedWrapper == null || normalized in cachedWrapper)
                            ) {
                                prefs.storefront = normalized
                            }
                        },
                        label = { Text("Apple Music 地区（默认 ca）") },
                        singleLine = true,
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        isError = storefrontValidationError != null,
                        supportingText = if (storefrontValidationError != null) {
                            { Text(storefrontValidationError) }
                        } else null,
                    )
                    regions.persistenceError?.let { StatusMessage(it, isError = true) }
                    StatusMessage("缓存的 wrapper 支持地区：${cachedWrapper?.joinToString()?.ifEmpty { "无" } ?: "未知"}")
                    regionEntry?.wrapperError?.let { StatusMessage("wrapper 地区：$it", isError = true) }
                    if (regionEntry?.wrapperError != null) {
                        StatusMessage(if (cachedWrapper != null) "刷新失败，继续使用已缓存地区" else "地区信息尚未获取", isError = true)
                        if (cachedWrapper != null) StatusMessage("wrapper 支持信息来自上次成功获取")
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = ::checkServer, enabled = !serverRequestBusy) {
                            Text(if (checking) "正在检查…" else "检查并保存服务器")
                        }
                        if (checking) CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.weight(1f))
                        Text("只访问填写的 Fantastic 地址，不发送凭据。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    saveError?.let { StatusMessage(it, isError = true) }
                    checkedAddress?.let { StatusMessage("已检查并保存服务器：$it") }
                    wrapperStatus?.let { status ->
                        StatusMessage("Fantastic ${if (status.serverReachable) "可达" else "状态未知"}（HTTP ${status.httpCode}）；Apple wrapper ${if (status.available) "可用" else "不可用"}：${status.message}")
                    }
                    wrapperError?.let {
                        StatusMessage("无法读取此 Fantastic 服务器的 /status：$it。请确认地址、网络及服务器是否允许客户端访问。", isError = true)
                    }
                    neteaseCapabilities?.let { capabilities ->
                        StatusMessage(
                            if (capabilities.enabled) "NetEase adapter：服务器已启用；能力档位：${capabilities.qualities.joinToString { it.displayName.ifBlank { it.quality } }.ifEmpty { "服务器未报告" }}"
                            else "NetEase adapter：服务器未启用。",
                            isError = !capabilities.enabled,
                        )
                    }
                    neteaseError?.let {
                        StatusMessage("无法读取 NetEase capabilities：$it。请使用包含 /ncm/capabilities 的 Fantastic 服务器版本。", isError = true)
                    }

                    Md3SectionHeader("字体字重")
                    FontWeightPreference("正文", fontWeights.body, 400) {
                        prefs.fontWeights = prefs.fontWeights.copy(body = it)
                    }
                    FontWeightPreference("标题", fontWeights.title, 600) {
                        prefs.fontWeights = prefs.fontWeights.copy(title = it)
                    }
                    FontWeightPreference("歌词", fontWeights.lyrics, 700) {
                        prefs.fontWeights = prefs.fontWeights.copy(lyrics = it)
                    }
                    Text(
                        "100–900，数值越大越粗。调整即保存并生效，重启后保留；恢复默认沿用各处原样式。静态或系统字体按可用字重匹配。",
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Md3SectionHeader("资料库")
                    Md3PreferenceRow(
                        title = "显示音源来源",
                        summary = "在资料库专辑和歌曲中显示 Apple Music、QQ 音乐或网易云音乐",
                        trailing = { Switch(checked = showLibrarySources, onCheckedChange = onShowLibrarySourcesChange) },
                    )

                    Md3SectionHeader("播放")
                    QualityPreference(SettingsChoicePanel.QQ.title, Prefs.QQ_QUALITIES.firstOrNull { it.first == qqQuality }?.second.orEmpty()) {
                        choicePanel = SettingsChoicePanel.QQ
                    }
                    QualityPreference(SettingsChoicePanel.Netease.title, Prefs.NETEASE_QUALITIES.firstOrNull { it.first == neteaseQuality }?.second.orEmpty()) {
                        choicePanel = SettingsChoicePanel.Netease
                    }
                    QualityPreference(SettingsChoicePanel.AppleWifi.title, Prefs.APPLE_PLAYBACK_QUALITIES.firstOrNull { it.first == appleWifiQuality }?.second.orEmpty()) {
                        choicePanel = SettingsChoicePanel.AppleWifi
                    }
                    QualityPreference(SettingsChoicePanel.AppleCellular.title, Prefs.APPLE_PLAYBACK_QUALITIES.firstOrNull { it.first == appleCellularQuality }?.second.orEmpty()) {
                        choicePanel = SettingsChoicePanel.AppleCellular
                    }
                    QualityPreference(SettingsChoicePanel.Spatial.title, Prefs.APPLE_SPATIALS.firstOrNull { it.first == appleSpatial }?.second.orEmpty()) {
                        choicePanel = SettingsChoicePanel.Spatial
                    }
                    QualityPreference(SettingsChoicePanel.Transition.title, Prefs.APPLE_TRANSITIONS.firstOrNull { it.first == appleTransition }?.second.orEmpty()) {
                        choicePanel = SettingsChoicePanel.Transition
                    }
                    Md3PreferenceRow(
                        title = SettingsChoicePanel.PlaybackCache.title,
                        summary = playbackCacheLimit.label,
                        onClick = { choicePanel = SettingsChoicePanel.PlaybackCache },
                    )

                    Md3SectionHeader("下载")
                    QualityPreference(SettingsChoicePanel.AppleDownload.title, Prefs.APPLE_QUALITIES.firstOrNull { it.first == appleDownloadQuality }?.second.orEmpty()) {
                        choicePanel = SettingsChoicePanel.AppleDownload
                    }

                    Md3SectionHeader("歌词")
                    OutlinedTextField(
                        value = appleLyricsLanguage,
                        onValueChange = {
                            appleLyricsLanguage = it
                            val language = it.trim().ifEmpty { defaultLyricsOptions.language }
                            lyricsLanguageError = !isValidAppleLyricsTag(language)
                            if (!lyricsLanguageError) {
                                val options = prefs.appleLyricsOptions.copy(language = language)
                                if (options != prefs.appleLyricsOptions) {
                                    prefs.appleLyricsOptions = options
                                    lyrics.onAppleLyricsOptionsChanged()
                                }
                            }
                        },
                        label = { Text("Apple Music 歌词语言") },
                        singleLine = true,
                        placeholder = { Text(defaultLyricsOptions.language) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        isError = lyricsLanguageError,
                        supportingText = if (lyricsLanguageError) {
                            { Text("请输入最多 35 个字母、数字或连字符，例如 ${defaultLyricsOptions.language}。") }
                        } else null,
                    )
                    OutlinedTextField(
                        value = appleLyricsScript,
                        onValueChange = {
                            appleLyricsScript = it
                            val script = it.trim().ifEmpty { defaultLyricsOptions.script }
                            lyricsScriptError = !isValidAppleLyricsTag(script)
                            if (!lyricsScriptError) {
                                val options = prefs.appleLyricsOptions.copy(script = script)
                                if (options != prefs.appleLyricsOptions) {
                                    prefs.appleLyricsOptions = options
                                    lyrics.onAppleLyricsOptionsChanged()
                                }
                            }
                        },
                        label = { Text("Apple Music 音译字形（script）") },
                        singleLine = true,
                        placeholder = { Text(defaultLyricsOptions.script) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        isError = lyricsScriptError,
                        supportingText = if (lyricsScriptError) {
                            { Text("请输入最多 35 个字母、数字或连字符，例如 ${defaultLyricsOptions.script}。") }
                        } else null,
                    )

                    Md3SectionHeader("诊断")
                    val report = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})\n" + diag.ifEmpty { "还没有播放过歌曲" }
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable {
                            clipboard.setText(AnnotatedString(report))
                        },
                    ) {
                        Text("歌词诊断（点按复制完整版本、GIT_SHA 和当前诊断）", Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Text(report, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Spacer(Modifier.height(LocalBarsPadding.current.calculateBottomPadding())) }
            }
        }
    } else {
        val options = when (panel) {
            SettingsChoicePanel.QQ -> Prefs.QQ_QUALITIES
            SettingsChoicePanel.Netease -> Prefs.NETEASE_QUALITIES
            SettingsChoicePanel.AppleWifi, SettingsChoicePanel.AppleCellular -> Prefs.APPLE_PLAYBACK_QUALITIES
            SettingsChoicePanel.AppleDownload -> Prefs.APPLE_QUALITIES
            SettingsChoicePanel.Spatial -> Prefs.APPLE_SPATIALS
            SettingsChoicePanel.Transition -> Prefs.APPLE_TRANSITIONS
            SettingsChoicePanel.PlaybackCache -> PlaybackCacheLimit.entries.map { it.id to it.label }
        }
        val selected = when (panel) {
            SettingsChoicePanel.QQ -> qqQuality
            SettingsChoicePanel.Netease -> neteaseQuality
            SettingsChoicePanel.AppleWifi -> appleWifiQuality
            SettingsChoicePanel.AppleCellular -> appleCellularQuality
            SettingsChoicePanel.AppleDownload -> appleDownloadQuality
            SettingsChoicePanel.Spatial -> appleSpatial
            SettingsChoicePanel.Transition -> appleTransition
            SettingsChoicePanel.PlaybackCache -> playbackCacheLimit.id
        }
        val select: (String) -> Unit = { value ->
            when (panel) {
                SettingsChoicePanel.QQ -> {
                    qqQuality = value
                    prefs.qqQuality = value
                }
                SettingsChoicePanel.Netease -> {
                    neteaseQuality = value
                    prefs.neteaseQuality = value
                }
                SettingsChoicePanel.AppleWifi -> {
                    appleWifiQuality = value
                    prefs.appleWifiQuality = value
                }
                SettingsChoicePanel.AppleCellular -> {
                    appleCellularQuality = value
                    prefs.appleCellularQuality = value
                }
                SettingsChoicePanel.AppleDownload -> {
                    appleDownloadQuality = value
                    prefs.appleQuality = value
                }
                SettingsChoicePanel.Spatial -> {
                    appleSpatial = value
                    prefs.appleSpatial = value
                }
                SettingsChoicePanel.Transition -> {
                    appleTransition = value
                    prefs.appleTransition = value
                }
                SettingsChoicePanel.PlaybackCache -> {
                    val limit = PlaybackCacheLimit.entries.first { it.id == value }
                    app.playbackCache.setLimit(limit)
                    playbackCacheLimit = limit
                }
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { choicePanel = null }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回设置")
                        }
                    },
                    title = { Text(panel.title) },
                )
            },
        ) { innerPadding ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = innerPadding) {
                item {
                    options.forEach { (id, label) ->
                        Md3PreferenceRow(
                            title = label,
                            onClick = { select(id) },
                            trailing = { RadioButton(selected = selected == id, onClick = null) },
                        )
                    }
                    if (panel == SettingsChoicePanel.PlaybackCache) {
                        Text(
                            "播放时缓存整首歌曲。空间不足时先清理最久未播放的歌曲。",
                            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (panel == SettingsChoicePanel.Transition && appleTransition == "crossfade") {
                        OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("交叉淡化时长", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TextButton(onClick = {
                                        appleCrossfade = (appleCrossfade - 1).coerceAtLeast(1)
                                        prefs.appleCrossfade = appleCrossfade
                                    }, enabled = appleCrossfade > 1) { Text("−") }
                                    Text("$appleCrossfade 秒")
                                    TextButton(onClick = {
                                        appleCrossfade = (appleCrossfade + 1).coerceAtMost(12)
                                        prefs.appleCrossfade = appleCrossfade
                                    }, enabled = appleCrossfade < 12) { Text("+") }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(LocalBarsPadding.current.calculateBottomPadding())) }
            }
        }
    }
    BackHandler(enabled = choicePanel != null) { choicePanel = null }
}

private suspend fun <T> sourceResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

@Composable
private fun FontWeightPreference(title: String, value: Int, initialWeight: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(if (value == 0) "默认" else value.toString(),
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onChange(0) }, enabled = value != 0) { Text("恢复默认") }
        }
        Slider(
            value = (if (value == 0) initialWeight else value).toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(FontWeights.MIN, FontWeights.MAX)) },
            valueRange = FontWeights.MIN.toFloat()..FontWeights.MAX.toFloat(),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${title}字重" },
        )
    }
}

@Composable
private fun StatusMessage(message: String, isError: Boolean = false) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp)) {
        Text(
            message,
            Modifier.padding(12.dp),
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private enum class SettingsChoicePanel(val title: String) {
    QQ("QQ 音乐音质"),
    Netease("网易云音质"),
    AppleWifi("Apple Music · Wi-Fi 音质"),
    AppleCellular("Apple Music · 蜂窝网络音质"),
    AppleDownload("Apple Music · 下载音质"),
    Spatial("Apple Music · 全景声偏好"),
    Transition("Apple Music · 曲目切换"),
    PlaybackCache("播放缓存上限"),
}

@Composable
private fun QualityPreference(title: String, selectedLabel: String, onExpand: () -> Unit) {
    Md3PreferenceRow(
        title = title,
        summary = selectedLabel.ifBlank { "选择偏好" },
        onClick = onExpand,
    )
}
