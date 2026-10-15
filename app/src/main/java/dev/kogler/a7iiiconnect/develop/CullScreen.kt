package dev.kogler.a7iiiconnect.develop

import android.graphics.RectF
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What a swipe decided: keep (on to the next round), reject/drop, or "skip for now". */
internal enum class Verdict { PICK, REJECT, SKIP }

internal val PickGreen = Color(0xFF3DDC84)
internal val RejectRed = Color(0xFFFF5A5F)
internal val SkipBlue = Color(0xFF8AB4F8)

private const val CULL_EDGE = 2048
private const val MAX_ZOOM = 8f

/** One decision, with the marks it replaced so undo can put them back. */
private class Step(val pos: Int, val previous: List<Pair<Shot, Mark?>>)

/**
 * Tinder-style culling, one round at a time. Round 1 sorts unreviewed shots into picks and
 * rejects; each later round deals the survivors of the one before, and right keeps a shot going
 * while left leaves it at its level. Bursts and near-duplicates come as one "pick the best" stack.
 * Double-tap or pinch to check focus; while zoomed in a drag pans instead of deciding.
 */
@Composable
fun CullScreen(
    shots: List<Shot>,
    round: Int,
    marks: Map<String, Mark>,
    onMark: (Shot, Mark?) -> Unit,
    rejectedCount: Int,
    onDeleteRejected: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var progress by remember { mutableFloatStateOf(0f) }
    var deck by remember { mutableStateOf<List<List<Shot>>?>(null) }
    LaunchedEffect(Unit) {
        // Newest day first, and within a day in the order the shots were taken.
        deck = withContext(Dispatchers.IO) { Similar.stacks(context, shots) { progress = it } }
            .sortedByDescending { dayOf(it.first().takenMillis) }
    }
    var pos by remember { mutableIntStateOf(0) }
    val undo = remember { mutableStateListOf<Step>() }
    val decided = remember { mutableStateMapOf<String, Verdict>() }
    // A button press flies the top card out; the card itself animates and then reports back.
    var fling by remember { mutableStateOf<Verdict?>(null) }
    // Which shot of the current stack is shown (and kept by ✓).
    var chosen by remember { mutableIntStateOf(0) }

    fun markFor(v: Verdict): Mark = when (v) {
        Verdict.PICK -> Mark(round)
        Verdict.REJECT -> if (round == 1) Mark.REJECTED else Mark(round - 1, settled = true)
        Verdict.SKIP -> error("skip leaves the mark alone")
    }

    fun record(verdicts: List<Pair<Shot, Verdict>>) {
        undo += Step(pos, verdicts.map { (shot, _) -> shot to marks[shot.key] })
        for ((shot, v) in verdicts) {
            decided[shot.key] = v
            if (v != Verdict.SKIP) onMark(shot, markFor(v))
        }
        fling = null
        chosen = 0
        pos++
    }

    fun undoLast() {
        val step = undo.removeLastOrNull() ?: return
        for ((shot, previous) in step.previous) {
            onMark(shot, previous)
            decided.remove(shot.key)
        }
        fling = null
        chosen = 0
        pos = step.pos
    }

    val items = deck
    val top = items?.getOrNull(pos)
    val rejectLabel = if (round == 1) "REJECT" else "DROP"

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("Round $round", color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (items != null) {
                    Text(
                        if (top != null) "${pos + 1} of ${items.size}" else "Done",
                        color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            IconButton(onClick = { undoLast() }, enabled = undo.isNotEmpty(), modifier = Modifier.testTag("cull-undo")) {
                Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", tint = if (undo.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f))
            }
        }

        if (items == null) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(progress = { progress }, color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text("Finding similar shots…", color = Color.White.copy(alpha = 0.7f))
            }
            return@Column
        }

        if (top == null) {
            Summary(
                round = round,
                picked = decided.values.count { it == Verdict.PICK },
                rejected = decided.values.count { it == Verdict.REJECT },
                skipped = decided.values.count { it == Verdict.SKIP },
                rejectedCount = rejectedCount,
                onDeleteRejected = onDeleteRejected,
                onBack = onBack,
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (top.size > 1) {
                StackCard(
                    stack = top,
                    chosen = chosen.coerceIn(top.indices),
                    onChoose = { chosen = it },
                    onSplit = {
                        deck = items.subList(0, pos) + top.map { listOf(it) } + items.subList(pos + 1, items.size)
                        chosen = 0
                    },
                )
            } else {
                // The next two cards wait underneath (already loaded), the top one is live.
                for (i in min(pos + 2, items.size - 1) downTo pos) {
                    val shot = items[i].first()
                    key(shot.key) {
                        SwipeCard(
                            shot = shot,
                            depth = i - pos,
                            fling = if (i == pos) fling else null,
                            rejectLabel = rejectLabel,
                            onDecided = { record(listOf(shot to it)) },
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (top.size > 1) {
                // A stack: ✓ keeps the shown shot and lets the rest go; ✕ lets them all go.
                val keep = top[chosen.coerceIn(top.indices)]
                RoundButton(Icons.Rounded.Close, "None of these", RejectRed, 64, "cull-reject") { record(top.map { it to Verdict.REJECT }) }
                RoundButton(Icons.Rounded.ArrowUpward, "Skip", SkipBlue, 48, "cull-skip") { record(top.map { it to Verdict.SKIP }) }
                RoundButton(Icons.Rounded.Check, "Keep this one", PickGreen, 64, "cull-pick") {
                    record(top.map { it to if (it == keep) Verdict.PICK else Verdict.REJECT })
                }
            } else {
                RoundButton(Icons.Rounded.Close, rejectLabel.lowercase().replaceFirstChar { it.uppercase() }, RejectRed, 64, "cull-reject") { if (fling == null) fling = Verdict.REJECT }
                RoundButton(Icons.Rounded.ArrowUpward, "Skip", SkipBlue, 48, "cull-skip") { if (fling == null) fling = Verdict.SKIP }
                RoundButton(Icons.Rounded.Check, "Pick", PickGreen, 64, "cull-pick") { if (fling == null) fling = Verdict.PICK }
            }
        }
    }
}

/**
 * Similar shots shown together: one big photo and a strip of the others. Swipe sideways or tap the
 * strip to flip between them; zoom stays where it is, so focus can be compared frame to frame.
 */
@Composable
internal fun StackCard(stack: List<Shot>, chosen: Int, onChoose: (Int) -> Unit, onSplit: (() -> Unit)?) {
    val haptics = LocalHapticFeedback.current
    var scale by remember(stack) { mutableFloatStateOf(1f) }
    var pan by remember(stack) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val shot = stack[chosen]
    // The shown shot and its neighbours stay decoded so flipping is instant (but a long burst
    // isn't all held in memory at once).
    val images = (chosen - 2..chosen + 2).mapNotNull { stack.getOrNull(it) }
        .associate { s -> s.key to key(s.key) { rememberCullImage(s) } }
    val image = images[shot.key]
    val tile = rememberSharpTile(shot, image, scale, pan, size)
    val latestChosen by rememberUpdatedState(chosen)

    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1E)).testTag("cull-stack")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${stack.size} similar shots", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Pick the best one", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
            if (onSplit != null) TextButton(onClick = onSplit) { Text("Not similar") }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { size = it }
                .clipToBounds()
                .pointerInput(stack) {
                    detectTapGestures(onDoubleTap = { at ->
                        if (scale > 1.01f) {
                            scale = 1f; pan = Offset.Zero
                        } else {
                            val s = 3f
                            val c = Offset(size.width / 2f, size.height / 2f)
                            pan = clampPan((at - c) * (1 - s), s, size)
                            scale = s
                        }
                    })
                }
                .pointerInput(stack) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var swipe = 0f
                        var pinched = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                pinched = true
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
                                if (delta == Offset.Zero) continue
                                if (scale > 1.01f) pan = clampPan(pan + delta, scale, size) else swipe += delta.x
                                change.consume()
                            }
                        }
                        // A sideways flick at normal size flips to the neighbouring shot.
                        if (!pinched && scale <= 1.01f && abs(swipe) > size.width * 0.15f) {
                            val next = (latestChosen + if (swipe < 0) 1 else -1).coerceIn(stack.indices)
                            if (next != latestChosen) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onChoose(next)
                            }
                        }
                    }
                },
        ) {
            ZoomedPhoto(shot, image, tile, scale, pan)
            CardInfo(shot, Modifier.align(Alignment.BottomStart).alpha(if (scale > 1.01f) 0f else 1f))
        }
        LazyRow(
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(stack, key = { _, s -> s.key }) { i, s ->
                Thumb(
                    s.jpeg ?: s.primary,
                    Modifier.size(64.dp)
                        .then(if (i == chosen) Modifier.border(2.dp, PickGreen, RoundedCornerShape(4.dp)) else Modifier)
                        .clickable { onChoose(i) },
                )
            }
        }
    }
}

