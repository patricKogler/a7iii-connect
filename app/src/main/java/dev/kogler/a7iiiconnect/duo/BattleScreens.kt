package dev.kogler.a7iiiconnect.duo

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kogler.a7iiiconnect.develop.EditStore
import dev.kogler.a7iiiconnect.develop.EditorScreen
import dev.kogler.a7iiiconnect.develop.OpenedPhoto
import dev.kogler.a7iiiconnect.develop.Photos
import dev.kogler.a7iiiconnect.develop.Shot
import dev.kogler.a7iiiconnect.develop.engine.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.random.Random

private val Mode.icon: ImageVector
    get() = when (this) {
        Mode.CLASSIC -> Icons.Rounded.Brush
        Mode.THEME -> Icons.Rounded.Style
        Mode.LIMITED -> Icons.Rounded.Tune
        Mode.SPEED -> Icons.Rounded.Timer
        Mode.COPYCAT -> Icons.Rounded.ContentCopy
        Mode.RELAY -> Icons.Rounded.Groups
        Mode.BEST_OF_3 -> Icons.Rounded.EmojiEvents
    }

/** The editor's names for its panels, for the twist chips. */
private fun toolLabel(name: String) = when (name) {
    "LIGHT" -> "Light"; "COLOR" -> "Color"; "EFFECTS" -> "Effects"; "DETAIL" -> "Detail"
    "CROP" -> "Crop"; "MASKS" -> "Masks"; "OPTICS" -> "Optics"; "GEOMETRY" -> "Geometry"
    else -> name.lowercase().replaceFirstChar { it.uppercase() }
}

private fun toolsLabel(tools: List<String>) = tools.map { toolLabel(it) }.let {
    if (it.size <= 1) it.firstOrNull().orEmpty() else it.dropLast(1).joinToString(", ") + " and " + it.last()
}

// ---- Starting a battle ----------------------------------------------------------------------------

/** Pick a mode (and extra twists), then the photo, or three for Best of 3. [preset] is picked to start with. */
@Composable
internal fun ColumnScope.NewBattle(room: Room, preset: String?, onBack: () -> Unit, onStarted: (String) -> Unit) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf<Mode?>(null) }
    var theme by rememberSaveable { mutableStateOf(false) }
    var timer by rememberSaveable { mutableStateOf(false) }
    var limited by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf(listOfNotNull(preset)) }
    val shots = room.album.keys.mapNotNull { room.shots[it] }
    val m = mode
    if (m == null) {
        TopBar("New edit battle", "with ${room.othersName}", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            for (each in Mode.entries) {
                Surface(
                    color = Panel, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable {
                        mode = each
                        theme = each == Mode.THEME; timer = each == Mode.SPEED; limited = each == Mode.LIMITED
                    }.testTag("mode-${each.name}"),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Gold.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Icon(each.icon, null, tint = Gold)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(each.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(each.blurb, color = Dim, fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        return
    }
    val need = if (m == Mode.BEST_OF_3) 3 else 1
    TopBar(m.title, if (need == 1) "Pick the photo" else "Pick up to three photos", onBack = { mode = null })
    if (m.isDuel && m != Mode.BEST_OF_3) {
        // Twists stack: a theme card on a speed round, limited tools with a theme…
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(theme, { theme = !theme }, label = { Text("Theme card") }, leadingIcon = { Icon(Icons.Rounded.Style, null, Modifier.size(16.dp)) })
            FilterChip(timer, { timer = !timer }, label = { Text("1 minute") }, leadingIcon = { Icon(Icons.Rounded.Timer, null, Modifier.size(16.dp)) })
            FilterChip(limited, { limited = !limited }, label = { Text("2 tools") }, leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp)) })
        }
    }
    Column(Modifier.weight(1f)) {
        ShotGrid(shots, picked, onTap = { s ->
            picked = when {
                s.key in picked -> picked - s.key
                need == 1 -> listOf(s.key)
                picked.size < need -> picked + s.key
                else -> picked
            }
        }, modifier = Modifier.fillMaxSize())
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (need > 1) {
            TextButton(onClick = { picked = shots.shuffled().take(need).map { it.key } }) { Text("Surprise me") }
        }
        Spacer(Modifier.weight(1f))
        GoldButton("Start", enabled = picked.isNotEmpty(), modifier = Modifier.testTag("start-battle")) {
            val r = Random.Default
            val extra = Twist(
                theme = if (theme && m != Mode.THEME) BattleRules.THEMES.random(r) else null,
                tools = if (limited && m != Mode.LIMITED) BattleRules.TOOL_POOL.shuffled(r).take(2) else emptyList(),
                seconds = if (timer && m != Mode.SPEED) BattleRules.SPEED_SECONDS else 0,
            )
            val base = when {
                m == Mode.THEME && !theme -> Mode.CLASSIC
                m == Mode.SPEED && !timer -> Mode.CLASSIC
                m == Mode.LIMITED && !limited -> Mode.CLASSIC
                else -> m
            }
            val id = UUID.randomUUID().toString().take(12)
            PlayStore.add(context, BattleRules.start(room.album.id, room.me, id, base, picked, System.currentTimeMillis(), r, extra))
            onStarted(id)
        }
    }
}

