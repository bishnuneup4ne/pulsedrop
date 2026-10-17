package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
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
import com.pulsedrop.app.ui.components.PulseAvatar
import com.pulsedrop.app.ui.components.PulseChip
import com.pulsedrop.app.ui.components.PulseStatePanel
import com.pulsedrop.app.ui.components.AttachmentPreview
import com.pulsedrop.app.ui.components.formatAttachmentSize
import com.pulsedrop.app.ui.components.PreviewKind
import com.pulsedrop.app.ui.components.previewKindOf
import com.pulsedrop.app.ui.components.PulseRow
import com.pulsedrop.app.ui.components.PulseRowDivider
import com.pulsedrop.app.ui.components.PulseRowGroup
import com.pulsedrop.app.ui.components.PulseRowLabel
import com.pulsedrop.app.ui.theme.LocalPulseColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

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
    onStartGroupCall: (List<String>, Boolean) -> Unit,
    onStartBrowserCall: (String, String, Boolean) -> Unit,
    onCallEveryone: (List<String>, String?, String?, Boolean) -> Unit,
    onSend: (String, String?, ClipMessage?) -> Unit,
    /** Delete one bubble by id; `true` retracts it everywhere it was delivered, not just here. */
    onDelete: (String, Boolean) -> Unit,
    /** Put a reaction on a bubble, or null to take this device's off it. */
    onReact: (String, String?) -> Unit,
    /** Rewrite one of this device's own bubbles. */
    onEdit: (String, String) -> Unit,
    /** Reaches back into history, past whatever window the thread list is showing. */
    onSearchHistory: suspend (String) -> List<com.pulsedrop.app.data.SearchHit>,
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
            onStartCall = { audience, video ->
                val pid = active.pid
                val ip = active.browserIp
                when {
                    pid != null -> onStartCall(pid, video)
                    audience == "all" ->
                        onCallEveryone(active.memberPids, ip, active.browserName ?: active.title, video)
                    audience == "browser" && ip != null ->
                        onStartBrowserCall(ip, active.browserName ?: active.title, video)
                    active.memberPids.isNotEmpty() -> onStartGroupCall(active.memberPids, video)
                    ip != null -> onStartBrowserCall(ip, active.browserName ?: active.title, video)
                }
            },
            onSend = { text, reply -> onSend(text, active.id, reply) },
            onDelete = { target, forEveryone -> onDelete(target.id, forEveryone) },
            onReact = { target, reaction -> onReact(target.id, reaction) },
            onEdit = { target, text -> onEdit(target.id, text) },
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
            onSearchHistory = onSearchHistory,
            modifier = modifier
        )
    }
}

// ------------------------------------------------------------------ threads

private const val ROOM_ID = "room"
private const val TWO_MINUTES = 120_000L

