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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.BuildConfig
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.AppleLyricsOptions
import io.github.kid141252010.fantastic.data.AppleStorefront
import io.github.kid141252010.fantastic.data.NeteaseCapabilities
import io.github.kid141252010.fantastic.data.PlaybackCacheLimit
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.SourceStatus
import io.github.kid141252010.fantastic.data.isValidAppleLyricsTag
import io.github.kid141252010.fantastic.lyrics.LyricsController
import io.github.kid141252010.fantastic.ui.LocalBarsPadding
import io.github.kid141252010.fantastic.ui.Md3PreferenceRow
import io.github.kid141252010.fantastic.ui.Md3SectionHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: FantasticApp, lyrics: LyricsController, onClose: () -> Unit) {
    val prefs = app.prefs
    val diag by lyrics.diag.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val initialBaseUrl = remember { prefs.baseUrl }
    var baseUrl by remember { mutableStateOf(prefs.baseUrl) }
    val initialStorefront = remember { prefs.storefront }
    var storefront by remember { mutableStateOf(prefs.storefront) }
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
    var appleStorefronts by remember { mutableStateOf<List<AppleStorefront>>(emptyList()) }
    var storefrontServer by remember { mutableStateOf<String?>(null) }
    var storefrontError by remember { mutableStateOf<String?>(null) }
    var storefrontNotice by remember { mutableStateOf<String?>(null) }
    var neteaseCapabilities by remember { mutableStateOf<NeteaseCapabilities?>(null) }
    var neteaseError by remember { mutableStateOf<String?>(null) }
    var choicePanel by remember { mutableStateOf<SettingsChoicePanel?>(null) }

    fun persistPlaybackSettings() {
        prefs.qqQuality = qqQuality
        prefs.neteaseQuality = neteaseQuality
        prefs.appleQuality = appleDownloadQuality
        prefs.appleWifiQuality = appleWifiQuality
        prefs.appleCellularQuality = appleCellularQuality
        prefs.appleSpatial = appleSpatial
        prefs.appleTransition = appleTransition
        prefs.appleCrossfade = appleCrossfade
    }

    fun closeSettings() {
        val language = appleLyricsLanguage.trim().ifEmpty { defaultLyricsOptions.language }
        val script = appleLyricsScript.trim().ifEmpty { defaultLyricsOptions.script }
        lyricsLanguageError = !isValidAppleLyricsTag(language)
        lyricsScriptError = !isValidAppleLyricsTag(script)
        if (lyricsLanguageError || lyricsScriptError) return

        val normalized = SourceStatus.normalizeBaseUrl(baseUrl)
        if (normalized == null) {
            saveError = "服务器地址无效。请输入完整的 HTTP(S) 地址，不要包含账号、查询参数或片段。"
            return
        }
        val original = SourceStatus.normalizeBaseUrl(initialBaseUrl)
        val selectedStorefront = storefront.trim().lowercase()
        if (!selectedStorefront.matches(Regex("[a-z]{2}"))) {
            saveError = "Apple Music 店面必须是两位英文字母代码，例如 us、tr 或 jp。"
            return
        }
        val sourceChanged = normalized != original || selectedStorefront != initialStorefront
        val storefrontWasVerified = storefrontServer == normalized && appleStorefronts.any { it.id == selectedStorefront }
        if (sourceChanged && !storefrontWasVerified) {
            saveError = "请先检查服务器；只保存该 Fantastic 服务器实际提供的 Apple Music 店面。"
            return
        }
        if (normalized != original &&
            (checkedAddress != normalized || wrapperStatus?.serverReachable != true)
        ) {
            saveError = "请先检查新服务器并确认其返回有效的 Fantastic /status。"
            return
        }
        prefs.baseUrl = normalized
        if (storefrontWasVerified) prefs.storefront = selectedStorefront
        persistPlaybackSettings()
        val lyricsOptions = AppleLyricsOptions(language, script)
        if (lyricsOptions != prefs.appleLyricsOptions) {
            prefs.appleLyricsOptions = lyricsOptions
            lyrics.onAppleLyricsOptionsChanged()
        }
        onClose()
    }

    fun checkServer() {
        val normalized = SourceStatus.normalizeBaseUrl(baseUrl)
        if (normalized == null) {
            saveError = "服务器地址无效。请输入完整的 HTTP(S) 地址，不要包含账号、查询参数或片段。"
            return
        }
        val previousStorefront = prefs.storefront
        val requestedStorefront = storefront.trim().lowercase()
        if (!requestedStorefront.matches(Regex("[a-z]{2}"))) {
            saveError = "Apple Music 店面必须是两位英文字母代码，例如 us、tr 或 jp。"
            return
        }

        saveError = null
        storefrontNotice = null
        wrapperStatus = null
        wrapperError = null
        storefrontError = null
        neteaseCapabilities = null
        neteaseError = null
        checkedAddress = normalized
        checking = true
        val previousBaseUrl = prefs.baseUrl
        var serverAccepted = false

        scope.launch {
            try {
                val statusResult = sourceResult { SourceStatus.wrapper(app.api.client, normalized) }
                if (statusResult.isFailure) {
                    wrapperError = statusResult.exceptionOrNull()?.message ?: "无法读取 /status"
                    return@launch
                }
                val status = statusResult.getOrThrow()
                wrapperStatus = status
                if (!status.serverReachable) {
                    wrapperError = "此地址未返回有效的 Fantastic /status。"
                    return@launch
                }

                prefs.baseUrl = normalized
                coroutineScope {
                    val storefrontRequest = async { sourceResult { app.apple.storefronts() } }
                    val neteaseRequest = async { sourceResult { app.netease.capabilities() } }

                    val storefrontResult = storefrontRequest.await()
                    storefrontResult.fold(
                        onSuccess = { available ->
                            val selected = available.firstOrNull { it.id == requestedStorefront }
                            val fallback = selected ?: available.firstOrNull { it.id == previousStorefront } ?: available.firstOrNull()
                            if (fallback == null) {
                                storefrontError = "服务器未返回可用 Apple Music 店面。"
                            } else {
                                appleStorefronts = available
                                storefrontServer = normalized
                                prefs.storefront = fallback.id
                                storefront = fallback.id
                                serverAccepted = true
                                if (selected == null) {
                                    storefrontNotice = "店面 $requestedStorefront 不在此服务器的可用列表中；已改用 ${fallback.id}（${fallback.name}）。"
                                }
                            }
                        },
                        onFailure = { storefrontError = it.message ?: "无法读取 Apple Music 店面列表" },
                    )
                    neteaseRequest.await().fold(
                        onSuccess = { neteaseCapabilities = it },
                        onFailure = { neteaseError = it.message ?: "无法读取 NetEase capabilities" },
                    )
                }
                if (!serverAccepted) prefs.baseUrl = previousBaseUrl
            } catch (e: CancellationException) {
                if (!serverAccepted) prefs.baseUrl = previousBaseUrl
                throw e
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
                    title = { Text("设置") },
                    actions = { TextButton(onClick = ::closeSettings) { Text("完成") } },
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
                        onValueChange = { baseUrl = it; saveError = null },
                        label = { Text("Fantastic 服务器地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        supportingText = if (baseUrl.trim().startsWith("http://", ignoreCase = true)) {
                            { Text("HTTP 是明文传输，只建议在局域网内使用。状态检查只请求此地址的 /status。") }
                        } else null,
                    )
                    OutlinedTextField(
                        value = storefront,
                        onValueChange = { storefront = it; saveError = null },
                        label = { Text("Apple Music 店面（如 us、tr、jp）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = ::checkServer, enabled = !checking) {
                            Text(if (checking) "正在检查…" else "检查并保存服务器")
                        }
                        if (checking) CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.weight(1f))
                        Text("只访问填写的 Fantastic 地址，不发送凭据。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    saveError?.let { StatusMessage(it, isError = true) }
                    checkedAddress?.let { StatusMessage("检查地址：$it") }
                    wrapperStatus?.let { status ->
                        StatusMessage("Fantastic ${if (status.serverReachable) "可达" else "状态未知"}（HTTP ${status.httpCode}）；Apple wrapper ${if (status.available) "可用" else "不可用"}：${status.message}")
                        StatusMessage("wrapper 报告的店面地区：${status.regions.joinToString().ifEmpty { "服务器未报告地区" }}")
                    }
                    wrapperError?.let {
                        StatusMessage("无法读取此 Fantastic 服务器的 /status：$it。请确认地址、网络及服务器是否允许客户端访问。", isError = true)
                    }
                    if (storefrontServer != null) {
                        StatusMessage("Apple Music 店面 (${appleStorefronts.size})：${appleStorefronts.joinToString { "${it.id} ${it.name}" }}")
                    }
                    storefrontError?.let {
                        StatusMessage("无法验证 Apple 店面：$it。请确认该服务器提供 /amp/v1/storefronts。", isError = true)
                    }
                    storefrontNotice?.let { StatusMessage(it, isError = true) }
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
                    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(
                            "NetEase 登录态（如需）仅由服务器管理员通过 FANTASTIC_NCM_COOKIE 或 --ncm-cookie 配置；此应用不收集或发送 Cookie。",
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

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
                        onValueChange = { appleLyricsLanguage = it; lyricsLanguageError = false },
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
                        onValueChange = { appleLyricsScript = it; lyricsScriptError = false },
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
                SettingsChoicePanel.QQ -> qqQuality = value
                SettingsChoicePanel.Netease -> neteaseQuality = value
                SettingsChoicePanel.AppleWifi -> appleWifiQuality = value
                SettingsChoicePanel.AppleCellular -> appleCellularQuality = value
                SettingsChoicePanel.AppleDownload -> appleDownloadQuality = value
                SettingsChoicePanel.Spatial -> appleSpatial = value
                SettingsChoicePanel.Transition -> appleTransition = value
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
                                    TextButton(onClick = { appleCrossfade = (appleCrossfade - 1).coerceAtLeast(1) }, enabled = appleCrossfade > 1) { Text("−") }
                                    Text("$appleCrossfade 秒")
                                    TextButton(onClick = { appleCrossfade = (appleCrossfade + 1).coerceAtMost(12) }, enabled = appleCrossfade < 12) { Text("+") }
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
