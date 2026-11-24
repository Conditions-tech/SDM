package io.github.conditionstech.sdm.library

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import io.github.conditionstech.sdm.ui.Artwork
import io.github.conditionstech.sdm.ui.SdmColors
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
private enum class MusicView(val label: String) {
    SONGS("Songs"), PLAYLISTS("Playlists"), ALBUMS("Albums"), ARTISTS("Artists"), FOLDERS("Folders")
}

/**
 * The Music tab (M17a): every song SDM can see, as songs, albums, artists or folders, with
 * search and sorting. Playing comes in M17b; [onPlay] receives the list and the chosen song.
 */
@Composable
fun MusicScreen(
    library: MediaStoreLibrary,
    playlists: Playlists,
    modifier: Modifier = Modifier,
    onPlay: (List<MediaEntry>, MediaEntry) -> Unit,
) {
    val all by library.songs.collectAsState()
    val lists by playlists.all.collectAsState()
    var openPlaylist by remember { mutableStateOf<Long?>(null) }
    var adding by remember { mutableStateOf<List<Long>?>(null) } // songs to add to a playlist
    adding?.let { AddToPlaylistDialog(playlists, it) { adding = null } }
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
            SongList(entries, onLongPress = { adding = listOf(it.id) }) { onPlay(entries, it) }
            return@Column
        }
        val playlist = openPlaylist?.let { id -> lists.firstOrNull { it.id == id } }
        if (playlist != null) {
            PlaylistDetail(playlist, playlists, playlists.songs(playlist, all), onBack = { openPlaylist = null }, onPlay = onPlay)
            return@Column
        }
        ChipRow(MusicView.entries.map { it.label }, view.label) { label -> view = MusicView.entries.first { it.label == label } }
        when (view) {
            MusicView.SONGS -> {
                ChipRow(SortOrder.entries.map { it.label }, order.label) { label -> order = SortOrder.entries.first { it.label == label } }
                val sorted = MediaLibrary.sorted(found, order)
                if (sorted.isEmpty()) Empty(if (all.isEmpty()) "No songs found yet." else "Nothing matches “$query”.")
                SongList(sorted, onLongPress = { adding = listOf(it.id) }) { onPlay(sorted, it) }
            }
            MusicView.PLAYLISTS -> PlaylistList(lists, playlists) { openPlaylist = it.id }
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
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint) },
        singleLine = true,
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = SdmColors.Raised,
            unfocusedContainerColor = SdmColors.Raised,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ChipRow(labels: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(labels) { label -> FilterChip(selected = label == selected, onClick = { onSelect(label) }, label = { Text(label) }) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongList(
    songs: List<MediaEntry>,
    onLongPress: ((MediaEntry) -> Unit)? = null,
    trailing: (@Composable (MediaEntry) -> Unit)? = null,
    onClick: (MediaEntry) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(songs, key = { it.id }) { song ->
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = { onClick(song) }, onLongClick = onLongPress?.let { { it(song) } })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(song.uri, 52.dp, corner = 12.dp, pixels = 160)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${song.artistOrUnknown} · ${song.albumOrUnknown}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(MediaLibrary.formatDuration(song.durationMs), style = MaterialTheme.typography.bodySmall)
                trailing?.invoke(song)
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

/** The Playlists view: a "New playlist" button and every playlist with its song count. */
@Composable
private fun PlaylistList(lists: List<Playlist>, playlists: Playlists, onOpen: (Playlist) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    if (naming) NameDialog("New playlist", "", { playlists.create(it) }) { naming = false }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            TextButton(onClick = { naming = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("+ New playlist") }
        }
        if (lists.isEmpty()) item { Empty("No playlists yet. Create one, then long-press a song to add it.") }
        items(lists, key = { it.id }) { playlist ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(playlist) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val count = playlist.songIds.size
                Text("$count song${if (count == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One playlist: play it, rename or delete it, and remove songs. */
@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    playlists: Playlists,
    songs: List<MediaEntry>,
    onBack: () -> Unit,
    onPlay: (List<MediaEntry>, MediaEntry) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (renaming) NameDialog("Rename playlist", playlist.name, { playlists.rename(playlist.id, it) }) { renaming = false }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${playlist.name}”?") },
            text = { Text("Only the playlist is deleted; the songs stay on your phone.") },
            confirmButton = { TextButton(onClick = { playlists.delete(playlist.id); deleting = false; onBack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Playlists") }
        Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    Row(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { songs.firstOrNull()?.let { onPlay(songs, it) } }, enabled = songs.isNotEmpty()) { Text("Play all") }
        TextButton(onClick = { renaming = true }) { Text("Rename") }
        TextButton(onClick = { deleting = true }) { Text("Delete") }
    }
    if (songs.isEmpty()) Empty("This playlist is empty. Long-press a song in Songs to add it.")
    SongList(
        songs,
        trailing = { song -> TextButton(onClick = { playlists.remove(playlist.id, song.id) }) { Text("Remove") } },
    ) { onPlay(songs, it) }
}

/** Chooses a playlist for [songIds], or makes a new one for them. Also used by Now Playing. */
@Composable
fun AddToPlaylistDialog(playlists: Playlists, songIds: List<Long>, onDone: () -> Unit) {
    val lists by playlists.all.collectAsState()
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameDialog("New playlist", "", { playlists.add(playlists.create(it), songIds) }) {
            naming = false
            onDone()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn {
                items(lists, key = { it.id }) { playlist ->
                    TextButton(onClick = { playlists.add(playlist.id, songIds); onDone() }, modifier = Modifier.fillMaxWidth()) {
                        Text(playlist.name, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item {
                    TextButton(onClick = { naming = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("+ New playlist", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
    )
}

/** Asks for a playlist name; [save] throws PlaylistException with a message to show. */
@Composable
private fun NameDialog(title: String, initial: String, save: (String) -> Unit, onDone: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("Name") },
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    save(name)
                    onDone()
                } catch (e: PlaylistException) {
                    error = e.message
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
