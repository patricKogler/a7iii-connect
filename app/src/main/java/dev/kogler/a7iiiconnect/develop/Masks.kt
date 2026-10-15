package dev.kogler.a7iiiconnect.develop

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kogler.a7iiiconnect.develop.engine.Geometry
import dev.kogler.a7iiiconnect.develop.engine.LocalP
import dev.kogler.a7iiiconnect.develop.engine.Mask
import dev.kogler.a7iiiconnect.develop.engine.MaskKind
import dev.kogler.a7iiiconnect.develop.engine.MaskSet
import dev.kogler.a7iiiconnect.develop.engine.Param
import dev.kogler.a7iiiconnect.develop.engine.Group
import dev.kogler.a7iiiconnect.develop.engine.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where finding the subject stands, for the Masks panel. */
internal sealed interface SubjectStatus {
    data object Ready : SubjectStatus
    data object Finding : SubjectStatus
    data class Failed(val message: String) : SubjectStatus
}

/** Screen position <-> position in the upright photo (fractions), through the photo's geometry. */
private class MaskSpace(val geom: Geometry, val rect: Rect) {
    private val m = DoubleArray(6)

    fun toPhoto(p: Offset): Offset {
        geom.map(((p.x - rect.left) / rect.width * geom.outW).toDouble(), ((p.y - rect.top) / rect.height * geom.outH).toDouble(), m)
        return Offset((m[2] / geom.srcW).toFloat(), (m[3] / geom.srcH).toFloat())
    }

    fun toScreen(u: Double, v: Double): Offset {
        val o = geom.unmap(u * geom.srcW, v * geom.srcH)
        return Offset(rect.left + (o[0] / geom.outW * rect.width).toFloat(), rect.top + (o[1] / geom.outH * rect.height).toFloat())
    }

    /** A point [rx], [ry] full-size pixels along a radial mask's turned axes from its centre. */
    fun radialPoint(mask: Mask, rx: Double, ry: Double): Offset {
        val a = mask.angle * PI / 180
        val x = mask.x0 * geom.srcW + rx * cos(a) - ry * sin(a)
        val y = mask.y0 * geom.srcH + rx * sin(a) + ry * cos(a)
        return toScreen(x / geom.srcW, y / geom.srcH)
    }
}

private enum class MaskHandle { MOVE, START, END, RADIUS_X, RADIUS_Y }

/**
 * The photo with the selected mask's handles over it. Linear: drag the solid line (full effect)
 * or the dashed one (no effect), or the middle to move both. Radial: drag the centre to move,
 * the two edge dots to size and turn it. Dragging anywhere else moves the mask. [overlay] tints
 * what the mask covers in red, as Lightroom does.
 */
