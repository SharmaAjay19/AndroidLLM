package com.example.androidllm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidllm.data.ChatListItem
import com.example.androidllm.data.MessageEntity
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScaffold(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(vm: MainViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val chats by vm.chats.collectAsState()
    val activeId by vm.activeChatId.collectAsState()

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
                    onDeleteChat = vm::deleteChat,
                    enabled = !vm.isGenerating
                )
            }
        }
    ) {
        Scaffold(
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
                            val tps = vm.lastTps
                            val sub = when {
                                vm.isGenerating -> "generating…"
                                tps != null -> "last: %.1f tok/s".format(tps)
                                else -> "Qwen3-4B Q4_K_M"
                            }
                            Text(sub, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.newChat() }, enabled = !vm.isGenerating) {
                            Icon(Icons.Filled.Add, contentDescription = "New chat")
                        }
                    }
                )
            }
        ) { padding ->
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
            placeholder = { Text("Search chats") }
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
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(chats, key = { it.id }) { chat ->
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatRow(
    chat: ChatListItem,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    chat.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val snippet = chat.snippet?.replace("\n", " ")?.trim().orEmpty()
                if (snippet.isNotEmpty()) {
                    Text(
                        snippet,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete chat")
            }
        }
    }
}

@Composable
private fun SetupSection(vm: MainViewModel) {
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

    // Apply the live streaming overlay on top of persisted rows.
    val display = rows.map { m ->
        if (m.id == vm.streamingId) m.copy(content = vm.streamingText.ifEmpty { "…" }) else m
    }

    LaunchedEffect(display.size, vm.streamingText) {
        if (display.isNotEmpty()) listState.animateScrollToItem(display.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Fast mode (no thinking)", style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = vm.disableThinking,
                onCheckedChange = { vm.disableThinking = it },
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        if (display.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Ask anything to start a new chat.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(display, key = { _, m -> m.id }) { _, msg ->
                    MessageBubble(msg)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask something…") },
                enabled = !vm.isGenerating
            )
            IconButton(
                onClick = {
                    vm.send(input)
                    input = ""
                },
                enabled = input.isNotBlank() && !vm.isGenerating
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
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
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = msg.content.ifEmpty { "…" },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
