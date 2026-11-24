package io.github.conditionstech.sdm.player

import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import io.github.conditionstech.sdm.ui.SdmColors
import kotlinx.coroutines.launch
import java.util.Locale

/** What the subtitle screens can ask the player activity to do (M18). */
class SubtitleActions(
    val pickFile: () -> Unit,
    val hasSaved: () -> Boolean,
    val shift: (Long) -> Unit,
    val removeSaved: () -> Unit,
    val canSearch: () -> Boolean,
    val search: suspend () -> List<SubtitleResult>,
    val fetch: suspend (SubtitleResult) -> Unit,
    val status: MutableState<String?>,
)

/** One subtitle track the player can show: inside the file, or SDM's saved English. */
private data class TextTrack(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

@OptIn(UnstableApi::class)
private fun textTracks(tracks: Tracks): List<TextTrack> = tracks.groups
    .filter { it.type == C.TRACK_TYPE_TEXT }
    .flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
            val format = group.getTrackFormat(index)
            val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
                ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } }
            TextTrack(group, index, format.label ?: language ?: "Subtitles ${index + 1}", group.isTrackSelected(index))
        }
    }

/** The subtitle menu: tracks, Off, a file, online search, and moving SDM's saved subtitles in time. */
@Composable
fun SubtitleMenu(tracks: Tracks, player: Player, subtitles: SubtitleActions, onOnline: () -> Unit, onDismiss: () -> Unit) {
    val list = textTracks(tracks)
    val anySelected = list.any { it.selected }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtitles") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MenuRow("Off", selected = !anySelected) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    onDismiss()
                }
                list.forEach { track ->
                    MenuRow(track.label, selected = track.selected) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.index))
                            .build()
                        onDismiss()
                    }
                }
                if (list.isEmpty()) {
                    Text(
                        "This video has no subtitles of its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SdmColors.Muted,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                MenuRow("Find English subtitles online") { onOnline() }
                MenuRow("Load a subtitle file (.srt, .vtt)") { subtitles.pickFile(); onDismiss() }
                if (subtitles.hasSaved()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Saved subtitles out of sync?", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { subtitles.shift(-500) }) { Text("0.5 s earlier") }
                        TextButton(onClick = { subtitles.shift(500) }) { Text("0.5 s later") }
                    }
                    TextButton(onClick = { subtitles.removeSaved(); onDismiss() }) { Text("Delete saved subtitles") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun MenuRow(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) SdmColors.Sky else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    )
}

/** M18c: searches OpenSubtitles for English subtitles; tapping one downloads and shows it. */
@Composable
fun OnlineSubtitlesDialog(subtitles: SubtitleActions, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<SubtitleResult>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (!subtitles.canSearch()) {
            error = "Add your free OpenSubtitles API key in Settings (Downloads tab) first."
            busy = false
            return@LaunchedEffect
        }
        try {
            results = subtitles.search()
        } catch (e: SubtitleException) {
            error = e.message
        } finally {
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("English subtitles online") },
        text = {
            Column {
                when {
                    busy -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    results.isNullOrEmpty() -> Text("No English subtitles were found for this video.")
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(results!!, key = { it.fileId }) { result ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    busy = true
                                    error = null
                                    scope.launch {
                                        try {
                                            subtitles.fetch(result)
                                            onDismiss()
                                        } catch (e: SubtitleException) {
                                            error = e.message
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }.padding(vertical = 10.dp),
                            ) {
                                Text(result.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        if (result.exactMatch) "Exact match" else null,
                                        if (result.machineTranslated) "machine translated" else null,
                                        "${result.downloads} downloads",
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (result.exactMatch) SdmColors.Sky else SdmColors.Muted,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") } },
    )
}