// ---- A battle -------------------------------------------------------------------------------------

@Composable
internal fun ColumnScope.BattleScreen(room: Room, id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val b = room.battle(id)
    if (b == null) {
        TopBar("Edit battle", onBack = onBack)
        Message("This battle hasn't come over yet", "Touch phones with ${room.othersName} to get it.")
        return
    }
    var menu by remember { mutableStateOf(false) }
    var calling by remember { mutableStateOf(false) }
    val actions: @Composable () -> Unit = {
        if (!b.done && b.droppedBy == null) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Color.White) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Call it off") }, onClick = { menu = false; calling = true })
            }
        }
    }
    if (calling) {
        AlertDialog(
            onDismissRequest = { calling = false },
            title = { Text("Call off this battle?") },
            text = { Text("It ends for ${room.othersName} too.") },
            confirmButton = { TextButton(onClick = { calling = false; PlayStore.add(context, b.dropFact(room.me, System.currentTimeMillis())); onBack() }) { Text("Call it off") } },
            dismissButton = { TextButton(onClick = { calling = false }) { Text("Keep playing") } },
        )
    }
    when {
        b.droppedBy != null -> {
            TopBar(b.mode.title, onBack = onBack)
            Message("Called off", "${room.name(b.droppedBy)} called this battle off.")
        }
        b.mode == Mode.COPYCAT -> CopycatFlow(room, b, onBack, actions)
        b.mode == Mode.RELAY -> RelayFlow(room, b, onBack, actions)
        else -> DuelFlow(room, b, onBack, actions)
    }
}

/** Round results already looked at, so coming back doesn't show them again. */
private fun seen(context: Context) = context.getSharedPreferences("battles_seen", Context.MODE_PRIVATE)

