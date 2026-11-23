package io.github.kid141252010.fantastic.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kid141252010.fantastic.FantasticApp
import io.github.kid141252010.fantastic.data.CatalogHit
import io.github.kid141252010.fantastic.data.SongCreditSection
import io.github.kid141252010.fantastic.data.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

private sealed interface SongCreditsState {
    data object Loading : SongCreditsState
    data class Loaded(val sections: List<SongCreditSection>) : SongCreditsState
    data object Failed : SongCreditsState
}

@Composable
internal fun SongCreditsDialog(app: FantasticApp, hit: CatalogHit, onDismiss: () -> Unit) {
    val credits by produceState<SongCreditsState>(SongCreditsState.Loading, hit.source, hit.id) {
        try {
            val sections = when (hit.source) {
                Source.Apple -> app.apple.songCredits(hit.id)
                Source.QQ -> app.qq.songCredits(hit.id)
                Source.Netease -> app.netease.songCredits(hit.id)
            }
            ensureActive()
            value = SongCreditsState.Loaded(sections)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            ensureActive()
            Log.w("FantasticCatalog", "Song credits failed for ${hit.source}/${hit.id}: ${error.javaClass.simpleName}")
            value = SongCreditsState.Failed
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("歌曲制作信息") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hit.title.isNotBlank()) Text(hit.title, style = MaterialTheme.typography.titleMedium)
                when (val state = credits) {
                    SongCreditsState.Loading -> Text("正在加载制作信息…")
                    SongCreditsState.Failed -> Text("暂时无法加载制作信息")
                    is SongCreditsState.Loaded -> {
                        if (state.sections.isEmpty()) Text("暂无歌曲制作信息")
                        for (section in state.sections) {
                            if (!section.title.isNullOrBlank()) {
                                Text(
                                    section.title,
                                    Modifier.semantics { heading() },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            for (entry in section.entries) {
                                Column {
                                    Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                    if (entry.roles.isNotEmpty()) {
                                        Text(
                                            entry.roles.joinToString(", "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
