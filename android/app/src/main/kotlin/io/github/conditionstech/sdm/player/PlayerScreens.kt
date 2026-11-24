package io.github.conditionstech.sdm.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.conditionstech.sdm.R
import io.github.conditionstech.sdm.library.AddToPlaylistDialog
import io.github.conditionstech.sdm.library.MediaLibrary
import io.github.conditionstech.sdm.library.Playlists
import io.github.conditionstech.sdm.ui.Artwork
import io.github.conditionstech.sdm.ui.SdmColors
import io.github.conditionstech.sdm.ui.rememberArtwork
import kotlinx.coroutines.delay

/** The bar above the tabs while music is loaded: cover, title, play/pause, next, a thin progress line. */
@Composable
fun MiniPlayer(player: MusicPlayer, onOpen: () -> Unit) {
    val now by player.nowPlaying.collectAsState()
    if (!now.hasMedia) return
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(now.index, now.isPlaying) {
        while (true) {
            progress = if (now.durationMs > 0) (player.positionMs().toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp)).background(SdmColors.Raised).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(now.uri, 44.dp, corner = 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(now.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = SdmColors.Muted)
            }
            IconButton(onClick = player::toggle) {
                Icon(painterResource(if (now.isPlaying) R.drawable.ic_pause else R.drawable.ic_play), if (now.isPlaying) "Pause" else "Play")
            }
            IconButton(onClick = player::next) { Icon(painterResource(R.drawable.ic_next), "Next") }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(SdmColors.Line)) {
            Box(Modifier.fillMaxWidth(progress).height(2.dp).background(SdmColors.Accent))
        }
    }
}

/**
 * The full player: the cover large, the same cover blurred behind the whole screen, a gradient
 * seek bar, big controls, shuffle/repeat, the sleep timer and the queue.
 */
