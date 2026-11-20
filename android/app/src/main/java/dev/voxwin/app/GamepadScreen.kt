package dev.voxwin.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun GamepadScreen(client: VoxwinClient, modifier: Modifier = Modifier) {
    val connState by client.state.collectAsState()

    if (connState != ConnectionState.CONNECTED) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.SportsEsports, contentDescription = null,
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

    // ── Gamepad state ─────────────────────────────────────────────────────────
    var lx by remember { mutableFloatStateOf(0f) }
    var ly by remember { mutableFloatStateOf(0f) }
    var rx by remember { mutableFloatStateOf(0f) }
    var ry by remember { mutableFloatStateOf(0f) }
    var ltVal by remember { mutableFloatStateOf(0f) }
    var rtVal by remember { mutableFloatStateOf(0f) }
    var heldButtons by remember { mutableIntStateOf(0) }

    fun sendState() {
        client.send(
            Command.of(
                Actions.GAMEPAD_STATE,
                mapOf(
                    "lx" to (lx * 32767f).roundToInt(),
                    "ly" to (ly * 32767f).roundToInt(),
                    "rx" to (rx * 32767f).roundToInt(),
                    "ry" to (ry * 32767f).roundToInt(),
                    "lt" to (ltVal * 255f).roundToInt(),
                    "rt" to (rtVal * 255f).roundToInt(),
                    "buttons" to heldButtons,
                ),
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {

        // ── Triggers ──────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("LT  ${"%.0f".format(ltVal * 100)}%",
                    style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = ltVal,
                    onValueChange = { ltVal = it; sendState() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(Modifier.weight(1f)) {
                Text("RT  ${"%.0f".format(rtVal * 100)}%",
                    style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = rtVal,
                    onValueChange = { rtVal = it; sendState() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── Shoulder + menu row ───────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GamepadBtn("LB", GamepadButtons.LB, heldButtons,
                onDown = { heldButtons = heldButtons or it; sendState() },
                onUp = { heldButtons = heldButtons and it.inv(); sendState() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GamepadBtn("SEL", GamepadButtons.BACK, heldButtons,
                    onDown = { heldButtons = heldButtons or it; sendState() },
                    onUp = { heldButtons = heldButtons and it.inv(); sendState() },
                )
                GamepadBtn("STA", GamepadButtons.START, heldButtons,
                    onDown = { heldButtons = heldButtons or it; sendState() },
                    onUp = { heldButtons = heldButtons and it.inv(); sendState() },
                )
            }
            GamepadBtn("RB", GamepadButtons.RB, heldButtons,
                onDown = { heldButtons = heldButtons or it; sendState() },
                onUp = { heldButtons = heldButtons and it.inv(); sendState() },
            )
        }

        // ── Sticks + D-pad + face buttons ─────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left stick + left-thumb button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ThumbStick(
                    onAxisChange = { nx, ny ->
                        lx = nx; ly = ny
                        sendState()
                    },
                )
                Spacer(Modifier.height(4.dp))
                GamepadBtn("L3", GamepadButtons.LEFT_THUMB, heldButtons,
                    onDown = { heldButtons = heldButtons or it; sendState() },
                    onUp = { heldButtons = heldButtons and it.inv(); sendState() },
                    modifier = Modifier.size(32.dp),
                )
            }

            // Centre: D-pad above, guide-less gap
            DPad(
                heldButtons = heldButtons,
                onDown = { heldButtons = heldButtons or it; sendState() },
                onUp = { heldButtons = heldButtons and it.inv(); sendState() },
            )

            // Face buttons + right-thumb button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FaceButtons(
                    heldButtons = heldButtons,
                    onDown = { heldButtons = heldButtons or it; sendState() },
                    onUp = { heldButtons = heldButtons and it.inv(); sendState() },
                )
                Spacer(Modifier.height(4.dp))
                GamepadBtn("R3", GamepadButtons.RIGHT_THUMB, heldButtons,
                    onDown = { heldButtons = heldButtons or it; sendState() },
                    onUp = { heldButtons = heldButtons and it.inv(); sendState() },
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        // ── Right stick ───────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            ThumbStick(
                onAxisChange = { nx, ny ->
                    rx = nx; ry = ny
                    sendState()
                },
            )
        }

        Spacer(Modifier.height(4.dp))
    }
}

// ── ThumbStick ────────────────────────────────────────────────────────────────

@Composable
private fun ThumbStick(
    onAxisChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outerDp = 56.dp
    val thumbDp = 22.dp

    var thumbOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .size(outerDp * 2)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .pointerInput(Unit) {
                val outerPx = outerDp.toPx() - thumbDp.toPx()
                val center = Offset(size.width / 2f, size.height / 2f)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pos = event.changes.firstOrNull()?.position ?: break
                        val raw = pos - center
                        val len = sqrt(raw.x * raw.x + raw.y * raw.y)
                        val clamped = if (len <= outerPx) raw else raw * (outerPx / len)
                        thumbOffset = clamped
                        onAxisChange(
                            (clamped.x / outerPx).coerceIn(-1f, 1f),
                            (clamped.y / outerPx).coerceIn(-1f, 1f),
                        )
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    thumbOffset = Offset.Zero
                    onAxisChange(0f, 0f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbOffset.x.roundToInt(), thumbOffset.y.roundToInt()) }
                .size(thumbDp * 2)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}

// ── D-Pad ─────────────────────────────────────────────────────────────────────

@Composable
private fun DPad(
    heldButtons: Int,
    onDown: (Int) -> Unit,
    onUp: (Int) -> Unit,
) {
    val btnDp = 44.dp
    Box(Modifier.size(btnDp * 3)) {
        // Up
        DPadArrow("▲", GamepadButtons.DPAD_UP, heldButtons, onDown, onUp,
            Modifier.align(Alignment.TopCenter).size(btnDp))
        // Down
        DPadArrow("▼", GamepadButtons.DPAD_DOWN, heldButtons, onDown, onUp,
            Modifier.align(Alignment.BottomCenter).size(btnDp))
        // Left
        DPadArrow("◄", GamepadButtons.DPAD_LEFT, heldButtons, onDown, onUp,
            Modifier.align(Alignment.CenterStart).size(btnDp))
        // Right
        DPadArrow("►", GamepadButtons.DPAD_RIGHT, heldButtons, onDown, onUp,
            Modifier.align(Alignment.CenterEnd).size(btnDp))
        // Center cross decoration
        Box(
            Modifier
                .align(Alignment.Center)
                .size(btnDp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
private fun DPadArrow(
    label: String,
    flag: Int,
    heldButtons: Int,
    onDown: (Int) -> Unit,
    onUp: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val held = (heldButtons and flag) != 0
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    onDown(flag)
                    tryAwaitRelease()
                    onUp(flag)
                },
            )
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium,
                color = if (held) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ── Face buttons (ABXY) ───────────────────────────────────────────────────────

@Composable
private fun FaceButtons(
    heldButtons: Int,
    onDown: (Int) -> Unit,
    onUp: (Int) -> Unit,
) {
    val btnDp = 48.dp
    Box(Modifier.size(btnDp * 3)) {
        GamepadBtn("Y", GamepadButtons.Y, heldButtons, onDown, onUp,
            Modifier.align(Alignment.TopCenter).size(btnDp))
        GamepadBtn("A", GamepadButtons.A, heldButtons, onDown, onUp,
            Modifier.align(Alignment.BottomCenter).size(btnDp))
        GamepadBtn("X", GamepadButtons.X, heldButtons, onDown, onUp,
            Modifier.align(Alignment.CenterStart).size(btnDp))
        GamepadBtn("B", GamepadButtons.B, heldButtons, onDown, onUp,
            Modifier.align(Alignment.CenterEnd).size(btnDp))
    }
}

// ── Generic gamepad button ────────────────────────────────────────────────────

@Composable
private fun GamepadBtn(
    label: String,
    flag: Int,
    heldButtons: Int,
    onDown: (Int) -> Unit,
    onUp: (Int) -> Unit,
    modifier: Modifier = Modifier.size(48.dp),
) {
    val held = (heldButtons and flag) != 0
    Surface(
        shape = CircleShape,
        color = if (held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = if (held) 0.dp else 2.dp,
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    onDown(flag)
                    tryAwaitRelease()
                    onUp(flag)
                },
            )
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (held) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