@Composable
internal fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, size: Int, tag: String, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.08f), contentColor = color),
        modifier = Modifier.size(size.dp).border(2.dp, color.copy(alpha = 0.6f), CircleShape).testTag(tag),
    ) {
        Icon(icon, label, modifier = Modifier.size((size * 0.5f).dp))
    }
}

@Composable
private fun Summary(
    round: Int,
    picked: Int, rejected: Int, skipped: Int, rejectedCount: Int,
    onDeleteRejected: () -> Unit, onBack: () -> Unit, modifier: Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Round $round done", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            listOfNotNull(
                "$picked kept", if (round == 1) "$rejected rejected" else "$rejected dropped",
                if (skipped > 0) "$skipped skipped" else null,
            ).joinToString(" · "),
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(32.dp))
        if (rejectedCount > 0) {
            Button(
                onClick = onDeleteRejected,
                colors = ButtonDefaults.buttonColors(containerColor = RejectRed, contentColor = Color.White),
            ) { Text("Delete $rejectedCount rejected") }
            Spacer(Modifier.height(12.dp))
        }
        OutlinedButton(onClick = onBack) { Text("Back to library") }
    }
}

/**
 * One card. [depth] 0 is the live top card; 1 and 2 sit behind it. The top card follows the finger,
 * tilts, shows a stamp, and flies off once let go past the threshold (or when [fling] is set).
 */