@Composable
private fun ColumnScope.DuelFlow(room: Room, b: TogetherBattle, onBack: () -> Unit, actions: @Composable () -> Unit) {
    val context = LocalContext.current
    var bump by remember { mutableStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") bump
    val prefs = seen(context)
    val me = room.me
    val subtitle = if (b.rounds.size > 1) b.players.joinToString(" – ") { "${room.name(it)} ${b.wins(it)}" } else "with ${room.othersName}"
    // A finished round (before the last) that I haven't seen the result of yet.
    val unseen = if (b.done) null else b.rounds.firstOrNull { b.stage(it.index) == Stage.DONE && !prefs.getBoolean("${b.id}/${it.index}", false) }
    when {
        unseen != null -> {
            TopBar(b.mode.title, "Round ${unseen.index + 1} of ${b.rounds.size}", onBack = onBack, actions = actions)
            DuelResult(room, b, unseen, last = false) {
                prefs.edit().putBoolean("${b.id}/${unseen.index}", true).apply()
                bump++
            }
        }
        b.done -> {
            TopBar(b.mode.title, subtitle, onBack = onBack)
            val final = b.rounds.last { b.stage(it.index) == Stage.DONE }
            DuelResult(room, b, final, last = true, onNext = onBack)
        }
        else -> {
            val round = b.current ?: return
            val r = round.index
            val shot = room.shots[round.key]
            TopBar(b.mode.title, if (b.rounds.size > 1) "Round ${r + 1} of ${b.rounds.size} · $subtitle" else subtitle, onBack = onBack, actions = actions)
            when {
                shot == null -> Message("The photo is still on its way", "Touch phones with ${room.othersName} to get it.")
                b.edit(me, r) == null -> EditTurn(
                    room, b, "r$r", shot, Settings(), round.twist,
                    intro = (round.twist.theme?.let { "Make it feel like “$it”." } ?: "Edit it any way you like.") +
                        " ${room.othersName} can't see your edit until you've all voted.",
                    started = b.started(me, r),
                    onStart = { PlayStore.add(context, b.startFact(me, r, System.currentTimeMillis())) },
                    onLock = { s -> PlayStore.add(context, b.editFact(me, r, pack(s), System.currentTimeMillis())) },
                    onLeave = onBack,
                )
                b.stage(r) == Stage.EDIT -> Waiting("Your edit is locked in", "${room.othersName} ${if (room.others.size == 1) "is" else "are"} still editing. You can leave: it all goes over next time you meet.", Icons.Rounded.Lock, onBack)
                b.pick(me, r) == null -> Vote(room, b, round, shot)
                else -> Waiting("Your vote is in", "Waiting for ${room.othersName} to vote.", Icons.Rounded.Lock, onBack)
            }
        }
    }
}

private fun pack(s: Settings) = Facts.packSettings(s.encode())
private fun unpack(v: String?): Settings? = v?.let { Settings.decode(Facts.unpackSettings(it)) }

/** Before editing: the photo and its twist, then Start. Then the editor, until it's locked in. */
@Composable
private fun EditTurn(
    room: Room, b: TogetherBattle, slot: String, shot: Shot, start: Settings, twist: Twist, intro: String,
    started: Long?, onStart: () -> Unit, onLock: (Settings) -> Unit, onLeave: () -> Unit,
    target: Settings? = null,
) {
    var editing by rememberSaveable(slot) { mutableStateOf(started != null && twist.seconds > 0) }
    if (!editing) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ShotImage(shot, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp)), edge = 1200)
            Spacer(Modifier.height(16.dp))
            TwistCards(twist)
            Spacer(Modifier.height(12.dp))
            Text(intro, color = Dim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            GoldButton(if (twist.seconds > 0) "Start the clock" else "Start editing", modifier = Modifier.testTag("start-editing")) {
                if (started == null) onStart()
                editing = true
            }
        }
        return
    }
    BattleEditor(room, b, slot, shot, start, twist, started, target, onLock, onLeave)
}

