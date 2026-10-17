package dev.kogler.a7iiiconnect.duo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The app icon's seal red. */
internal val SealRed = Color(0xFFB3202A)

/**
 * The end of a round or a battle: a red seal, like the app's icon, stamped on the result. It
 * reads [word] (SEALED, KEPT, WINNER…), the date, and the initials of everyone who sealed it.
 * [plain] leaves the words off, for a small one in a list. With [animate] it lands: dropped from above, a thud, ink spattering round it.
 */
@Composable
internal fun Stamp(word: String, initials: List<String>, at: Long, animate: Boolean, modifier: Modifier = Modifier.size(170.dp), plain: Boolean = false) {
    val haptics = LocalHapticFeedback.current
    val drop = remember { Animatable(if (animate) 0f else 1f) }
    val ink = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        drop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
    }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        delay(140)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        ink.animateTo(1f, tween(380))
        delay(90)
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    // The spatter is the same every time this seal is drawn.
    val dots = remember(word, at) {
        val r = Random(at)
        List(14) { Triple(r.nextFloat() * 6.283f, 0.62f + r.nextFloat() * 0.38f, 1.5f + r.nextFloat() * 4f) }
    }
    val date = remember(at) { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(at)) }
    Box(
        modifier.testTag("seal").graphicsLayer {
            val d = drop.value
            scaleX = 2.6f - 1.6f * d
            scaleY = 2.6f - 1.6f * d
            alpha = (d * 1.6f).coerceAtMost(1f) * 0.92f
            rotationZ = -9f
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(SealRed, r * 0.97f, c, style = Stroke(r * 0.07f))
            drawCircle(SealRed, r * 0.80f, c, style = Stroke(r * 0.025f))
            for ((angle, dist, dot) in dots) {
                val d = (r * (1.02f + dist * 0.25f * ink.value))
                drawCircle(SealRed.copy(alpha = 0.85f * ink.value), dot.dp.toPx() * ink.value, Offset(c.x + cos(angle) * d, c.y + sin(angle) * d))
            }
        }
        if (!plain) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (initials.isNotEmpty()) {
                Text(initials.joinToString(" · "), color = SealRed, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Serif)
            }
            Text(word, color = SealRed, fontWeight = FontWeight.Black, fontSize = 26.sp, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center)
            Text(date, color = SealRed, fontSize = 11.sp, fontFamily = FontFamily.Serif)
        }
    }
}

/**
 * Press and hold to seal: a ring fills round the button while held, and the seal comes down once
 * it's full. Letting go early lets the ring run back.
 */
@Composable
internal fun HoldToSeal(label: String, onSealed: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val fill = remember { Animatable(0f) }
    Box(
        modifier.size(132.dp).testTag("hold-to-seal").pointerInput(Unit) {
            detectTapGestures(onPress = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val run = scope.launch {
                    fill.animateTo(1f, tween(((1f - fill.value) * HOLD_MS).toInt().coerceAtLeast(1), easing = LinearEasing))
                    onSealed()
                }
                val released = tryAwaitRelease()
                if (fill.value < 1f) {
                    run.cancel()
                    scope.launch { fill.animateTo(0f, tween(250)) }
                } else if (!released) run.cancel()
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 * 0.9f
            drawCircle(Color.White.copy(alpha = 0.12f), r, style = Stroke(6.dp.toPx()))
            drawArc(
                SealRed, -90f, 360f * fill.value, false, style = Stroke(6.dp.toPx()),
                topLeft = Offset(size.width / 2 - r, size.height / 2 - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            )
            drawCircle(SealRed.copy(alpha = 0.25f + 0.6f * fill.value), r * 0.78f)
        }
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
    }
}

private const val HOLD_MS = 800