@Composable
internal fun SwipeCard(
    shot: Shot, depth: Int, fling: Verdict?, rejectLabel: String, onDecided: (Verdict) -> Unit, skipLabel: String = "SKIP",
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val image = rememberCullImage(shot)

    var drag by remember { mutableStateOf(Offset.Zero) }
    var leaving by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val tile = rememberSharpTile(shot, image, scale, pan, size)

    fun verdictFor(d: Offset, w: Float, h: Float): Verdict? = when {
        -d.y > h * 0.18f && -d.y > abs(d.x) -> Verdict.SKIP
        d.x > w * 0.28f -> Verdict.PICK
        d.x < -w * 0.28f -> Verdict.REJECT
        else -> null
    }

    fun flyOut(v: Verdict, velocity: Offset = Offset.Zero) {
        if (leaving) return
        leaving = true
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val target = when (v) {
            Verdict.PICK -> Offset(w * 1.5f, drag.y + velocity.y * 0.1f)
            Verdict.REJECT -> Offset(-w * 1.5f, drag.y + velocity.y * 0.1f)
            Verdict.SKIP -> Offset(drag.x, -h * 1.3f)
        }
        scope.launch {
            animate(Offset.VectorConverter, drag, target, animationSpec = tween(220)) { value, _ -> drag = value }
            onDecided(v)
        }
    }

    LaunchedEffect(fling) { fling?.let { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); flyOut(it) } }

    val w = size.width.toFloat().coerceAtLeast(1f)
    val h = size.height.toFloat().coerceAtLeast(1f)
    val progressX = (drag.x / (w * 0.28f)).coerceIn(-1f, 1f)
    val progressUp = (-drag.y / (h * 0.18f)).coerceIn(0f, 1f)
    // A card behind grows into place when the one above it leaves.
    val cardScale by animateFloatAsState(if (depth == 0) 1f else 0.94f, label = "cardScale")

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .then(if (depth == 0) Modifier.testTag("cull-card") else Modifier)
            .graphicsLayer {
                if (depth == 0) {
                    scaleX = cardScale; scaleY = cardScale
                    translationX = drag.x
                    translationY = drag.y
                    rotationZ = drag.x / w * 12f
                } else {
                    scaleX = cardScale; scaleY = cardScale
                    alpha = if (depth == 1) 1f else 0f
                }
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            .then(
                if (depth != 0) Modifier else Modifier
                    .pointerInput(shot.key) {
                        detectTapGestures(onDoubleTap = { at ->
                            if (scale > 1.01f) {
                                scale = 1f; pan = Offset.Zero
                            } else {
                                val s = 3f
                                val c = Offset(size.width / 2f, size.height / 2f)
                                pan = clampPan((at - c) * (1 - s), s, size)
                                scale = s
                            }
                        })
                    }
                    .pointerInput(shot.key) {
                        val tracker = VelocityTracker()
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            tracker.resetTracking()
                            var pinched = false
                            var armed: Verdict? = null
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty() || leaving) break
                                if (pressed.size >= 2) {
                                    pinched = true
                                    val zoom = event.calculateZoom()
                                    val centroid = event.calculateCentroid()
                                    val c = Offset(size.width / 2f, size.height / 2f)
                                    val s = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                    // Keep the point under the fingers where it is.
                                    val p = (centroid - c) - (centroid - c - pan) * (s / scale) + event.calculatePan()
                                    scale = s
                                    pan = if (s <= 1.01f) Offset.Zero else clampPan(p, s, size)
                                    event.changes.forEach { it.consume() }
                                } else {
                                    val change = pressed.first()
                                    val delta = change.positionChange()
                                    if (delta == Offset.Zero) continue
                                    if (scale > 1.01f) {
                                        pan = clampPan(pan + delta, scale, size)
                                    } else if (!pinched) {
                                        drag += delta
                                        tracker.addPosition(change.uptimeMillis, change.position)
                                        val v = verdictFor(drag, size.width.toFloat(), size.height.toFloat())
                                        if (v != armed) {
                                            if (v != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            armed = v
                                        }
                                    }
                                    change.consume()
                                }
                            }
                            if (scale <= 1.01f && !pinched && !leaving && drag != Offset.Zero) {
                                val vel = tracker.calculateVelocity().let { Offset(it.x, it.y) }
                                val fast = with(density) { 1200.dp.toPx() }
                                val v = verdictFor(drag, size.width.toFloat(), size.height.toFloat()) ?: when {
                                    -vel.y > fast && -vel.y > abs(vel.x) -> Verdict.SKIP
                                    vel.x > fast && drag.x > 0 -> Verdict.PICK
                                    vel.x < -fast && drag.x < 0 -> Verdict.REJECT
                                    else -> null
                                }
                                if (v != null) flyOut(v, vel)
                                else scope.launch {
                                    animate(Offset.VectorConverter, drag, Offset.Zero, animationSpec = spring(dampingRatio = 0.7f)) { value, _ -> drag = value }
                                }
                            }
                        }
                    },
            ),
    ) {
        ZoomedPhoto(shot, image, tile, scale, pan)

        if (depth == 0) {
            CardInfo(shot, Modifier.align(Alignment.BottomStart).alpha(if (scale > 1.01f) 0f else 1f))
            if (progressX > 0f) Stamp("PICK", PickGreen, -14f, Modifier.align(Alignment.TopStart).alpha(progressX))
            if (progressX < 0f) Stamp(rejectLabel, RejectRed, 14f, Modifier.align(Alignment.TopEnd).alpha(-progressX))
            if (progressUp > 0f) Stamp(skipLabel, SkipBlue, 0f, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).alpha(progressUp))
        }
    }
}

