package com.torboxquest.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TorBoxPlayerApp()
                }
            }
        }
    }
}

private fun prefs(context: Context) = context.getSharedPreferences("torbox_prefs", Context.MODE_PRIVATE)

@Composable
fun TorBoxPlayerApp() {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf(prefs(context).getString("api_key", "") ?: "") }
    var showSettings by remember { mutableStateOf(apiKey.isBlank()) }

    if (showSettings) {
        SettingsScreen(
            initialKey = apiKey,
            allowCancel = apiKey.isNotBlank(),
            onCancel = { showSettings = false },
            onSave = { newKey ->
                apiKey = newKey
                prefs(context).edit().putString("api_key", newKey).apply()
                showSettings = false
            }
        )
    } else {
        DownloadsScreen(apiKey = apiKey, onOpenSettings = { showSettings = true })
    }
}

@Composable
fun SettingsScreen(
    initialKey: String,
    allowCancel: Boolean,
    onCancel: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialKey) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Connect TorBox", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Paste your TorBox API key (Account Settings > API Key on torbox.app).",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row {
            Button(
                onClick = { onSave(text.trim()) },
                enabled = text.isNotBlank()
            ) {
                Text("Save & Continue")
            }
            if (allowCancel) {
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(apiKey: String, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var items by remember { mutableStateOf(listOf<TorBoxItem>()) }
    var loading by remember { mutableStateOf(true) }
    var expandedId by remember { mutableStateOf<Long?>(null) }

    fun load() {
        scope.launch {
            loading = true
            val errors = mutableListOf<String>()
            val results = mutableListOf<TorBoxItem>()
            coroutineScope {
                val calls = SourceType.entries.map { type ->
                    async {
                        runCatching { TorBoxApi.fetchList(apiKey, type) }
                            .onSuccess { results += it }
                            .onFailure { errors += "${type.label}: ${it.message}" }
                    }
                }
                calls.forEach { it.await() }
            }
            items = results.sortedWith(compareByDescending<TorBoxItem> { it.createdAt }.thenByDescending { it.id })
            loading = false
            if (errors.isNotEmpty() && results.isEmpty()) {
                snackbarHostState.showSnackbar(errors.joinToString("  |  "))
            }
        }
    }

    LaunchedEffect(apiKey) { load() }

    fun playFile(item: TorBoxItem, file: TorBoxFile) {
        scope.launch {
            try {
                val link = TorBoxApi.requestDownloadLink(apiKey, item.type, item.id, file.id)
                openInExternalPlayer(context, link, file.shortName)
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Couldn't get link: ${e.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TorBox") },
                actions = {
                    IconButton(onClick = { load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && items.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                items.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No downloads found.")
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { load() }) { Text("Retry") }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items, key = { "${it.type}-${it.id}" }) { item ->
                            DownloadCard(
                                item = item,
                                expanded = expandedId == item.id,
                                onToggleExpand = {
                                    expandedId = if (expandedId == item.id) null else item.id
                                },
                                onPlay = { file -> playFile(item, file) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadCard(
    item: TorBoxItem,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onPlay: (TorBoxFile) -> Unit
) {
    val singleFile = item.files.size == 1
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                when {
                    !item.ready -> {}
                    singleFile -> onPlay(item.files.first())
                    item.files.isNotEmpty() -> onToggleExpand()
                }
            }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row {
                        Badge(item.type.label)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "${item.statusLabel} · ${formatSize(item.size)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                when {
                    !item.ready -> {}
                    singleFile -> IconButton(onClick = { onPlay(item.files.first()) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                    }
                    item.files.size > 1 -> IconButton(onClick = onToggleExpand) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = "Show files"
                        )
                    }
                }
            }
            if (expanded && item.files.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                item.files.forEach { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(file) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            file.shortName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(formatSize(file.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun Badge(text: String) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1) return "%.2f GB".format(gb)
    val mb = bytes / 1_000_000.0
    return "%.0f MB".format(mb)
}

private fun openInExternalPlayer(context: Context, url: String, fileName: String) {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/*"
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), mime)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        val chooser = Intent.createChooser(viewIntent, "Open with").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No video player app found on this headset", Toast.LENGTH_LONG).show()
    }
}
