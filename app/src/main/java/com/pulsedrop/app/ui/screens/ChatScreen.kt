package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.model.ChatAttachment
import com.pulsedrop.app.model.ChatGroup
import com.pulsedrop.app.model.ClipMessage
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.ui.components.AttachmentPreview
import com.pulsedrop.app.ui.components.formatAttachmentSize
import com.pulsedrop.app.ui.components.PreviewKind
import com.pulsedrop.app.ui.components.previewKindOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Instagram-style messaging: a thread inbox, then a full-screen conversation with
 * day separators, run-grouped bubbles and an emoji/attach/send composer.
 */
@Composable
fun ChatScreen(
    messages: List<ClipMessage>,
    myName: String,
    typingUsers: List<String>,
    pairedDevices: List<PairedDevice>,
    groups: List<ChatGroup>,
    lanPeers: List<PeerDevice>,
    lastReadAt: Map<String, Long>,
    selectedThreadId: String?,
    onSelectThread: (String?) -> Unit,
    onStartCall: (String, Boolean) -> Unit,
    onSend: (String, String?) -> Unit,
    onTyping: (Boolean) -> Unit,
    onAttach: (String?) -> Unit,
    resolveAttachment: (ChatAttachment) -> File?,
    onCreateGroup: (String, List<String>) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onMarkRead: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val threads = remember(messages, pairedDevices, groups, lanPeers, myName, lastReadAt) {
        buildThreads(messages, pairedDevices, groups, lanPeers, myName, lastReadAt)
    }

    val active = threads.firstOrNull { it.id == selectedThreadId }

    LaunchedEffect(selectedThreadId, threads.size) {
        if (selectedThreadId != null && active == null) onSelectThread(null)
    }

    if (active != null) {
        LaunchedEffect(active.id, messages.size) { onMarkRead(active.id) }
        ConversationView(
            thread = active,
            myName = myName,
            typingUsers = typingUsers,
            onBack = { onSelectThread(null) },
            onStartCall = { video -> active.pid?.let { onStartCall(it, video) } },
            onSend = { text -> onSend(text, active.id) },
            onAttach = { onAttach(active.id) },
            onTyping = onTyping,
            resolveAttachment = resolveAttachment,
            onDisband = if (active.isGroup) {
                { onDeleteGroup(active.id!!.removePrefix("group:")) }
            } else null,
            modifier = modifier
        )
    } else {
        InboxView(
            threads = threads,
            typingUsers = typingUsers,
            pairedDevices = pairedDevices,
            lanPeers = lanPeers,
            onOpen = { onSelectThread(it) },
            onCreateGroup = onCreateGroup,
            modifier = modifier
        )
    }
}

// ------------------------------------------------------------------ threads

private const val ROOM_ID = "room"
private const val TWO_MINUTES = 120_000L

private val AVATAR_PALETTE: List<Pair<Int, Int>> = listOf(
    0xFF2563EB.toInt() to 0xFF7C3AED.toInt(),
    0xFF0EA5E9.toInt() to 0xFF2563EB.toInt(),
    0xFF10B981.toInt() to 0xFF0EA5E9.toInt(),
    0xFFF59E0B.toInt() to 0xFFEF4444.toInt(),
    0xFF8B5CF6.toInt() to 0xFFEC4899.toInt(),
    0xFF14B8A6.toInt() to 0xFF6366F1.toInt()
)

private data class ChatThread(
    val id: String?,
    val title: String,
    val hint: String,
    val encrypted: Boolean,
    val isGroup: Boolean,
    val memberNames: List<String>,
    val pid: String?,
    val messages: List<ClipMessage>,
    val unread: Int
) {
    val lastActivity: Long get() = messages.lastOrNull()?.timestamp ?: 0L
}

