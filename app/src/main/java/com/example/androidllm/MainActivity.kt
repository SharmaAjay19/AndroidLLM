package com.example.androidllm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidllm.data.ChatListItem
import com.example.androidllm.data.MessageEntity
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Light lavender theme → use dark status-bar icons so the clock/icons stay legible.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScaffold(viewModel)
                }
            }
        }
        handleShareIntent(intent)
        handleOpenChatIntent(intent)
        handleCaptureIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleOpenChatIntent(intent)
        handleCaptureIntent(intent)
    }

    private fun handleCaptureIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CAPTURE_CLIPBOARD, false) == true) {
            viewModel.saveClipboardToMemory()
            viewModel.openMemories()
            intent.removeExtra(EXTRA_CAPTURE_CLIPBOARD)
        }
    }

    private fun handleOpenChatIntent(intent: android.content.Intent?) {
        val chatId = intent?.getLongExtra(EXTRA_OPEN_CHAT_ID, -1L) ?: -1L
        if (chatId > 0) {
            viewModel.openChatById(chatId)
            intent?.removeExtra(EXTRA_OPEN_CHAT_ID)
        }
    }

    private fun handleShareIntent(intent: android.content.Intent?) {
        if (intent == null) return
        when (intent.action) {
            android.content.Intent.ACTION_SEND -> viewModel.handleShare(
                intent.getStringExtra(android.content.Intent.EXTRA_TEXT),
                intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
            )
            android.content.Intent.ACTION_SEND_MULTIPLE -> viewModel.handleShare(
                intent.getStringExtra(android.content.Intent.EXTRA_TEXT),
                intent.getParcelableArrayListExtra(
                    android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java
                )?.firstOrNull()
            )
            android.content.Intent.ACTION_PROCESS_TEXT -> viewModel.handleShare(
                intent.getCharSequenceExtra(
                    android.content.Intent.EXTRA_PROCESS_TEXT
                )?.toString(),
                null
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have just granted "All files access" in system settings.
        viewModel.refreshStorage()
    }

    companion object {
        const val EXTRA_OPEN_CHAT_ID = "open_chat_id"
        const val EXTRA_CAPTURE_CLIPBOARD = "capture_clipboard"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(vm: MainViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val chats by vm.chats.collectAsState()
    val activeId by vm.activeChatId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val onDeleteChat: (Long) -> Unit = { id ->
        vm.deleteChatWithUndo(id)
        scope.launch {
            val res = snackbarHostState.showSnackbar(
                message = "Chat deleted", actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (res == SnackbarResult.ActionPerformed) vm.undoDeleteChat()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ChatDrawer(
                    chats = chats,
                    activeId = activeId,
                    searchText = vm.searchText,
                    onSearch = vm::onSearchChange,
                    onNewChat = {
                        vm.newChat()
                        scope.launch { drawerState.close() }
                    },
                    onOpenChat = {
                        vm.openChat(it)
                        scope.launch { drawerState.close() }
                    },
                    onDeleteChat = onDeleteChat,
                    enabled = !vm.isGenerating
                )
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Chats")
                        }
                    },
                    title = {
                        Column {
                            Text(vm.currentTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = when {
                                vm.isGenerating -> "generating…"
                                else -> "Qwen3-4B"
                            }
                            Text(sub, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.newChat() }, enabled = !vm.isGenerating) {
                            Icon(Icons.Filled.Add, contentDescription = "New chat")
                        }
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Chat options") },
                                leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                                onClick = { menuOpen = false; vm.showChatOptions = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Memories") },
                                leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                                onClick = { menuOpen = false; vm.openMemories() }
                            )
                            DropdownMenuItem(
                                text = { Text("Documents") },
                                leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                                onClick = { menuOpen = false; vm.openDocuments() }
                            )
                            DropdownMenuItem(
                                text = { Text("Schedules") },
                                leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                                onClick = { menuOpen = false; vm.openSchedules() }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = { menuOpen = false; vm.openSettings() }
                            )
                        }
                    }
                )
            }
        ) { padding ->
            if (vm.showSettings) {
                SettingsDialog(vm)
            }
            if (vm.showSchedules) {
                SchedulesScreen(vm)
            }
            if (vm.showDocuments) {
                DocumentsScreen(vm)
            }
            if (vm.showMemories) {
                MemoriesScreen(vm)
            }
            if (vm.showChatOptions) {
                ChatOptionsSheet(vm)
            }
            if (vm.pendingShare != null) {
                ShareSheet(vm)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(horizontal = 12.dp)
            ) {
                when (vm.phase) {
                    Phase.NEEDS_MODEL -> SetupSection(vm)
                    Phase.DOWNLOADING, Phase.LOADING -> ProgressSection(vm)
                    Phase.READY -> ChatSection(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareSheet(vm: MainViewModel) {
    val share = vm.pendingShare ?: return
    val kindLabel = when (share.kind) {
        ShareRouting.Kind.URL -> "Shared link"
        ShareRouting.Kind.FILE -> "Shared file"
        ShareRouting.Kind.TEXT -> "Shared text"
    }
    Dialog(onDismissRequest = { vm.dismissShare() }) {
        Card {
            Column(modifier = Modifier.padding(20.dp).widthIn(max = 420.dp)) {
                Text("Ask AndroidLLM", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(kindLabel, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        share.display,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Choose an action:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShareActionChip("Summarize", ShareRouting.Action.SUMMARIZE, vm)
                    ShareActionChip("Key points", ShareRouting.Action.KEY_POINTS, vm)
                    ShareActionChip("Translate", ShareRouting.Action.TRANSLATE, vm)
                    ShareActionChip("Reply draft", ShareRouting.Action.REPLY, vm)
                    ShareActionChip("Ask…", ShareRouting.Action.ASK, vm)
                    ShareActionChip("Save to memory", ShareRouting.Action.SAVE_MEMORY, vm)
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.dismissShare() }) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun ShareActionChip(
    label: String,
    action: ShareRouting.Action,
    vm: MainViewModel,
) {
    OutlinedButton(onClick = { vm.runSharedAction(action) }) { Text(label) }
}

@Composable
private fun ChatDrawer(
    chats: List<ChatListItem>,
    activeId: Long?,
    searchText: String,
    onSearch: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onDeleteChat: (Long) -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("AndroidLLM", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onNewChat,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("New chat")
        }
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = searchText,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search chats and messages") }
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        if (chats.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (searchText.isBlank()) "No chats yet" else "No matches",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            // Group chats by recency (Today / Yesterday / Earlier) — the #1 cue for re-finding.
            val grouped = remember(chats) { chats.groupBy { UiFormat.dayGroup(it.updatedAt) } }
            val order = listOf("Today", "Yesterday", "Earlier")
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                order.forEach { group ->
                    val rows = grouped[group] ?: return@forEach
                    item(key = "hdr-$group") {
                        Text(
                            group,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                        )
                    }
                    items(rows, key = { it.id }) { chat ->
                        ChatRow(
                            chat = chat,
                            selected = chat.id == activeId,
                            onClick = { onOpenChat(chat.id) },
                            onDelete = { onDeleteChat(chat.id) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: ChatListItem,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // Tap to open; long-press to delete (with Undo via the snackbar). No per-row trash icon.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onDelete)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Scheduled-briefing chats are stored with a leading bell emoji; render that as
                // a small icon + "Scheduled" badge instead, and clean the title.
                val isScheduled = chat.title.startsWith("\uD83D\uDD14")
                val cleanTitle = chat.title.removePrefix("\uD83D\uDD14").trim()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isScheduled) {
                        Icon(
                            Icons.Filled.Schedule, contentDescription = "Scheduled",
                            modifier = Modifier.width(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        cleanTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val raw = chat.snippet?.replace("\n", " ")?.trim().orEmpty()
                // Show aborted turns as a gentle "Interrupted" instead of "(no response)".
                val snippet = when (raw) {
                    "(no response)", "(interrupted)" -> "Interrupted"
                    else -> raw
                }
                if (snippet.isNotEmpty()) {
                    Text(
                        snippet,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                UiFormat.relative(chat.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SetupSection(vm: MainViewModel) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "On-device chat with Qwen3-4B (Q4_K_M).",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            "The ~2.5 GB model is downloaded on demand and stored on the device. " +
                    "Use Wi-Fi for the first download.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        // Offer persistent storage so the model survives uninstall/reinstall.
        if (!vm.hasPersistentStorage) {
            OutlinedButton(
                onClick = { (context as? android.app.Activity)?.let { ModelStorage.requestAllFilesAccess(it) } },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text("Grant storage access (keep model across reinstalls)")
            }
            Text(
                "Optional: lets the app save the model to /sdcard/AndroidLLM so upgrading " +
                        "the app won't re-download it. Otherwise it's saved to app storage.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        } else {
            Text(
                "Model will be saved to /sdcard/AndroidLLM and kept across reinstalls.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        OutlinedTextField(
            value = vm.modelUrl,
            onValueChange = { vm.modelUrl = it },
            label = { Text("GGUF URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        )
        Button(onClick = { vm.downloadModel() }) {
            Text("Download & load model")
        }
        if (vm.statusLine.isNotEmpty()) {
            Text(
                vm.statusLine,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun ProgressSection(vm: MainViewModel) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val p = vm.downloadProgress
        if (vm.phase == Phase.DOWNLOADING && p in 0f..1f) {
            LinearProgressIndicator(
                progress = { p },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 12.dp))
        }
        Text(vm.statusLine, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ChatSection(vm: MainViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val rows by vm.messages.collectAsState()
    val context = LocalContext.current

    // Consume a one-shot prefill produced by the "Ask…" share action.
    LaunchedEffect(vm.draftInput) {
        vm.draftInput?.let {
            input = it
            vm.draftInput = null
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.attachFile(it) } }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startRecording() }

    // Phone-tool permissions: the agent loop suspends until the user responds.
    val phonePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> vm.onPermissionResult(result.values.all { it }) }

    LaunchedEffect(vm.permissionRequest) {
        vm.permissionRequest?.let { phonePermLauncher.launch(it.permissions.toTypedArray()) }
    }

    // Confirmation for state-changing phone tools (create event/reminder, message draft).
    vm.confirmRequest?.let { req ->
        AlertDialog(
            onDismissRequest = { vm.resolveConfirm(false) },
            title = { Text(req.title) },
            text = { Text(req.detail) },
            confirmButton = { TextButton(onClick = { vm.resolveConfirm(true) }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { vm.resolveConfirm(false) }) { Text("Cancel") } }
        )
    }

    // Warm up (download/load) the voice model the first time the chat is shown.
    LaunchedEffect(Unit) { vm.ensureVoiceModel() }

    // Apply the live streaming overlay on top of persisted rows.
    val display = rows.map { m ->
        if (m.id == vm.streamingId) m.copy(content = vm.streamingText.ifEmpty { "…" }) else m
    }

    LaunchedEffect(display.size, vm.streamingText) {
        if (display.isNotEmpty()) listState.animateScrollToItem(display.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (display.isEmpty()) {
            EmptyChatState(
                onPick = { suggestion ->
                    input = suggestion
                },
                modifier = Modifier.weight(1f)
            )
        } else {
            // Pair each tool-call row with its following tool-result row so the machinery shows
            // as one quiet, expandable line instead of three loud blocks.
            val items = remember(display) { pairChatItems(display) }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.Msg -> MessageBubble(item.msg)
                        is ChatItem.ToolActivity -> ToolActivityBubble(item)
                    }
                }
            }
        }

        // Chip showing a file queued to send.
        vm.pendingUpload?.let { name ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.AttachFile, contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(
                    "Attached: $name",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { vm.clearUpload() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove attachment")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                enabled = !vm.isGenerating
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach file")
            }
            // Mic: tap to record, tap again to stop + transcribe into the input field. The
            // trailing send button doubles as the mic when the field is empty (no extra space).
            val micAction = {
                if (vm.isRecording) {
                    vm.stopRecordingAndTranscribe { text ->
                        input = (input.trim() + " " + text).trim()
                    }
                } else {
                    vm.startRecording()
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(if (vm.voiceStatus.isNotEmpty()) vm.voiceStatus else "Ask something…")
                },
                shape = RoundedCornerShape(16.dp),
                enabled = !vm.isGenerating
            )
            val hasInput = input.isNotBlank() || vm.pendingUpload != null
            IconButton(
                onClick = {
                    when {
                        vm.isGenerating -> vm.stopGenerating()
                        hasInput -> { vm.send(input); input = "" }
                        // Empty field → the button acts as the mic (WhatsApp pattern; no extra space).
                        hasAudioPermission(context) -> micAction()
                        else -> micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !vm.isTranscribing
            ) {
                when {
                    vm.isGenerating -> Icon(
                        Icons.Filled.Stop, contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.error
                    )
                    hasInput -> Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    vm.isRecording -> Icon(
                        Icons.Filled.Stop, contentDescription = "Stop recording",
                        tint = MaterialTheme.colorScheme.error
                    )
                    else -> Icon(Icons.Filled.Mic, contentDescription = "Voice input")
                }
            }
        }
    }
}

/** A renderable chat item: a normal message, or a collapsed tool-activity (call + result). */
private sealed interface ChatItem {
    val key: Long
    data class Msg(val msg: MessageEntity) : ChatItem { override val key get() = msg.id }
    data class ToolActivity(
        val call: MessageEntity?,      // the assistant row containing the tool-call JSON
        val parsed: ToolCall?,
        val result: MessageEntity?,    // the following tool-result row, if any
    ) : ChatItem {
        override val key get() = (call?.id ?: result?.id ?: 0L)
    }
}

/** Fold consecutive tool-call + tool-result rows into a single [ChatItem.ToolActivity]. */
private fun pairChatItems(rows: List<MessageEntity>): List<ChatItem> = buildList {
    var i = 0
    while (i < rows.size) {
        val m = rows[i]
        val call = if (m.role == "assistant") Tools.parseToolCall(m.content) else null
        when {
            call != null -> {
                val result = rows.getOrNull(i + 1)?.takeIf { it.role == "tool" }
                add(ChatItem.ToolActivity(m, call, result))
                i += if (result != null) 2 else 1
            }
            m.role == "tool" -> { add(ChatItem.ToolActivity(null, null, m)); i++ }
            else -> { add(ChatItem.Msg(m)); i++ }
        }
    }
}

@Composable
private fun EmptyChatState(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val suggestions = listOf(
        "Summarize a document" to "Summarize the document ",
        "What's on my calendar?" to "What's on my calendar today?",
        "Brief me on my day" to "Give me a brief on my day",
        "Search my notes" to "Search my notes for ",
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("What can I help with?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        suggestions.forEach { (label, prefill) ->
            OutlinedButton(
                onClick = { onPick(prefill) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: MessageEntity) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            if (isUser) {
                Text(
                    text = msg.content.ifEmpty { "…" },
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                // Render assistant answers as Markdown (bold, lists, code) — big quality win.
                MarkdownText(
                    text = msg.content.ifEmpty { "…" },
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/** One quiet, expandable line for tool activity: "✓ Checked your calendar ▸" → raw on expand. */
@Composable
private fun ToolActivityBubble(item: ChatItem.ToolActivity) {
    var expanded by remember { mutableStateOf(false) }
    val toolName = item.parsed?.name
    val label = if (toolName != null) ToolLabels.done(toolName) else "Tool result"

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { expanded = !expanded }
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.width(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Hide details" else "Show details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    item.call?.let {
                        Text(
                            it.content.trim().take(400),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    item.result?.let {
                        Text(
                            it.content.take(1000),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoriesScreen(vm: MainViewModel) {
    val memories by vm.memories.collectAsState()
    val count by vm.memoryCount.collectAsState()

    Dialog(onDismissRequest = { vm.closeMemories() }) {
        Card {
            Column(modifier = Modifier.padding(16.dp).widthIn(max = 480.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Memories ($count)", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.closeMemories() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                Text(
                    "Everything you share, dictate, or clip — searchable offline. " +
                        "Ask about them in chat too.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = vm.memorySearch,
                    onValueChange = { vm.onMemorySearchChange(it) },
                    label = { Text("Search memories") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (vm.memoryStatus.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(vm.memoryStatus, style = MaterialTheme.typography.labelSmall)
                }

                Spacer(Modifier.height(8.dp))
                if (memories.isEmpty()) {
                    Text(
                        "No memories yet. Share text/links/screenshots to \"Save to memory\", " +
                            "or use the Quick Settings tile.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(memories, key = { it.id }) { m ->
                            MemoryRow(
                                item = m,
                                onDelete = { vm.deleteMemory(m.id) },
                                onTogglePin = { vm.togglePinMemory(m.id, !m.pinned) }
                            )
                            HorizontalDivider()
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.saveClipboardToMemory() }, modifier = Modifier.weight(1f)) {
                        Text("Save clipboard")
                    }
                    OutlinedButton(
                        onClick = { vm.exportMemories() },
                        enabled = count > 0, modifier = Modifier.weight(1f)
                    ) { Text("Export") }
                    OutlinedButton(
                        onClick = { vm.clearMemories() },
                        enabled = count > 0, modifier = Modifier.weight(1f)
                    ) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun MemoryRow(
    item: com.example.androidllm.data.MemoryListItem,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.text, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            val fmt = remember { java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US) }
            Text(
                "${item.type} · ${fmt.format(java.util.Date(item.createdAt))}",
                style = MaterialTheme.typography.labelSmall
            )
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                if (item.pinned) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = "Pin"
            )
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatOptionsSheet(vm: MainViewModel) {
    ModalBottomSheet(onDismissRequest = { vm.showChatOptions = false }) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Chat options", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Qwen3-4B, running entirely on your phone.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Technical: Q4_K_M quantization.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            OptionRow(
                title = "Fast replies",
                subtitle = "Skip the model's step-by-step thinking for quicker answers.",
                checked = vm.disableThinking,
                onChange = { vm.disableThinking = it }
            )
            HorizontalDivider()
            OptionRow(
                title = "Use tools",
                subtitle = "Let the assistant search the web, read files, and use your calendar.",
                checked = vm.toolsEnabled,
                onChange = { vm.toolsEnabled = it }
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentsScreen(vm: MainViewModel) {
    val fileCount by vm.docFileCount.collectAsState()
    val chunkCount by vm.docChunkCount.collectAsState()
    val files by vm.docFiles.collectAsState()
    val busy = vm.docPhase == MainViewModel.DocPhase.DOWNLOADING ||
        vm.docPhase == MainViewModel.DocPhase.LOADING ||
        vm.docPhase == MainViewModel.DocPhase.INDEXING
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemoveAll by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = { vm.closeDocuments() }) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).widthIn(max = 460.dp)) {
            // Hero status in plain words — no "snippets", no raw path.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (chunkCount == 0) "No documents yet" else "$fileCount documents ready",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)
                )
                if (chunkCount > 0) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rebuild library") },
                            onClick = { menuOpen = false; vm.indexDocuments(force = true) })
                        DropdownMenuItem(text = { Text("Remove all documents") },
                            onClick = { menuOpen = false; confirmRemoveAll = true })
                    }
                }
            }
            Text(
                "Let the assistant read your workspace files so it can answer from them — offline.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Answer using my documents", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                Switch(
                    checked = vm.ragEnabled,
                    onCheckedChange = { vm.ragEnabled = it },
                    enabled = chunkCount > 0
                )
            }

            Spacer(Modifier.height(12.dp))
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(vm.docStatus.ifEmpty { "Working…" }, style = MaterialTheme.typography.bodySmall)
                }
            } else if (vm.docStatus.isNotEmpty()) {
                Text(vm.docStatus, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // The library, made concrete: the actual indexed files.
            if (files.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    files.forEach { f ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Description, contentDescription = null,
                                modifier = Modifier.width(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.path, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(UiFormat.relative(f.mtime),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.removeDocument(f.path) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.indexDocuments(force = false) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (vm.docPhase == MainViewModel.DocPhase.IDLE)
                        "Get started (~34 MB download)"
                    else "Update documents"
                )
            }
        }
    }

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text("Remove all documents?") },
            text = { Text("This clears the search library. Your files are not deleted.") },
            confirmButton = {
                TextButton(onClick = { confirmRemoveAll = false; vm.clearDocuments() }) {
                    Text("Remove all")
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("Cancel") } }
        )
    }
}

private fun hasAudioPermission(context: android.content.Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulesScreen(vm: MainViewModel) {
    val schedules by vm.schedules.collectAsState()
    var editing by remember { mutableStateOf<com.example.androidllm.data.ScheduleEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    // Ask for notification permission so briefing results can be delivered (API 33+).
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    ModalBottomSheet(onDismissRequest = { vm.closeSchedules() }) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).widthIn(max = 460.dp)) {
            Text("Scheduled prompts", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Run a saved prompt automatically and get the result as a notification.",
                    style = MaterialTheme.typography.bodySmall
                )

                val context = LocalContext.current
                var exactOk by remember { mutableStateOf(ScheduleAlarms.canScheduleExact(context)) }
                if (!exactOk) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Text(
                                "Exact alarms are off. Schedules may not run on time. " +
                                    "Grant \"Alarms & reminders\" so briefings fire reliably.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                                android.net.Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    }
                                }
                            }) { Text("Allow alarms & reminders") }
                        }
                    }
                    LaunchedEffect(Unit) { exactOk = ScheduleAlarms.canScheduleExact(context) }
                }
                Spacer(Modifier.height(8.dp))

                if (schedules.isEmpty()) {
                    Text(
                        "No schedules yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        schedules.forEach { s ->
                            ScheduleRow(
                                schedule = s,
                                onToggle = { vm.toggleSchedule(s, it) },
                                onEdit = { editing = s; showEditor = true }
                            )
                            HorizontalDivider()
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { editing = null; showEditor = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("New schedule")
                }
        }
    }

    if (showEditor) {
        ScheduleEditor(
            existing = editing,
            onDismiss = { showEditor = false },
            onSave = { name, prompt, hour, minute, daysMask, tools ->
                vm.saveSchedule(editing, name, prompt, hour, minute, daysMask, tools)
                showEditor = false
            },
            onDelete = editing?.let { s -> { vm.deleteSchedule(s); showEditor = false } }
        )
    }
}

@Composable
private fun ScheduleRow(
    schedule: com.example.androidllm.data.ScheduleEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    // One control per row: tap the row to edit; only the enable toggle is inline (delete lives
    // inside the editor, so it can't be hit by accident next to the everyday toggle).
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onEdit() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(schedule.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                UiFormat.scheduleInWords(schedule.hour, schedule.minute, schedule.daysMask),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            schedule.lastRunAt?.let {
                Text(
                    "Last ran ${UiFormat.relative(it)} ✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = schedule.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun ScheduleEditor(
    existing: com.example.androidllm.data.ScheduleEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Int, Int, Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var prompt by remember { mutableStateOf(existing?.prompt ?: "") }
    var hour by remember { mutableStateOf(existing?.hour ?: 8) }
    var minute by remember { mutableStateOf(existing?.minute ?: 0) }
    var daysMask by remember { mutableStateOf(existing?.daysMask ?: 0) }
    var tools by remember { mutableStateOf(existing?.toolsEnabled ?: true) }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .widthIn(max = 460.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    if (existing == null) "New schedule" else "Edit schedule",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Time", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberStepper("Hour", hour, 0, 23) { hour = it }
                    Spacer(Modifier.width(16.dp))
                    NumberStepper("Min", minute, 0, 59, step = 5) { minute = it }
                }
                Spacer(Modifier.height(12.dp))
                Text("Days", style = MaterialTheme.typography.labelLarge)
                DayPicker(daysMask) { daysMask = it }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow tools (web, calendar…)", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = tools, onCheckedChange = { tools = it })
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(name, prompt, hour, minute, daysMask, tools) },
                        enabled = prompt.isNotBlank()
                    ) { Text("Save") }
                }
                // Destructive action lives here, quietly, one step away from everyday controls.
                if (onDelete != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Delete schedule") }
                }
            }
        }
    }
}

@Composable
private fun NumberStepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onChange(((value - step).coerceAtLeast(min))) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.width(40.dp)
            ) { Text("–") }
            Text(
                "%02d".format(value),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.widthIn(min = 40.dp),
                textAlign = TextAlign.Center
            )
            OutlinedButton(
                onClick = { onChange(((value + step).coerceAtMost(max))) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.width(40.dp)
            ) { Text("+") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPicker(daysMask: Int, onChange: (Int) -> Unit) {
    val names = listOf("S", "M", "T", "W", "T", "F", "S")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { i, n ->
            val bit = 1 shl i
            val selected = daysMask == 0 || (daysMask and bit) != 0
            FilterChip(
                selected = selected && daysMask != 0,
                onClick = {
                    // Treat "every day" (0) as all-selected when the user starts toggling.
                    val base = if (daysMask == 0) 0b1111111 else daysMask
                    val toggled = base xor bit
                    onChange(if (toggled == 0b1111111) 0 else toggled)
                },
                label = { Text(n) }
            )
        }
    }
    Text(
        ScheduleTime.daysLabel(daysMask),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp)
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(vm: MainViewModel) {
    val context = LocalContext.current
    var showAdvanced by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { vm.closeSettings() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            // Storage access as a status row with an inline action.
            if (vm.hasPersistentStorage) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("File access granted", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("File access needed",
                            style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Without it, files are saved to private app storage you can't browse.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = {
                                (context as? android.app.Activity)?.let {
                                    ModelStorage.requestAllFilesAccess(it)
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text("Grant") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Where files are saved", style = MaterialTheme.typography.titleSmall)
            Text(
                "Where the assistant saves files it creates and looks for files you mention.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            // Preset folders as selectable cards named by meaning; raw path is fine print in
            // the selected card only.
            val current = vm.workspacePathInput.ifBlank { vm.workspaceDir }
            vm.workspacePresets.forEach { (label, path) ->
                val selected = path == current
                Card(
                    onClick = { vm.workspacePathInput = path },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            if (selected) {
                                Text(path, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Advanced: raw free-text path, behind a disclosure.
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide advanced" else "Advanced ▸")
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = vm.workspacePathInput,
                    onValueChange = { vm.workspacePathInput = it },
                    label = { Text("Custom folder path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { vm.closeSettings() }) { Text("Close") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    vm.applyWorkspacePath(vm.workspacePathInput)
                    vm.closeSettings()
                }) { Text("Save") }
            }
        }
    }
}
