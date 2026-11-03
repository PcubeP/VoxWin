package dev.voxwin.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val client = VoxwinClient()
    private var speech: SpeechController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VoxwinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    VoxwinApp(client = client, activity = this)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speech?.destroy()
        client.disconnect()
    }

    fun attachSpeech(controller: SpeechController) {
        speech = controller
    }
}

/** A log entry, classified so the UI can colour it. */
private enum class LogKind { SENT, ACK, ERROR, INFO }

private data class LogLine(val time: String, val text: String, val kind: LogKind)

private fun classify(text: String): LogKind = when {
    text.startsWith("-> ") -> LogKind.SENT
    text.contains("\"error\"") || text.startsWith("Error:") ||
        text.contains("unauthorized") || text.startsWith("unmatched") -> LogKind.ERROR
    text.startsWith("<- ") || text.startsWith("Connected") || text.startsWith("heard ") -> LogKind.ACK
    else -> LogKind.INFO
}

private val SUGGESTIONS = listOf(
    "volume up", "mute", "play pause", "next",
    "open notepad", "type hello team", "press control c", "lock the computer",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoxwinApp(client: VoxwinClient, activity: MainActivity) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // Persisted connection settings.
    val prefs = remember { context.getSharedPreferences("voxwin", Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(prefs.getString("host", "192.168.0.") ?: "192.168.0.") }
    var port by remember { mutableStateOf(prefs.getString("port", "8787") ?: "8787") }
    var token by remember { mutableStateOf(prefs.getString("token", "voxwin-dev") ?: "voxwin-dev") }

    var transcript by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var micPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val logLines = remember { mutableStateListOf<LogLine>() }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    fun log(line: String) {
        logLines.add(LogLine(timeFmt.format(Date()), line, classify(line)))
        if (logLines.size > 200) logLines.removeAt(0)
    }

    val connState by client.state.collectAsState()

    remember {
        client.onLog = { activity.runOnUiThread { log(it) } }
        true
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micPermission = granted }

    val speech = remember {
        SpeechController(
            context = context,
            callbacks = object : SpeechController.Callbacks {
                override fun onPartial(text: String) = activity.runOnUiThread { transcript = text }

                override fun onResult(text: String) = activity.runOnUiThread {
                    transcript = text
                    val command = CommandParser.parse(text)
                    if (command == null) {
                        hint = "Didn't catch that — try one of the suggestions below."
                        log("unmatched: \"$text\"")
                    } else {
                        hint = ""
                        log("heard \"$text\" -> ${command.action}")
                        client.send(command)
                    }
                }

                override fun onError(message: String) = activity.runOnUiThread { hint = message }

                override fun onListeningChanged(isListening: Boolean) = activity.runOnUiThread {
                    listening = isListening
                    if (isListening) hint = ""
                }
            },
        ).also { activity.attachSpeech(it) }
    }

    fun ensureMicThen(block: () -> Unit) {
        if (micPermission) block() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun doConnect() {
        prefs.edit().putString("host", host).putString("port", port).putString("token", token).apply()
        client.connect(host.trim(), port.toIntOrNull() ?: 8787, token.trim())
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("voxwin", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = { StatusChip(connState); Spacer(Modifier.width(8.dp)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConnectionCard(
                host = host, onHost = { host = it },
                port = port, onPort = { port = it.filter(Char::isDigit) },
                token = token, onToken = { token = it },
                state = connState,
                onConnect = { doConnect() },
                onDisconnect = { client.disconnect() },
            )

            MicPanel(
                listening = listening,
                connected = connState == ConnectionState.CONNECTED,
                transcript = transcript,
                hint = hint,
                onStart = {
                    ensureMicThen {
                        if (connState != ConnectionState.CONNECTED) doConnect()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        speech.start()
                    }
                },
                onStop = { speech.stop() },
            )

            SuggestionRow(enabled = connState == ConnectionState.CONNECTED) { phrase ->
                CommandParser.parse(phrase)?.let { cmd ->
                    transcript = phrase
                    hint = ""
                    log("tapped \"$phrase\" -> ${cmd.action}")
                    client.send(cmd)
                }
            }

            ActivityLog(
                lines = logLines,
                onClear = { logLines.clear() },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatusChip(state: ConnectionState) {
    val (label, color) = when (state) {
        ConnectionState.CONNECTED -> "Connected" to Color(0xFF2E7D32)
        ConnectionState.CONNECTING -> "Connecting" to Color(0xFFF9A825)
        ConnectionState.ERROR -> "Error" to MaterialTheme.colorScheme.error
        ConnectionState.DISCONNECTED -> "Offline" to Color(0xFF9E9E9E)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun ConnectionCard(
    host: String, onHost: (String) -> Unit,
    port: String, onPort: (String) -> Unit,
    token: String, onToken: (String) -> Unit,
    state: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = state == ConnectionState.CONNECTED
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your PC", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = onHost,
                    label = { Text("Host / IP") }, singleLine = true,
                    enabled = !connected, modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = port, onValueChange = onPort,
                    label = { Text("Port") }, singleLine = true, enabled = !connected,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = token, onValueChange = onToken,
                label = { Text("Token") }, singleLine = true, enabled = !connected,
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                onClick = { if (connected) onDisconnect() else onConnect() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (connected) "Disconnect" else "Connect") }
        }
    }
}

@Composable
private fun MicPanel(
    listening: Boolean,
    connected: Boolean,
    transcript: String,
    hint: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.22f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale",
    )
    val ringColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(150.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (listening) {
                Box(
                    Modifier.size(128.dp).scale(scale)
                        .background(ringColor.copy(alpha = 0.16f), CircleShape),
                )
            }
            val btnColor = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            Surface(
                shape = CircleShape,
                color = btnColor,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(104.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { if (listening) onStop() else onStart() },
                            onPress = {
                                onStart()
                                val released = tryAwaitRelease()
                                if (released) onStop()
                            },
                        )
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Talk",
                        tint = if (listening) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
        }
        Text(
            text = when {
                listening -> "Listening…"
                !connected -> "Tap to connect & talk"
                else -> "Hold to talk · tap for one command"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = transcript.ifEmpty { "Say a command…" },
            style = MaterialTheme.typography.titleMedium,
            color = if (transcript.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
        )
        AnimatedVisibility(visible = hint.isNotEmpty()) {
            Text(hint, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SuggestionRow(enabled: Boolean, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Try saying", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in SUGGESTIONS) {
                AssistChip(
                    onClick = { if (enabled) onPick(s) },
                    label = { Text(s) },
                    enabled = enabled,
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }
    }
}

@Composable
private fun ActivityLog(
    lines: List<LogLine>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Auto-scroll to the newest line as entries arrive.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Activity", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear log")
                }
            }
            if (lines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Commands you send will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                ) {
                    items(lines) { line ->
                        val color = when (line.kind) {
                            LogKind.SENT -> MaterialTheme.colorScheme.primary
                            LogKind.ACK -> Color(0xFF2E7D32)
                            LogKind.ERROR -> MaterialTheme.colorScheme.error
                            LogKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = "${line.time}  ${line.text}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = color,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
