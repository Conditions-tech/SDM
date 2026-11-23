package io.github.conditionstech.sdm

import android.Manifest
import android.os.Build
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
import io.github.conditionstech.sdm.engine.SdmException
import io.github.conditionstech.sdm.engine.Units

/** The download list and Add URL (M14d). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val downloads = (application as SdmApplication).downloads
        setContent { SdmTheme { HomeScreen(downloads) } }
    }
}

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
fun HomeScreen(downloads: Downloads) {
    val items by downloads.items.collectAsState()
    var adding by remember { mutableStateOf(false) }
    // Android 13+ asks before an app may show notifications; downloads work either way.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }) { Text("Add URL") }
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyList(Modifier.padding(padding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item -> DownloadRow(item, downloads) }
            }
        }
    }
    if (adding) {
        AddUrlDialog(
            onDismiss = { adding = false },
            onAdd = { url ->
                downloads.add(url) // throws for a bad address: the dialog shows why
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
private fun AddUrlDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    // The clipboard is read only when the user taps Paste: reading it by itself would show
    // Android's "SDM accessed the clipboard" notice (seen on the owner's phone, Android 16).
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add URL") },
        text = {
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
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    onAdd(url)
                } catch (e: SdmException) {
                    error = e.message
                }
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DownloadRow(item: DownloadItem, downloads: Downloads) {
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
                    DownloadState.COMPLETED, DownloadState.CANCELLED ->
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
        DownloadState.DOWNLOADING -> buildList {
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
