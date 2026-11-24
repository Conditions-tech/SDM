package io.github.conditionstech.sdm

import io.github.conditionstech.sdm.player.SubtitleException
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import io.github.conditionstech.sdm.ui.SdmTheme
import io.github.conditionstech.sdm.ui.SdmColors
import io.github.conditionstech.sdm.ui.ScreenTitle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.conditionstech.sdm.library.MusicScreen
import io.github.conditionstech.sdm.player.MiniPlayer
import io.github.conditionstech.sdm.player.VideoPlayerActivity
import androidx.compose.ui.platform.LocalContext
import io.github.conditionstech.sdm.player.NowPlayingScreen
import androidx.activity.compose.BackHandler
import io.github.conditionstech.sdm.library.VideosScreen
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conditionstech.sdm.downloads.DownloadItem
import io.github.conditionstech.sdm.downloads.DownloadState
import io.github.conditionstech.sdm.downloads.Downloads
import io.github.conditionstech.sdm.downloads.SharedLinks
import io.github.conditionstech.sdm.media.Conversion
import io.github.conditionstech.sdm.media.ConversionState
import io.github.conditionstech.sdm.media.Conversions
import io.github.conditionstech.sdm.media.ConvertFormat
import io.github.conditionstech.sdm.media.ConvertInput
import io.github.conditionstech.sdm.media.VideoChoice
import io.github.conditionstech.sdm.engine.SdmException
import io.github.conditionstech.sdm.engine.Units

/** The three tabs (M17a): Downloads, Music, Videos. A shared link always opens Downloads. */
@Composable
fun SdmTabs(app: SdmApplication, shared: SharedLink?, onSharedHandled: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(shared) { if (shared != null) tab = 0 }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.musicPlayer.reconnect() } // music may still be playing
    if (showPlayer) {
        BackHandler { showPlayer = false }
        NowPlayingScreen(app.musicPlayer, app.sleepTimer, app.playlists) { showPlayer = false }
        return
    }
    fun comingNext() = scope.launch { snackbar.showSnackbar("The video player comes in the next step (M17c).") }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
            MiniPlayer(app.musicPlayer) { showPlayer = true }
            NavigationBar(containerColor = SdmColors.Surface) {
                listOf(
                    Triple("Downloads", R.drawable.ic_tab_downloads, 0),
                    Triple("Music", R.drawable.ic_tab_music, 1),
                    Triple("Videos", R.drawable.ic_tab_video, 2),
                ).forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(painterResource(icon), contentDescription = null) },
                        label = { Text(label) },
                    )
                }
            }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeScreen(app.downloads, app.conversions, app.preferences, shared, onSharedHandled)
                1 -> TabPage("Music") { MusicScreen(app.library, app.playlists) { songs, song -> app.musicPlayer.play(songs, song) } }
                else -> TabPage("Videos") { VideosScreen(app.library) { video -> context.startActivity(VideoPlayerActivity.intent(context, video.uri, video.id, video.title)) } }
            }
        }
    }
}

@Composable
private fun TabPage(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Text(title, style = ScreenTitle, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
        content()
    }
}

