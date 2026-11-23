package io.github.conditionstech.sdm.library

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conditionstech.sdm.engine.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How the Music tab is browsed. */
private enum class MusicView(val label: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), FOLDERS("Folders") }

/**
 * The Music tab (M17a): every song SDM can see, as songs, albums, artists or folders, with
 * search and sorting. Playing comes in M17b; [onPlay] receives the list and the chosen song.
 */
@Composable
fun MusicScreen(library: MediaStoreLibrary, modifier: Modifier = Modifier, onPlay: (List<MediaEntry>, MediaEntry) -> Unit) {
    val all by library.songs.collectAsState()
    var view by remember { mutableStateOf(MusicView.SONGS) }
    var query by remember { mutableStateOf("") }
    var order by remember { mutableStateOf(SortOrder.TITLE) }
    var openGroup by remember { mutableStateOf<MediaGroup?>(null) }
    val found = MediaLibrary.search(all, query)

    Column(modifier.fillMaxSize()) {
        AccessBanner(library, MediaKind.AUDIO)
        SearchBox(query, "Search songs, artists, albums") { query = it }
        val group = openGroup
        if (group != null) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { openGroup = null }) { Text("‹ ${view.label}") }
                Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val entries = MediaLibrary.search(group.entries, query)
            SongList(entries) { onPlay(entries, it) }
            return@Column
        }
        ChipRow(MusicView.entries.map { it.label }, view.label) { label -> view = MusicView.entries.first { it.label == label } }
        when (view) {
            MusicView.SONGS -> {
                ChipRow(SortOrder.entries.map { it.label }, order.label) { label -> order = SortOrder.entries.first { it.label == label } }
                val sorted = MediaLibrary.sorted(found, order)
                if (sorted.isEmpty()) Empty(if (all.isEmpty()) "No songs found yet." else "Nothing matches “$query”.")
                SongList(sorted) { onPlay(sorted, it) }
            }
            MusicView.ALBUMS -> GroupList(MediaLibrary.albums(found)) { openGroup = it }
            MusicView.ARTISTS -> GroupList(MediaLibrary.artists(found)) { openGroup = it }
            MusicView.FOLDERS -> GroupList(MediaLibrary.folders(found)) { openGroup = it }
        }
    }
}

/** The Videos tab (M17a): every video SDM can see, with thumbnails, search and sorting. */
@Composable
fun VideosScreen(library: MediaStoreLibrary, modifier: Modifier = Modifier, onPlay: (MediaEntry) -> Unit) {
    val all by library.videos.collectAsState()
    var query by remember { mutableStateOf("") }
    var order by remember { mutableStateOf(SortOrder.NEWEST) }
    val shown = MediaLibrary.sorted(MediaLibrary.search(all, query), order)
    Column(modifier.fillMaxSize()) {
        AccessBanner(library, MediaKind.VIDEO)
        SearchBox(query, "Search videos and folders") { query = it }
        ChipRow(SortOrder.entries.map { it.label }, order.label) { label -> order = SortOrder.entries.first { it.label == label } }
        if (shown.isEmpty()) Empty(if (all.isEmpty()) "No videos found yet." else "Nothing matches “$query”.")
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { video ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPlay(video) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumbnail(video)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val details = listOfNotNull(
                            MediaLibrary.formatDuration(video.durationMs).ifEmpty { null },
                            if (video.height > 0) "${minOf(video.width, video.height)}p" else null,
                            if (video.sizeBytes > 0) Units.formatSize(video.sizeBytes.toDouble()) else null,
                        ).joinToString(" · ")
                        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(video.folder.trimEnd('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Shown until the user allows this tab's media; until then SDM lists its own files (or, with
 * Android 14's "selected" choice, the videos the user picked).
 */
@Composable
private fun AccessBanner(library: MediaStoreLibrary, kind: MediaKind) {
    var access by remember { mutableStateOf(library.access(kind)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = library.access(kind)
        library.refresh()
    }
    if (access == MediaStoreLibrary.Access.FULL) return
    val what = if (kind == MediaKind.AUDIO) "music" else "videos"
    Card(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            val (title, text) = if (access == MediaStoreLibrary.Access.SELECTED) {
                "Showing only the videos you picked" to
                    "You allowed SDM to see some videos. To list all of them, allow all videos."
            } else {
                "Showing only SDM's downloads" to
                    "Allow SDM to see the $what on this phone to play all of it. SDM only reads your media to list and play it."
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { ask.launch(library.permissionsFor(kind)) }) {
                Text(if (access == MediaStoreLibrary.Access.SELECTED) "Change access" else "Allow access")
            }
        }
    }
}

@Composable
private fun SearchBox(value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ChipRow(labels: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(labels) { label -> FilterChip(selected = label == selected, onClick = { onSelect(label) }, label = { Text(label) }) }
    }
}

@Composable
private fun SongList(songs: List<MediaEntry>, onClick: (MediaEntry) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(songs, key = { it.id }) { song ->
            Row(
                Modifier.fillMaxWidth().clickable { onClick(song) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${song.artistOrUnknown} · ${song.albumOrUnknown}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(MediaLibrary.formatDuration(song.durationMs), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GroupList(groups: List<MediaGroup>, onOpen: (MediaGroup) -> Unit) {
    if (groups.isEmpty()) Empty("Nothing here yet.")
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(groups, key = { it.name }) { group ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(group) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(group.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val count = group.entries.size
                Text(
                    "$count song${if (count == 1) "" else "s"} · ${MediaLibrary.formatDuration(group.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A small frame of the video, made by Android (never by opening the file in another app). */
@Composable
private fun Thumbnail(video: MediaEntry) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, video.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(Uri.parse(video.uri), Size(320, 180), null) }.getOrNull()
        }
    }
    Box(
        Modifier.size(width = 120.dp, height = 68.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.BottomEnd,
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        val length = MediaLibrary.formatDuration(video.durationMs)
        if (length.isNotEmpty()) {
            Text(
                length,
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.background(androidx.compose.ui.graphics.Color(0x99000000)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
