package io.github.conditionstech.sdm.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.conditionstech.sdm.R
import io.github.conditionstech.sdm.library.MediaLibrary
import io.github.conditionstech.sdm.ui.SdmColors
import io.github.conditionstech.sdm.ui.SdmTheme
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The video player (M17c, ADR-0025): full screen, rotates with the phone, gestures for
 * brightness, volume and seeking, double tap to skip, speed, a touch lock, subtitles the user
 * picks (.srt), and it resumes where the video was left. Our own design.
 */
class VideoPlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var videoUri: Uri
    private var videoId = 0L

    private val pickSubtitles = registerForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) loadSubtitles(picked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoUri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: return finish()
        videoId = intent.getLongExtra(EXTRA_ID, 0)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // no dimming while watching
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()) // true full screen; a swipe shows the bars
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true, // takes the audio focus: SDM's music pauses while a video plays
            )
            .build()
        player.setMediaItem(MediaItem.fromUri(videoUri))
        savedPosition()?.let { player.seekTo(it) }
        player.prepare()
        player.play()
        setContent {
            SdmTheme {
                VideoScreen(
                    player = player,
                    title = title,
                    onBack = ::finish,
                    onSubtitles = { pickSubtitles.launch(arrayOf("application/x-subrip", "text/plain", "application/octet-stream")) },
                    setBrightness = { level ->
                        window.attributes = window.attributes.apply { screenBrightness = level }
                    },
                    currentBrightness = { window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f },
                )
            }
        }
    }

    /** Subtitles from a file the user picked; the video continues from where it was. */
    private fun loadSubtitles(subtitles: Uri) {
        val position = player.currentPosition
        val item = MediaItem.Builder()
            .setUri(videoUri)
            .setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(subtitles)
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
            .build()
        player.setMediaItem(item, position)
        player.prepare()
        player.play()
    }

    override fun onStop() {
        super.onStop()
        val keep = VideoRules.positionToRemember(player.currentPosition, player.duration.coerceAtLeast(0))
        positions().edit().apply { if (keep != null) putLong(key(), keep) else remove(key()) }.apply()
        player.pause()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    private fun positions() = getSharedPreferences("video_positions", Context.MODE_PRIVATE)
    private fun key() = if (videoId != 0L) "id:$videoId" else "uri:$videoUri"
    private fun savedPosition(): Long? = positions().getLong(key(), -1).takeIf { it > 0 }

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, uri: String, id: Long, title: String) =
            Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TITLE, title)
    }
}

private enum class Drag { SEEK, BRIGHTNESS, VOLUME }