private fun buildThreads(
    messages: List<ClipMessage>,
    paired: List<PairedDevice>,
    groups: List<ChatGroup>,
    lanPeers: List<PeerDevice>,
    myName: String,
    lastReadAt: Map<String, Long>
): List<ChatThread> {
    fun unreadFor(id: String?, list: List<ClipMessage>): Int {
        val since = lastReadAt[id ?: ROOM_ID] ?: 0L
        return list.count { it.sender != myName && it.timestamp > since }
    }

    val out = mutableListOf<ChatThread>()

    val room = messages.filter { it.threadId == null }
    out += ChatThread(
        id = ROOM_ID,
        title = "Everyone",
        hint = if (room.isEmpty()) "Open LAN room · say hi" else "Open LAN room",
        encrypted = false,
        isGroup = false,
        memberNames = emptyList(),
        pid = null,
        messages = room,
        unread = unreadFor(null, room)
    )

    groups.forEach { g ->
        val tid = "group:${g.id}"
        val list = messages.filter { it.threadId == tid }
        val names = g.memberPids.mapNotNull { pid -> paired.firstOrNull { it.pid == pid }?.name }
        out += ChatThread(
            id = tid,
            title = g.name,
            hint = "${names.size + 1} members · encrypted",
            encrypted = true,
            isGroup = true,
            memberNames = listOf(myName) + names,
            pid = null,
            messages = list,
            unread = unreadFor(tid, list)
        )
    }

    paired.forEach { p ->
        val tid = "pair:${p.pid}"
        val list = messages.filter { it.threadId == tid }
        out += ChatThread(
            id = tid,
            title = p.name,
            hint = "Private · only these two devices",
            encrypted = true,
            isGroup = false,
            memberNames = listOf(myName, p.name),
            pid = p.pid,
            messages = list,
            unread = unreadFor(tid, list)
        )
    }

    messages.asSequence()
        .mapNotNull { it.threadId }
        .filter { it.startsWith("ip:") }
        .distinct()
        .forEach { tid ->
            val ip = tid.removePrefix("ip:")
            val list = messages.filter { it.threadId == tid }
            val named = lanPeers.firstOrNull { it.ip == ip }?.name
                ?: list.firstOrNull { it.sender != myName }?.sender
            out += ChatThread(
                id = tid,
                title = named ?: ip,
                hint = if (named != null) "Direct · $ip" else "Direct message to $ip",
                encrypted = false,
                isGroup = false,
                memberNames = emptyList(),
                pid = null,
                messages = list,
                unread = unreadFor(tid, list)
            )
        }

    return out.sortedWith(
        compareByDescending<ChatThread> { it.lastActivity }
            .thenBy { if (it.id == ROOM_ID) 0 else 1 }
            .thenBy { it.title.lowercase(Locale.getDefault()) }
    )
}

// ------------------------------------------------------------------ inbox

