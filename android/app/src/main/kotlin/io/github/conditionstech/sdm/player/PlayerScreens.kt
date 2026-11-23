package io.github.conditionstech.sdm.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.conditionstech.sdm.R
import io.github.conditionstech.sdm.library.MediaLibrary
import kotlinx.coroutines.delay

/** The bar above the tabs while music is loaded (M17b): tap it for the full player. */
@Composable
fun MiniPlayer(player: MusicPlayer, onOpen: () -> Unit) {
    val now by player.nowPlaying.collectAsState()
    if (!now.hasMedia) return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(now.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = player::toggle) {
                Icon(painterResource(if (now.isPlaying) R.drawable.ic_pause else R.drawable.ic_play), if (now.isPlaying) "Pause" else "Play")
            }
            IconButton(onClick = player::next) { Icon(painterResource(R.drawable.ic_next), "Next") }
        }
    }
}

/** The full player (M17b): seek bar, controls, shuffle, repeat, sleep timer and the queue. */
@Composable
fun NowPlayingScreen(player: MusicPlayer, timer: SleepTimer, onClose: () -> Unit) {
    val now by player.nowPlaying.collectAsState()
    val sleep by timer.state.collectAsState()
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var timerLeft by remember { mutableStateOf<Long?>(null) }
    var showTimer by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) { // the seek bar and the countdown move a few times a second
            position = player.positionMs()
            timerLeft = timer.remainingMs()
            delay(300)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("‹ Back") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showTimer = true }) {
                    Icon(painterResource(R.drawable.ic_timer), null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(sleepLabel(sleep, timerLeft))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(now.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(now.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(24.dp))
            val duration = now.durationMs.coerceAtLeast(1)
            Slider(
                value = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seekTo((it * duration).toLong()) }
                    dragging = null
                },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(MediaLibrary.formatDuration(dragging?.let { (it * duration).toLong() } ?: position).ifEmpty { "0:00" }, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(MediaLibrary.formatDuration(now.durationMs), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Toggle(R.drawable.ic_shuffle, "Shuffle", now.shuffle, player::toggleShuffle)
                IconButton(onClick = player::previous) { Icon(painterResource(R.drawable.ic_previous), "Previous", Modifier.size(32.dp)) }
                FilledIconButton(onClick = player::toggle, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors()) {
                    Icon(painterResource(if (now.isPlaying) R.drawable.ic_pause else R.drawable.ic_play), if (now.isPlaying) "Pause" else "Play", Modifier.size(40.dp))
                }
                IconButton(onClick = player::next) { Icon(painterResource(R.drawable.ic_next), "Next", Modifier.size(32.dp)) }
                Toggle(
                    if (now.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                    when (now.repeatMode) { Player.REPEAT_MODE_ONE -> "Repeat one"; Player.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat off" },
                    now.repeatMode != Player.REPEAT_MODE_OFF,
                    player::cycleRepeat,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Up next", style = MaterialTheme.typography.titleSmall)
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(now.queue) { index, title ->
                    val current = index == now.index
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        color = if (current) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { player.jumpTo(index) }.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
    if (showTimer) SleepTimerDialog(timer, sleep) { showTimer = false }
}

@Composable
private fun Toggle(icon: Int, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painterResource(icon),
            label,
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.then(if (on) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier),
        )
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
