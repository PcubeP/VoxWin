package dev.voxwin.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun DesktopScreen(
    client: VoxwinClient,
    host: String,
    controlPort: Int,
    modifier: Modifier = Modifier,
) {
    val connState by client.state.collectAsState()
    val streamClient = remember { StreamClient() }
    val streamState by streamClient.state.collectAsState()
    val frameBytes by streamClient.frame.collectAsState()

    // Connect the stream when the control client is connected; disconnect otherwise.
    DisposableEffect(connState) {
        if (connState == ConnectionState.CONNECTED) {
            streamClient.connect(host, controlPort + 1)
        } else {
            streamClient.disconnect()
        }
        onDispose { streamClient.disconnect() }
    }

    if (connState != ConnectionState.CONNECTED) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.DesktopWindows, contentDescription = null,
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

    // Decode the latest JPEG frame into a bitmap.
    val bitmap by remember(frameBytes) {
        derivedStateOf {
            frameBytes?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = "Remote desktop",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(streamState, streamClient) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val startTime = System.currentTimeMillis()
                                var totalMoved = 0f
                                val startPos = down.position

                                do {
                                    val event = awaitPointerEvent()
                                    val pos = event.changes.firstOrNull()?.position ?: break
                                    val dx = pos.x - startPos.x
                                    val dy = pos.y - startPos.y
                                    totalMoved = kotlin.math.sqrt(dx * dx + dy * dy)

                                    // Continuously send abs position on drag
                                    if (totalMoved > 4f) {
                                        val screenX = (pos.x / size.width  * streamClient.screenWidth).roundToInt()
                                        val screenY = (pos.y / size.height * streamClient.screenHeight).roundToInt()
                                        client.send(
                                            Command.of(
                                                Actions.MOUSE_MOVE_ABS,
                                                mapOf("x" to screenX, "y" to screenY),
                                            )
                                        )
                                    }
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })

                                // Tap → click
                                if (System.currentTimeMillis() - startTime < 300L && totalMoved < 20f) {
                                    val pos = startPos
                                    val screenX = (pos.x / size.width  * streamClient.screenWidth).roundToInt()
                                    val screenY = (pos.y / size.height * streamClient.screenHeight).roundToInt()
                                    client.send(
                                        Command.of(
                                            Actions.MOUSE_MOVE_ABS,
                                            mapOf("x" to screenX, "y" to screenY),
                                        )
                                    )
                                    client.send(
                                        Command.of(Actions.MOUSE_CLICK, mapOf("button" to "left"))
                                    )
                                }
                            }
                        },
                )
            }
            streamState == ConnectionState.CONNECTING ||
            streamState == ConnectionState.CONNECTED -> {
                // Waiting for first frame
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Connecting to stream…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Make sure Ollama is NOT running on port ${controlPort + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.DesktopWindows, contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Stream unavailable",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Daemon running? Stream port: ${controlPort + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
