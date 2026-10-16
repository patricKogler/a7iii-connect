package dev.kogler.a7iiiconnect.duo

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kogler.a7iiiconnect.develop.EditorScreen
import dev.kogler.a7iiiconnect.develop.ExportOptions
import dev.kogler.a7iiiconnect.develop.LibraryPhoto
import dev.kogler.a7iiiconnect.develop.OpenedPhoto
import dev.kogler.a7iiiconnect.develop.Photos
import dev.kogler.a7iiiconnect.develop.PickGreen
import dev.kogler.a7iiiconnect.develop.RejectRed
import dev.kogler.a7iiiconnect.develop.RoundButton
import dev.kogler.a7iiiconnect.develop.Shot
import dev.kogler.a7iiiconnect.develop.SkipBlue
import dev.kogler.a7iiiconnect.develop.StackCard
import dev.kogler.a7iiiconnect.develop.SwipeCard
import dev.kogler.a7iiiconnect.develop.Thumb
import dev.kogler.a7iiiconnect.develop.Verdict
import dev.kogler.a7iiiconnect.develop.engine.Settings
import dev.kogler.a7iiiconnect.duo.Duo.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private val Gold = Color(0xFFFFC94D)
private val Pink = Color(0xFFFF6B9A)

@Composable
fun DuoScreen(onExit: () -> Unit, onShareApp: () -> Unit, onInstall: (File) -> Unit) {
    var confirmLeave by remember { mutableStateOf(false) }
    val leave = { if (Duo.connected && !Duo.ended) confirmLeave = true else onExit() }
    BackHandler { leave() }
    val renders = rememberBattleRenders()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            when {
                Duo.ended -> Ended(onExit)
                !Duo.connected && Duo.phase == Phase.LOBBY -> Lobby(onClose = leave, onShareApp = onShareApp)
                Duo.albumsOnly && Duo.phase == Phase.LOBBY -> AlbumsTogether(onDone = onExit)
                Duo.phase == Phase.LOBBY -> Centered {
                    Spinner(if (Duo.battleOnly) "Connected to ${Duo.partnerName}. Getting the photo ready…" else "Connected to ${Duo.partnerName}. Getting the photos ready…")
                }
                Duo.phase == Phase.CULL -> Deck(onClose = leave)
                Duo.phase == Phase.REVEAL -> Reveal(onClose = leave)
                Duo.phase == Phase.DEBATE -> Debate(onClose = leave)
                Duo.phase == Phase.DONE -> Done(onClose = onExit)
                Duo.phase == Phase.BATTLE_PICK -> BattlePick(onClose = leave)
                Duo.phase == Phase.BATTLE_EDIT -> BattleEdit(onClose = leave)
                Duo.phase == Phase.BATTLE_VOTE -> BattleVote(renders, onClose = leave)
                Duo.phase == Phase.BATTLE_RESULT -> BattleResult(renders, onClose = leave)
                Duo.phase == Phase.BATTLE_OVER -> BattleOver(onClose = onExit)
            }
        }
        MatchBurst()
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (Duo.reconnecting && !Duo.ended) Banner("Lost ${Duo.partnerName}. Reconnecting…")
            Duo.transfer?.let { (label, f) -> Banner(label, f) }
            // Albums swap alongside whatever else is going on.
            if (!Duo.albumsOnly && Duo.connected && AlbumSync.busy) albumProgress()?.let { (label, f) -> Banner(label, f) }
            Duo.update?.let { apk ->
                Banner("${Duo.partnerName} has a newer Shootback", action = "Install") { onInstall(apk) }
            }
        }
        CoinResult()
        Duo.notice?.let { text ->
            LaunchedEffect(text) { delay(3500); if (Duo.notice == text) Duo.notice = null }
            Surface(
                color = Color(0xFF2C2C2E), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            ) { Text(text, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave Together?") },
            text = { Text(if (Duo.phase.ordinal < Phase.DONE.ordinal && !Duo.battleOnly) "The votes so far are lost for both of you." else "This ends the session for ${Duo.partnerName} too.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onExit() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}

// ---- Bits and pieces ------------------------------------------------------------------------

@Composable
private fun TopBar(title: String, subtitle: String? = null, onClose: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Leave", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
        }
        actions()
    }
}

@Composable
private fun Avatar(name: String, color: Color, size: Int = 28) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = (size * 0.45f).sp)
    }
}

