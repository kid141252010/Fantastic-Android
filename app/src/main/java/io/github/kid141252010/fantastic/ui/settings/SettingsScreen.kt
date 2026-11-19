package io.github.kid141252010.fantastic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.BuildConfig
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.lyrics.LyricsController

@Composable
fun SettingsScreen(prefs: Prefs, lyrics: LyricsController, onClose: () -> Unit) {
    val diag by lyrics.diag.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var baseUrl by remember { mutableStateOf(prefs.baseUrl) }
    var storefront by remember { mutableStateOf(prefs.storefront) }
    var quality by remember { mutableStateOf(prefs.qqQuality) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TextButton(onClick = {
                prefs.baseUrl = baseUrl.ifBlank { Prefs.DEFAULT_BASE_URL }
                prefs.storefront = storefront.ifBlank { "us" }
                prefs.qqQuality = quality
                onClose()
            }) { Text("完成", fontWeight = FontWeight.SemiBold) }
        }
        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it },
            label = { Text("Fantastic 服务器地址") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            supportingText = if (baseUrl.trim().startsWith("http://", ignoreCase = true)) {
                { Text("HTTP 是明文传输，只建议在局域网里用") }
            } else null,
        )
        OutlinedTextField(
            value = storefront, onValueChange = { storefront = it },
            label = { Text("Apple Music 店面（如 us、tr、jp）") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Text(
            "QQ 音乐音质（不可用时自动降级）",
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        Prefs.QQ_QUALITIES.forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { quality = id }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = quality == id, onClick = { quality = id })
                Text(label)
            }
        }
        Text(
            "歌词诊断（歌词不显示时点按复制发给开发者）",
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        val report = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})\n" + diag.ifEmpty { "还没有播放过歌曲" }
        Text(
            report,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { clipboard.setText(AnnotatedString(report)) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
