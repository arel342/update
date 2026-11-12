package com.nodelix.coreos.ui.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import com.nodelix.coreos.core.AppContainer
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.core.UiState
import com.nodelix.coreos.domain.model.MediaPreviewType
import com.nodelix.coreos.domain.model.StorageDirectoryListing
import com.nodelix.coreos.domain.model.StorageEntry
import com.nodelix.coreos.domain.model.TextFilePreview
import com.nodelix.coreos.domain.model.TransferProgress
import com.nodelix.coreos.ui.components.BadgeTone
import com.nodelix.coreos.ui.components.ConfirmSensitiveActionDialog
import com.nodelix.coreos.ui.components.CyberDualRingGauge
import com.nodelix.coreos.ui.components.CyberLinearBar
import com.nodelix.coreos.ui.components.EmptyOrStatusCard
import com.nodelix.coreos.ui.components.ErrorStateCard
import com.nodelix.coreos.ui.components.LoadingStateCard
import com.nodelix.coreos.ui.components.NodelixCard
import com.nodelix.coreos.ui.components.StatusPill
import com.nodelix.coreos.ui.theme.NodelixBackground
import com.nodelix.coreos.ui.theme.NodelixError
import com.nodelix.coreos.ui.theme.NodelixMonoStyles
import com.nodelix.coreos.ui.theme.NodelixPrimary
import com.nodelix.coreos.ui.theme.NodelixSecondary
import com.nodelix.coreos.ui.theme.NodelixTextSecondary
import com.nodelix.coreos.util.DateFormatter
import com.nodelix.coreos.util.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale

data class ActivePreviewState(
    val type: MediaPreviewType,
    val title: String,
    val mediaUrl: String? = null,
    val textPreview: TextFilePreview? = null
)

/** Pending server-side copy/cut selection executed via `POST /api/paste`. */
data class StorageClipboardState(
    val action: String, // "copy" | "cut"
    val sourceDir: String,
    val items: List<String>
)