/** The download list and Add URL (M14d). */
class MainActivity : ComponentActivity() {
    /** A link another app shared (Share → SDM), waiting for the user to confirm it. */
    private var shared by mutableStateOf<SharedLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SDM is always dark (owner's design choice): light status and navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val app = application as SdmApplication
        if (savedInstanceState == null) takeShared(intent)
        setContent {
            SdmTheme { SdmTabs(app, shared, onSharedHandled = { shared = null }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    private fun takeShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        // A share with no usable link still opens the dialog, saying why.
        shared = SharedLink(SharedLinks.firstLink(text))
    }
}

/** [url] is null when the shared text had no address SDM can download. */
class SharedLink(val url: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    downloads: Downloads,
    conversions: Conversions,
    preferences: Preferences,
    shared: SharedLink? = null,
    onSharedHandled: () -> Unit = {},
) {
    val items by downloads.items.collectAsState()
    val converting by conversions.items.collectAsState()
    var converts by remember { mutableStateOf<DownloadItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf("") }
    var prefillError by remember { mutableStateOf<String?>(null) }
    var prefillChoice by remember { mutableStateOf<VideoChoice?>(null) }
    var prefillHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shared) {
        if (shared != null) {
            prefill = shared.url.orEmpty()
            prefillError = if (shared.url == null) "The shared text has no http:// or https:// link." else null
            // M15c: a shared video page starts on 720p, or says how to get videos.
            val videoPage = shared.url != null && SharedLinks.looksLikeVideoPage(shared.url)
            prefillChoice = if (videoPage && preferences.videoPagesEnabled) VideoChoice.P720 else null
            prefillHint = if (videoPage && !preferences.videoPagesEnabled) {
                "This looks like a video page. To save the video, turn on video pages in Settings."
            } else {
                null
            }
            adding = true
            onSharedHandled()
        }
    }
    // Android 13+ asks before an app may show notifications; downloads work either way.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text("Downloads", style = ScreenTitle) },
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("Settings") }
                    TextButton(onClick = { showAbout = true }) { Text("About") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(containerColor = SdmColors.Indigo, contentColor = Color.White, onClick = {
                prefill = ""
                prefillError = null
                prefillChoice = null
                prefillHint = null
                adding = true
            }) { Text("Add URL") }
        },
    ) { padding ->
        if (items.isEmpty() && converting.isEmpty()) {
            EmptyList(Modifier.padding(padding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(converting, key = { "c" + it.id }) { conversion -> ConversionRow(conversion, conversions) }
                items(items, key = { it.id }) { item -> DownloadRow(item, downloads, onConvert = { converts = item }) }
            }
        }
    }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showSettings) SettingsDialog(preferences, onDismiss = { showSettings = false })
    converts?.let { item ->
        ConvertDialog(item, onDismiss = { converts = null }) { format ->
            conversions.start(ConvertInput(item.savedUri!!, item.savedName ?: item.fileName ?: "download"), format)
            converts = null
        }
    }
    if (adding) {
        AddUrlDialog(
            initialUrl = prefill,
            initialError = prefillError,
            initialChoice = prefillChoice,
            hint = prefillHint,
            videoPages = preferences.videoPagesEnabled,
            onDismiss = { adding = false },
            onAdd = { url, choice ->
                downloads.add(url, choice) // throws for a bad address: the dialog shows why
                adding = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
    }
}

@Composable
private fun EmptyList(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.sdm_logo), contentDescription = null, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.app_full_name), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_list),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddUrlDialog(
    initialUrl: String,
    initialError: String?,
    initialChoice: VideoChoice?,
    hint: String?,
    videoPages: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String, VideoChoice?) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    // The clipboard is read only when the user taps Paste: reading it by itself would show
    // Android's "SDM accessed the clipboard" notice (seen on the owner's phone, Android 16).
    var url by remember { mutableStateOf(initialUrl) }
    var error by remember { mutableStateOf(initialError) }
    var choice by remember { mutableStateOf(initialChoice) } // null: the file itself
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add URL") },
        text = {
            Column {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    error = null
                },
                label = { Text("Address of the file") },
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                trailingIcon = {
                    TextButton(onClick = {
                        url = clipboard.getText()?.text?.trim().orEmpty()
                        error = null
                    }) { Text("Paste") }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (videoPages) {
                // Download as (M15b): the file at this address, or a video page read by yt-dlp.
                Text("Download as", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = choice == null, onClick = { choice = null }, label = { Text("File") })
                    for (option in VideoChoice.entries) {
                        FilterChip(selected = choice == option, onClick = { choice = option }, label = { Text(option.label) })
                    }
                }
            }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    onAdd(url, choice)
                } catch (e: SdmException) {
                    error = e.message
                }
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DownloadRow(item: DownloadItem, downloads: Downloads, onConvert: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = SdmColors.Raised, contentColor = SdmColors.Text)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                item.savedName ?: item.fileName ?: item.url,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                statusLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.state == DownloadState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.PAUSED) {
                Spacer(Modifier.height(8.dp))
                val total = item.totalBytes
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (item.receivedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    )
                } else if (item.state == DownloadState.DOWNLOADING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (item.state) {
                    DownloadState.QUEUED, DownloadState.DOWNLOADING -> {
                        TextButton(onClick = { downloads.pause(item.id) }) { Text("Pause") }
                        TextButton(onClick = { downloads.cancel(item.id) }) { Text("Cancel") }
                    }
                    DownloadState.PAUSED -> {
                        TextButton(onClick = { downloads.resume(item.id) }) { Text("Resume") }
                        TextButton(onClick = { downloads.cancel(item.id) }) { Text("Cancel") }
                    }
                    DownloadState.FAILED -> {
                        TextButton(onClick = { downloads.resume(item.id) }) { Text("Retry") }
                        TextButton(onClick = { downloads.remove(item.id) }) { Text("Remove") }
                    }
                    DownloadState.COMPLETED -> {
                        if (item.savedUri != null) TextButton(onClick = onConvert) { Text("Convert") }
                        TextButton(onClick = { downloads.remove(item.id) }) { Text("Remove") }
                    }
                    DownloadState.CANCELLED ->
                        TextButton(onClick = { downloads.remove(item.id) }) { Text("Remove") }
                }
            }
        }
    }
}

/** "Downloading · 4 connections · 1.2 MB of 5.0 MB · 300.0 KB/s", and so on. */
internal fun statusLine(item: DownloadItem): String {
    val size = buildString {
        append(Units.formatSize(item.receivedBytes.toDouble()))
        item.totalBytes?.let { append(" of ").append(Units.formatSize(it.toDouble())) }
    }
    return when (item.state) {
        DownloadState.QUEUED -> "Waiting"
        DownloadState.DOWNLOADING -> if (item.videoChoice != null && item.fileName == null) "Reading the video page…" else buildList {
            add(if (item.connections > 1) "Downloading · ${item.connections} connections" else "Downloading")
            add(size)
            item.bytesPerSecond?.takeIf { it > 0 }?.let { add(Units.formatSpeed(it)) }
        }.joinToString(" · ")
        DownloadState.PAUSED -> "Paused · $size"
        DownloadState.COMPLETED -> buildString {
            append("Completed · ").append(Units.formatSize(item.receivedBytes.toDouble())).append(" · in Download/SDM")
            if (item.unconfirmedSize) append(" · the server didn't say the size, so completeness can't be confirmed")
        }
        DownloadState.FAILED -> item.error ?: "Failed"
        DownloadState.CANCELLED -> "Cancelled"
    }
}