@Composable
internal fun MaskView(
    layer: PhotoLayer,
    opened: OpenedPhoto,
    settings: Settings,
    selected: Int,
    overlay: Boolean,
    onChange: (Settings) -> Unit,
    onDone: () -> Unit,
    onTap: () -> Unit = {},
) {
    var box by remember { mutableStateOf(Size.Zero) }
    var handleDrag by remember { mutableStateOf(false) }
    val s by rememberUpdatedState(settings)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val tap by rememberUpdatedState(onTap)
    val touch = with(LocalDensity.current) { 32.dp.toPx() }
    val mask = settings.masks.getOrNull(selected)
    val geom = opened.geometry(settings)

    // The red coverage tint, rendered small on the CPU.
    var tint by remember { mutableStateOf<ImageBitmap?>(null) }
    val showTint = mask != null && (overlay || handleDrag)
    LaunchedEffect(showTint, mask?.copy(values = emptyMap()), geom.outW, geom.outH, opened.preview.maps.subject) {
        tint = if (!showTint || mask == null) null else withContext(Dispatchers.Default) { coverage(opened, settings, mask) }
    }

    Box(
        Modifier.fillMaxSize().testTag("mask-view")
            .onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }
            // A tap (not a drag) shows or hides full screen's controls, as on the plain photo.
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) }
            .pointerInput(selected, mask?.kind, layer.width, layer.height) {
                var handle = MaskHandle.MOVE
                var start: Mask? = null
                var origin = Offset.Zero
                var moved = Offset.Zero
                detectDragGestures(
                    onDragStart = { p ->
                        start = null
                        val m = s.masks.getOrNull(selected)?.takeIf { it.kind != MaskKind.SUBJECT }
                        if (m != null) {
                            val space = MaskSpace(opened.geometry(s), fitRect(box, layer.width, layer.height))
                            val near = handlesOf(space, m).minByOrNull { (_, at) -> hypot(at.x - p.x, at.y - p.y) }
                            val hit = near?.takeIf { (_, at) -> hypot(at.x - p.x, at.y - p.y) < touch * 1.3f }
                            handle = hit?.first ?: MaskHandle.MOVE
                            origin = hit?.second ?: p
                            start = m
                            moved = Offset.Zero
                            handleDrag = true
                        }
                    },
                    onDrag = { c, d ->
                        val m0 = start
                        if (m0 != null) {
                            c.consume()
                            moved += d
                            val space = MaskSpace(opened.geometry(s), fitRect(box, layer.width, layer.height))
                            val m = dragMask(space, m0, handle, origin, origin + moved)
                            if (selected < s.masks.size) change(s.withMask(selected, m))
                        }
                    },
                    onDragEnd = { if (start != null) done(); start = null; handleDrag = false },
                    onDragCancel = { if (start != null) done(); start = null; handleDrag = false },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = fitRect(size, layer.width, layer.height)
            drawLayer(layer, r)
            tint?.let {
                drawImage(
                    it, dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                    dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()), filterQuality = FilterQuality.Low,
                )
            }
            if (mask != null && mask.kind != MaskKind.SUBJECT) {
                clipRect(r.left, r.top, r.right, r.bottom) { drawHandles(MaskSpace(geom, r), mask, r) }
            }
        }
    }
}

private fun handlesOf(space: MaskSpace, m: Mask): List<Pair<MaskHandle, Offset>> = when (m.kind) {
    MaskKind.LINEAR -> {
        val a = space.toScreen(m.x0.toDouble(), m.y0.toDouble())
        val b = space.toScreen(m.x1.toDouble(), m.y1.toDouble())
        listOf(MaskHandle.START to a, MaskHandle.END to b, MaskHandle.MOVE to (a + b) / 2f)
    }
    MaskKind.RADIAL -> {
        val g = space.geom
        listOf(
            MaskHandle.MOVE to space.toScreen(m.x0.toDouble(), m.y0.toDouble()),
            MaskHandle.RADIUS_X to space.radialPoint(m, m.x1 * g.srcW, 0.0),
            MaskHandle.RADIUS_Y to space.radialPoint(m, 0.0, m.y1 * g.srcH),
        )
    }
    MaskKind.SUBJECT -> emptyList()
}

/** [m0] with [handle] dragged from screen point [from] to [to]. */
private fun dragMask(space: MaskSpace, m0: Mask, handle: MaskHandle, from: Offset, to: Offset): Mask {
    val g = space.geom
    val p = space.toPhoto(to)
    return when (handle) {
        MaskHandle.MOVE -> {
            val d = p - space.toPhoto(from)
            if (m0.kind == MaskKind.LINEAR) m0.copy(x0 = m0.x0 + d.x, y0 = m0.y0 + d.y, x1 = m0.x1 + d.x, y1 = m0.y1 + d.y)
            else m0.copy(x0 = m0.x0 + d.x, y0 = m0.y0 + d.y)
        }
        MaskHandle.START -> m0.copy(x0 = p.x, y0 = p.y)
        MaskHandle.END -> m0.copy(x1 = p.x, y1 = p.y)
        MaskHandle.RADIUS_X -> {
            val vx = (p.x - m0.x0) * g.srcW; val vy = (p.y - m0.y0) * g.srcH
            m0.copy(x1 = (hypot(vx, vy) / g.srcW).toFloat().coerceAtLeast(0.005f), angle = (atan2(vy, vx) * 180 / PI).toFloat())
        }
        MaskHandle.RADIUS_Y -> {
            val vx = (p.x - m0.x0) * g.srcW; val vy = (p.y - m0.y0) * g.srcH
            val a = m0.angle * PI / 180
            val along = -vx * sin(a) + vy * cos(a)
            m0.copy(y1 = (kotlin.math.abs(along) / g.srcH).toFloat().coerceAtLeast(0.005f))
        }
    }
}