@Composable
private fun Stamp(text: String, color: Color, angle: Float, modifier: Modifier) {
    Text(
        text,
        color = color,
        fontSize = 34.sp,
        fontWeight = FontWeight.Black,
        modifier = modifier
            .padding(28.dp)
            .rotate(angle)
            .border(4.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

@Composable
private fun CardInfo(shot: Shot, modifier: Modifier) {
    val kind = when {
        shot.raw != null && shot.jpeg != null -> "RAW+JPEG"
        shot.raw != null -> "RAW"
        else -> "JPEG"
    }
    val time = SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault()).format(Date(shot.takenMillis))
    Column(
        modifier.fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(shot.primary.stem, color = Color.White, fontWeight = FontWeight.SemiBold)
        Text("$kind · $time", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
    }
}

/** Where a ContentScale.Fit image of [iw]x[ih] lands inside [box]. */
private fun fitRect(box: IntSize, iw: Int, ih: Int): Rect {
    val k = min(box.width / iw.toFloat(), box.height / ih.toFloat())
    val w = iw * k
    val h = ih * k
    val l = (box.width - w) / 2
    val t = (box.height - h) / 2
    return Rect(l, t, l + w, t + h)
}

/** Keeps a zoomed card from being dragged past its edges. */
private fun clampPan(p: Offset, scale: Float, size: IntSize): Offset {
    val mx = size.width * (scale - 1) / 2
    val my = size.height * (scale - 1) / 2
    return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
}

@Composable
private fun rememberCullImage(shot: Shot): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, shot.key) {
        value = withContext(Dispatchers.IO) { Photos.cullImage(context, shot, CULL_EDGE)?.asImageBitmap() }
    }
    return image
}

/** Zoomed in and settled: the visible part of the JPEG decoded at full detail, in content coordinates. */
@Composable
private fun rememberSharpTile(shot: Shot, image: ImageBitmap?, scale: Float, pan: Offset, size: IntSize): Pair<Rect, ImageBitmap>? {
    val context = LocalContext.current
    var tile by remember(shot.key) { mutableStateOf<Pair<Rect, ImageBitmap>?>(null) }
    LaunchedEffect(shot.key, scale, pan, image, size) {
        tile = null
        val jpeg = shot.jpeg ?: return@LaunchedEffect
        val img = image ?: return@LaunchedEffect
        if (scale < 1.5f || size.width == 0) return@LaunchedEffect
        delay(250)
        val fit = fitRect(size, img.width, img.height)
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val c = Offset(w / 2, h / 2)
        // Screen point q shows content point c + (q - c - pan) / scale.
        fun content(q: Offset) = c + (q - c - pan) / scale
        val tl = content(Offset.Zero)
        val br = content(Offset(w, h))
        val vis = Rect(max(tl.x, fit.left), max(tl.y, fit.top), min(br.x, fit.right), min(br.y, fit.bottom))
        if (vis.width <= 0 || vis.height <= 0) return@LaunchedEffect
        val area = RectF(
            (vis.left - fit.left) / fit.width, (vis.top - fit.top) / fit.height,
            (vis.right - fit.left) / fit.width, (vis.bottom - fit.top) / fit.height,
        )
        val screenWidth = (vis.width * scale).toInt()
        val bmp = withContext(Dispatchers.IO) { Photos.jpegRegion(context, jpeg.uri, area, screenWidth) } ?: return@LaunchedEffect
        tile = vis to bmp.asImageBitmap()
    }
    return tile
}

/** The photo fitted to the box, zoomed by [scale] and [pan], with the sharp [tile] on top. */
@Composable
private fun ZoomedPhoto(shot: Shot, image: ImageBitmap?, tile: Pair<Rect, ImageBitmap>?, scale: Float, pan: Offset) {
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale; scaleY = scale
            translationX = pan.x; translationY = pan.y
        },
    ) {
        image?.let { img ->
            Image(img, shot.primary.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            tile?.let { (r, bmp) ->
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(
                        bmp,
                        dstOffset = IntOffset(r.left.toInt(), r.top.toInt()),
                        dstSize = IntSize(r.width.toInt(), r.height.toInt()),
                    )
                }
            }
        }
    }
}
