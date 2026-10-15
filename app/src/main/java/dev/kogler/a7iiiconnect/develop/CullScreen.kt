package dev.kogler.a7iiiconnect.develop

import android.graphics.RectF
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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

/** What a swipe decided: a flag, or "skip for now" (stays unreviewed). */
private enum class Verdict(val flag: Flag?) { PICK(Flag.PICK), REJECT(Flag.REJECT), SKIP(null) }

private val PickGreen = Color(0xFF3DDC84)
private val RejectRed = Color(0xFFFF5A5F)
private val SkipBlue = Color(0xFF8AB4F8)

private const val CULL_EDGE = 2048
private const val MAX_ZOOM = 8f

/**
 * Tinder-style culling: right picks, left rejects, up skips. Double-tap or pinch to check focus;
 * while zoomed in a drag pans instead of deciding.
 */
@Composable
fun CullScreen(
    deck: List<Shot>,
    flags: Map<String, Flag>,
    onFlag: (Shot, Flag?) -> Unit,
    rejectedCount: Int,
    onDeleteRejected: () -> Unit,
    onBack: () -> Unit,
) {
    var pos by remember { mutableIntStateOf(0) }
    // What each decision replaced, so undo can put it back.
    val undo = remember { mutableStateListOf<Pair<Int, Flag?>>() }
    val decided = remember { mutableStateMapOf<String, Verdict>() }
    // A button press flies the top card out; the card itself animates and then reports back.
    var fling by remember { mutableStateOf<Verdict?>(null) }

    fun decide(v: Verdict) {
        val shot = deck[pos]
        undo += pos to flags[shot.key]
        decided[shot.key] = v
        if (v != Verdict.SKIP) onFlag(shot, v.flag)
        fling = null
        pos++
    }

    fun undoLast() {
        val (i, previous) = undo.removeLastOrNull() ?: return
        val shot = deck[i]
        onFlag(shot, previous)
        decided.remove(shot.key)
        fling = null
        pos = i
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text(
                if (pos < deck.size) "${pos + 1} of ${deck.size}" else "Done",
                color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { undoLast() }, enabled = undo.isNotEmpty(), modifier = Modifier.testTag("cull-undo")) {
                Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", tint = if (undo.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f))
            }
        }

        if (pos >= deck.size) {
            Summary(
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
            // The next two cards wait underneath (already loaded), the top one is live.
            for (i in min(pos + 2, deck.size - 1) downTo pos) {
                val shot = deck[i]
                key(shot.key) {
                    SwipeCard(
                        shot = shot,
                        depth = i - pos,
                        fling = if (i == pos) fling else null,
                        onDecided = { decide(it) },
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Icons.Rounded.Close, "Reject", RejectRed, 64, "cull-reject") { if (fling == null) fling = Verdict.REJECT }
            RoundButton(Icons.Rounded.ArrowUpward, "Skip", SkipBlue, 48, "cull-skip") { if (fling == null) fling = Verdict.SKIP }
            RoundButton(Icons.Rounded.Check, "Pick", PickGreen, 64, "cull-pick") { if (fling == null) fling = Verdict.PICK }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, size: Int, tag: String, onClick: () -> Unit) {
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
    picked: Int, rejected: Int, skipped: Int, rejectedCount: Int,
    onDeleteRejected: () -> Unit, onBack: () -> Unit, modifier: Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("All caught up", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            listOfNotNull("$picked picked", "$rejected rejected", if (skipped > 0) "$skipped skipped" else null).joinToString(" · "),
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
private fun SwipeCard(shot: Shot, depth: Int, fling: Verdict?, onDecided: (Verdict) -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val image by produceState<ImageBitmap?>(null, shot.key) {
        value = withContext(Dispatchers.IO) { Photos.cullImage(context, shot, CULL_EDGE)?.asImageBitmap() }
    }

    var drag by remember { mutableStateOf(Offset.Zero) }
    var leaving by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var tile by remember { mutableStateOf<Pair<Rect, ImageBitmap>?>(null) }

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

    // Zoomed in and settled: decode the visible part of the JPEG at full detail.
    LaunchedEffect(scale, pan, image, size) {
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

        if (depth == 0) {
            CardInfo(shot, Modifier.align(Alignment.BottomStart).alpha(if (scale > 1.01f) 0f else 1f))
            if (progressX > 0f) Stamp("PICK", PickGreen, -14f, Modifier.align(Alignment.TopStart).alpha(progressX))
            if (progressX < 0f) Stamp("REJECT", RejectRed, 14f, Modifier.align(Alignment.TopEnd).alpha(-progressX))
            if (progressUp > 0f) Stamp("SKIP", SkipBlue, 0f, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).alpha(progressUp))
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