@OptIn(UnstableApi::class)
@Composable
private fun VideoScreen(
    player: ExoPlayer,
    title: String,
    onBack: () -> Unit,
    onSubtitles: () -> Unit,
    setBrightness: (Float) -> Unit,
    currentBrightness: () -> Float,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var hint by remember { mutableStateOf<String?>(null) } // "Volume 40%", "+0:30"...
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            // Controls hide themselves 3 s after the last touch while the video plays.
            if (showControls && playing && System.currentTimeMillis() - lastTouch > 3_000) showControls = false
            delay(250)
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(900)
            hint = null
        }
    }
    fun touched() {
        lastTouch = System.currentTimeMillis()
    }

    Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // The gesture layer: taps, double taps and drags (ignored while locked, except a tap).
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(locked) {
                        detectTapGestures(
                            onTap = {
                                touched()
                                showControls = !showControls
                            },
                            onDoubleTap = { offset ->
                                if (locked) return@detectTapGestures
                                val skip = VideoRules.doubleTapSkip(offset.x, size.width.toFloat())
                                if (skip != 0L) {
                                    player.seekTo((player.currentPosition + skip).coerceIn(0, player.duration.coerceAtLeast(0)))
                                    hint = if (skip > 0) "+10 s" else "−10 s"
                                }
                            },
                        )
                    }
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        var mode: Drag? = null
                        var startX = 0f
                        var total = androidx.compose.ui.geometry.Offset.Zero
                        var startPosition = 0L
                        var startLevel = 0f
                        detectDragGestures(
                            onDragStart = {
                                mode = null
                                startX = it.x
                                total = androidx.compose.ui.geometry.Offset.Zero
                                startPosition = player.currentPosition
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                total += amount
                                if (mode == null && total.getDistance() > 24f) {
                                    mode = when {
                                        abs(total.x) > abs(total.y) -> Drag.SEEK
                                        startX < size.width / 2 -> Drag.BRIGHTNESS.also { startLevel = currentBrightness() }
                                        else -> Drag.VOLUME.also {
                                            startLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                                        }
                                    }
                                }
                                when (mode) {
                                    Drag.SEEK -> {
                                        val target = VideoRules.seekBySwipe(startPosition, total.x, size.width.toFloat(), player.duration.coerceAtLeast(0))
                                        seekPreview = target
                                        val delta = (target - startPosition) / 1000
                                        hint = "${if (delta >= 0) "+" else "−"}${MediaLibrary.formatDuration(abs(delta) * 1000).ifEmpty { "0:00" }}  →  ${MediaLibrary.formatDuration(target).ifEmpty { "0:00" }}"
                                    }
                                    Drag.BRIGHTNESS -> {
                                        val level = VideoRules.levelBySwipe(startLevel, -total.y, size.height.toFloat())
                                        setBrightness(level.coerceAtLeast(0.01f))
                                        hint = "Brightness ${(level * 100).roundToInt()}%"
                                    }
                                    Drag.VOLUME -> {
                                        val level = VideoRules.levelBySwipe(startLevel, -total.y, size.height.toFloat())
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * maxVolume).roundToInt(), 0)
                                        hint = "Volume ${(level * 100).roundToInt()}%"
                                    }
                                    null -> Unit
                                }
                            },
                            onDragEnd = {
                                if (mode == Drag.SEEK) seekPreview?.let(player::seekTo)
                                seekPreview = null
                                mode = null
                            },
                        )
                    },
            )
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
            if (locked && showControls) {
                // Locked: only the unlock button, so pocket taps can't change anything.
                IconButton(
                    onClick = { locked = false; touched() },
                    modifier = Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                ) { Icon(painterResource(R.drawable.ic_lock), "Unlock") }
            }
            if (!locked && showControls) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") }
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onSubtitles(); touched() }) { Icon(painterResource(R.drawable.ic_subtitles), "Subtitles") }
                        Text(
                            VideoRules.speedLabel(speed),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.15f))
                                .clickable {
                                    speed = VideoRules.nextSpeed(speed)
                                    player.playbackParameters = PlaybackParameters(speed)
                                    touched()
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        IconButton(onClick = { locked = true; touched() }) { Icon(painterResource(R.drawable.ic_lock_open), "Lock") }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        RoundText("−10") { player.seekTo((player.currentPosition - VideoRules.SKIP_MS).coerceAtLeast(0)); touched() }
                        Spacer(Modifier.width(36.dp))
                        Box(
                            Modifier.size(76.dp).clip(CircleShape).background(SdmColors.Accent)
                                .clickable { if (player.isPlaying) player.pause() else player.play(); touched() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), if (playing) "Pause" else "Play", Modifier.size(38.dp))
                        }
                        Spacer(Modifier.width(36.dp))
                        RoundText("+10") { player.seekTo(player.currentPosition + VideoRules.SKIP_MS); touched() }
                    }
                    Spacer(Modifier.weight(1f))
                    val total = duration.coerceAtLeast(1)
                    GradientSeekBar(
                        fraction = ((seekPreview ?: position).toFloat() / total).coerceIn(0f, 1f),
                        onDrag = { seekPreview = (it * total).toLong(); touched() },
                        onRelease = { seekPreview?.let(player::seekTo); seekPreview = null },
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        Text(MediaLibrary.formatDuration(seekPreview ?: position).ifEmpty { "0:00" }, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        Text(MediaLibrary.formatDuration(duration), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundText(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(54.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}
