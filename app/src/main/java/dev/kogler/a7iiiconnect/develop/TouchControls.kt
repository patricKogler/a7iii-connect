package dev.kogler.a7iiiconnect.develop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** True while a slider (or curve, wheel, swipe) is being dragged. Panels fade the rest out. */
internal val LocalAdjusting = compositionLocalOf { false }

/** Fades a panel element out while something is being dragged, so the photo has your eye. */
internal fun Modifier.fadeWhileAdjusting(): Modifier = composed {
    val alpha by animateFloatAsState(if (LocalAdjusting.current) 0f else 1f, label = "fade")
    graphicsLayer { this.alpha = alpha }
}

/**
 * Moves a value by [delta] while keeping its unsnapped position in [raw]: the value sticks at
 * [default] for a short stretch, so zero is easy to hit. Returns the value to show.
 */
internal class Detent(private val min: Float, private val max: Float, private val default: Float) {
    var raw = 0f
    private val width = (max - min) * 0.012f

    fun start(v: Float) { raw = v }

    fun move(delta: Float): Float {
        raw = (raw + delta).coerceIn(min, max)
        return if (default in min..max && abs(raw - default) < width) default else raw
    }
}

/** How much slower a slider moves when the finger is [away] px from it (Apple-style scrubbing). */
internal fun scrubSpeed(away: Float, dp: Float): Float = when {
    away < 48 * dp -> 1f
    away < 140 * dp -> 0.5f
    away < 260 * dp -> 0.25f
    else -> 0.1f
}

internal fun speedLabel(speed: Float) = when (speed) {
    0.5f -> "Half speed"
    0.25f -> "Fine"
    else -> "Very fine"
}

/**
 * A slider built for fingers: it moves only when dragged sideways (a tap never makes it jump,
 * and a vertical swipe still scrolls the panel), fills from its default, clicks and sticks
 * briefly at the default, and slows down as the finger moves away from it, so sliding up over
 * the photo gives fine control.
 */
@Composable
internal fun PrecisionSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    track: Brush?,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    onActive: (Boolean) -> Unit,
    onSpeed: (Float) -> Unit,
    onDoubleTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val active by rememberUpdatedState(onActive)
    val speedChanged by rememberUpdatedState(onSpeed)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val lo = range.start; val hi = range.endInclusive
    val span = (hi - lo).coerceAtLeast(1e-6f)

    Canvas(
        modifier.fillMaxWidth().height(36.dp).pointerInput(lo, hi, default) {
            val pad = 12.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var over = 0f
                val first = awaitHorizontalTouchSlopOrCancellation(down.id) { c, o -> c.consume(); over = o }
                    ?: return@awaitEachGesture
                active(true)
                val width = (size.width - 2 * pad).coerceAtLeast(1f)
                val detent = Detent(lo, hi, default).apply { start(current) }
                var shown = current
                var speed = 1f
                fun move(dx: Float, y: Float) {
                    val away = max(0f, max(-y, y - size.height))
                    val s = scrubSpeed(away, density)
                    if (s != speed) { speed = s; speedChanged(s) }
                    val next = detent.move(dx / width * span * speed)
                    if (next == shown) return
                    if ((next == default) || next == lo || next == hi) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    shown = next
                    change(next)
                }
                move(over, first.position.y)
                drag(first.id) { c ->
                    move(c.positionChange().x, c.position.y)
                    c.consume()
                }
                active(false)
                if (speed != 1f) speedChanged(1f)
                done()
            }
        }.pointerInput(Unit) {
            // A drag consumes its moves, which cancels this; a double tap resets.
            detectTapGestures(onDoubleTap = { doubleTap?.invoke() })
        },
    ) {
        val pad = 12.dp.toPx()
        val w = size.width - 2 * pad
        val cy = size.height / 2
        val h = 4.dp.toPx()
        fun x(v: Float) = pad + (v.coerceIn(lo, hi) - lo) / span * w
        if (track != null) {
            translate(pad, cy - h / 2) { drawRoundRect(track, size = Size(w, h), cornerRadius = CornerRadius(h / 2)) }
        } else {
            drawRoundRect(Color(0xFF4A4A4A), Offset(pad, cy - h / 2), Size(w, h), CornerRadius(h / 2))
            val a = x(default); val b = x(value)
            drawRect(Color(0xFFBDBDBD), Offset(min(a, b), cy - h / 2), Size(abs(b - a), h))
        }
        if (default > lo && default < hi) {
            val a = x(default)
            drawLine(Color(0xFF9A9A9A), Offset(a, cy - 7.dp.toPx()), Offset(a, cy + 7.dp.toPx()), 1.5f.dp.toPx())
        }
        val tx = x(value)
        drawCircle(Color.Black.copy(alpha = 0.35f), 11.dp.toPx(), Offset(tx, cy))
        drawCircle(Color.White, 9.dp.toPx(), Offset(tx, cy))
    }
}

internal fun parseNumber(s: String): Float? = s.trim().replace(',', '.').removePrefix("+").toFloatOrNull()

/** Type an exact value. */
@Composable
internal fun ValueDialog(title: String, initial: String, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSet(text.text) }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                // Number pads often have no minus key.
                TextButton(onClick = {
                    val t = text.text.trim()
                    val flipped = when {
                        t.startsWith("-") -> t.drop(1)
                        t.startsWith("+") -> "-" + t.drop(1)
                        else -> "-$t"
                    }
                    text = TextFieldValue(flipped, TextRange(flipped.length))
                }) { Text("±") }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        },
        confirmButton = { TextButton(onClick = { onSet(text.text) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
