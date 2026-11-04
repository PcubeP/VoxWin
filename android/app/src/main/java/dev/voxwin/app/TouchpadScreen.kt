package dev.voxwin.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchpadScreen(client: VoxwinClient, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("voxwin", Context.MODE_PRIVATE) }

    val connState by client.state.collectAsState()

    var showKeyboard by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }
    var dragLocked by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var sensitivity by remember { mutableStateOf(prefs.getFloat("trackpad_sensitivity", 1.5f)) }
    val sheetState = rememberModalBottomSheetState()

    // Shared handler for long-press scheduling across gestures.
    val handler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        onDispose { handler.removeCallbacksAndMessages(null) }
    }

    if (connState != ConnectionState.CONNECTED) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.Mouse, contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Not connected",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Connect from the Voice tab first",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Column(modifier.fillMaxSize()) {

        // ── Touchpad surface ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(12.dp),
                )
                // sensitivity is the key so the block restarts when it changes.
                .pointerInput(sensitivity) {
                    awaitEachGesture {
                        var dragFired = false
                        var maxFingers = 1
                        var totalMoved = 0f

                        // Long-press fires after 400 ms of stillness.
                        val longPressRunnable = Runnable {
                            dragFired = true
                            dragLocked = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            client.send(
                                Command.of(Actions.MOUSE_DOWN, mapOf("button" to "left"))
                            )
                        }

                        awaitFirstDown(requireUnconsumed = false)
                        val startTime = System.currentTimeMillis()
                        handler.postDelayed(longPressRunnable, 400L)

                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            if (fingers > maxFingers) maxFingers = fingers

                            val primary = event.changes.firstOrNull() ?: break
                            val delta = primary.positionChange()
                            val dist = sqrt(delta.x * delta.x + delta.y * delta.y)

                            if (dist > 0.5f) {
                                totalMoved += dist
                                // Cancel long-press once the finger moves too far.
                                if (totalMoved > 12f) handler.removeCallbacks(longPressRunnable)

                                when (fingers) {
                                    1 -> {
                                        val dx = (delta.x * sensitivity).roundToInt()
                                        val dy = (delta.y * sensitivity).roundToInt()
                                        if (dx != 0 || dy != 0) {
                                            client.send(
                                                Command.of(
                                                    Actions.MOUSE_MOVE_REL,
                                                    mapOf("x" to dx, "y" to dy),
                                                )
                                            )
                                        }
                                    }
                                    2 -> {
                                        val scrollY = (delta.y / 2.5f).roundToInt()
                                        if (scrollY != 0) {
                                            client.send(
                                                Command.of(
                                                    Actions.MOUSE_SCROLL,
                                                    mapOf("x" to 0, "y" to scrollY),
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })

                        handler.removeCallbacks(longPressRunnable)

                        when {
                            dragFired -> {
                                client.send(
                                    Command.of(Actions.MOUSE_UP, mapOf("button" to "left"))
                                )
                                dragLocked = false
                            }
                            System.currentTimeMillis() - startTime < 300L && totalMoved < 20f -> {
                                val btn = if (maxFingers >= 2) "right" else "left"
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                client.send(
                                    Command.of(Actions.MOUSE_CLICK, mapOf("button" to btn))
                                )
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (dragLocked) {
                    Icon(
                        Icons.Filled.Lock, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        "Drag locked — release to drop",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        "Drag to move  ·  Tap to click  ·  2 fingers to scroll",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Keyboard input ───────────────────────────────────────────────────
        AnimatedVisibility(visible = showKeyboard) {
            OutlinedTextField(
                value = keyboardText,
                onValueChange = { new ->
                    val old = keyboardText
                    when {
                        new.length > old.length -> client.send(
                            Command.of(
                                Actions.TYPE_TEXT,
                                mapOf("text" to new.substring(old.length)),
                            )
                        )
                        new.length < old.length -> repeat(old.length - new.length) {
                            client.send(Command.of(Actions.KEY, mapOf("combo" to "backspace")))
                        }
                    }
                    keyboardText = new
                },
                label = { Text("Type to send to PC") },
                singleLine = false,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        client.send(Command.of(Actions.KEY, mapOf("combo" to "enter")))
                        keyboardText = ""
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // ── Button bar ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = {
                    client.send(Command.of(Actions.MOUSE_CLICK, mapOf("button" to "left")))
                },
                modifier = Modifier.weight(1f),
            ) { Text("LEFT") }

            FilledTonalButton(
                onClick = {
                    client.send(Command.of(Actions.MOUSE_CLICK, mapOf("button" to "right")))
                },
                modifier = Modifier.weight(1f),
            ) { Text("RIGHT") }

            FilledTonalButton(
                onClick = {
                    client.send(Command.of(Actions.MOUSE_CLICK, mapOf("button" to "middle")))
                },
                modifier = Modifier.weight(1f),
            ) { Text("MID") }

            IconButton(onClick = { showKeyboard = !showKeyboard }) {
                Icon(
                    Icons.Filled.Keyboard, contentDescription = "Toggle keyboard",
                    tint = if (showKeyboard) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    }

    // ── Sensitivity settings sheet ────────────────────────────────────────────
    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Trackpad Settings", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pointer speed: ${"%.1f".format(sensitivity)}×",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = sensitivity,
                    onValueChange = {
                        sensitivity = it
                        prefs.edit().putFloat("trackpad_sensitivity", it).apply()
                    },
                    valueRange = 0.5f..4.0f,
                    steps = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
