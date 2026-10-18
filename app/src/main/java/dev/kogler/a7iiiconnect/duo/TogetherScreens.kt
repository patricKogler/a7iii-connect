package dev.kogler.a7iiiconnect.duo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kogler.a7iiiconnect.develop.Album
import dev.kogler.a7iiiconnect.develop.AlbumStore
import dev.kogler.a7iiiconnect.develop.Albums
import dev.kogler.a7iiiconnect.develop.Library
import dev.kogler.a7iiiconnect.develop.Shot
import dev.kogler.a7iiiconnect.develop.Thumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One shared album as the Together screens see it: who's in it, its shots on this phone, and
 * what's been done together in it.
 */
class Room(val album: Album, val me: String, val myName: String, val shots: Map<String, Shot>, val facts: List<Fact>) {
    val members: List<String> = (album.members.keys + me).distinct().sorted()
    val others: List<String> = members - me
    val review = TogetherReview(album.id, members, album.keys, facts)
    val battles = TogetherBattle.all(album.id, members, facts)

    fun name(phone: String): String = if (phone == me) myName else album.members[phone] ?: "Someone"
    fun color(phone: String) = if (phone == me) MeColor else PartnerColor

    /** "Anna", or "Anna and Ben". */
    val othersName: String get() = others.map { name(it) }.let { if (it.size <= 1) it.firstOrNull() ?: "nobody yet" else it.dropLast(1).joinToString(", ") + " and " + it.last() }

    fun battle(id: String) = battles.firstOrNull { it.id == id }

    /** Album shots not on this phone yet (still to come from the others). */
    val missing: Int get() = album.keys.count { it !in shots }
}

/** Where Together is: one of these, as text so it survives rotation. */
internal object Routes {
    const val HOME = "home"
    fun hub(album: String) = "hub:$album"
    fun review(album: String) = "review:$album"
    fun newBattle(album: String, key: String? = null) = "new:$album:${key.orEmpty()}"
    fun battle(album: String, id: String) = "battle:$album:$id"
}

/**
 * The Together screens: the shared albums and the phones nearby (home), one album's review and
 * battles (hub), and the review and battles themselves. [start] is the route to open on.
 */