private fun DrawScope.drawHandles(space: MaskSpace, m: Mask, r: Rect) {
    val line = 2.dp.toPx()
    val shadow = Color.Black.copy(alpha = 0.5f)
    fun stroke(path: Path, dashed: Boolean = false, width: Float = line) {
        val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx())) else null
        drawPath(path, shadow, style = Stroke(width + 2.dp.toPx(), pathEffect = effect))
        drawPath(path, Color.White, style = Stroke(width, pathEffect = effect))
    }
    fun dot(at: Offset, big: Boolean) {
        val rad = if (big) 9.dp.toPx() else 7.dp.toPx()
        drawCircle(shadow, rad + 2.dp.toPx(), at)
        drawCircle(Color.White, rad, at)
        if (big) drawCircle(Color.Black.copy(alpha = 0.6f), rad * 0.45f, at)
    }
    when (m.kind) {
        MaskKind.LINEAR -> {
            val a = space.toScreen(m.x0.toDouble(), m.y0.toDouble())
            val b = space.toScreen(m.x1.toDouble(), m.y1.toDouble())
            val d = b - a
            val len = hypot(d.x, d.y).coerceAtLeast(1f)
            val n = Offset(-d.y / len, d.x / len) * (r.width + r.height)
            for ((at, dashed) in listOf(a to false, b to true)) {
                stroke(Path().apply { moveTo(at.x - n.x, at.y - n.y); lineTo(at.x + n.x, at.y + n.y) }, dashed)
            }
            val mid = (a + b) / 2f
            stroke(Path().apply { moveTo(mid.x - n.x, mid.y - n.y); lineTo(mid.x + n.x, mid.y + n.y) }, width = 1.dp.toPx())
            dot(a, false); dot(b, false); dot(mid, true)
        }
        MaskKind.RADIAL -> {
            val g = space.geom
            val rx = m.x1 * g.srcW; val ry = m.y1 * g.srcH
            fun ring(k: Double) = Path().apply {
                for (i in 0..72) {
                    val t = i * 2 * PI / 72
                    val p = space.radialPoint(m, rx * k * cos(t), ry * k * sin(t))
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            stroke(ring(1.0))
            // Inside the dashed ring the effect is full; it fades out towards the solid one.
            val inner = 1.0 - maxOf(0.02, m.feather / 100.0)
            if (inner > 0.02) stroke(ring(inner), dashed = true, width = 1.dp.toPx())
            dot(space.toScreen(m.x0.toDouble(), m.y0.toDouble()), true)
            dot(space.radialPoint(m, rx, 0.0), false)
            dot(space.radialPoint(m, 0.0, ry), false)
        }
        MaskKind.SUBJECT -> {}
    }
}

/** What [mask] covers, as a red tint over the framed photo (about 240 px on the long edge). */
private fun coverage(o: OpenedPhoto, s: Settings, mask: Mask): ImageBitmap {
    val geom = o.geometry(s)
    val k = 240.0 / maxOf(geom.outW, geom.outH)
    val w = maxOf(1, (geom.outW * k).toInt()); val h = maxOf(1, (geom.outH * k).toInt())
    val set = MaskSet(listOf(mask), geom.srcW, geom.srcH, o.preview.maps.subject)
    val px = IntArray(w * h)
    val m = DoubleArray(6)
    for (y in 0 until h) for (x in 0 until w) {
        geom.map((x + 0.5) / k, (y + 0.5) / k, m)
        val a = (set.weight(0, m[2].toFloat(), m[3].toFloat()).coerceIn(0f, 1f) * 0.55f * 255).toInt()
        px[y * w + x] = (a shl 24) or 0xff2a2a
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private val FEATHER = Param("feather", "Feather", Group.LIGHT, 0f, 100f, 50f)

private fun maskLabel(m: Mask) = when (m.kind) {
    MaskKind.LINEAR -> "Linear"
    MaskKind.RADIAL -> "Radial"
    MaskKind.SUBJECT -> "Subject"
}

/**
 * The Masks panel: the photo's masks as chips (tap to select), buttons to add one, and the
 * selected mask's options and sliders.
 */
@Composable
internal fun MaskPanel(
    c: Controls,
    selected: Int,
    onSelect: (Int) -> Unit,
    aspect: Float,
    subject: SubjectStatus,
    onRetrySubject: () -> Unit,
    overlay: Boolean,
    onOverlay: (Boolean) -> Unit,
) {
    val s = c.s
    val masks = s.masks
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp).fadeWhileAdjusting(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        masks.forEachIndexed { i, m ->
            FilterChip(
                selected = i == selected, onClick = { onSelect(i) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1} · ${maskLabel(m)}")
                        if (m.values.isNotEmpty()) ChangedDot(Modifier.padding(start = 6.dp))
                    }
                },
            )
        }
        if (masks.size < MaskSet.MAX) {
            for ((label, make) in listOf<Pair<String, () -> Mask>>(
                "Linear" to { Mask.linear() }, "Radial" to { Mask.radial(aspect) }, "Subject" to { Mask.subject() },
            )) {
                AssistChip(
                    onClick = {
                        c.commit(s.copy(masks = masks + make()))
                        onSelect(masks.size)
                        onOverlay(true)
                    },
                    label = { Text(label) },
                    leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(AssistChipDefaults.IconSize)) },
                    modifier = Modifier.testTag("add-mask-${label.lowercase()}"),
                )
            }
        }
    }
    val m = masks.getOrNull(selected)
    if (m == null) {
        Text(
            "Add a mask to change part of the photo: Linear for a sky, Radial for a face or a spotlight, " +
                "Subject to pick out the person or thing in front.",
            color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp),
        )
        return
    }
    fun set(next: Mask, live: Boolean = false) { if (live) c.change(s.withMask(selected, next)) else c.commit(s.withMask(selected, next)) }

    Row(Modifier.fillMaxWidth().fadeWhileAdjusting(), verticalAlignment = Alignment.CenterVertically) {
        Text(maskLabel(m), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = { onOverlay(!overlay) }) {
            Icon(if (overlay) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (overlay) "Hide the mask overlay" else "Show the mask overlay", tint = Color.White)
        }
        if (m.values.isNotEmpty()) TextButton(onClick = { set(m.copy(values = emptyMap())) }) { Text("Reset", fontSize = 12.sp) }
        IconButton(onClick = {
            c.commit(s.copy(masks = masks.filterIndexed { i, _ -> i != selected }))
            onSelect((selected - 1).coerceAtLeast(0))
        }) { Icon(Icons.Outlined.Delete, "Delete mask", tint = Color.White) }
    }
    if (m.usesSubject) {
        when (subject) {
            SubjectStatus.Finding -> {
                Text("Finding the subject…", color = Color.Gray, fontSize = 12.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
            is SubjectStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn't find the subject: ${subject.message}", color = Color(0xFFFF8A80), fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetrySubject) { Text("Retry") }
            }
            SubjectStatus.Ready -> {}
        }
    }
    Row(Modifier.fillMaxWidth().fadeWhileAdjusting(), verticalAlignment = Alignment.CenterVertically) {
        Text("Invert", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = m.invert, onCheckedChange = { set(m.copy(invert = it)) })
    }
    if (m.kind != MaskKind.SUBJECT) {
        Row(Modifier.fillMaxWidth().fadeWhileAdjusting(), verticalAlignment = Alignment.CenterVertically) {
            Text("Leave out the subject", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = m.excludeSubject, onCheckedChange = { set(m.copy(excludeSubject = it)) })
        }
    }
    if (m.kind == MaskKind.RADIAL) {
        AdjustSlider(
            FEATHER.label, m.feather, FEATHER.min..FEATHER.max, FEATHER.default, { formatParam(FEATHER, it) }, null,
            { set(m.copy(feather = it), live = true) }, c.done,
        )
    }
    for (p in LocalP.all) {
        val track = when (p) {
            LocalP.TEMP -> TEMP_TRACK
            LocalP.TINT -> TINT_TRACK
            LocalP.SATURATION -> SAT_TRACK
            else -> null
        }
        AdjustSlider(
            p.label, m[p], p.min..p.max, p.default, { formatParam(p, it) }, track,
            { set(m.with(p, it), live = true) }, c.done,
        )
    }
}