class StorageViewModel(
    private val container: AppContainer
) : ViewModel() {

    val transferProgress: StateFlow<TransferProgress?> = container.storageRepository.transferProgress

    private val _currentDir = MutableStateFlow("")
    val currentDir: StateFlow<String> = _currentDir.asStateFlow()

    private val _listingState = MutableStateFlow<UiState<StorageDirectoryListing>>(UiState.Loading)
    val listingState: StateFlow<UiState<StorageDirectoryListing>> = _listingState.asStateFlow()

    private val _previewState = MutableStateFlow<ActivePreviewState?>(null)
    val previewState: StateFlow<ActivePreviewState?> = _previewState.asStateFlow()

    private val _feedback = MutableStateFlow<Pair<String, Boolean>?>(null)
    val feedback: StateFlow<Pair<String, Boolean>?> = _feedback.asStateFlow()

    private val _clipboard = MutableStateFlow<StorageClipboardState?>(null)
    val clipboard: StateFlow<StorageClipboardState?> = _clipboard.asStateFlow()

    fun authenticatedImageLoader(): ImageLoader = container.coilImageLoader()
    fun authenticatedOkHttpClient(): OkHttpClient = container.apiClient.currentOkHttpClient()

    fun openDirectory(relativeDir: String = _currentDir.value) {
        viewModelScope.launch {
            _listingState.value = UiState.Loading
            when (val res = container.storageRepository.listDirectory(relativeDir)) {
                is AppResult.Failure -> {
                    _listingState.value = UiState.Error(res.error)
                }
                is AppResult.Success -> {
                    _currentDir.value = res.data.currentDir
                    _listingState.value = UiState.Success(res.data)
                }
            }
        }
    }

    fun navigateUp() {
        val cur = _currentDir.value
        if (cur.isEmpty()) return
        val parent = cur.substringBeforeLast('/', missingDelimiterValue = "")
        openDirectory(parent)
    }

    fun createFolder(folderName: String) {
        viewModelScope.launch {
            when (val res = container.storageRepository.createFolder(_currentDir.value, folderName)) {
                is AppResult.Failure -> _feedback.value = res.error.userMessage to true
                is AppResult.Success -> {
                    _feedback.value = "Folder '$folderName' created." to false
                    openDirectory(_currentDir.value)
                }
            }
        }
    }

    fun renameEntry(oldName: String, newName: String) {
        viewModelScope.launch {
            when (val res = container.storageRepository.renameEntry(_currentDir.value, oldName, newName)) {
                is AppResult.Failure -> _feedback.value = res.error.userMessage to true
                is AppResult.Success -> {
                    _feedback.value = "Renamed '$oldName' to '$newName'." to false
                    openDirectory(_currentDir.value)
                }
            }
        }
    }

    fun deleteEntry(entry: StorageEntry) {
        viewModelScope.launch {
            when (val res = container.storageRepository.deleteEntry(entry.relativePath)) {
                is AppResult.Failure -> _feedback.value = res.error.userMessage to true
                is AppResult.Success -> {
                    _feedback.value = "Deleted '${entry.name}'." to false
                    openDirectory(_currentDir.value)
                }
            }
        }
    }

    fun compressEntry(entry: StorageEntry) {
        viewModelScope.launch {
            when (val res = container.storageRepository.compressToZip(_currentDir.value, entry.name)) {
                is AppResult.Failure -> _feedback.value = res.error.userMessage to true
                is AppResult.Success -> {
                    _feedback.value = "Created archive '${res.data}'." to false
                    openDirectory(_currentDir.value)
                }
            }
        }
    }

    fun uploadFromUri(context: Context, uri: Uri) {
        val (name, size) = resolveSafMetadata(context, uri)
        viewModelScope.launch {
            when (val res = container.storageRepository.uploadFile(_currentDir.value, uri, name, size)) {
                is AppResult.Failure -> _feedback.value = res.error.userMessage to true
                is AppResult.Success -> {
                    _feedback.value = "Uploaded '${res.data}'." to false
                    openDirectory(_currentDir.value)
                }
            }
        }
    }

    fun downloadToUri(entry: StorageEntry, destinationUri: Uri) {
        viewModelScope.launch {
            val res = container.storageRepository.downloadFile(
                relativePath = entry.relativePath,
                destinationUri = destinationUri,
                asZip = entry.isDirectory
            )
            when (res) {
                is AppResult.Failure -> _feedback.value = res.error.userMessage to true
                is AppResult.Success -> {
                    _feedback.value = "Saved '${entry.name}' (${FileUtils.formatBytes(res.data)})." to false
                }
            }
        }
    }

    fun cancelTransfer() {
        container.storageRepository.cancelActiveTransfer()
    }

    fun copyEntry(entry: StorageEntry) {
        _clipboard.value = StorageClipboardState(
            action = "copy",
            sourceDir = _currentDir.value,
            items = listOf(entry.name)
        )
        _feedback.value = "Copied '${entry.name}'. Navigate to a folder and tap Paste." to false
    }

    fun cutEntry(entry: StorageEntry) {
        _clipboard.value = StorageClipboardState(
            action = "cut",
            sourceDir = _currentDir.value,
            items = listOf(entry.name)
        )
        _feedback.value = "Cut '${entry.name}'. Navigate to a folder and tap Paste." to false
    }

    fun clearClipboard() {
        _clipboard.value = null
    }

    fun pasteClipboard() {
        val clip = _clipboard.value ?: return
        viewModelScope.launch {
            when (val res = container.storageRepository.pasteEntries(
                action = clip.action,
                sourceDir = clip.sourceDir,
                targetDir = _currentDir.value,
                items = clip.items
            )) {
                is AppResult.Failure -> _feedback.value = res.error.userMessage to true
                is AppResult.Success -> {
                    _feedback.value = res.data to false
                    _clipboard.value = null
                    openDirectory(_currentDir.value)
                }
            }
        }
    }

    fun openPreview(entry: StorageEntry) {
        if (entry.isDirectory) {
            openDirectory(entry.relativePath)
            return
        }
        when {
            FileUtils.isImageFile(entry.name) -> {
                val url = container.storageRepository.buildAuthenticatedMediaUrl(entry.relativePath)
                if (url != null) {
                    _previewState.value = ActivePreviewState(
                        type = MediaPreviewType.IMAGE,
                        title = entry.name,
                        mediaUrl = url
                    )
                }
            }
            FileUtils.isVideoFile(entry.name) -> {
                val url = container.storageRepository.buildAuthenticatedMediaUrl(entry.relativePath)
                if (url != null) {
                    _previewState.value = ActivePreviewState(
                        type = MediaPreviewType.VIDEO,
                        title = entry.name,
                        mediaUrl = url
                    )
                }
            }
            FileUtils.isTextFile(entry.name) -> {
                viewModelScope.launch {
                    when (val res = container.storageRepository.readTextPreview(entry.relativePath)) {
                        is AppResult.Failure -> _feedback.value = res.error.userMessage to true
                        is AppResult.Success -> {
                            _previewState.value = ActivePreviewState(
                                type = MediaPreviewType.TEXT,
                                title = entry.name,
                                textPreview = res.data
                            )
                        }
                    }
                }
            }
            else -> {
                _feedback.value = "Preview not supported for '${entry.name}'. Use Download to save locally." to false
            }
        }
    }

    fun closePreview() {
        _previewState.value = null
    }

    private fun resolveSafMetadata(context: Context, uri: Uri): Pair<String, Long> {
        var displayName = "upload_${System.currentTimeMillis()}"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return displayName to size
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    StorageViewModel(container) as T
            }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    viewModel: StorageViewModel
) {
    val currentDir by viewModel.currentDir.collectAsStateWithLifecycle()
    val listingState by viewModel.listingState.collectAsStateWithLifecycle()
    val transferProgress by viewModel.transferProgress.collectAsStateWithLifecycle()
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var entryToRename by remember { mutableStateOf<StorageEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var entryToDelete by remember { mutableStateOf<StorageEntry?>(null) }
    var pendingDownloadEntry by remember { mutableStateOf<StorageEntry?>(null) }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.uploadFromUri(context, uri)
        }
    }

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val target = pendingDownloadEntry
        pendingDownloadEntry = null
        if (uri != null && target != null) {
            viewModel.downloadToUri(target, uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.openDirectory("")
    }

    Scaffold(
        containerColor = NodelixBackground,
        topBar = {
            TopAppBar(
                title = { Text("Storage Manager") },
                actions = {
                    IconButton(onClick = { showCreateFolderDialog = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New Folder")
                    }
                    IconButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = "Upload File")
                    }
                    IconButton(onClick = { viewModel.openDirectory(currentDir) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NodelixBackground)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            feedback?.let { (msg, isErr) ->
                item {
                    NodelixCard(accentBorder = if (isErr) NodelixError else NodelixPrimary) {
                        Text(msg, color = if (isErr) NodelixError else NodelixPrimary)
                    }
                }
            }

            // Active Upload / Download Progress Card with Cancellation
            transferProgress?.let { prog ->
                item {
                    NodelixCard(accentBorder = NodelixSecondary) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (prog.isUpload) "Uploading ${prog.fileName}" else "Downloading ${prog.fileName}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${FileUtils.formatBytes(prog.bytesTransferred)} / ${FileUtils.formatBytes(prog.totalBytes)} • ${String.format(Locale.US, "%.1f Mbps", prog.speedMbps)}",
                                    style = NodelixMonoStyles.technical,
                                    color = NodelixTextSecondary
                                )
                            }
                            TextButton(onClick = { viewModel.cancelTransfer() }) {
                                Text("Cancel", color = NodelixError)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        CyberLinearBar(fraction = prog.fraction, color = NodelixSecondary)
                    }
                }
            }

            when (val s = listingState) {
                UiState.Loading -> item { LoadingStateCard("Reading server flash storage...") }
                is UiState.Error -> item {
                    ErrorStateCard(
                        message = s.error.userMessage,
                        onRetry = { viewModel.openDirectory(currentDir) }
                    )
                }
                is UiState.Success -> {
                    val listing = s.data
                    val total = listing.totalBytes ?: 0L
                    val free = listing.freeBytes ?: 0L
                    val used = (total - free).coerceAtLeast(0L)
                    val usedFraction = if (total > 0L) (used.toFloat() / total.toFloat()) else 0f

                    // Flash Storage Overview Ring Card (inspired by visual reference)
                    item {
                        NodelixCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CyberDualRingGauge(
                                    outerFraction = usedFraction,
                                    innerFraction = null,
                                    centerValue = if (total > 0L) "${(usedFraction * 100).toInt()}%" else "READY",
                                    centerLabel = "USED",
                                    size = 112.dp
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Server Flash Drive", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = listing.rootPath ?: "/upload",
                                        style = NodelixMonoStyles.technical,
                                        color = NodelixSecondary
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "Used: ${FileUtils.formatBytes(used.takeIf { total > 0L })}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "Free: ${FileUtils.formatBytes(listing.freeBytes)} of ${FileUtils.formatBytes(listing.totalBytes)}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }

                    // Breadcrumb Navigation Bar
                    item {
                        NodelixCard(secondarySurface = true) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (currentDir.isNotEmpty()) {
                                    OutlinedButton(onClick = { viewModel.navigateUp() }) {
                                        Icon(Icons.Outlined.ArrowUpward, contentDescription = "Up", modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Up")
                                    }
                                    Spacer(Modifier.width(10.dp))
                                }
                                Text(
                                    text = "/upload" + if (currentDir.isEmpty()) "" else "/$currentDir",
                                    style = NodelixMonoStyles.technical
                                )
                            }
                        }
                    }

                    // Pending copy/cut clipboard with Paste action into current folder
                    clipboard?.let { clip ->
                        item {
                            NodelixCard(accentBorder = NodelixSecondary) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (clip.action == "copy") "Clipboard: Copy" else "Clipboard: Cut",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = NodelixSecondary
                                        )
                                        Text(
                                            text = "${clip.items.size} item(s) from '${clip.sourceDir.ifBlank { "/" }}'",
                                            style = NodelixMonoStyles.technical,
                                            color = NodelixTextSecondary
                                        )
                                    }
                                    Row {
                                        TextButton(onClick = { viewModel.clearClipboard() }) {
                                            Text("Clear", color = NodelixTextSecondary)
                                        }
                                        Button(
                                            onClick = { viewModel.pasteClipboard() },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = NodelixSecondary,
                                                contentColor = Color(0xFF001F29)
                                            )
                                        ) {
                                            Text("Paste Here", fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (listing.entries.isEmpty()) {
                        item {
                            EmptyOrStatusCard(
                                icon = Icons.Outlined.Folder,
                                title = "Folder is Empty",
                                description = "Use the Upload or New Folder buttons above to manage files."
                            )
                        }
                    } else {
                        items(listing.entries, key = { it.relativePath }) { entry ->
                            StorageEntryCard(
                                entry = entry,
                                onOpenOrPreview = { viewModel.openPreview(entry) },
                                onDownload = {
                                    pendingDownloadEntry = entry
                                    val suggestedName = if (entry.isDirectory) "${entry.name}.zip" else entry.name
                                    downloadLauncher.launch(suggestedName)
                                },
                                onRename = {
                                    entryToRename = entry
                                    renameText = entry.name
                                },
                                onCompress = { viewModel.compressEntry(entry) },
                                onCopy = { viewModel.copyEntry(entry) },
                                onCut = { viewModel.cutEntry(entry) },
                                onDelete = { entryToDelete = entry }
                            )
                        }
                    }
                }
                else -> Unit
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create New Folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createFolder(newFolderName)
                    newFolderName = ""
                    showCreateFolderDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    entryToRename?.let { target ->
        AlertDialog(
            onDismissRequest = { entryToRename = null },
            title = { Text("Rename '${target.name}'") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.renameEntry(target.name, renameText)
                    entryToRename = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { entryToRename = null }) { Text("Cancel") }
            }
        )
    }

    entryToDelete?.let { target ->
        ConfirmSensitiveActionDialog(
            title = "Delete '${target.name}'?",
            description = "Permanently remove this ${if (target.isDirectory) "folder and all its contents" else "file"} from the server flash drive?",
            confirmLabel = "Delete",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteEntry(target)
                entryToDelete = null
            },
            onDismiss = { entryToDelete = null }
        )
    }

    previewState?.let { preview ->
        MediaPreviewDialog(
            preview = preview,
            imageLoader = viewModel.authenticatedImageLoader(),
            okHttpClient = viewModel.authenticatedOkHttpClient(),
            onDismiss = { viewModel.closePreview() }
        )
    }
}

@Composable
private fun StorageEntryCard(
    entry: StorageEntry,
    onOpenOrPreview: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onCompress: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit
) {
    val icon = when {
        entry.isDirectory -> Icons.Outlined.Folder
        FileUtils.isImageFile(entry.name) -> Icons.Outlined.Image
        FileUtils.isVideoFile(entry.name) -> Icons.Outlined.VideoFile
        FileUtils.isTextFile(entry.name) -> Icons.Outlined.Description
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
    val tint = when {
        entry.isDirectory -> NodelixPrimary
        FileUtils.isImageFile(entry.name) || FileUtils.isVideoFile(entry.name) -> NodelixSecondary
        else -> NodelixTextSecondary
    }

    NodelixCard(onClick = onOpenOrPreview) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (entry.isDirectory) {
                        "Directory • ${DateFormatter.formatEpochMillis(entry.modifiedMillis)}"
                    } else {
                        "${FileUtils.formatBytes(entry.sizeBytes)} • ${DateFormatter.formatEpochMillis(entry.modifiedMillis)}"
                    },
                    style = NodelixMonoStyles.technical,
                    color = NodelixTextSecondary
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onDownload) {
                Icon(Icons.Outlined.Download, contentDescription = "Download")
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Outlined.Edit, contentDescription = "Rename")
            }
            IconButton(onClick = onCompress) {
                Icon(Icons.Outlined.Archive, contentDescription = "Compress ZIP")
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy")
            }
            IconButton(onClick = onCut) {
                Icon(Icons.Outlined.ContentCut, contentDescription = "Cut")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete", tint = NodelixError)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MediaPreviewDialog(
    preview: ActivePreviewState,
    imageLoader: ImageLoader,
    okHttpClient: OkHttpClient,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.nodelix.coreos.ui.theme.NodelixCard,
        title = { Text(preview.title, style = MaterialTheme.typography.titleMedium) },
        text = {
            when (preview.type) {
                MediaPreviewType.IMAGE -> {
                    AsyncImage(
                        model = preview.mediaUrl,
                        imageLoader = imageLoader,
                        contentDescription = preview.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                    )
                }

                MediaPreviewType.VIDEO -> {
                    val exoPlayer = remember(preview.mediaUrl) {
                        val dsFactory = OkHttpDataSource.Factory(okHttpClient)
                        ExoPlayer.Builder(context)
                            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
                            .build()
                            .apply {
                                preview.mediaUrl?.let { url ->
                                    setMediaItem(MediaItem.fromUri(url))
                                    prepare()
                                    playWhenReady = true
                                }
                            }
                    }
                    DisposableEffect(exoPlayer) {
                        onDispose { exoPlayer.release() }
                    }
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    )
                }

                MediaPreviewType.TEXT -> {
                    val textData = preview.textPreview
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (textData?.truncated == true) {
                            StatusPill(
                                text = "Truncated to first 200 KB (${FileUtils.formatBytes(textData.sizeBytes)} total)",
                                tone = BadgeTone.WARNING,
                                showDot = false
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            text = textData?.content.orEmpty(),
                            style = NodelixMonoStyles.technical
                        )
                    }
                }

                MediaPreviewType.UNSUPPORTED -> {
                    Text("Preview unavailable.")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NodelixPrimary,
                    contentColor = Color(0xFF002116)
                )
            ) {
                Text("Close", fontWeight = FontWeight.SemiBold)
            }
        }
    )
}