@Composable
fun NowPlayingScreen(player: MusicPlayer, timer: SleepTimer, playlists: Playlists, onClose: () -> Unit) {
    val now by player.nowPlaying.collectAsState()
    val sleep by timer.state.collectAsState()
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var timerLeft by remember { mutableStateOf<Long?>(null) }
    var showTimer by remember { mutableStateOf(false) }
    var addToPlaylist by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) { // the seek bar and the countdown move a few times a second
            position = player.positionMs()
            timerLeft = timer.remainingMs()
            delay(300)
        }
    }
    val art = rememberArtwork(now.uri, 720)
    // A Surface sets the light text and icon colour for everything on this screen (without it,
    // titles and icons came out black on the dark background, seen on the owner's phone).
    Surface(Modifier.fillMaxSize(), color = SdmColors.Background, contentColor = SdmColors.Text) {
    Box(Modifier.fillMaxSize()) {
        // The cover, blurred and darkened, fills the screen behind everything (Android 12+ blurs).
        art?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(60.dp).alpha(0.55f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, SdmColors.Background.copy(alpha = 0.6f), SdmColors.Background)),
            ),
        )
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_chevron_down), "Close") }
                Spacer(Modifier.weight(1f))
                Pill(onClick = { showTimer = true }) {
                    Icon(painterResource(R.drawable.ic_timer), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(sleepLabel(sleep, timerLeft), style = MaterialTheme.typography.labelLarge)
                }
            }
            if (showQueue) {
                Text("Up next", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(now.queue) { index, title ->
                        val current = index == now.index
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (current) SdmColors.Raised else Color.Transparent)
                                .clickable { player.jumpTo(index) }.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${index + 1}", color = SdmColors.Muted, modifier = Modifier.width(32.dp))
                            Text(
                                title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                color = if (current) SdmColors.Sky else SdmColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(0.4f))
                Artwork(now.uri, 0.dp, Modifier.fillMaxWidth().aspectRatio(1f), corner = 28.dp, pixels = 720)
                Spacer(Modifier.weight(0.4f))
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(now.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(now.artist, style = MaterialTheme.typography.titleMedium, color = SdmColors.Muted, maxLines = 1)
                }
                if (now.mediaId != null) {
                    IconButton(onClick = { addToPlaylist = true }) { Icon(painterResource(R.drawable.ic_playlist_add), "Add to playlist") }
                }
            }
            Spacer(Modifier.height(16.dp))
            val duration = now.durationMs.coerceAtLeast(1)
            GradientSeekBar(
                fraction = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                onDrag = { dragging = it },
                onRelease = {
                    dragging?.let { player.seekTo((it * duration).toLong()) }
                    dragging = null
                },
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(MediaLibrary.formatDuration(dragging?.let { (it * duration).toLong() } ?: position).ifEmpty { "0:00" },
                    style = MaterialTheme.typography.labelMedium, color = SdmColors.Muted)
                Spacer(Modifier.weight(1f))
                Text(MediaLibrary.formatDuration(now.durationMs), style = MaterialTheme.typography.labelMedium, color = SdmColors.Muted)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Toggle(R.drawable.ic_shuffle, "Shuffle", now.shuffle, player::toggleShuffle)
                IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_previous), "Previous", Modifier.size(34.dp))
                }
                Box(
                    Modifier.size(78.dp).clip(CircleShape).background(SdmColors.Accent).clickable(onClick = player::toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(if (now.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        if (now.isPlaying) "Pause" else "Play", Modifier.size(40.dp), tint = Color.White)
                }
                IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_next), "Next", Modifier.size(34.dp))
                }
                Toggle(
                    if (now.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                    when (now.repeatMode) { Player.REPEAT_MODE_ONE -> "Repeat one"; Player.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat off" },
                    now.repeatMode != Player.REPEAT_MODE_OFF,
                    player::cycleRepeat,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Pill(onClick = { showQueue = !showQueue }) {
                    Icon(painterResource(R.drawable.ic_queue), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (showQueue) "Show cover" else "Up next · ${now.queue.size}", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    }
    if (showTimer) SleepTimerDialog(timer, sleep) { showTimer = false }
    if (addToPlaylist) now.mediaId?.let { AddToPlaylistDialog(playlists, listOf(it)) { addToPlaylist = false } }
}

/** A thin rounded track with the gradient fill and a white knob; drag or tap anywhere to seek. */
@Composable
private fun GradientSeekBar(fraction: Float, onDrag: (Float) -> Unit, onRelease: () -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier.fillMaxWidth().height(28.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDrag((it.x / width).coerceIn(0f, 1f)); onRelease() })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { onDrag((it.x / width).coerceIn(0f, 1f)) },
                    onHorizontalDrag = { change, _ -> onDrag((change.position.x / width).coerceIn(0f, 1f)) },
                    onDragEnd = onRelease,
                    onDragCancel = onRelease,
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(SdmColors.Line))
        Box(Modifier.fillMaxWidth(fraction).height(5.dp).clip(CircleShape).background(SdmColors.Accent))
        Box(
            Modifier.offset { IntOffset((fraction * width).toInt() - 8.dp.roundToPx(), 0) }
                .size(16.dp).clip(CircleShape).background(Color.White),
        )
    }
}

@Composable
private fun Pill(onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Toggle(icon: Int, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), label, tint = if (on) SdmColors.Sky else SdmColors.Muted)
    }
}

internal fun sleepLabel(state: SleepState, leftMs: Long?): String = when (state) {
    SleepState.Off -> "Sleep timer"
    SleepState.EndOfSong -> "Stops after this song"
    is SleepState.Counting -> "Stops in " + MediaLibrary.formatDuration(((leftMs ?: 0) + 999) / 1000 * 1000).ifEmpty { "0:00" }
}

/** Sleep timer choices: presets, a custom number of minutes, the end of this song, or off. */
@Composable
private fun SleepTimerDialog(timer: SleepTimer, state: SleepState, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                for (minutes in SleepTimer.PRESETS) {
                    TextButton(onClick = { timer.start(minutes); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text("$minutes minutes", modifier = Modifier.fillMaxWidth())
                    }
                }
                TextButton(onClick = { timer.endOfSong(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("At the end of this song", modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it.filter(Char::isDigit).take(4); error = null },
                        label = { Text("Custom minutes") },
                        isError = error != null,
                        supportingText = { error?.let { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val minutes = custom.toIntOrNull()
                        if (minutes == null || minutes !in 1..SleepTimer.MAX_MINUTES) {
                            error = "1 to ${SleepTimer.MAX_MINUTES} minutes"
                        } else {
                            timer.start(minutes)
                            onDismiss()
                        }
                    }) { Text("Start") }
                }
            }
        },
        confirmButton = {
            if (state != SleepState.Off) TextButton(onClick = { timer.cancel(); onDismiss() }) { Text("Turn off") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
