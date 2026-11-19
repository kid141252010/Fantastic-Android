package io.github.kid141252010.fantastic.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.ui.LargeTitle
import io.github.kid141252010.fantastic.ui.SongRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(app: FantasticApp) {
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(Source.QQ) }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val player by app.player.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(query, source) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList(); error = null; loading = false
            return@LaunchedEffect
        }
        delay(350) // 输入防抖
        loading = true
        error = null
        try {
            results = when (source) {
                Source.QQ -> app.api.qqSearch(q)
                Source.Apple -> app.api.amSearch(q)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            results = emptyList()
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        LargeTitle("搜索", Modifier.padding(top = 8.dp))
        // 搜索框
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.padding(start = 6.dp).weight(1f)) {
                if (query.isEmpty()) {
                    Text("艺人、歌曲、歌词及更多", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        SingleChoiceSegmentedButtonRow(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
        ) {
            val options = listOf(Source.Apple to "Apple Music", Source.QQ to "QQ 音乐")
            options.forEachIndexed { i, (s, label) ->
                SegmentedButton(
                    selected = source == s,
                    onClick = { source = s },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                ) { Text(label, fontSize = 13.sp) }
            }
        }
        when {
            loading && results.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            error != null -> Text(
                "搜索失败：$error",
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(results, key = { _, t -> "${t.source}:${t.id}" }) { i, t ->
                    SongRow(t, playing = player.mediaId == "qq:${t.id}") {
                        if (t.source == Source.Apple) {
                            Toast.makeText(context, "Apple Music 播放会在后续阶段接入", Toast.LENGTH_SHORT).show()
                        } else {
                            app.player.playQueue(results, i)
                        }
                    }
                }
            }
        }
    }
}