/** The twist of a round, big: the theme card, the tools, the clock. */
@Composable
private fun TwistCards(twist: Twist) {
    if (twist.theme != null) {
        Surface(color = Gold, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("theme-card")) {
            Column(Modifier.padding(vertical = 18.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("THEME", color = Color.Black.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(twist.theme, color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (twist.tools.isNotEmpty()) TwistChip(Icons.Rounded.Tune, "Only ${toolsLabel(twist.tools)}")
        if (twist.seconds > 0) TwistChip(Icons.Rounded.Timer, "${twist.seconds} seconds")
    }
}

@Composable
private fun TwistChip(icon: ImageVector, text: String) {
    Surface(color = Panel, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Gold, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = Color.White, fontSize = 13.sp)
        }
    }
}

/**
 * The editor for a battle: only the twist's tools, its theme and clock above the photo (and for
 * Copycat a peek at the look to copy). Leaving asks whether to lock the edit in.
 */
@Composable
private fun BattleEditor(
    room: Room, b: TogetherBattle, slot: String, shot: Shot, start: Settings, twist: Twist, started: Long?,
    target: Settings?, onLock: (Settings) -> Unit, onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val photo = shot.primary
    val store = remember(b.id, slot) {
        EditStore(context, "battles/${b.id}/$slot").also { s -> if (s.load(photo).isDefault && !start.isDefault) s.save(photo, start) }
    }
    var asking by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    fun lock() {
        if (locked) return
        locked = true
        onLock(store.load(photo))
    }
    BackHandler { asking = true }

    // The clock runs from when editing started, even across leaving and coming back.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val left = if (twist.seconds > 0) ((started ?: now) + twist.seconds * 1000L - now).coerceAtLeast(0) else null
    if (left != null) LaunchedEffect(started) {
        while (true) {
            now = System.currentTimeMillis()
            if ((started ?: now) + twist.seconds * 1000L <= now) {
                // Time's up: what's saved so far is the edit (give the editor a moment to save the last drag).
                delay(300)
                lock()
                break
            }
            delay(200)
        }
    }
    var peek by remember { mutableStateOf(false) }
    val targetImage = target?.let { rememberRenders(shot, listOf(it), 900).firstOrNull() }

    EditorScreen(
        photos = listOf(photo),
        startIndex = 0,
        store = store,
        onBack = { asking = true },
        onPhotoChanged = {},
        tools = twist.tools.toSet().takeIf { it.isNotEmpty() },
        header = {
            Row(Modifier.fillMaxWidth().background(Color(0xFF101012)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (twist.theme != null) {
                    Surface(color = Gold, shape = RoundedCornerShape(8.dp)) {
                        Text(twist.theme, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (twist.tools.isNotEmpty()) Text("Only ${toolsLabel(twist.tools)}", color = Dim, fontSize = 13.sp)
                if (target != null) {
                    TextButton(onClick = { peek = !peek }) {
                        Icon(if (peek) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (peek) "Hide the look" else "Peek at the look")
                    }
                }
                Spacer(Modifier.weight(1f))
                if (left != null) {
                    val s = (left + 999) / 1000
                    Text(
                        "%d:%02d".format(s / 60, s % 60),
                        color = if (s <= 10) Color(0xFFFF5A5F) else Color.White,
                        fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.testTag("battle-clock"),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = { asking = true }) { Text("Lock in", fontWeight = FontWeight.Bold) }
            }
            if (peek && target != null) {
                Box(Modifier.fillMaxWidth().height(180.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                    if (targetImage != null) Image(targetImage, "The look to copy", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    else Spinner("")
                }
            }
        },
    )
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Lock in your edit?") },
            text = { Text("You can't change it after that.") },
            confirmButton = { TextButton(onClick = { asking = false; lock() }) { Text("Lock in") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { asking = false; onLeave() }) { Text(if (left != null) "Leave (the clock keeps going)" else "Leave for now") }
                    TextButton(onClick = { asking = false }) { Text("Keep editing") }
                }
            },
        )
    }
}

@Composable
private fun Waiting(title: String, detail: String, icon: ImageVector, onBack: () -> Unit) {
    Message(title, detail, icon = { Icon(icon, null, tint = Gold, modifier = Modifier.size(56.dp)) }, below = { Button(onClick = onBack) { Text("Back to the album") } })
}

/**
 * Renders of [settings] on [shot], made on this phone: the photo is opened once and each edit
 * developed from its preview. Null entries are still on their way.
 */
@Composable
internal fun rememberRenders(shot: Shot, settings: List<Settings?>, edge: Int = 1600): List<ImageBitmap?> {
    val context = LocalContext.current
    val opened by produceState<OpenedPhoto?>(null, shot.key) {
        value = withContext(Dispatchers.IO) { runCatching { Photos.open(context, shot.primary) }.getOrNull() }
    }
    val texts = settings.map { it?.encode() }
    val images by produceState(List<ImageBitmap?>(settings.size) { null }, opened, texts) {
        val o = opened ?: return@produceState
        val out = MutableList<ImageBitmap?>(settings.size) { null }
        for ((i, s) in settings.withIndex()) {
            if (s == null) continue
            out[i] = withContext(Dispatchers.Default) { runCatching { Photos.renderPreview(o, s, edge).asImageBitmap() }.getOrNull() }
            value = out.toList()
        }
    }
    return images
}

/** Every edit side by side, without names, in an order of this phone's own: tap the better one. */
@Composable
private fun ColumnScope.Vote(room: Room, b: TogetherBattle, round: BattleRound, shot: Shot) {
    val context = LocalContext.current
    val r = round.index
    val order = remember(b.id, r) { b.players.shuffled() }
    val images = rememberRenders(shot, order.map { unpack(b.edit(it, r)) })
    if (round.twist.theme != null) {
        Text("Which one is more “${round.twist.theme}”?", color = Gold, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center)
    } else {
        Text("Which one wins? Names come after the vote.", color = Dim, modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center)
    }
    Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        order.forEachIndexed { k, owner ->
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel)
                    .clickable(enabled = images[k] != null) { PlayStore.add(context, b.pickFact(room.me, r, owner, System.currentTimeMillis())) }
                    .testTag("vote-$k"),
                contentAlignment = Alignment.Center,
            ) {
                val img = images[k]
                if (img != null) Image(img, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) else Spinner("Developing…")
                Text(
                    "${'A' + k}",
                    color = Color.Black, fontWeight = FontWeight.Black, fontSize = 20.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.White, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * A round's result: a coin first if the vote split, then the winner big, and everyone's edit with
 * their votes. The [last] round's (the battle's) ends with the seal.
 */
@Composable
private fun DuelResult(room: Room, b: TogetherBattle, round: BattleRound, last: Boolean, onNext: () -> Unit) {
    val r = round.index
    val winner = b.winner(r) ?: return
    val shot = room.shots[round.key]
    val edits = b.players.map { unpack(b.edit(it, r)) }
    val images = shot?.let { rememberRenders(it, edits) } ?: List(edits.size) { null }
    var revealed by rememberSaveable(b.id, r) { mutableStateOf(!b.flipped(r) || b.sealedBy(room.me)) }
    val spin = remember { Animatable(0f) }
    LaunchedEffect(revealed) {
        if (!revealed) {
            spin.animateTo(1800f, tween(2200))
            revealed = true
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!revealed) {
            Text("Split vote!", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("Let the coin decide…", color = Dim)
            Spacer(Modifier.height(32.dp))
            Box(
                Modifier.size(120.dp).graphicsLayer { rotationY = spin.value; cameraDistance = 12 * density }.clip(CircleShape).background(Gold),
                contentAlignment = Alignment.Center,
            ) {
                val face = b.players[((spin.value / 180).toInt()) % b.players.size]
                Text(room.name(face).take(1), fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color.Black)
            }
            return@Column
        }
        val champion = if (last) b.champion ?: winner else winner
        Icon(Icons.Rounded.EmojiEvents, null, tint = Gold, modifier = Modifier.size(56.dp))
        Text(
            if (last && b.rounds.size > 1) "${room.name(champion)} wins the battle!" else "${if (champion == room.me) "Your" else room.name(champion) + "'s"} edit wins!",
            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
        )
        Text(
            when {
                last && b.rounds.size > 1 -> b.players.joinToString(" – ") { "${room.name(it)} ${b.wins(it)}" }
                b.flipped(r) -> "The coin has spoken"
                b.players.all { b.pick(it, r) == winner } -> "Everyone voted for it"
                else -> "By ${b.players.count { b.pick(it, r) == winner }} votes to ${b.players.count { b.pick(it, r) != winner }}"
            },
            color = Dim,
        )
        round.twist.theme?.let { Text("Theme: $it", color = Gold, fontSize = 13.sp) }
        Spacer(Modifier.height(16.dp))
        val w = b.players.indexOf(winner)
        if (shot == null) {
            Text("The photo is still on its way", color = Dim)
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(16.dp)).border(3.dp, Gold, RoundedCornerShape(16.dp)).background(Panel), contentAlignment = Alignment.Center) {
                images.getOrNull(w)?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) } ?: Spinner("Developing…")
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                b.players.forEachIndexed { i, p ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(8.dp)).background(Panel)) {
                            images.getOrNull(i)?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                        }
                        val votes = b.players.count { b.pick(it, r) == p }
                        Text("${room.name(p)} · ${plural(votes, "vote")}", color = Dim, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (!last) {
            GoldButton("Next round", onClick = onNext)
        } else {
            val winning = b.players.indexOf(champion).takeIf { it >= 0 }?.let { idx ->
                // The battle's winner: their edit of the last round they won.
                val lastWon = b.rounds.lastOrNull { b.winner(it.index) == champion } ?: round
                unpack(b.edit(b.players[idx], lastWon.index))?.let { lastWon.key to it }
            }
            SealEnd(room, b, word = "WINNER", saveAs = winning?.let { (key, s) -> Triple(key, s, "Battle: ${room.name(champion)}'s edit") }, onDone = onNext)
        }
    }
}

/**
 * The end of a battle: hold to seal, then the seal comes down. Sealing keeps [saveAs] (a shot,
 * an edit and a name) as a new version of that photo on this phone, so it's never lost.
 */
@Composable
private fun SealEnd(room: Room, b: TogetherBattle, word: String, saveAs: Triple<String, Settings, String>?, onDone: () -> Unit) {
    val context = LocalContext.current
    var justNow by rememberSaveable(b.id) { mutableStateOf<Long?>(null) }
    val sealedAt = justNow ?: b.sealedAt(room.me)
    val saved = saveAs?.let { (key, _, _) -> room.shots[key]?.primary?.stem }
    if (sealedAt != null) {
        Stamp(word, b.sealers.ifEmpty { listOf(room.me) }.map { room.name(it).take(1).uppercase() }, sealedAt, animate = justNow != null, modifier = Modifier.size(180.dp))
        Spacer(Modifier.height(16.dp))
        if (saved != null) Text("Kept as a version of $saved", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onDone) { Text("Back to the album") }
    } else {
        val others = b.sealers.filter { it != room.me }
        if (others.isNotEmpty()) Text("${others.joinToString(" and ") { room.name(it) }} sealed it. Add your seal.", color = Dim)
        Spacer(Modifier.height(8.dp))
        HoldToSeal("Hold to seal", onSealed = {
            val now = System.currentTimeMillis()
            PlayStore.add(context, b.sealFact(room.me, now))
            saveAs?.let { (key, s, name) ->
                room.shots[key]?.primary?.let { photo ->
                    runCatching {
                        val store = EditStore(context)
                        store.addVersion(photo, s)
                        store.renameVersion(photo, store.versions(photo).active, name)
                    }
                }
            }
            justNow = now
        })
        if (saved != null) Text("Sealing keeps it as a new version of $saved.", color = Dim, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

// ---- Copycat --------------------------------------------------------------------------------------

@Composable
private fun CopycatFlow(room: Room, b: TogetherBattle, onBack: () -> Unit, actions: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = room.me
    val round = b.rounds.firstOrNull()
    val shot = round?.let { room.shots[it.key] }
    val target = b.target(me)
    TopBar(b.mode.title, "with ${room.othersName}", onBack = onBack, actions = if (b.done) ({}) else actions)
    var scoring by remember { mutableStateOf(false) }
    when {
        round == null || shot == null -> Message("The photo is still on its way", "Touch phones with ${room.othersName} to get it.")
        b.done -> CopycatResult(room, b, shot, onBack)
        b.look(me) == null -> EditTurn(
            room, b, "look", shot, Settings(), round.twist,
            intro = "Make a look for ${room.name(target)} to copy. Go bold: the harder it is to copy, the better for you.",
            started = null, onStart = {},
            onLock = { s -> PlayStore.add(context, b.lookFact(me, pack(s), System.currentTimeMillis())) },
            onLeave = onBack,
        )
        b.copyStage == Stage.EDIT -> Waiting("Your look is locked in", "${room.othersName} ${if (room.others.size == 1) "is" else "are"} still making theirs.", Icons.Rounded.Palette, onBack)
        scoring -> Centered { Spinner("Comparing your copy…") }
        b.copy(me) == null -> {
            val look = unpack(b.look(target)) ?: Settings()
            EditTurn(
                room, b, "copy", shot, Settings(), round.twist,
                intro = "Now copy ${room.name(target)}'s look. You can peek at it, but not at the settings. The closer you get, the better.",
                started = null, onStart = {},
                target = look,
                onLock = { s ->
                    scoring = true
                    scope.launch {
                        val score = withContext(Dispatchers.Default) { likeness(context, shot, look, s) }
                        PlayStore.add(context, b.copyFact(me, score, pack(s), System.currentTimeMillis()))
                        scoring = false
                    }
                },
                onLeave = onBack,
            )
        }
        else -> Waiting("Your copy is in: ${b.copy(me)!!.first}%", "Waiting for ${room.othersName} to finish copying.", Icons.Rounded.ContentCopy, onBack)
    }
}

/** How close [copy] gets to [look] on [shot], 0..100: both developed small and compared colour by colour. */
private fun likeness(context: Context, shot: Shot, look: Settings, copy: Settings): Int = runCatching {
    val o = Photos.open(context, shot.primary)
    val a = Photos.renderPreview(o, look, 512)
    val c = Photos.renderPreview(o, copy, 512)
    fun pixels(bmp: Bitmap): IntArray {
        // A crop changes the size; both are compared at the same small size.
        val s = Bitmap.createScaledBitmap(bmp, 64, 64, true)
        return IntArray(64 * 64).also { s.getPixels(it, 0, 64, 0, 0, 64, 64) }
    }
    Likeness.score(pixels(a), pixels(c))
}.getOrDefault(0)

@Composable
private fun CopycatResult(room: Room, b: TogetherBattle, shot: Shot, onBack: () -> Unit) {
    val winner = b.copyWinner ?: return
    val renders = rememberRenders(shot, b.players.flatMap { p -> listOf(unpack(b.look(b.target(p))), unpack(b.copy(p)?.second)) }, 900)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.EmojiEvents, null, tint = Gold, modifier = Modifier.size(56.dp))
        Text("${room.name(winner)} copied best!", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        b.players.forEachIndexed { i, p ->
            val score = b.copy(p)!!.first
            Text("${room.name(p)} copying ${room.name(b.target(p))}: $score%", color = if (p == winner) Gold else Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((j, label) in listOf("The look", "The copy").withIndex()) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(8.dp)).background(Panel)) {
                            renders.getOrNull(i * 2 + j)?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                        }
                        Text(label, color = Dim, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        // The winner's look is the one worth keeping: the look they copied so well.
        val keep = unpack(b.copy(winner)?.second)
        SealEnd(room, b, "WINNER", keep?.let { Triple(shot.key, it, "Copycat: ${room.name(winner)}'s copy") }, onBack)
    }
}

// ---- Relay ------------------------------------------------------------------------------------------

@Composable
private fun RelayFlow(room: Room, b: TogetherBattle, onBack: () -> Unit, actions: @Composable () -> Unit) {
    val context = LocalContext.current
    val me = room.me
    val round = b.rounds.firstOrNull()
    val shot = round?.let { room.shots[it.key] }
    val n = b.legsDone
    val legs = BattleRules.LEGS
    TopBar(b.mode.title, if (b.done) "Made together" else "Leg ${(n + 1).coerceAtMost(legs.size)} of ${legs.size}", onBack = onBack, actions = if (b.done) ({}) else actions)
    val sofar = unpack(b.relayEdit) ?: Settings()
    when {
        round == null || shot == null -> Message("The photo is still on its way", "Touch phones with ${room.othersName} to get it.")
        b.done -> {
            val img = rememberRenders(shot, listOf(sofar)).first()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Made together", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(legs.indices.joinToString(" → ") { "${legs[it].title} (${room.name(b.holder(it))})" }, color = Dim, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(16.dp)).background(Panel), contentAlignment = Alignment.Center) {
                    img?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) } ?: Spinner("Developing…")
                }
                Spacer(Modifier.height(20.dp))
                SealEnd(room, b, "TOGETHER", Triple(shot.key, sofar, "Relay with ${room.othersName}"), onBack)
            }
        }
        b.holder(n) == me -> {
            val leg = legs[n]
            val prev = if (n > 0) room.name(b.holder(n - 1)) else null
            EditTurn(
                room, b, "leg$n", shot, sofar, Twist(tools = leg.tools),
                intro = "Leg ${n + 1} of ${legs.size}: ${leg.title}." + (if (prev != null) " It starts where $prev left it." else " You start.") +
                    (if (leg.tools.isEmpty()) " Every panel is open for the finishing touches." else ""),
                started = null, onStart = {},
                onLock = { s -> PlayStore.add(context, b.legFact(me, n, pack(s), System.currentTimeMillis())) },
                onLeave = onBack,
            )
        }
        else -> {
            val img = if (n > 0) rememberRenders(shot, listOf(sofar), 1200).first() else null
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (n > 0) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(16.dp)).background(Panel), contentAlignment = Alignment.Center) {
                        img?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) } ?: Spinner("Developing…")
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text("${room.name(b.holder(n))}'s turn: ${legs[n].title}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Their leg comes over when they're done, live or next time you meet.", color = Dim, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onBack) { Text("Back to the album") }
            }
        }
    }
}
