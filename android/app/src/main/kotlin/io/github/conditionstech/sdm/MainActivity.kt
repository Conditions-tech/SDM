package io.github.conditionstech.sdm

import android.Manifest
import android.os.Build
import android.content.Intent
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/** The download list and Add URL (M14d). */
class MainActivity : ComponentActivity() {
    /** A link another app shared (Share → SDM), waiting for the user to confirm it. */
    private var shared by mutableStateOf<SharedLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SdmApplication
        if (savedInstanceState == null) takeShared(intent)
        setContent {
            SdmTheme { HomeScreen(app.downloads, app.conversions, app.preferences, shared, onSharedHandled = { shared = null }) }
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

// The logo's indigo and sky blue (ADR-0021).
private val Indigo = Color(0xFF4F46E5)
private val Sky = Color(0xFF0EA5E9)

@Composable
fun SdmTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Sky, secondary = Indigo)
    } else {
        lightColorScheme(primary = Indigo, secondary = Sky)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("Settings") }
                    TextButton(onClick = { showAbout = true }) { Text("About") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
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
    Card(Modifier.fillMaxWidth()) {
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

/** Versions and licences, including the bundled tools (ADR-0023). */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    var tools by remember { mutableStateOf("Checking the bundled tools…") }
    LaunchedEffect(Unit) {
        tools = withContext(Dispatchers.IO) {
            try {
                val found = Python.getInstance().getModule("sdm_media").callAttr("versions").asMap()
                found.entries.joinToString("\n") { (name, version) -> "$name $version" }
            } catch (e: Exception) {
                "The bundled tools could not start (${e.javaClass.simpleName})."
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About SDM") },
        text = {
            Text(
                "Smart Download Manager ${BuildConfig.VERSION_NAME} for Android\n" +
                    "Apache License 2.0 · github.com/Conditions-tech/SDM\n\n" +
                    "Bundled:\n$tools\n\n" +
                    "Licences: OkHttp, Kotlin coroutines, AndroidX (Apache-2.0); Chaquopy (MIT); " +
                    "Python (PSF); yt-dlp (Unlicense); LAME (LGPL-2.0, used as a separate library).",
            )
        },
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
    Card(Modifier.fillMaxWidth()) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Download from video pages", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Adds 720p, 1080p, MP3 and M4A to Add URL, using the yt-dlp built into SDM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = preferences.videoPagesEnabled,
                    onCheckedChange = { on -> if (on) notice = true else preferences.setVideoPages(false) },
                )
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