@Composable
fun TogetherScreen(start: String, onExit: () -> Unit, onShareApp: () -> Unit, onInstall: (File) -> Unit) {
    val context = LocalContext.current
    var route by rememberSaveable { mutableStateOf(start) }
    val store = remember { AlbumStore(context) }
    val me = store.phoneId
    val playVersion = PlayStore.version
    val albumChanges = AlbumSync.changes

    // Shots and albums on this phone, read again whenever albums or facts change. Shots that came
    // from the other phone get this phone's keys, in albums and facts alike.
    var shots by remember { mutableStateOf<Map<String, Shot>?>(null) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    LaunchedEffect(albumChanges, playVersion) {
        val (s, a) = withContext(Dispatchers.IO) {
            val all = Library.shots(Library.load(context))
            val resolve = Albums.resolver(all.map { it.key })
            val stored = store.load()
            val rekeyed = Albums.rekey(stored, resolve)
            if (rekeyed != stored) store.save(rekeyed)
            PlayStore.rekey(context, resolve)
            all.associateBy { it.key } to rekeyed
        }
        shots = s
        albums = a
    }
    val facts = remember(playVersion) { PlayStore.facts(context) }
    val loaded = shots
    fun room(id: String): Room? = albums.firstOrNull { it.id == id && it.alive }?.let { Room(it, me, Duo.myName, loaded ?: emptyMap(), facts) }

    val parts = route.split(':')
    val back: () -> Unit = {
        when (parts[0]) {
            "review", "new", "battle" -> route = Routes.hub(parts[1])
            "hub" -> if (start.startsWith("hub:")) onExit() else route = Routes.HOME
            else -> onExit()
        }
    }
    BackHandler { back() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val room = parts.getOrNull(1)?.let { room(it) }
            when {
                loaded == null -> Centered { Spinner("Getting the albums ready…") }
                parts[0] == "home" -> Home(albums.filter { it.alive && it.shared && it.members.size > 1 }, me, facts, onOpen = { route = Routes.hub(it.id) }, onClose = onExit, onShareApp = onShareApp)
                room == null -> Message("This album isn't here any more", below = { Button(onClick = { route = Routes.HOME }) { Text("Back") } })
                parts[0] == "hub" -> Hub(
                    room,
                    onBack = back,
                    onReview = { route = Routes.review(room.album.id) },
                    onNewBattle = { route = Routes.newBattle(room.album.id) },
                    onBattle = { route = Routes.battle(room.album.id, it) },
                    onShareApp = onShareApp,
                )
                parts[0] == "review" -> ReviewScreen(room, onBack = back)
                parts[0] == "new" -> NewBattle(room, preset = parts.getOrNull(2)?.ifEmpty { null }, onBack = back, onStarted = { route = Routes.battle(room.album.id, it) })
                parts[0] == "battle" -> BattleScreen(room, parts.getOrNull(2).orEmpty(), onBack = back)
            }
        }
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Duo.transfer?.let { (label, f) -> Banner(label, f) }
            Duo.update?.let { apk -> Banner("${Duo.partnerName} has a newer Shootback", action = "Install") { onInstall(apk) } }
        }
        Duo.notice?.let { text ->
            LaunchedEffect(text) { delay(3500); if (Duo.notice == text) Duo.notice = null }
            Surface(
                color = Color(0xFF2C2C2E), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            ) { Text(text, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        }
    }
}

// ---- The link -------------------------------------------------------------------------------------

/** What's on its way, for the albums: the photos coming here first, then the ones going over. */
internal fun albumProgress(): Pair<String, Float>? {
    val (got, of) = AlbumSync.getting
    val (sent, to) = AlbumSync.sending
    return when {
        AlbumSync.asked -> "Asking ${Duo.partnerName} for photos" to 0f
        got < of -> "Getting photo ${got + 1} of $of from ${Duo.partnerName}" to got / of.toFloat()
        sent < to -> "Sending photo ${sent + 1} of $to to ${Duo.partnerName}" to sent / to.toFloat()
        else -> null
    }
}

/**
 * Who's here: connected (live), or how to get the other phone here. [who] is who we're waiting
 * for ("Anna"), [inviting] when the album is waiting for its first guest.
 */
@Composable
internal fun LinkCard(who: String?, inviting: Boolean = false, onShareApp: (() -> Unit)? = null) {
    var renaming by remember { mutableStateOf(false) }
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("link-card")) {
        Column(Modifier.padding(16.dp)) {
            when {
                Duo.connected -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(Duo.partnerName, PartnerColor, 32)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${Duo.partnerName} is here", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Live: votes and edits go over as you make them", color = Dim, fontSize = 13.sp)
                        }
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF3DDC84)))
                    }
                    val progress = albumProgress()
                    if (progress != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(progress.first, color = Dim, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.material3.LinearProgressIndicator(progress = { progress.second }, modifier = Modifier.fillMaxWidth())
                    } else if (!AlbumSync.merged) {
                        Spacer(Modifier.height(8.dp))
                        Text("Swapping albums…", color = Dim, fontSize = 13.sp)
                    }
                }
                Duo.ended -> Text("${Duo.partnerName} closed Together. What you do now goes over next time you meet.", color = Dim)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PhonesTouching(Modifier.size(84.dp, 56.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    Duo.reconnecting && Duo.reconnectSlow -> "Still looking for ${Duo.partnerName}…"
                                    Duo.reconnecting -> "Lost ${Duo.partnerName}. Reconnecting…"
                                    inviting -> "Touch the backs of your phones"
                                    who != null -> "Touch phones with $who to sync"
                                    else -> "Touch the backs of two phones"
                                },
                                color = Color.White, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                when {
                                    Duo.reconnecting && Duo.reconnectSlow -> "Keep Together open on both phones and bring them closer. Nothing done meanwhile is lost."
                                    inviting -> "The other phone joins this album and gets its photos."
                                    else -> "Or just open Together on both phones nearby. Apart works too: it all goes over next time."
                                },
                                color = Dim, fontSize = 13.sp,
                            )
                        }
                    }
                    val others = Duo.nearby.entries.toList()
                    for ((id, name) in others) {
                        Spacer(Modifier.height(8.dp))
                        Surface(color = Color.White.copy(alpha = 0.06f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = Duo.connecting == null) { Duo.connectTo(id) }) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(name, PartnerColor, 28)
                                Spacer(Modifier.width(10.dp))
                                Text(name, color = Color.White, modifier = Modifier.weight(1f))
                                if (Duo.connecting == id) Text("Connecting…", color = Dim)
                                else Text("Connect", color = MaterialTheme.colorScheme.primary.copy(alpha = if (Duo.connecting == null) 1f else 0.4f), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(16.dp)).clickable { renaming = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(Duo.myName, MeColor, 20)
                    Spacer(Modifier.width(6.dp))
                    Text("You're ${Duo.myName}", color = Dim, fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.Edit, "Change name", tint = Dim, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.weight(1f))
                if (onShareApp != null && !Duo.connected) {
                    TextButton(onClick = onShareApp) {
                        Icon(Icons.Rounded.Share, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Send the app", fontSize = 13.sp)
                    }
                }
            }
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

// ---- Home: every shared album ---------------------------------------------------------------------

@Composable
private fun Home(albums: List<Album>, me: String, facts: List<Fact>, onOpen: (Album) -> Unit, onClose: () -> Unit, onShareApp: () -> Unit) {
    TopBar("Together", onBack = onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LinkCard(who = null, onShareApp = onShareApp)
        Spacer(Modifier.height(20.dp))
        if (albums.isEmpty()) {
            Text(
                "No shared albums yet. Open an album in Photos and tap Together to invite someone into it.",
                color = Dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        } else {
            SectionTitle("Shared albums")
            for (a in albums.sortedByDescending { it.createdAt }) {
                val room = Room(a, me, Duo.myName, emptyMap(), facts)
                AlbumRow(room, onClick = { onOpen(a) })
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp))
}

@Composable
private fun AlbumRow(room: Room, onClick: () -> Unit) {
    val turns = room.battles.count { it.status(room.me) == BattleStatus.YOUR_TURN }
    val toVote = room.review.toVote(room.me).size
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(room.album.name, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(
                    "with ${room.othersName}",
                    if (toVote > 0) "$toVote to review" else null,
                    if (turns > 0) "your turn in ${plural(turns, "battle")}" else null,
                ).joinToString(" · "),
                color = Dim, fontSize = 13.sp,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Dim)
    }
}

// ---- Hub: one album -------------------------------------------------------------------------------

@Composable
internal fun Hub(room: Room, onBack: () -> Unit, onReview: () -> Unit, onNewBattle: () -> Unit, onBattle: (String) -> Unit, onShareApp: () -> Unit) {
    val inviting = room.others.isEmpty()
    TopBar(room.album.name, if (inviting) "Waiting for someone to join" else "with ${room.othersName}", onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LinkCard(who = room.othersName.takeIf { !inviting }, inviting = inviting, onShareApp = onShareApp.takeIf { inviting })
        if (inviting) return@Column
        if (room.missing > 0) {
            Text(
                "${plural(room.missing, "photo")} still to come from ${room.othersName}",
                color = Dim, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        ReviewCard(room, onReview)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Edit battles")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onNewBattle, enabled = room.shots.isNotEmpty() && room.album.keys.any { it in room.shots }, modifier = Modifier.testTag("new-battle")) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New battle")
            }
        }
        val battles = room.battles.filter { it.droppedBy == null }
        if (battles.isEmpty()) {
            Text("No battles yet. Pick a photo and a mode, and see whose edit wins.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        }
        for (b in battles) BattleRow(room, b, onClick = { onBattle(b.id) })
        val rounds = room.review.rounds.filter { it.first == room.me }
        if (rounds.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionTitle("Sealed reviews")
            for ((_, at, t) in rounds.reversed()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Stamp("", emptyList(), at, animate = false, modifier = Modifier.size(36.dp), plain = true)
                    Spacer(Modifier.width(12.dp))
                    Text("${t.kept} kept · ${t.out} out · ${t.score}% in sync", color = Color.White, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReviewCard(room: Room, onReview: () -> Unit) {
    val r = room.review
    val mine = r.toVote(room.me).count { it in room.shots }
    val theirs = room.others.sumOf { r.toVote(it).size }
    val talk = r.disputes.size
    val seal = r.toSeal(room.me).isNotEmpty()
    val (title, detail) = when {
        r.open.isEmpty() && !seal -> "Nothing to review" to "Shots added to the album start a new round."
        mine > 0 -> "Review together" to "${plural(mine, "shot")} for you to go through" + if (theirs > 0) ", $theirs for ${room.othersName}" else ""
        talk > 0 -> "Talk it over" to "You don't agree on ${plural(talk, "shot")}"
        seal -> "Ready to seal" to "Everything's sorted. Seal the round."
        theirs > 0 -> "Waiting for ${room.othersName}" to "$theirs still to go. Their votes come over next time you meet."
        else -> "Review together" to "Swipe through the album, both of you"
    }
    Surface(
        color = Panel, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onReview).testTag("review-card"),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Pink.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Swipe, null, tint = Pink)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Dim, fontSize = 13.sp)
            }
            if (mine > 0 || talk > 0 || seal) Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Gold))
        }
    }
}

@Composable
private fun BattleRow(room: Room, b: TogetherBattle, onClick: () -> Unit) {
    val status = b.status(room.me)
    val shot = b.rounds.firstOrNull()?.key?.let { room.shots[it] }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Panel), contentAlignment = Alignment.Center) {
            if (shot != null) Thumb(shot.jpeg ?: shot.primary, Modifier.fillMaxSize())
            else Icon(Icons.Rounded.Brush, null, tint = Dim)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.mode.title, color = Color.White, fontWeight = FontWeight.SemiBold)
            val champ = b.champion
            Text(
                when (status) {
                    BattleStatus.YOUR_TURN -> "Your turn"
                    BattleStatus.WAITING -> "Waiting for ${room.othersName}"
                    BattleStatus.RESULT -> "The result is in!"
                    BattleStatus.SEALED -> if (b.mode == Mode.RELAY) "Made together" else if (champ != null) "${room.name(champ)} won" else "Sealed"
                    BattleStatus.DROPPED -> "Called off"
                },
                color = if (status == BattleStatus.YOUR_TURN || status == BattleStatus.RESULT) Gold else Dim, fontSize = 13.sp,
            )
        }
        if (status == BattleStatus.SEALED) Stamp("", emptyList(), b.sealedAt(room.me) ?: b.createdAt, animate = false, modifier = Modifier.size(30.dp), plain = true)
        else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Dim)
    }
}

/** A grid of the album's shots on this phone, to pick from; [picked] are marked in order. */
@Composable
internal fun ShotGrid(shots: List<Shot>, picked: List<String>, onTap: (Shot) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 96.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier,
    ) {
        items(shots.size, key = { shots[it].key }) { i ->
            val shot = shots[i]
            val n = picked.indexOf(shot.key)
            Box(Modifier.aspectRatio(1f).clickable { onTap(shot) }.border(3.dp, if (n >= 0) Gold else Color.Transparent).testTag("pick-${shot.key}")) {
                Thumb(shot.jpeg ?: shot.primary, Modifier.fillMaxSize())
                if (n >= 0) {
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(RoundedCornerShape(12.dp)).background(Gold), contentAlignment = Alignment.Center) {
                        Text("${n + 1}", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** A gold call to action. */
@Composable
internal fun GoldButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black),
    ) { Text(text, fontWeight = FontWeight.Bold) }
}

@Composable
internal fun LoadingShot() = Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Dim) }

@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit) = OutlinedButton(onClick = onClick) { Text(text) }