private val MeColor = Color(0xFF8AB4F8)
private val PartnerColor = Pink

/** Both players' progress through the deck, as a little race. */
@Composable
private fun Race(mine: Int, theirs: Int, total: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((name, n, color) in listOf(Triple(Duo.myName, mine, MeColor), Triple(Duo.partnerName, theirs, PartnerColor))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, color, 20)
                Spacer(Modifier.width(8.dp))
                val p by animateFloatAsState(if (total == 0) 0f else n / total.toFloat(), label = "race")
                LinearProgressIndicator(
                    progress = { p }, color = color, trackColor = Color.White.copy(alpha = 0.1f),
                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.width(8.dp))
                Text("$n/$total", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Spinner(text: String, progress: Float? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (progress != null) CircularProgressIndicator(progress = { progress }, color = Color.White)
        else CircularProgressIndicator(color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(text, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun Banner(text: String, progress: Float? = null, action: String? = null, onAction: () -> Unit = {}) {
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

/** A deck picture, loaded off the main thread. */
@Composable
private fun ShotImage(shot: Shot, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, shot.key) {
        value = withContext(Dispatchers.IO) { Photos.cullImage(context, shot, 2048)?.asImageBitmap() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        image?.let { Image(it, shot.primary.name, contentScale = contentScale, modifier = Modifier.fillMaxSize()) }
            ?: CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
    }
}

// ---- Albums --------------------------------------------------------------------------------------

/** What's on its way, for the albums: the photos coming here first, then the ones going over. */
private fun albumProgress(): Pair<String, Float>? {
    val (got, of) = AlbumSync.getting
    val (sent, to) = AlbumSync.sending
    return when {
        AlbumSync.asked -> "Albums: asking ${Duo.partnerName} for photos" to 0f
        got < of -> "Albums: getting ${got + 1} of $of from ${Duo.partnerName}" to got / of.toFloat()
        sent < to -> "Albums: sending ${sent + 1} of $to to ${Duo.partnerName}" to sent / to.toFloat()
        else -> null
    }
}

/** Swapping albums, started from an album: how it's going, and Done. */
@Composable
private fun AlbumsTogether(onDone: () -> Unit) {
    TopBar("Albums with ${Duo.partnerName}", onClose = onDone)
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val progress = albumProgress()
        when {
            !AlbumSync.merged -> Spinner("Swapping albums with ${Duo.partnerName}…")
            progress != null -> Spinner(progress.first, progress.second)
            else -> {
                Icon(Icons.Rounded.Check, null, tint = Gold, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("Your albums are in sync", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        if (AlbumSync.merged) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${AlbumSync.albumCount} shared ${if (AlbumSync.albumCount == 1) "album" else "albums"} · ${AlbumSync.shotCount} shots",
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onDone) { Text(if (AlbumSync.inSync) "Done" else "Stop for now") }
        if (AlbumSync.merged && !AlbumSync.inSync) {
            Spacer(Modifier.height(8.dp))
            Text(
                "The albums are already swapped. Photos that don't make it now come the next time you tap.",
                color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- Lobby --------------------------------------------------------------------------------------

@Composable
private fun Lobby(onClose: () -> Unit, onShareApp: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    val hosting = Duo.hosting
    TopBar("Together", onClose = onClose)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when {
                hosting && Duo.battleOnly -> "Edit battle"
                hosting && Duo.albumsOnly -> "Albums together"
                hosting -> "Cull together"
                else -> "Join"
            },
            color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !hosting -> "Go through someone's photos with them"
                Duo.battleOnly -> "You both edit the same photo, then vote blind"
                Duo.albumsOnly -> "You both get the albums you share, and the photos in them"
                Duo.deck.isEmpty() -> "Getting the photos ready… ${(Duo.preparing * 100).toInt()}%"
                else -> "${Duo.deck.sumOf { it.size }} shots ready to share"
            },
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(28.dp))
        PhonesTouching()
        Spacer(Modifier.height(20.dp))
        Text(
            "Touch the backs of your phones together",
            color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (hosting) "Or open Together in Shootback on the other phone and pick ${Duo.myName}."
            else "Or pick the phone below.",
            color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        val others = Duo.nearby.entries.toList()
        if (others.isNotEmpty()) {
            Text("Nearby", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            for ((id, endpointName) in others) {
                val name = endpointName.substringBeforeLast('|')
                Surface(
                    color = Color(0xFF1C1C1E), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { Duo.connectTo(id) },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name, PartnerColor, 36)
                        Spacer(Modifier.width(12.dp))
                        Text("$name's photos", color = Color.White, modifier = Modifier.weight(1f))
                        Text("Join", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else if (!hosting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White.copy(alpha = 0.6f), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Looking for phones nearby…", color = Color.White.copy(alpha = 0.6f))
            }
        }

        Spacer(Modifier.height(32.dp))
        Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(20.dp), modifier = Modifier.clickable { renaming = true }) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(Duo.myName, MeColor, 24)
                Spacer(Modifier.width(8.dp))
                Text("Playing as ${Duo.myName}", color = Color.White)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.Edit, "Change name", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onShareApp) {
            Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Other phone hasn't got Shootback? Send it")
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(Duo.myName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Your name") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { Duo.rename(name); renaming = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

/** Two phones sliding together, back to back, with NFC waves where they meet. */
@Composable
private fun PhonesTouching() {
    val t = rememberInfiniteTransition(label = "phones")
    val gap by t.animateFloat(1f, 0f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "gap")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "wave")
    Canvas(Modifier.size(220.dp, 140.dp)) {
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

// ---- Culling --------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Deck(onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val tick = Duo.tick
    val deck = Duo.deck
    val session = Duo.session
    val me = Duo.me
    val available = if (Duo.hosting) deck.size else Duo.ready
    val pos = (0 until deck.size).firstOrNull { session.voteOf(me, it) == null } ?: deck.size
    val history = remember { mutableStateListOf<Int>() }
    var fling by remember { mutableStateOf<Verdict?>(null) }
    var chosen by remember { mutableIntStateOf(0) }

    fun record(item: Int, choice: Choice) {
        Duo.vote(item, choice)
        history += item
        fling = null
        chosen = 0
    }

    TopBar(
        "Round together",
        if (pos < deck.size) "${pos + 1} of ${deck.size}" else "Done",
        onClose = onClose,
    ) {
        IconButton(onClick = {
            history.removeLastOrNull()?.let { Duo.vote(it, null) }
            fling = null
            chosen = 0
        }, enabled = history.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", tint = if (history.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f))
        }
    }
    Race(session.progress(me), session.progress(Duo.partner), deck.size)

    if (pos >= deck.size) {
        Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("You're through!", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Waiting for ${Duo.partnerName}: ${session.progress(Duo.partner)} of ${deck.size}",
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
        return
    }
    val top = deck[pos]
    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        when {
            pos >= available -> Centered { Spinner("Getting the next photo from ${Duo.partnerName}…") }
            top.size > 1 -> StackCard(top, chosen.coerceIn(top.indices), onChoose = { chosen = it }, onSplit = null)
            else -> {
                for (i in min(pos + 2, available - 1) downTo pos) {
                    val shot = deck[i].first()
                    key(shot.key) {
                        SwipeCard(
                            shot = shot,
                            depth = i - pos,
                            fling = if (i == pos) fling else null,
                            rejectLabel = "NOPE",
                            skipLabel = "HMM",
                            onDecided = { v -> record(i, v.toChoice()) },
                        )
                    }
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val enabled = pos < available
        if (top.size > 1) {
            RoundButton(Icons.Rounded.Close, "None of these", RejectRed, 64, "duo-reject") { if (enabled) record(pos, Choice(Choice.Vote.REJECT)) }
            RoundButton(Icons.Rounded.QuestionMark, "Not sure", SkipBlue, 48, "duo-maybe") { if (enabled) record(pos, Choice(Choice.Vote.MAYBE)) }
            RoundButton(Icons.Rounded.Check, "Keep this one", PickGreen, 64, "duo-pick") {
                if (enabled) record(pos, Choice(Choice.Vote.PICK, chosen.coerceIn(top.indices)))
            }
        } else {
            RoundButton(Icons.Rounded.Close, "Nope", RejectRed, 64, "duo-reject") { if (enabled && fling == null) fling = Verdict.REJECT }
            RoundButton(Icons.Rounded.QuestionMark, "Not sure", SkipBlue, 48, "duo-maybe") { if (enabled && fling == null) fling = Verdict.SKIP }
            RoundButton(Icons.Rounded.Check, "Keep", PickGreen, 64, "duo-pick") { if (enabled && fling == null) fling = Verdict.PICK }
        }
    }
}

private fun Verdict.toChoice() = when (this) {
    Verdict.PICK -> Choice(Choice.Vote.PICK)
    Verdict.REJECT -> Choice(Choice.Vote.REJECT)
    Verdict.SKIP -> Choice(Choice.Vote.MAYBE)
}

/** "It's a match!" with a heart that pops, every time both kept the same shot. */
@Composable
private fun MatchBurst() {
    val haptics = LocalHapticFeedback.current
    val first = remember { Duo.matchTick }
    var visible by remember { mutableStateOf(false) }
    val tick = Duo.matchTick
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
                Text("You both kept this one", color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

// ---- Reveal and Debate -------------------------------------------------------------------------

@Composable
private fun Reveal(onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val tick = Duo.tick
    val s = Duo.session
    val talk = s.disputes.size
    val shown = remember { Animatable(0f) }
    LaunchedEffect(s.score) { shown.animateTo(s.score.toFloat(), tween(1600)) }
    TopBar("The reveal", onClose = onClose)
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("${shown.value.toInt()}%", color = Color.White, fontSize = 88.sp, fontWeight = FontWeight.Black)
        Text("in sync", color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                s.score >= 90 -> "Soulmates"
                s.score >= 70 -> "Great minds think alike"
                s.score >= 50 -> "Mostly on the same page"
                else -> "Opposites attract"
            },
            color = Gold, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(36.dp))
        StatRow(Icons.Rounded.Favorite, Pink, "${s.matches}", "you both love")
        StatRow(Icons.Rounded.Close, RejectRed, "${s.bothOut}", "you both threw out")
        StatRow(Icons.Rounded.QuestionMark, SkipBlue, "$talk", "to talk about")
        Spacer(Modifier.height(36.dp))
        Button(onClick = { Duo.next() }) { Text(if (talk > 0) "Talk about $talk" else "Finish") }
    }
}

@Composable
private fun StatRow(icon: ImageVector, color: Color, n: String, label: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Text(n, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 17.sp)
    }
}

@Composable
private fun ColumnScope.Debate(onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val tick = Duo.tick
    val item = Duo.debateItem ?: return
    val s = Duo.session
    val stack = Duo.deck.getOrNull(item) ?: return
    val disputes = s.disputes
    val mine = s.voteOf(Duo.me, item)
    val theirs = s.voteOf(Duo.partner, item)
    // The shots worth showing: the ones either of you kept, or the whole stack if neither did.
    val kept = listOfNotNull(mine, theirs).filter { it.vote == Choice.Vote.PICK }.map { it.keep }.distinct()
    val candidates = kept.ifEmpty { stack.indices.toList() }
    var shown by remember(item) { mutableIntStateOf(0) }
    val idx = candidates[shown.coerceIn(candidates.indices)]

    TopBar("Let's talk", "${disputes.indexOf(item) + 1} of ${disputes.size}", onClose = onClose)
    Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1E))) {
        key(item, idx) { ShotImage(stack[idx], Modifier.fillMaxSize()) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VoteChip(Duo.myName, MeColor, mine, idx, stack.size)
        VoteChip(Duo.partnerName, PartnerColor, theirs, idx, stack.size)
    }
    if (candidates.size > 1) {
        LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(candidates) { k, j ->
                Thumb(
                    stack[j].primary,
                    Modifier.size(64.dp).border(2.dp, if (k == shown) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
                        .clickable { shown = k },
                )
            }
        }
    }
    // What each of us wants done with it now; the card moves on once both say the same.
    val myCall = s.proposalOf(Duo.me, item)
    val theirCall = s.proposalOf(Duo.partner, item)
    Text(
        when {
            s.isStuck(item) -> "${Duo.partnerName} says ${callLabel(theirCall!!, stack.size)}. Agree, or let a coin decide"
            theirCall != null -> "${Duo.partnerName} says ${callLabel(theirCall, stack.size)}"
            myCall != null -> "Waiting for ${Duo.partnerName}"
            else -> "Talk it over, then both tap the same call"
        },
        color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        textAlign = TextAlign.Center,
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val out = Choice(Choice.Vote.REJECT)
        val later = Choice(Choice.Vote.MAYBE)
        val keep = Choice(Choice.Vote.PICK, idx)
        CallButton(myCall == out, theirCall == out) {
            RoundButton(Icons.Rounded.Close, "Out", RejectRed, 64, "debate-out") { Duo.propose(item, out) }
        }
        CallButton(myCall == later, theirCall == later) {
            RoundButton(Icons.Rounded.QuestionMark, "Decide later", SkipBlue, 48, "debate-later") { Duo.propose(item, later) }
        }
        CallButton(myCall == keep, theirCall == keep) {
            RoundButton(Icons.Rounded.Check, "Keep", PickGreen, 64, "debate-keep") { Duo.propose(item, keep) }
        }
    }
    if (s.isStuck(item)) {
        OutlinedButton(onClick = { Duo.flipCoin(item) }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) {
            Text("Flip a coin")
        }
    }
}

private fun callLabel(c: Choice, stackSize: Int) = when (c.vote) {
    Choice.Vote.REJECT -> "out"
    Choice.Vote.MAYBE -> "decide later"
    Choice.Vote.PICK -> if (stackSize > 1) "keep photo ${c.keep + 1}" else "keep"
}

/** A Debate button with who picked it: a white ring for me, a dot with their initial for them. */
@Composable
private fun CallButton(mine: Boolean, theirs: Boolean, button: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.border(3.dp, if (mine) Color.White else Color.Transparent, CircleShape).padding(5.dp)) { button() }
        if (theirs) Box(Modifier.align(Alignment.TopEnd)) { Avatar(Duo.partnerName, PartnerColor, 22) }
    }
}

/** "Heads!": which call the coin picked, on both phones. */
@Composable
private fun CoinResult() {
    val (winner, call) = Duo.coin ?: return
    val tick = Duo.coinTick
    LaunchedEffect(tick) { delay(2800); if (Duo.coinTick == tick) Duo.coin = null }
    val spin = remember(tick) { Animatable(0f) }
    LaunchedEffect(tick) { spin.animateTo(1f, tween(900)) }
    val name = if (winner == Duo.me) "Your" else "${Duo.partnerName}'s"
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable { Duo.coin = null }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(96.dp).graphicsLayer { rotationY = spin.value * 1080f }.clip(CircleShape).background(Gold),
                contentAlignment = Alignment.Center,
            ) { Text("?", color = Color.Black, fontSize = 44.sp, fontWeight = FontWeight.Black) }
            Spacer(Modifier.height(20.dp))
            if (spin.value >= 1f) {
                Text("$name call wins", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(callLabel(call, 1).replaceFirstChar { it.uppercase() }, color = Gold, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun VoteChip(name: String, color: Color, vote: Choice?, shown: Int, stackSize: Int) {
    val (text, tint) = when {
        vote == null -> "—" to Color.White
        vote.vote == Choice.Vote.REJECT -> "out" to RejectRed
        vote.vote == Choice.Vote.MAYBE -> "not sure" to SkipBlue
        stackSize == 1 || vote.keep == shown -> "keep" to PickGreen
        else -> "kept another" to Color.White.copy(alpha = 0.6f)
    }
    Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, color, 20)
            Spacer(Modifier.width(6.dp))
            Text(name, color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- Done -----------------------------------------------------------------------------------------

@Composable
private fun Done(onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val tick = Duo.tick
    val s = Duo.session
    val kept = (0 until s.size).count { s.outcome(it)?.vote == Choice.Vote.PICK }
    val out = (0 until s.size).count { s.outcome(it)?.vote == Choice.Vote.REJECT }
    val (sent, total) = Duo.keepers
    Box(Modifier.fillMaxSize()) {
        Confetti()
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("All sorted!", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text("$kept kept · $out out · ${s.score}% in sync", color = Color.White.copy(alpha = 0.75f))
            Spacer(Modifier.height(24.dp))
            val offer = Duo.offer
            val want = Duo.want
            when {
                total > 0 -> {
                    Text(
                        when {
                            sent >= total && Duo.hosting -> "${Duo.partnerName} has the photos"
                            sent >= total -> "The photos are in your Photos"
                            Duo.hosting -> "Sending ${wantLabel(want)} to ${Duo.partnerName}: $sent of $total"
                            else -> "Getting your photos: $sent of $total"
                        },
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { sent / total.toFloat() }, modifier = Modifier.fillMaxWidth(0.7f))
                    Spacer(Modifier.height(32.dp))
                }
                offer == null || offer.both.count + offer.mine.count == 0 -> {}
                want != null && want.format == null -> {
                    Text(if (Duo.hosting) "${Duo.partnerName} passed on the photos" else "No photos this time", color = Color.White.copy(alpha = 0.6f))
                    Spacer(Modifier.height(32.dp))
                }
                Duo.hosting && want == null -> {
                    Text("${Duo.partnerName} is choosing which photos to take", color = Color.White.copy(alpha = 0.6f))
                    Spacer(Modifier.height(32.dp))
                }
                !Duo.hosting && want == null && offer != null -> {
                    WantPhotos(offer)
                    Spacer(Modifier.height(24.dp))
                }
            }
            if (Duo.hosting) {
                Button(
                    onClick = { Duo.pickBattle() },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black),
                ) {
                    Icon(Icons.Rounded.Brush, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start an edit battle", fontWeight = FontWeight.Bold)
                }
            } else {
                Text("${Duo.partnerName} can start an edit battle", color = Color.White.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onClose) { Text("Finish") }
        }
    }
}

private fun wantLabel(w: Duo.Want?) = when (w?.format) {
    Duo.Format.RAW -> "the RAWs"
    Duo.Format.BOTH -> "RAWs and JPEGs"
    else -> "the JPEGs"
}

/** Guest, at the end: whether to take the photos home, which ones, and in what format. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WantPhotos(offer: Duo.Offer) {
    val last = remember { Duo.lastWant() }
    var format by remember { mutableStateOf(last.format ?: Duo.Format.JPEG) }
    var alsoMine by remember { mutableStateOf(last.alsoMine && offer.mine.count > 0) }
    val bundle = if (alsoMine) offer.both + offer.mine else offer.both
    Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Want the photos?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "${offer.both.count} you both kept" + if (alsoMine) " + ${offer.mine.count} only you kept" else "",
                color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
            )
            if (offer.mine.count > 0) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Also the ${offer.mine.count} only I kept", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = alsoMine, onCheckedChange = { alsoMine = it })
                }
            }
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(Duo.Format.JPEG to "JPEG", Duo.Format.RAW to "RAW", Duo.Format.BOTH to "Both")
                options.forEachIndexed { i, (f, label) ->
                    SegmentedButton(
                        selected = format == f,
                        onClick = { format = f },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("About ${megabytes(bundle.bytes(format))}", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { Duo.choose(Duo.Want(null, alsoMine)) }) { Text("No thanks") }
                Button(onClick = { Duo.choose(Duo.Want(format, alsoMine)) }, enabled = bundle.count > 0) { Text("Get ${bundle.count} photos") }
            }
        }
    }
}

private fun megabytes(bytes: Long): String =
    if (bytes >= 1_000_000_000) String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9) else "${(bytes / 1_000_000).coerceAtLeast(1)} MB"

/** Battle only: after the result. Same photo again, or done. */
@Composable
private fun BattleOver(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Brush, null, tint = Gold, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text("Good battle!", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(32.dp))
        if (Duo.hosting) {
            Button(
                onClick = { Duo.rematch() },
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black),
            ) { Text("Rematch", fontWeight = FontWeight.Bold) }
        } else {
            Text("${Duo.partnerName} can start a rematch", color = Color.White.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onClose) { Text("Finish") }
    }
}

@Composable
private fun Confetti() {
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

// ---- Edit battle ------------------------------------------------------------------------------------

@Composable
private fun BattlePick(onClose: () -> Unit) {
    if (!Duo.hosting) {
        TopBar("Edit battle", onClose = onClose)
        Centered { Spinner("${Duo.partnerName} is picking a photo to fight over…") }
        return
    }
    val s = Duo.session
    // Keepers first: those are the ones worth an edit.
    val shots = Duo.deck.flatMapIndexed { i, stack ->
        val o = s.outcome(i)
        stack.mapIndexed { j, shot -> Triple(shot, o?.vote == Choice.Vote.PICK && o.keep == j, i) }
    }.sortedByDescending { it.second }.map { it.first }
    TopBar("Edit battle", "Pick a photo. You both edit it, then vote blind.", onClose = onClose) {
        TextButton(onClick = { Duo.next() }) { Text("Back") }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(shots, key = { it.key }) { shot ->
            Thumb(shot.jpeg ?: shot.primary, Modifier.aspectRatio(1f).clickable { Duo.startBattle(shot) })
        }
    }
}

@Composable
private fun BattleEdit(onClose: () -> Unit) {
    val photo = Duo.battlePhoto
    var started by remember(photo) { mutableStateOf(false) }
    when {
        photo == null -> {
            TopBar("Edit battle", onClose = onClose)
            Centered { Spinner("Getting the photo from ${Duo.partnerName}…", Duo.transfer?.second) }
        }
        Duo.battleEdits[Duo.me] != null -> {
            TopBar("Edit battle", onClose = onClose)
            Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Brush, null, tint = Gold, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Your edit is locked in", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (Duo.partnerEdited) "${Duo.partnerName} is done too…" else "${Duo.partnerName} is still editing…",
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
        !started -> {
            TopBar("Edit battle", onClose = onClose)
            Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Brush, null, tint = Gold, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Make it yours", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Edit ${photo.stem} any way you like. ${Duo.partnerName} can't see your edit until you're both done. Tap ← when you're happy with it.",
                        color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { started = true }) { Text("Start editing") }
                }
            }
        }
        else -> {
            val store = remember { Duo.battleStore() }
            BackHandler { Duo.finishEdit() }
            EditorScreen(
                photos = listOf(photo),
                startIndex = 0,
                store = store,
                onBack = { Duo.finishEdit() },
                onPhotoChanged = {},
            )
        }
    }
}

/**
 * Both edits rendered on this phone (the RAW and both settings are here), kept for vote and result.
 * [images] fill in as each edit is known; [error] when the photo couldn't be opened or rendered.
 */
private class BattleRenders(val opened: OpenedPhoto?, val images: List<ImageBitmap?>, val error: String?, val retry: () -> Unit) {
    val ready get() = opened != null && images.all { it != null }
    val done get() = images.count { it != null }
}

/**
 * Opens the RAW as soon as my own edit is locked in (the editor has let go of its copy by then) and
 * renders each edit the moment it's known, from the opened preview: no second demosaic of the
 * full RAW, so the vote screen is there right after the second edit arrives.
 */
@Composable
private fun rememberBattleRenders(): BattleRenders? {
    val context = LocalContext.current
    val photo = Duo.battlePhoto ?: return null
    val edits = Duo.battleEdits
    val myEditDone = edits[Duo.me] != null
    var attempt by remember(photo) { mutableIntStateOf(0) }
    var error by remember(photo) { mutableStateOf<String?>(null) }
    val opened by produceState<OpenedPhoto?>(null, photo, myEditDone, attempt) {
        value = null
        if (!myEditDone) return@produceState
        error = null
        value = withContext(Dispatchers.IO) {
            runCatching { Photos.open(context, photo) }
                .onFailure { error = "Couldn't open ${photo.name}: ${it.message ?: it.javaClass.simpleName}" }
                .getOrNull()
        }
    }
    // Rendered edits by their settings, so a repeated state message doesn't render them again.
    val rendered = remember(photo) { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(opened, edits, attempt) {
        val o = opened ?: return@LaunchedEffect
        for (owner in listOf(Duo.me, Duo.partner)) {
            val text = edits[owner] ?: continue
            if (text in rendered) continue
            val img = withContext(Dispatchers.Default) {
                runCatching { Photos.renderPreview(o, Settings.decode(text), 1600).asImageBitmap() }
                    .onFailure { error = "Couldn't develop an edit: ${it.message ?: it.javaClass.simpleName}" }
                    .getOrNull()
            } ?: return@LaunchedEffect
            rendered[text] = img
        }
    }
    return BattleRenders(
        opened,
        listOf(DuoSession.HOST, DuoSession.GUEST).map { owner -> edits[owner]?.let { rendered[it] } },
        error,
        retry = { attempt++ },
    )
}

@Composable
private fun BattleVote(renders: BattleRenders?, onClose: () -> Unit) {
    TopBar("Which one wins?", "Names are hidden until you've both voted", onClose = onClose)
    if (renders == null || !renders.ready) {
        Centered {
            val err = renders?.error
            if (err != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(err, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = renders.retry) { Text("Try again") }
                }
            } else {
                Spinner("Developing both edits… ${renders?.done ?: 0} of 2")
            }
        }
        return
    }
    val order = if (Duo.hostFirst) listOf(DuoSession.HOST, DuoSession.GUEST) else listOf(DuoSession.GUEST, DuoSession.HOST)
    val myVote = Duo.battleVotes[Duo.me]
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        order.forEachIndexed { k, owner ->
            val picked = myVote == owner
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .border(3.dp, if (picked) Gold else Color.Transparent, RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1C1E))
                    .clickable(enabled = myVote == null) { Duo.battleVote(owner) },
            ) {
                Image(renders.images[owner]!!, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Text(
                    if (k == 0) "A" else "B",
                    color = Color.Black, fontWeight = FontWeight.Black, fontSize = 20.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.White, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            if (myVote == null) "Tap the one you like more" else "Waiting for ${Duo.partnerName}'s vote…",
            color = Color.White.copy(alpha = 0.75f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BattleResult(renders: BattleRenders?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val winner = Duo.winner ?: return
    val name = { owner: Int -> if (owner == Duo.me) Duo.myName else Duo.partnerName }
    var revealed by remember(winner) { mutableStateOf(!Duo.flipped) }
    val spin = remember(winner) { Animatable(0f) }
    LaunchedEffect(winner) {
        if (Duo.flipped) {
            spin.animateTo(1800f, tween(2200))
            revealed = true
        }
    }
    var saving by remember { mutableStateOf(false) }

    TopBar("Edit battle", onClose = onClose)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!revealed) {
            Text("Split vote!", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("Let the coin decide…", color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(32.dp))
            Box(
                Modifier.size(120.dp).graphicsLayer { rotationY = spin.value; cameraDistance = 12 * density }
                    .clip(CircleShape).background(Gold),
                contentAlignment = Alignment.Center,
            ) {
                Text(if ((spin.value / 180).toInt() % 2 == 0) name(DuoSession.HOST).take(1) else name(DuoSession.GUEST).take(1), fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color.Black)
            }
            return@Column
        }
        Icon(Icons.Rounded.EmojiEvents, null, tint = Gold, modifier = Modifier.size(64.dp).scale(1.1f))
        Text("${name(winner)}'s edit wins!", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        if (Duo.flipped) Text("The coin has spoken", color = Color.White.copy(alpha = 0.6f))
        else if (Duo.battleVotes.all { it == winner }) Text("You both voted for it", color = Color.White.copy(alpha = 0.6f))
        Spacer(Modifier.height(16.dp))
        if (renders != null && renders.ready) {
            Image(
                renders.images[winner]!!, null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(3.dp, Gold, RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (owner in listOf(DuoSession.HOST, DuoSession.GUEST)) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(renders.images[owner]!!, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
                        Text("${name(owner)}'s", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) {
                            runCatching { Photos.export(context, renders.opened!!, Duo.battleSettings(winner)!!, ExportOptions(), {}) }.isSuccess
                        }
                        Duo.notice = if (ok) "Saved to your phone's pictures" else "Couldn't save the picture"
                        saving = false
                    }
                },
            ) { Text(if (saving) "Saving…" else "Save the winner to my phone") }
        } else {
            Spinner("Developing…")
        }
        if (Duo.hosting) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { Duo.useWinner() }) { Text("Use it as the photo's edit") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { Duo.next() }) { Text("Done with the battle") }
        }
    }
}

// ---- The end -------------------------------------------------------------------------------------

@Composable
private fun Ended(onExit: () -> Unit) {
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${Duo.partnerName} left", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onExit) { Text("Close") }
        }
    }
}