/** About: the app's name and version only (owner's choice). Licences are in the release's NOTICE. */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_full_name)) },
        text = { Text("Version ${BuildConfig.VERSION_NAME}") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Converting a finished download (M15a): the three formats, explained. */
@Composable
private fun ConvertDialog(item: DownloadItem, onDismiss: () -> Unit, onChoose: (ConvertFormat) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Convert") },
        text = {
            Column {
                Text(item.savedName ?: item.fileName.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                for (format in ConvertFormat.entries) {
                    TextButton(onClick = { onChoose(format) }, modifier = Modifier.fillMaxWidth()) {
                        Text(format.label, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text(
                    "The result goes to Download/SDM; the original stays as it is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConversionRow(conversion: Conversion, conversions: Conversions) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = SdmColors.Raised, contentColor = SdmColors.Text)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                conversion.savedName ?: conversion.targetName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
            Spacer(Modifier.height(4.dp))
            val line = when (conversion.state) {
                ConversionState.RUNNING -> "Converting to ${conversion.format.name} · ${(conversion.progress * 100).toInt()}%"
                ConversionState.COMPLETED -> "Converted · in Download/SDM"
                ConversionState.FAILED -> conversion.error ?: "Conversion failed"
                ConversionState.CANCELLED -> "Conversion cancelled"
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if (conversion.state == ConversionState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (conversion.state == ConversionState.RUNNING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { conversion.progress }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (conversion.state == ConversionState.RUNNING) {
                    TextButton(onClick = { conversions.cancel(conversion.id) }) { Text("Cancel") }
                } else {
                    TextButton(onClick = { conversions.dismiss(conversion.id) }) { Text("Dismiss") }
                }
            }
        }
    }
}

/** Settings: video pages (M15b), off until the user accepts the notice. */
@Composable
private fun SettingsDialog(preferences: Preferences, onDismiss: () -> Unit) {
    var notice by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingSwitch(
                    "Download from video pages",
                    "Adds 720p, 1080p, MP3 and M4A to Add URL, using the yt-dlp built into SDM.",
                    preferences.videoPagesEnabled,
                ) { on -> if (on) notice = true else preferences.setVideoPages(false) }
                Spacer(Modifier.height(16.dp))
                SettingSwitch(
                    "Save English subtitles with videos",
                    "When a video page has English subtitles (YouTube can translate them), the player shows them.",
                    preferences.saveSubtitles,
                    preferences::changeSaveSubtitles,
                )
                Spacer(Modifier.height(16.dp))
                OpenSubtitlesSettings(preferences)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    if (notice) {
        AlertDialog(
            onDismissRequest = { notice = false },
            title = { Text("Before you turn this on") },
            text = {
                Text(
                    "Video pages are read with yt-dlp, built into SDM. You are responsible for following each " +
                        "site's terms and for having the right to download what you save.\n\n" +
                        "SDM never uses your accounts, cookies or logins, and can't download DRM-protected " +
                        "videos or anything behind a paywall.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    preferences.setVideoPages(true)
                    notice = false
                }) { Text("I understand, turn on") }
            },
            dismissButton = { TextButton(onClick = { notice = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * M18c: the user's own OpenSubtitles.com API key, and an optional sign-in for more downloads a
 * day. The password is sent once to OpenSubtitles and never stored; only the token is kept.
 */
@Composable
private fun OpenSubtitlesSettings(preferences: Preferences) {
    val app = LocalContext.current.applicationContext as SdmApplication
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(preferences.openSubtitlesKey) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text("OpenSubtitles (find subtitles online)", style = MaterialTheme.typography.titleSmall)
    Text(
        "Create a free account and API key at opensubtitles.com (Profile, API consumers), then paste the key here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = key,
        onValueChange = { key = it.trim(); preferences.changeOpenSubtitlesKey(it) },
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    val signedIn = preferences.openSubtitlesUser
    if (signedIn != null) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Signed in as $signedIn", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { preferences.setOpenSubtitlesLogin(null, null) }) { Text("Sign out") }
        }
    } else {
        Text(
            "Optional: sign in for more downloads a day. SDM keeps the sign-in, not the password.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            enabled = !busy && key.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
            onClick = {
                busy = true
                status = null
                scope.launch {
                    try {
                        val client = app.openSubtitles() ?: throw SubtitleException("Paste your API key first.")
                        val token = client.login(user.trim(), password)
                        preferences.setOpenSubtitlesLogin(user.trim(), token)
                        password = ""
                    } catch (e: SubtitleException) {
                        status = e.message
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) "Signing in…" else "Sign in") }
    }
    status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

