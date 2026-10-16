package dev.kogler.a7iiiconnect.develop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compare shots picked in the library, two at a time ([Duel]). When the last choice is made the
 * kept shots become picks and the rest rejects; undo takes that back too.
 */
@Composable
fun CompareScreen(
    shots: List<Shot>,
    marks: Map<String, Mark>,
    onMark: (Shot, Mark?) -> Unit,
    onBack: () -> Unit,
) {
    val duel = remember(shots) { Duel(shots) }
    // What the shots were marked before, so undoing the last choice can put it back.
    val before = remember(shots) { shots.associate { it.key to marks[it.key] } }
    var applied by remember(shots) { mutableStateOf(false) }
    LaunchedEffect(duel.done) {
        if (duel.done && !applied) {
            duel.kept.forEach { onMark(it, Mark.PICK) }
            duel.dropped.forEach { onMark(it, Mark.REJECT) }
            applied = true
        }
    }
    fun undo() {
        if (applied) {
            shots.forEach { onMark(it, before[it.key]) }
            applied = false
        }
        duel.undo()
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("Compare ${shots.size}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (duel.done) "Done" else "${duel.round + 1} of ${duel.rounds} · tap the better one",
                    color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = { undo() }, enabled = duel.canUndo, modifier = Modifier.testTag("compare-undo")) {
                Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", tint = if (duel.canUndo) Color.White else Color.White.copy(alpha = 0.3f))
            }
        }
        if (duel.done) {
            CompareResult(duel.kept, duel.dropped.size, onBack, Modifier.weight(1f))
        } else {
            HeadToHead(duel, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                RoundButton(Icons.Rounded.DoneAll, "Keep both", PickGreen, 56, "compare-keep-both") { duel.keepBoth() }
            }
        }
    }
}

@Composable
private fun CompareResult(kept: List<Shot>, rejected: Int, onBack: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (kept.size == 1) "The winner" else "Keeping ${kept.size}", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in kept.take(3)) {
                Thumb(s.jpeg ?: s.primary, Modifier.width(if (kept.size == 1) 220.dp else 100.dp).aspectRatio(1.5f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "${kept.size} picked · $rejected rejected",
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onBack) { Text("Back to library") }
    }
}

/**
 * Two shots, the best so far on top (left when the phone is sideways) and the challenger below.
 * Tap the better one. Zoom is linked: a pinch or double-tap on either shows both at the same spot,
 * so focus can be compared directly.
 */
@Composable
internal fun HeadToHead(duel: Duel<Shot>, modifier: Modifier, header: (@Composable () -> Unit)? = null) {
    val haptics = LocalHapticFeedback.current
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val best = duel.best
    val challenger = duel.challenger ?: return
    // Zoom resets for each new pairing.
    LaunchedEffect(best, challenger) { scale = 1f; pan = Offset.Zero }
    fun choose(bestWins: Boolean) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        duel.choose(bestWins)
    }

    @Composable
    fun Side(shot: Shot, bestWins: Boolean, label: String, tag: String, m: Modifier) {
        val image = key(shot.key) { rememberCullImage(shot) }
        val tile = key(shot.key) { rememberSharpTile(shot, image, scale, pan, size) }
        Box(
            m.clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1C1C1E))
                .onSizeChanged { size = it }
                .clipToBounds()
                .testTag(tag)
                .pointerInput(shot.key) {
                    detectTapGestures(
                        onTap = { choose(bestWins) },
                        onDoubleTap = { at ->
                            if (scale > 1.01f) {
                                scale = 1f; pan = Offset.Zero
                            } else {
                                val s = 3f
                                val c = Offset(size.width / 2f, size.height / 2f)
                                pan = clampPan((at - c) * (1 - s), s, size)
                                scale = s
                            }
                        },
                    )
                }
                .pointerInput(shot.key) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var moved = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val c = Offset(size.width / 2f, size.height / 2f)
                                val centroid = event.calculateCentroid()
                                val s = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                val p = (centroid - c) - (centroid - c - pan) * (s / scale) + event.calculatePan()
                                scale = s
                                pan = if (s <= 1.01f) Offset.Zero else clampPan(p, s, size)
                                event.changes.forEach { it.consume() }
                            } else {
                                val change = pressed.first()
                                val delta = change.positionChange()
                                moved += delta.getDistance()
                                // Past the touch slop it's a drag, not a tap: pan when zoomed in.
                                if (moved > slop) {
                                    if (scale > 1.01f) pan = clampPan(pan + delta, scale, size)
                                    change.consume()
                                }
                            }
                        }
                    }
                },
        ) {
            ZoomedPhoto(shot, image, tile, scale, pan)
            Text(
                label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .then(if (bestWins) Modifier.border(1.dp, PickGreen.copy(alpha = 0.7f), RoundedCornerShape(6.dp)) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            if (scale <= 1.01f) CardInfo(shot, Modifier.align(Alignment.BottomStart))
        }
    }

    Column(modifier) {
        header?.invoke()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val sideways = maxWidth > maxHeight
            if (sideways) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Side(best, true, "Best so far", "compare-best", Modifier.weight(1f).fillMaxSize())
                    Side(challenger, false, "Challenger", "compare-challenger", Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Side(best, true, "Best so far", "compare-best", Modifier.weight(1f).fillMaxWidth())
                    Side(challenger, false, "Challenger", "compare-challenger", Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}
