package dev.kogler.a7iiiconnect.duo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kogler.a7iiiconnect.develop.PickGreen
import dev.kogler.a7iiiconnect.develop.Photos
import dev.kogler.a7iiiconnect.develop.Shot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

internal val Gold = Color(0xFFFFC94D)
internal val Pink = Color(0xFFFF6B9A)
internal val MeColor = Color(0xFF8AB4F8)
internal val PartnerColor = Pink
internal val Panel = Color(0xFF1C1C1E)
internal val Dim = Color.White.copy(alpha = 0.65f)

@Composable
internal fun TopBar(title: String, subtitle: String? = null, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = Dim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
internal fun Avatar(name: String, color: Color, size: Int = 28) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = (size * 0.45f).sp)
    }
}

/** Everyone's progress through the deck, as a little race. */
@Composable
internal fun Race(rows: List<Triple<String, Int, Color>>, total: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((name, n, color) in rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, color, 20)
                Spacer(Modifier.width(8.dp))
                val p by animateFloatAsState(if (total == 0) 0f else n / total.toFloat(), label = "race")
                LinearProgressIndicator(
                    progress = { p }, color = color, trackColor = Color.White.copy(alpha = 0.1f),
                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.width(8.dp))
                Text("$n/$total", color = Dim, fontSize = 12.sp, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun Spinner(text: String, progress: Float? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (progress != null) CircularProgressIndicator(progress = { progress }, color = Color.White)
        else CircularProgressIndicator(color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(text, color = Dim, textAlign = TextAlign.Center)
    }
}

/** A big message in the middle: an icon, a title, a line under it. */
@Composable
internal fun Message(title: String, detail: String? = null, icon: @Composable () -> Unit = {}, below: @Composable () -> Unit = {}) {
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Spacer(Modifier.height(12.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (detail != null) {
                Spacer(Modifier.height(8.dp))
                Text(detail, color = Dim, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            below()
        }
    }
}

@Composable
internal fun Banner(text: String, progress: Float? = null, action: String? = null, onAction: () -> Unit = {}) {
    Surface(color = Color(0xEE2C2C2E), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = Color.White, modifier = Modifier.weight(1f))
                if (action != null) TextButton(onClick = onAction) { Text(action) }
            }
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** A shot, loaded off the main thread. */
@Composable
internal fun ShotImage(shot: Shot, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit, edge: Int = 2048) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, shot.key, edge) {
        value = withContext(Dispatchers.IO) { runCatching { Photos.cullImage(context, shot, edge)?.asImageBitmap() }.getOrNull() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        image?.let { Image(it, shot.primary.name, contentScale = contentScale, modifier = Modifier.fillMaxSize()) }
            ?: CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
    }
}

/** Two phones sliding together, back to back, with NFC waves where they meet. */
@Composable
internal fun PhonesTouching(modifier: Modifier = Modifier.size(220.dp, 140.dp)) {
    val t = rememberInfiniteTransition(label = "phones")
    val gap by t.animateFloat(1f, 0f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "gap")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "wave")
    Canvas(modifier) {
        val pw = size.width * 0.26f
        val ph = size.height * 0.92f
        val cx = size.width / 2
        val top = (size.height - ph) / 2
        val off = 6.dp.toPx() + gap * size.width * 0.14f
        drawRoundRect(MeColor, Offset(cx - off - pw, top), Size(pw, ph), CornerRadius(14.dp.toPx()), style = Stroke(3.dp.toPx()))
        drawRoundRect(PartnerColor, Offset(cx + off, top), Size(pw, ph), CornerRadius(14.dp.toPx()), style = Stroke(3.dp.toPx()))
        if (gap < 0.35f) {
            for (k in 0..2) {
                val r = (k + wave) * 10.dp.toPx()
                drawCircle(Color.White.copy(alpha = (1f - (k + wave) / 3f).coerceIn(0f, 1f) * 0.6f), r, Offset(cx, size.height / 2), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

@Composable
internal fun Confetti() {
    val pieces = remember { List(70) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextInt(5)) } }
    val fall = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fall.animateTo(1f, tween(3200, easing = LinearEasing)) }
    val colors = listOf(Pink, Gold, PickGreen, MeColor, Color.White)
    Canvas(Modifier.fillMaxSize()) {
        val t = fall.value
        if (t >= 1f) return@Canvas
        for ((x, delayFrac, c) in pieces) {
            val y = (t * 1.6f - delayFrac * 0.6f) * size.height
            if (y < 0) continue
            val sway = sin((t * 6 + x * 10) * PI.toFloat()) * 18.dp.toPx()
            drawRect(colors[c], Offset(x * size.width + sway, y), Size(7.dp.toPx(), 12.dp.toPx()), alpha = 1f - t)
        }
    }
}

/** "It's a match!" with a heart that pops, every time [tick] goes up. */
@Composable
internal fun MatchBurst(tick: Int, line: String) {
    val haptics = LocalHapticFeedback.current
    val first = remember { tick }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        if (tick == first) return@LaunchedEffect
        visible = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        delay(1300)
        visible = false
    }
    AnimatedVisibility(visible, enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Favorite, null, tint = Pink, modifier = Modifier.size(120.dp))
                Text("It's a match!", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text(line, color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

internal fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