@Composable
private fun InboxView(
    threads: List<ChatThread>,
    typingUsers: List<String>,
    pairedDevices: List<PairedDevice>,
    lanPeers: List<PeerDevice>,
    onOpen: (String?) -> Unit,
    onCreateGroup: (String, List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showIpDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val visible = remember(threads, query) {
        if (query.isBlank()) threads
        else threads.filter {
            it.title.contains(query.trim(), ignoreCase = true) ||
                it.messages.lastOrNull()?.text.orEmpty().contains(query.trim(), ignoreCase = true)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Chats",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface
                )
                Text(
                    text = threads.firstOrNull { it.id == ROOM_ID }?.messages?.size?.let { "$it room messages" }
                        ?: "No traffic yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colorScheme.primaryContainer)
                        .testTag("chat_new_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New conversation",
                        tint = colorScheme.onPrimaryContainer
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("New encrypted group") },
                        leadingIcon = { Icon(Icons.Default.Groups, null) },
                        enabled = pairedDevices.size >= 2,
                        onClick = {
                            menuOpen = false
                            showGroupDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Message an IP directly") },
                        leadingIcon = { Icon(Icons.Default.Router, null) },
                        onClick = {
                            menuOpen = false
                            showIpDialog = true
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search conversations") },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = colorScheme.onSurfaceVariant) },
            singleLine = true,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag("chat_search")
        )

        if (visible.isEmpty()) {
            EmptyState(
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant
                    )
                },
                title = if (query.isBlank()) "No conversations yet" else "Nothing matches \"$query\"",
                subtitle = if (pairedDevices.isEmpty())
                    "Pair a device from the Radar tab to open an encrypted channel, or talk to Everyone on this network."
                else
                    "Open the room below, or start a private channel with a paired device."
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(visible, key = { it.id ?: ROOM_ID }) { t ->
                    ThreadRow(
                        thread = t,
                        typingUsers = typingUsers,
                        onClick = { onOpen(t.id) }
                    )
                }
            }
        }

        if (pairedDevices.isNotEmpty()) {
            HorizontalDivider(color = colorScheme.outlineVariant)
            Text(
                text = "QUICK CHANNELS",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp)
            )
            pairedDevices.forEach { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpen("pair:${p.pid}") }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Message ${p.name} privately",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.primary,
                        modifier = Modifier.testTag("chat_channel_pair_${p.pid}")
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showGroupDialog) {
        GroupDialog(
            pairedDevices = pairedDevices,
            onDismiss = { showGroupDialog = false },
            onCreate = { name, pids ->
                onCreateGroup(name, pids)
                showGroupDialog = false
            }
        )
    }

    if (showIpDialog) {
        IpDialog(
            lanPeers = lanPeers,
            onDismiss = { showIpDialog = false },
            onPick = { ip ->
                showIpDialog = false
                onOpen("ip:$ip")
            }
        )
    }
}

@Composable
private fun ThreadRow(
    thread: ChatThread,
    typingUsers: List<String>,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val last = thread.messages.lastOrNull()
    val unread = thread.unread > 0
    val typing = if (thread.id == ROOM_ID) typingUsers.firstOrNull() else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp)
            .testTag(
                "chat_channel_" + when (thread.id) {
                    null, ROOM_ID -> "everyone"
                    else -> thread.id.replace(":", "_")
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThreadAvatar(
            name = thread.title,
            size = 54.dp,
            isGroup = thread.isGroup,
            isRoom = thread.id == ROOM_ID
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (thread.encrypted) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Encrypted",
                        tint = colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                }
                if (last != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = shortTime(last.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (unread) colorScheme.primary else colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val body = last?.text?.takeIf { it.isNotBlank() }
                    ?: last?.attachment?.name?.let { "File: $it" }
                    ?: ""
                val preview = when {
                    typing != null -> "$typing is typing…"
                    last == null -> thread.hint
                    last.sender == thread.title -> body
                    last.sender.isNotEmpty() -> "${last.sender}: $body"
                    else -> body
                }
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (unread) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (unread) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (thread.unread > 9) "9+" else thread.unread.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupDialog(
    pairedDevices: List<PairedDevice>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New encrypted group") },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                Text(
                    text = "Every member gets the message sealed with their own pairwise key — a device you are not paired with cannot read anything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group_name_input")
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text("Members", style = MaterialTheme.typography.labelMedium)
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    pairedDevices.forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selected = if (p.pid in selected) selected - p.pid
                                    else selected + p.pid
                                }
                                .padding(vertical = 2.dp)
                                .testTag("group_member_${p.pid}")
                        ) {
                            Checkbox(checked = p.pid in selected, onCheckedChange = null)
                            Text(p.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (selected.size < 2) "Pick at least two devices"
                    else "${selected.size} devices selected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim(), selected.toList()) },
                enabled = name.isNotBlank() && selected.size >= 2
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun IpDialog(
    lanPeers: List<PeerDevice>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    var manual by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message an IP directly") },
        text = {
            Column(modifier = Modifier.heightIn(max = 380.dp)) {
                Text(
                    text = "A direct thread delivers to that address over the local network, but keeps the exchange in its own conversation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (lanPeers.isEmpty()) {
                    Text(
                        text = "Nothing on the radar yet — scan from the Radar tab, or type an address below.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        lanPeers.forEach { peer ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onPick(peer.ip) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                                    .testTag("ip_peer_${peer.ip}")
                            ) {
                                ThreadAvatar(name = peer.name, size = 34.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(peer.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "${peer.ip}:${peer.port}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    placeholder = { Text("192.168.1.24") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ip_manual_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onPick(manual.trim()) },
                enabled = manual.trim().let { it.count { c -> c == '.' } == 3 && it.all { c -> c.isDigit() || c == '.' } }
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ------------------------------------------------------------------ conversation

@Composable
private fun ConversationView(
    thread: ChatThread,
    myName: String,
    typingUsers: List<String>,
    onBack: () -> Unit,
    onStartCall: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onAttach: () -> Unit,
    onTyping: (Boolean) -> Unit,
    resolveAttachment: (ChatAttachment) -> File?,
    onDisband: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("") }
    var lastTypingSent by remember { mutableLongStateOf(0L) }
    var previewAttachment by remember { mutableStateOf<ChatAttachment?>(null) }
    val listState = rememberLazyListState()
    val rows = remember(thread.messages, myName) { layoutRows(thread.messages, myName) }

    previewAttachment?.let { att ->
        AttachmentPreview(
            attachment = att,
            file = resolveAttachment(att),
            onDismiss = { previewAttachment = null }
        )
    }

    LaunchedEffect(thread.messages.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_btn")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            ThreadAvatar(
                name = thread.title,
                size = 38.dp,
                isGroup = thread.isGroup,
                isRoom = thread.id == ROOM_ID
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = thread.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = thread.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (thread.encrypted) colorScheme.primary else colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (thread.pid != null) {
                IconButton(
                    onClick = { onStartCall(false) },
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(colorScheme.secondaryContainer)
                        .testTag("call_audio_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Voice call",
                        tint = colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(
                    onClick = { onStartCall(true) },
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(colorScheme.secondaryContainer)
                        .testTag("call_video_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Video call",
                        tint = colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(19.dp)
                    )
                }
            } else if (onDisband != null) {
                TextButton(onClick = onDisband, modifier = Modifier.testTag("chat_delete_group_btn")) {
                    Text("Disband", color = colorScheme.error)
                }
            }
        }
        HorizontalDivider(color = colorScheme.outlineVariant)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (thread.messages.isEmpty()) {
                EmptyState(
                    icon = {
                        Icon(
                            imageVector = if (thread.encrypted) Icons.Default.Lock
                            else Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant
                        )
                    },
                    title = "Say hi to ${thread.title}",
                    subtitle = if (thread.encrypted)
                        "Everything here is sealed on your device and opened only by ${thread.title}. No server can read it."
                    else
                        "Messages in this thread travel straight across the local network."
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is RowItem.Day -> DaySeparator(row.label)
                            is RowItem.Message -> MessageRow(
                                msg = row.msg,
                                mine = row.mine,
                                lastOfRun = row.lastOfRun,
                                showSender = row.showSender,
                                grouped = thread.isGroup,
                                onOpenAttachment = { previewAttachment = it }
                            )
                        }
                    }
                    if (typingUsers.isNotEmpty()) {
                        item(key = "typing") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp, top = 6.dp)
                            ) {
                                TypingDots()
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = typingUsers.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (thread.memberNames.size > 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                thread.memberNames.forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        ChatComposer(
            input = input,
            onInputChange = {
                input = it
                val now = System.currentTimeMillis()
                if (it.isNotBlank() && now - lastTypingSent > 2000) {
                    lastTypingSent = now
                    onTyping(true)
                }
            },
            canAttach = true,
            onAttach = onAttach,
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    onSend(text)
                    input = ""
                    onTyping(false)
                    lastTypingSent = 0L
                }
            }
        )
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatComposer(
    input: String,
    onInputChange: (String) -> Unit,
    canAttach: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var emojiOpen by remember { mutableStateOf(false) }

    Column {
        if (emojiOpen) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 104.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EMOJI_SET.forEach { e ->
                    Text(
                        text = e,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onInputChange(input + e) }
                            .padding(6.dp)
                    )
                }
            }
            HorizontalDivider(color = colorScheme.outlineVariant)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(
                onClick = { emojiOpen = !emojiOpen },
                modifier = Modifier.testTag("chat_emoji_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.EmojiEmotions,
                    contentDescription = "Emoji",
                    tint = if (emojiOpen) colorScheme.primary else colorScheme.onSurfaceVariant
                )
            }
            if (canAttach) {
                IconButton(onClick = onAttach, modifier = Modifier.testTag("chat_attach_btn")) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Send a file",
                        tint = colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text("Message") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 140.dp)
                    .testTag("chat_input")
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (input.isBlank()) colorScheme.surfaceContainerHigh else colorScheme.primary
                    )
                    .testTag("chat_send_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (input.isBlank()) colorScheme.onSurfaceVariant else colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentCard(
    attachment: ChatAttachment,
    mine: Boolean,
    onOpen: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val kind = previewKindOf(attachment.name)
    val glyph = when (kind) {
        PreviewKind.IMAGE -> Icons.Default.Image
        PreviewKind.VIDEO -> Icons.Default.Videocam
        PreviewKind.AUDIO -> Icons.Default.AudioFile
        PreviewKind.PDF -> Icons.Default.PictureAsPdf
        PreviewKind.TEXT -> Icons.Default.Description
        PreviewKind.OTHER -> Icons.Default.InsertDriveFile
    }
    val fill = if (mine) Color.White.copy(alpha = 0.14f) else colorScheme.surface
    val edge = if (mine) Color.White.copy(alpha = 0.32f) else colorScheme.outlineVariant
    val title = if (mine) Color.White else colorScheme.onSurface
    val sub = if (mine) Color.White.copy(alpha = 0.78f) else colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .border(1.dp, edge, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (mine) Color.White.copy(alpha = 0.2f) else colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = if (mine) Color.White else colorScheme.onPrimaryContainer,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(modifier = Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatAttachmentSize(attachment.size) + "  ·  " + attachmentKindLabel(attachment),
                style = MaterialTheme.typography.labelSmall,
                color = sub
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (mine) Color.White.copy(alpha = 0.22f) else colorScheme.primary.copy(alpha = 0.10f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = null,
                tint = if (mine) Color.White else colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Preview",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (mine) Color.White else colorScheme.primary
            )
        }
    }
}

private fun attachmentKindLabel(attachment: ChatAttachment): String = when (previewKindOf(attachment.name)) {
    PreviewKind.IMAGE -> "Image"
    PreviewKind.VIDEO -> "Video"
    PreviewKind.AUDIO -> "Audio"
    PreviewKind.PDF -> "PDF"
    PreviewKind.TEXT -> "Text"
    PreviewKind.OTHER -> attachment.name.substringAfterLast('.', "File").uppercase()
}

@Composable
private fun MessageRow(
    msg: ClipMessage,
    mine: Boolean,
    lastOfRun: Boolean,
    showSender: Boolean,
    grouped: Boolean,
    onOpenAttachment: (ChatAttachment) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (mine) 18.dp else if (lastOfRun) 6.dp else 18.dp,
        bottomEnd = if (mine && lastOfRun) 6.dp else 18.dp
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        if (showSender && !mine) {
            Text(
                text = msg.sender,
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.primary,
                modifier = Modifier.padding(start = 14.dp, bottom = 2.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!mine && grouped) {
                if (lastOfRun) {
                    ThreadAvatar(name = msg.sender, size = 26.dp)
                } else {
                    Spacer(modifier = Modifier.width(26.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(bubbleShape)
                    .background(if (mine) colorScheme.primary else colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Column {
                    msg.attachment?.let { att ->
                        AttachmentCard(
                            attachment = att,
                            mine = mine,
                            onOpen = { onOpenAttachment(att) }
                        )
                        if (msg.text.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (msg.text.isNotEmpty() || msg.attachment == null) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (mine) colorScheme.onPrimary else colorScheme.onSurface
                            )
                            if (msg.encrypted) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Encrypted",
                                    tint = if (mine) colorScheme.onPrimary.copy(alpha = 0.85f)
                                    else colorScheme.primary.copy(alpha = 0.85f),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        if (lastOfRun) {
            Text(
                text = shortTime(msg.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 14.dp, end = 14.dp)
            )
        }
    }
}

@Composable
private fun DaySeparator(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun TypingDots() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.35f + 0.2f * i))
            )
        }
    }
}

// ------------------------------------------------------------------ atoms

@Composable
private fun ThreadAvatar(
    name: String,
    size: Dp,
    isGroup: Boolean = false,
    isRoom: Boolean = false
) {
    val idx = kotlin.math.abs(name.hashCode()) % AVATAR_PALETTE.size
    val (from, to) = AVATAR_PALETTE[idx]
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(from), Color(to)))),
        contentAlignment = Alignment.Center
    ) {
        when {
            isRoom -> Icon(
                imageVector = Icons.Default.Groups,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.5f)
            )
            isGroup -> Icon(
                imageVector = Icons.Default.PersonAddAlt1,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.5f)
            )
            else -> Text(
                text = name.take(1).uppercase(Locale.getDefault()),
                style = if (size >= 40.dp) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

private sealed class RowItem {
    abstract val key: String

    data class Day(val label: String, override val key: String) : RowItem()

    data class Message(
        val msg: ClipMessage,
        val mine: Boolean,
        val lastOfRun: Boolean,
        val showSender: Boolean
    ) : RowItem() {
        override val key: String get() = msg.id
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = a }
    val c2 = Calendar.getInstance().apply { timeInMillis = b }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
        c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
}

private fun layoutRows(messages: List<ClipMessage>, myName: String): List<RowItem> {
    val sorted = messages.sortedBy { it.timestamp }
    val out = mutableListOf<RowItem>()
    sorted.forEachIndexed { i, msg ->
        val prev = sorted.getOrNull(i - 1)
        val next = sorted.getOrNull(i + 1)
        if (prev == null || !sameDay(prev.timestamp, msg.timestamp)) {
            out += RowItem.Day(dayLabel(msg.timestamp), "day-${msg.id}")
        }
        val continuesRun = prev != null && prev.sender == msg.sender &&
            sameDay(prev.timestamp, msg.timestamp) &&
            msg.timestamp - prev.timestamp < TWO_MINUTES
        val endsRun = next == null || next.sender != msg.sender ||
            !sameDay(next.timestamp, msg.timestamp) ||
            next.timestamp - msg.timestamp >= TWO_MINUTES
        out += RowItem.Message(
            msg = msg,
            mine = msg.sender == myName,
            lastOfRun = endsRun,
            showSender = msg.sender != myName && !continuesRun
        )
    }
    return out
}

private fun dayLabel(ts: Long): String = when {
    sameDay(System.currentTimeMillis(), ts) -> "Today"
    sameDay(System.currentTimeMillis() - 86_400_000L, ts) -> "Yesterday"
    else -> SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(ts))
}

private fun shortTime(ts: Long): String {
    val now = System.currentTimeMillis()
    return when {
        now - ts < 60_000L -> "now"
        sameDay(now, ts) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        sameDay(now - 86_400_000L, ts) -> "Yesterday"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
    }
}

private val EMOJI_SET = listOf(
    "😀", "😂", "🥰", "😎", "🤔", "🙌", "👍", "🔥", "✨", "🎉",
    "❤️", "💙", "💚", "🧡", "💜", "😅", "😊", "🙂", "😉", "😍",
    "😴", "🤯", "🙏", "👀", "💯", "✅", "🚀", "📎", "📷", "🎧",
    "☕", "🍕", "🌙", "☀️", "🏠", "💼", "📱", "🔒", "🔑", "🛰️"
)