private data class ChatThread(
    val id: String?,
    val title: String,
    val hint: String,
    val encrypted: Boolean,
    val isGroup: Boolean,
    val memberNames: List<String>,
    val memberPids: List<String>,
    val pid: String?,
    /** Set when the other end is a browser tab rather than a PulseDrop app. */
    val browserIp: String?,
    /** Display name for [browserIp]; the room borrows its liveliest tab as a dial target. */
    val browserName: String? = null,
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

    // Browser tabs first: the room borrows the liveliest one as its dial-a-browser target.
    val browserThreads = messages.asSequence()
        .mapNotNull { it.threadId }
        .filter { it.startsWith("ip:") }
        .distinct()
        .map { tid ->
            val ip = tid.removePrefix("ip:")
            val list = messages.filter { it.threadId == tid }
            // A tab dials the phone into the *phone's* address book ("ip:<phoneIp>"), so the
            // thread suffix is the phone, not the peer. The real browser to ring is whoever
            // wrote the other side of this thread; only when nobody but us has spoken (we
            // opened it cold from the peer list) is the suffix the tab's own address.
            val peerIp = list.firstOrNull { it.sender != myName && !it.clientIp.isNullOrEmpty() }?.clientIp
            val browserIp = peerIp ?: ip
            val named = lanPeers.firstOrNull { it.ip == browserIp }?.name
                ?: lanPeers.firstOrNull { it.ip == ip }?.name
                ?: list.firstOrNull { it.sender != myName }?.sender
            // A paired device already owns its private thread; its browser tab is not a second chat.
            if (named != null && paired.any { it.name == named }) null
            else {
                val loopback = browserIp == "127.0.0.1" || browserIp == "::1" || browserIp == "::ffff:127.0.0.1"
                ChatThread(
                    id = tid,
                    title = named ?: if (loopback) "Browser tab" else browserIp,
                    hint = if (loopback) "Web client on this device" else "Web client \u00b7 direct message",
                    encrypted = false,
                    isGroup = false,
                    memberNames = emptyList(),
                    memberPids = emptyList(),
                    pid = null,
                    browserIp = browserIp,
                    messages = list,
                    unread = unreadFor(tid, list)
                )
            }
        }
        .filterNotNull()
        .toList()

    val room = messages.filter { it.threadId == null }
    val dialable = browserThreads.maxByOrNull { it.lastActivity }
    // A tab that only ever posted to the room owns no "ip:" thread of its own, so the room
    // falls back to any browser the server is currently seeing. Without this the phone has
    // nobody to dial and a web call can only ever go one way.
    val webPeer = lanPeers.firstOrNull { it.appKind == "Browser" }
    out += ChatThread(
        id = ROOM_ID,
        title = "Everyone",
        hint = if (room.isEmpty()) "Open LAN room \u00b7 say hi" else "Open LAN room",
        encrypted = false,
        isGroup = false,
        memberNames = emptyList(),
        memberPids = paired.map { it.pid },
        pid = null,
        browserIp = dialable?.browserIp ?: webPeer?.ip,
        browserName = dialable?.title ?: webPeer?.name,
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
            hint = "${names.size + 1} members \u00b7 encrypted",
            encrypted = true,
            isGroup = true,
            memberNames = listOf(myName) + names,
            memberPids = g.memberPids,
            pid = null,
            browserIp = null,
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
            hint = "Private \u00b7 only these two devices",
            encrypted = true,
            isGroup = false,
            memberNames = listOf(myName, p.name),
            memberPids = listOf(p.pid),
            pid = p.pid,
            browserIp = null,
            messages = list,
            unread = unreadFor(tid, list)
        )
    }

    out += browserThreads

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
    onSearchHistory: suspend (String) -> List<com.pulsedrop.app.data.SearchHit>,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showIpDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showPrivateThreads by remember { mutableStateOf(false) }

    /**
     * History hits, which are not the same thing as the threads below.
     *
     * The thread list only knows the newest window of messages; this asks the index, so a word from
     * a conversation last had last year is findable. Debounced because every keystroke would be a
     * query, and run off the composition.
     */
    var historyHits by remember { mutableStateOf<List<com.pulsedrop.app.data.SearchHit>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            historyHits = emptyList()
        } else {
            delay(280)
            historyHits = onSearchHistory(query)
        }
    }

    // The inbox is for direct conversations: the open room, one-to-one IP threads, and
    // paired one-to-one channels - a pairing IS a direct chat, so it belongs here too.
    // Only groups stay behind the filter toggle.
    val visible = remember(threads, query, showPrivateThreads) {
        val scoped = if (showPrivateThreads) threads else threads.filter {
            it.id == ROOM_ID || it.id?.startsWith("ip:") == true || it.id?.startsWith("pair:") == true
        }
        if (query.isBlank()) scoped
        else scoped.filter {
            it.title.contains(query.trim(), ignoreCase = true) ||
                it.messages.lastOrNull()?.text.orEmpty().contains(query.trim(), ignoreCase = true)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
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

        val privateCount = threads.count {
            it.id != null && !it.id.startsWith("ip:") && it.id != ROOM_ID
        }
        if (privateCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PulseChip(
                    label = "Direct",
                    selected = !showPrivateThreads,
                    onClick = { showPrivateThreads = false }
                )
                PulseChip(
                    label = "Private & groups ($privateCount)",
                    selected = showPrivateThreads,
                    onClick = { showPrivateThreads = true }
                )
            }
        }

        if (visible.isEmpty() && historyHits.isEmpty()) {
            if (query.isNotBlank()) {
                PulseStatePanel(
                    icon = Icons.Outlined.SearchOff,
                    title = "Nothing matches \"$query\"",
                    message = "No conversation and no message in the history on this phone.",
                    actionLabel = "Clear search",
                    onAction = { query = "" }
                )
            } else {
                PulseStatePanel(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = "No conversations yet",
                    message = if (pairedDevices.isEmpty())
                        "Pair a device, or talk to Everyone."
                    else
                        "Open the room, or message a paired device."
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
            ) {
                itemsIndexed(visible, key = { _, t -> t.id ?: ROOM_ID }) { index, t ->
                    if (index > 0) PulseRowDivider(insetStart = 84)
                    ThreadRow(
                        thread = t,
                        typingUsers = typingUsers,
                        onClick = { onOpen(t.id) }
                    )
                }
                // Separate because the two answer different questions: the list above is "who am I
                // talking to", this is "what was said". Opening a hit goes to the conversation, not
                // to the message - the row is a pointer into a thread, not a scroll position.
                if (historyHits.isNotEmpty()) {
                    item(key = "history-label") {
                        Column {
                            if (visible.isNotEmpty()) PulseRowDivider(insetStart = 0)
                            PulseRowLabel("In history")
                        }
                    }
                    itemsIndexed(historyHits, key = { _, h -> "hit-${h.message.id}" }) { index, hit ->
                        if (index > 0) PulseRowDivider(insetStart = 20)
                        PulseRow(
                            title = hit.message.sender,
                            subtitle = hit.snippet,
                            trailing = {
                                Text(
                                    text = shortTime(hit.message.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = { onOpen(hit.message.threadId) },
                            modifier = Modifier.testTag("chat_history_hit")
                        )
                    }
                }
            }
        }

        if (pairedDevices.isNotEmpty()) {
            PulseRowLabel("Quick channels")
            pairedDevices.forEachIndexed { index, p ->
                if (index > 0) PulseRowDivider(insetStart = 70)
                PulseRow(
                    title = "Message ${p.name} privately",
                    leading = { PulseAvatar(name = p.name, size = 40) },
                    onClick = { onOpen("pair:${p.pid}") },
                    modifier = Modifier.testTag("chat_channel_pair_${p.pid}")
                )
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
    val c = LocalPulseColors.current
    val last = thread.messages.lastOrNull()
    val unread = thread.unread > 0
    val typing = if (thread.id == ROOM_ID) typingUsers.firstOrNull() else null

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

    PulseRow(
        title = thread.title,
        subtitle = preview,
        detail = last?.let { shortTime(it.timestamp) },
        leading = {
            PulseAvatar(
                name = thread.title,
                size = 54,
                icon = when {
                    thread.id == ROOM_ID -> Icons.Default.Groups
                    thread.isGroup -> Icons.Default.PersonAddAlt1
                    else -> null
                }
            )
        },
        trailing = if (unread) ({
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(c.accent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (thread.unread > 9) "9+" else thread.unread.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.onAccent
                )
            }
        }) else null,
        onClick = onClick,
        modifier = Modifier.testTag(
            "chat_channel_" + when (thread.id) {
                null, ROOM_ID -> "everyone"
                else -> thread.id.replace(":", "_")
            }
        )
    )
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
                    text = "Sealed with each member's own key.",
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
                PulseRowLabel("Members")
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    PulseRowGroup {
                        pairedDevices.forEachIndexed { index, p ->
                            if (index > 0) PulseRowDivider(insetStart = 70)
                            PulseRow(
                                title = p.name,
                                leading = { PulseAvatar(name = p.name, size = 40) },
                                showChevron = false,
                                trailing = {
                                    Checkbox(checked = p.pid in selected, onCheckedChange = null)
                                },
                                onClick = {
                                    selected = if (p.pid in selected) selected - p.pid
                                    else selected + p.pid
                                },
                                modifier = Modifier.testTag("group_member_${p.pid}")
                            )
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
                    text = "A direct local thread, kept out of the room.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (lanPeers.isEmpty()) {
                    Text(
                        text = "Nothing found yet — type an address below.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        PulseRowGroup {
                            lanPeers.forEachIndexed { index, peer ->
                                if (index > 0) PulseRowDivider(insetStart = 70)
                                PulseRow(
                                    title = peer.name,
                                    subtitle = if (peer.port > 0) "${peer.ip}:${peer.port}"
                                    else "${peer.ip} · ${peer.appKind?.lowercase() ?: "browser"}, chat only",
                                    leading = { PulseAvatar(name = peer.name, size = 40) },
                                    onClick = { onPick(peer.ip) },
                                    modifier = Modifier.testTag("ip_peer_${peer.ip}")
                                )
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
private fun HeaderCallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tag: String,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(container)
            .testTag(tag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = content,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun ConversationView(
    thread: ChatThread,
    myName: String,
    typingUsers: List<String>,
    onBack: () -> Unit,
    /** Dial one audience on this thread: "pair", "group" or "browser". */
    onStartCall: (String, Boolean) -> Unit,
    onSend: (String, ClipMessage?) -> Unit,
    /** A bubble the long-press menu asked about, and whether to retract it on every device. */
    onDelete: (ClipMessage, Boolean) -> Unit,
    onReact: (ClipMessage, String?) -> Unit,
    onEdit: (ClipMessage, String) -> Unit,
    onAttach: () -> Unit,
    onTyping: (Boolean) -> Unit,
    resolveAttachment: (ChatAttachment) -> File?,
    onDisband: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("") }
    var emojiOpen by remember(thread.id) { mutableStateOf(false) }
    var replyingTo by remember(thread.id) { mutableStateOf<ClipMessage?>(null) }
    var editing by remember(thread.id) { mutableStateOf<ClipMessage?>(null) }
    var lastTypingSent by remember { mutableLongStateOf(0L) }
    var previewAttachment by remember { mutableStateOf<ChatAttachment?>(null) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val rows = remember(thread.messages, myName) { layoutRows(thread.messages, myName) }

    previewAttachment?.let { att ->
        AttachmentPreview(
            attachment = att,
            file = resolveAttachment(att),
            onDismiss = { previewAttachment = null }
        )
    }

    editing?.let { target ->
        EditMessageDialog(
            original = target.text,
            onDismiss = { editing = null },
            onSave = { text ->
                editing = null
                onEdit(target, text)
            }
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
            PulseAvatar(
                name = thread.title,
                size = 38,
                icon = when {
                    thread.id == ROOM_ID -> Icons.Default.Groups
                    thread.isGroup -> Icons.Default.PersonAddAlt1
                    else -> null
                }
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
            // Every thread with somebody on the other end gets one dial pair. The room holds both
            // paired devices and browser tabs, and one call can only have one host, so a second
            // pair of buttons would just be a second way to be confused - the room's pair rings
            // everyone through whichever end picks up.
            val isPair = thread.pid != null
            val canDial = isPair || thread.memberPids.isNotEmpty() || thread.browserIp != null
            if (canDial) {
                val audience = if (isPair) "pair" else if (thread.memberPids.isNotEmpty() || thread.browserIp != null) "all" else "group"
                val whom = if (isPair) "" else "everyone"
                val tint = if (isPair) colorScheme.secondaryContainer else colorScheme.primaryContainer
                val content = if (isPair) colorScheme.onSecondaryContainer else colorScheme.onPrimaryContainer
                HeaderCallButton(
                    icon = Icons.Default.Call,
                    description = if (whom.isEmpty()) "Voice call" else "Call $whom",
                    tag = if (isPair) "call_audio_btn" else "group_call_btn",
                    container = tint,
                    content = content
                ) { onStartCall(audience, false) }
                Spacer(modifier = Modifier.width(6.dp))
                HeaderCallButton(
                    icon = Icons.Default.Videocam,
                    description = if (whom.isEmpty()) "Video call" else "Call $whom with video",
                    tag = if (isPair) "call_video_btn" else "group_video_btn",
                    container = tint,
                    content = content
                ) { onStartCall(audience, true) }
            }
            if (onDisband != null) {
                Spacer(modifier = Modifier.width(6.dp))
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
                PulseStatePanel(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = "Say hi to ${thread.title}",
                    message = if (thread.encrypted)
                        "Sealed on your device; only ${thread.title} can open it."
                    else
                        "Messages travel straight over the local network."
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
                                onOpenAttachment = { previewAttachment = it },
                                onReply = { replyingTo = row.msg },
                                onDelete = { forEveryone ->
                                    if (replyingTo?.id == row.msg.id) replyingTo = null
                                    onDelete(row.msg, forEveryone)
                                },
                                onReact = { reaction -> onReact(row.msg, reaction) },
                                onEdit = { editing = row.msg },
                                canReact = thread.id?.startsWith("ip:") != true
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

        replyingTo?.let { quoted ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorScheme.surfaceContainerHigh)
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Replying to ${quoted.sender}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.primary
                    )
                    Text(
                        text = if (quoted.deleted) "This message was deleted" else quoted.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { replyingTo = null },
                    modifier = Modifier.size(36.dp).testTag("chat_reply_cancel")
                ) {
                    Icon(Icons.Default.Close, "Cancel reply", modifier = Modifier.size(17.dp))
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
            emojiOpen = emojiOpen,
            onToggleEmoji = { emojiOpen = !emojiOpen },
            canAttach = true,
            onAttach = {
                emojiOpen = false
                focusManager.clearFocus()
                onAttach()
            },
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    onSend(text, replyingTo)
                    input = ""
                    replyingTo = null
                    emojiOpen = false
                    focusManager.clearFocus()
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
    emojiOpen: Boolean,
    onToggleEmoji: () -> Unit,
    canAttach: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

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
                onClick = onToggleEmoji,
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
    val c = LocalPulseColors.current
    val kind = previewKindOf(attachment.name)
    val glyph = when (kind) {
        PreviewKind.IMAGE -> Icons.Default.Image
        PreviewKind.VIDEO -> Icons.Default.Videocam
        PreviewKind.AUDIO -> Icons.Default.AudioFile
        PreviewKind.PDF -> Icons.Default.PictureAsPdf
        PreviewKind.TEXT -> Icons.Default.Description
        PreviewKind.OTHER -> Icons.Default.InsertDriveFile
    }
    val fill = if (mine) c.onAccent.copy(alpha = 0.14f) else c.surfaceSunken
    val edge = if (mine) c.onAccent.copy(alpha = 0.32f) else c.borderSubtle
    val title = if (mine) c.onAccent else c.textPrimary
    val sub = if (mine) c.onAccent.copy(alpha = 0.78f) else c.textSecondary

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
                .background(if (mine) c.onAccent.copy(alpha = 0.2f) else c.accentTint),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = if (mine) c.onAccent else c.onAccentTint,
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
                .background(if (mine) c.onAccent.copy(alpha = 0.22f) else c.accent.copy(alpha = 0.10f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = null,
                tint = if (mine) c.onAccent else c.accent,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Preview",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (mine) c.onAccent else c.accent
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    msg: ClipMessage,
    mine: Boolean,
    lastOfRun: Boolean,
    showSender: Boolean,
    grouped: Boolean,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onReply: () -> Unit,
    onDelete: (Boolean) -> Unit,
    onReact: (String?) -> Unit,
    onEdit: () -> Unit,
    /** False on a browser-tab thread, where a signal has no channel to travel on. */
    canReact: Boolean
) {
    val c = LocalPulseColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val bubbleRadius = 18.dp
    val bubbleShape = RoundedCornerShape(
        topStart = bubbleRadius,
        topEnd = bubbleRadius,
        bottomStart = if (mine) bubbleRadius else if (lastOfRun) 6.dp else bubbleRadius,
        bottomEnd = if (mine && lastOfRun) 6.dp else bubbleRadius
    )

    Box {
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
                    color = c.accent,
                    modifier = Modifier.padding(start = 14.dp, bottom = 2.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!mine && grouped) {
                    if (lastOfRun) {
                        PulseAvatar(name = msg.sender, size = 26)
                    } else {
                        Spacer(modifier = Modifier.width(26.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Box(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(bubbleShape)
                        .background(
                            if (msg.deleted) c.surfaceSunken
                            else if (mine) c.accent else c.surfaceElevated
                        )
                        .combinedClickable(
                            onClick = { menuOpen = true },
                            onLongClickLabel = "Message options",
                            onLongClick = { menuOpen = true }
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Column {
                        if (msg.deleted) {
                            // The bubble stays: a thread that renumbers under somebody already
                            // reading it is worse than one honest placeholder.
                            Text(
                                text = "This message was deleted",
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic,
                                color = c.textSecondary
                            )
                        } else {
                            if (msg.replyTo != null) ReplyQuote(msg = msg, mine = mine)
                            msg.attachment?.let { att ->
                                AttachmentCard(
                                    attachment = att,
                                    mine = mine,
                                    onOpen = { onOpenAttachment(att) }
                                )
                                if (msg.text.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))
                            }
                            if (msg.text.isNotEmpty() || msg.attachment == null) {
                                Text(
                                    text = msg.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (mine) c.onAccent else c.textPrimary
                                )
                            }
                            if (lastOfRun) {
                                // Instagram tucks the time inside the corner of the last bubble
                                // of a run; it belongs to the message, not to the page.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (msg.editedAt != null) {
                                        Text(
                                            text = "edited",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontStyle = FontStyle.Italic,
                                            color = if (mine) c.onAccent.copy(alpha = 0.75f) else c.textTertiary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = shortTime(msg.timestamp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (mine) c.onAccent.copy(alpha = 0.75f) else c.textTertiary,
                                        modifier = Modifier.offset(x = 8.dp, y = 4.dp)
                                    )
                                    if (mine && msg.readAt != null) {
                                        // Only ever a peer's own word that it read this: the tick is
                                        // set by a sealed receipt, never by this device guessing.
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Read",
                                            tint = c.onAccent.copy(alpha = 0.85f),
                                            modifier = Modifier
                                                .padding(start = 4.dp)
                                                .size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Outside the bubble, so a reaction survives a delete and does not inherit the
            // sender's background colour.
            if (msg.reaction != null && !msg.deleted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(c.highlight)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(text = msg.reaction, style = MaterialTheme.typography.labelSmall)
                    if (msg.reactionBy != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = msg.reactionBy,
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textTertiary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (canReact && !msg.deleted) {
                REACTIONS.forEach { emoji ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                // Tapping the one already on the bubble takes it off, which is what
                                // "react" means everywhere else.
                                if (msg.reaction == emoji) "Remove $emoji" else emoji
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onReact(if (msg.reaction == emoji) null else emoji)
                        }
                    )
                }
            }
            DropdownMenuItem(
                text = { Text("Reply") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null) },
                onClick = {
                    menuOpen = false
                    onReply()
                }
            )
            if (mine && !msg.deleted && msg.attachment == null) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(if (mine) "Delete for me" else "Delete here") },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = c.danger) },
                onClick = {
                    menuOpen = false
                    onDelete(false)
                }
            )
            if (mine) {
                DropdownMenuItem(
                    text = { Text("Delete for everyone") },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = c.danger) },
                    onClick = {
                        menuOpen = false
                        onDelete(true)
                    }
                )
            }
        }
    }
}

/** The reaction set offered on a bubble. Few enough to fit the menu without a picker. */
private val REACTIONS = listOf("\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDE42", "\uD83C\uDF89", "\uD83D\uDD25")

/** Rewrite of a bubble this device wrote. Text only: an attachment is bytes, not a caption. */
@Composable
private fun EditMessageDialog(original: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(original) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onSave(text.trim()) },
                enabled = text.isNotBlank() && text.trim() != original
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The quoted message inside a reply. It renders from the snapshot the sender attached rather than
 * looking the original up, so the quote survives both a deletion and a thread the original never
 * reached this device on.
 */
@Composable
private fun ReplyQuote(msg: ClipMessage, mine: Boolean) {
    val c = LocalPulseColors.current
    val accent = if (mine) c.onAccent else c.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (mine) c.onAccent.copy(alpha = 0.13f)
                else c.textPrimary.copy(alpha = 0.07f)
            )
            .padding(start = 7.dp, end = 9.dp, top = 5.dp, bottom = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(accent.copy(alpha = 0.75f))
        )
        Spacer(modifier = Modifier.width(7.dp))
        Column {
            Text(
                text = msg.replySender.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = msg.replyText.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = if (mine) c.onAccent.copy(alpha = 0.85f) else c.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Spacer(modifier = Modifier.height(5.dp))
}

@Composable
private fun DaySeparator(label: String) {
    val c = LocalPulseColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = c.surfaceSunken,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = c.textSecondary,
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
