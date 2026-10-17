package dev.kogler.a7iiiconnect.duo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kogler.a7iiiconnect.develop.AlbumStore
import dev.kogler.a7iiiconnect.develop.Albums
import dev.kogler.a7iiiconnect.develop.PickGreen
import dev.kogler.a7iiiconnect.develop.RejectRed
import dev.kogler.a7iiiconnect.develop.RoundButton
import dev.kogler.a7iiiconnect.develop.Shot
import dev.kogler.a7iiiconnect.develop.Similar
import dev.kogler.a7iiiconnect.develop.SkipBlue
import dev.kogler.a7iiiconnect.develop.StackCard
import dev.kogler.a7iiiconnect.develop.SwipeCard
import dev.kogler.a7iiiconnect.develop.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.random.Random

/**
 * The album's review, together: swipe your way through the shots, see how in sync you are, talk
 * over the ones you disagree on, and seal the round. Leave whenever you like; it all waits here.
 */
@Composable
internal fun ReviewScreen(room: Room, onBack: () -> Unit) {
    val r = room.review
    val me = room.me
    var revealed by rememberSaveable(room.album.id) { mutableStateOf(false) }
    var justSealed by rememberSaveable(room.album.id) { mutableStateOf<Long?>(null) }
    val mine = r.toVote(me).filter { it in room.shots }
    val othersLeft = room.others.sumOf { r.toVote(it).size }

    // A match pops up whoever made it: me voting, or a vote arriving from the other phone.
    val matches = r.open.count { r.isMatch(it) }
    var matchTick by remember { mutableIntStateOf(0) }
    var lastMatches by remember { mutableIntStateOf(matches) }
    LaunchedEffect(matches) {
        if (matches > lastMatches) matchTick++
        lastMatches = matches
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            when {
                justSealed != null -> ReviewSealed(room, justSealed!!, onBack)
                mine.isNotEmpty() -> Deck(room, mine, onBack)
                r.disputes.isNotEmpty() && !revealed -> Reveal(room, r.open.filter { r.state(it) != ReviewState.VOTING }, onTalk = { revealed = true }, onBack = onBack)
                r.disputes.isNotEmpty() -> Debate(room, onBack)
                r.toSeal(me).isNotEmpty() -> ReviewFinale(room, onBack, onSealed = { justSealed = it })
                othersLeft > 0 -> {
                    TopBar("Review together", onBack = onBack)
                    Message(
                        "You're through!",
                        "${room.othersName} has $othersLeft still to go. You can close this: their votes come over next time you meet.",
                        icon = { Icon(Icons.Rounded.Check, null, tint = PickGreen, modifier = Modifier.size(56.dp)) },
                        below = { Button(onClick = onBack) { Text("Back to the album") } },
                    )
                }
                else -> {
                    TopBar("Review together", onBack = onBack)
                    Message(
                        if (room.missing > 0) "Photos are still coming" else "Nothing to review",
                        if (room.missing > 0) "${plural(room.missing, "photo")} from ${room.othersName} aren't on this phone yet. Touch phones to get them."
                        else "Add shots to the album to start a new round.",
                        icon = { Icon(Icons.Rounded.Swipe, null, tint = Dim, modifier = Modifier.size(56.dp)) },
                        below = { Button(onClick = onBack) { Text("Back to the album") } },
                    )
                }
            }
        }
        MatchBurst(matchTick, if (room.others.size == 1) "You both kept this one" else "You all kept this one")
        CoinOverlay(room)
    }
}

// ---- Deck ---------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Deck(room: Room, toVote: List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    val r = room.review
    // The deck is dealt once per visit, similar shots stacked; it doesn't reshuffle under the thumb.
    var deck by remember { mutableStateOf<List<List<Shot>>?>(null) }
    var preparing by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val shots = toVote.mapNotNull { room.shots[it] }
        deck = withContext(Dispatchers.IO) { Similar.stacks(context, shots) { preparing = it } }
    }
    val history = remember { mutableStateListOf<List<String>>() }
    var fling by remember { mutableStateOf<Verdict?>(null) }
    var chosen by remember { mutableIntStateOf(0) }
    val total = r.open.size

    TopBar("Review together", room.album.name, onBack = onBack) {
        IconButton(onClick = {
            history.removeLastOrNull()?.let { keys -> PlayStore.add(context, keys.map { r.voteFact(room.me, it, null, System.currentTimeMillis()) }) }
            fling = null
            chosen = 0
        }, enabled = history.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", tint = if (history.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f))
        }
    }
    Race(room.members.map { Triple(room.name(it), r.progress(it), room.color(it)) }, total)

    val stacks = deck
    if (stacks == null) {
        Centered { Spinner("Dealing the cards… ${(preparing * 100).toInt()}%") }
        return
    }
    val pos = stacks.indexOfFirst { s -> s.any { r.vote(room.me, it.key) == null } }
    if (pos < 0) {
        // Everything dealt is voted on; the screen moves on as the facts change.
        Centered { Spinner("") }
        return
    }
    fun record(keys: List<String>, facts: List<Fact>) {
        PlayStore.add(context, facts)
        history += keys
        fling = null
        chosen = 0
    }
    val top = stacks[pos]
    val now = { System.currentTimeMillis() }
    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (top.size > 1) {
            StackCard(top, chosen.coerceIn(top.indices), onChoose = { chosen = it }, onSplit = null)
        } else {
            for (i in min(pos + 2, stacks.size - 1) downTo pos) {
                val shot = stacks[i].first()
                if (stacks[i].size > 1) continue
                key(shot.key) {
                    SwipeCard(
                        shot = shot,
                        depth = i - pos,
                        fling = if (i == pos) fling else null,
                        rejectLabel = "NOPE",
                        skipLabel = "HMM",
                        onDecided = { v -> record(listOf(shot.key), listOf(r.voteFact(room.me, shot.key, v.toCall(), now()))) },
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
        val keys = top.map { it.key }
        if (top.size > 1) {
            RoundButton(Icons.Rounded.Close, "None of these", RejectRed, 64, "duo-reject") { record(keys, r.stackFacts(room.me, keys, null, Call.REJECT, now())) }
            RoundButton(Icons.Rounded.QuestionMark, "Not sure", SkipBlue, 48, "duo-maybe") { record(keys, r.stackFacts(room.me, keys, null, Call.MAYBE, now())) }
            RoundButton(Icons.Rounded.Check, "Keep this one", PickGreen, 64, "duo-pick") {
                record(keys, r.stackFacts(room.me, keys, chosen.coerceIn(top.indices), Call.PICK, now()))
            }
        } else {
            RoundButton(Icons.Rounded.Close, "Nope", RejectRed, 64, "duo-reject") { if (fling == null) fling = Verdict.REJECT }
            RoundButton(Icons.Rounded.QuestionMark, "Not sure", SkipBlue, 48, "duo-maybe") { if (fling == null) fling = Verdict.SKIP }
            RoundButton(Icons.Rounded.Check, "Keep", PickGreen, 64, "duo-pick") { if (fling == null) fling = Verdict.PICK }
        }
    }
}

private fun Verdict.toCall() = when (this) {
    Verdict.PICK -> Call.PICK
    Verdict.REJECT -> Call.REJECT
    Verdict.SKIP -> Call.MAYBE
}

// ---- Reveal and talking it over -----------------------------------------------------------------

@Composable
internal fun Reveal(room: Room, voted: List<String>, onTalk: () -> Unit, onBack: () -> Unit) {
    val t = room.review.tally(voted)
    val talk = room.review.disputes.size
    val shown = remember { Animatable(0f) }
    LaunchedEffect(t.score) { shown.animateTo(t.score.toFloat(), tween(1600)) }
    TopBar("The reveal", room.album.name, onBack = onBack)
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("${shown.value.toInt()}%", color = Color.White, fontSize = 88.sp, fontWeight = FontWeight.Black)
        Text("in sync", color = Dim, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(syncLine(t.score), color = Gold, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(36.dp))
        StatRow(Icons.Rounded.Favorite, Pink, "${t.matches}", "you all love")
        StatRow(Icons.Rounded.Close, RejectRed, "${voted.count { room.review.state(it) == ReviewState.AGREED && room.review.outcome(it) == Call.REJECT }}", "out, no questions")
        StatRow(Icons.Rounded.QuestionMark, SkipBlue, "$talk", "to talk about")
        Spacer(Modifier.height(36.dp))
        GoldButton("Talk about $talk", onClick = onTalk)
    }
}

internal fun syncLine(score: Int) = when {
    score >= 90 -> "Soulmates"
    score >= 70 -> "Great minds think alike"
    score >= 50 -> "Mostly on the same page"
    else -> "Opposites attract"
}

@Composable
internal fun StatRow(icon: ImageVector, color: Color, n: String, label: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Text(n, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 17.sp)
    }
}

@Composable
private fun ColumnScope.Debate(room: Room, onBack: () -> Unit) {
    val context = LocalContext.current
    val r = room.review
    val disputes = r.disputes
    val key = disputes.firstOrNull() ?: return
    val shot = room.shots[key]
    val all = disputes.size + r.open.count { r.state(it) == ReviewState.DECIDED }
    val now = { System.currentTimeMillis() }

    TopBar("Let's talk", "${all - disputes.size + 1} of $all", onBack = onBack)
    Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Panel)) {
        if (shot != null) key(key) { ShotImage(shot, Modifier.fillMaxSize()) }
        else Centered { Text("This photo is still on its way", color = Dim) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in room.members) VoteChip(room.name(m), room.color(m), r.vote(m, key))
    }
    val myCall = r.say(room.me, key)
    val theirCalls = room.others.mapNotNull { o -> r.say(o, key)?.let { o to it } }
    Text(
        when {
            r.isStuck(key) -> theirCalls.joinToString { (o, c) -> "${room.name(o)} says ${callLabel(c)}" } + ". Agree, or let a coin decide"
            theirCalls.isNotEmpty() -> theirCalls.joinToString { (o, c) -> "${room.name(o)} says ${callLabel(c)}" }
            myCall != null -> "Waiting for ${room.othersName}. You can leave this; it waits here."
            else -> "Talk it over, then all tap the same call"
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
        CallButton(myCall == Call.REJECT, theirCalls.filter { it.second == Call.REJECT }.map { room.name(it.first) }) {
            RoundButton(Icons.Rounded.Close, "Out", RejectRed, 64, "debate-out") { PlayStore.add(context, r.sayFacts(room.me, key, Call.REJECT, now())) }
        }
        CallButton(myCall == Call.PICK, theirCalls.filter { it.second == Call.PICK }.map { room.name(it.first) }) {
            RoundButton(Icons.Rounded.Check, "Keep", PickGreen, 64, "debate-keep") { PlayStore.add(context, r.sayFacts(room.me, key, Call.PICK, now())) }
        }
    }
    if (r.isStuck(key)) {
        OutlinedButton(
            onClick = { r.coinFact(room.me, key, Random.nextInt(room.members.size), now())?.let { PlayStore.add(context, it) } },
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
        ) { Text("Flip a coin") }
    }
}

private fun callLabel(c: Call) = when (c) {
    Call.REJECT -> "out"
    Call.MAYBE -> "not sure"
    Call.PICK -> "keep"
}

/** A call button with who picked it: a white ring for me, their initials for the others. */
@Composable
private fun CallButton(mine: Boolean, theirs: List<String>, button: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.border(3.dp, if (mine) Color.White else Color.Transparent, CircleShape).padding(5.dp)) { button() }
        if (theirs.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd)) { Avatar(theirs.first(), PartnerColor, 22) }
    }
}

@Composable
private fun VoteChip(name: String, color: Color, vote: Call?) {
    val (text, tint) = when (vote) {
        null -> "—" to Color.White
        Call.REJECT -> "out" to RejectRed
        Call.MAYBE -> "not sure" to SkipBlue
        Call.PICK -> "keep" to PickGreen
    }
    Surface(color = Panel, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, color, 20)
            Spacer(Modifier.width(6.dp))
            Text(name, color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A coin spinning, then whose call it went with, every time a coin settles a shot (here or on the other phone). */
@Composable
private fun CoinOverlay(room: Room) {
    val r = room.review
    val coins = r.keys.filter { r.decision(it)?.slot == TogetherReview.COIN }
    var seen by remember { mutableStateOf(coins.toSet()) }
    val fresh = coins.firstOrNull { it !in seen } ?: return
    val winner = r.coinWinner(fresh)
    val call = r.decision(fresh)?.value?.let { Call.of(it) }
    val spin = remember(fresh) { Animatable(0f) }
    LaunchedEffect(fresh) {
        spin.animateTo(1f, tween(900))
        delay(1900)
        seen = seen + fresh
    }
    val name = if (winner == room.me) "Your" else "${winner?.let { room.name(it) } ?: "Their"}'s"
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable { seen = seen + fresh }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(96.dp).graphicsLayer { rotationY = spin.value * 1080f }.clip(CircleShape).background(Gold),
                contentAlignment = Alignment.Center,
            ) { Text("?", color = Color.Black, fontSize = 44.sp, fontWeight = FontWeight.Black) }
            Spacer(Modifier.height(20.dp))
            if (spin.value >= 1f) {
                Text("$name call wins", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (call != null) Text(callLabel(call).replaceFirstChar { it.uppercase() }, color = Gold, fontSize = 18.sp)
            }
        }
    }
}

// ---- The end of a round -------------------------------------------------------------------------

/** All sorted: how it went, and the seal. Sealing takes the shots that are out out of the album. */
@Composable
private fun ReviewFinale(room: Room, onBack: () -> Unit, onSealed: (Long) -> Unit) {
    val context = LocalContext.current
    val r = room.review
    val keys = r.toSeal(room.me)
    val t = r.tally(keys)
    val sealers = room.others.filter { o -> keys.all { r.sealedBy(o, it) } }
    val best = keys.firstOrNull { r.isMatch(it) && it in room.shots } ?: keys.firstOrNull { r.outcome(it) == Call.PICK && it in room.shots }
    TopBar("All sorted!", room.album.name, onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        Confetti()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (best != null) {
                ShotImage(room.shots.getValue(best), Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)), edge = 1024)
                Spacer(Modifier.height(16.dp))
            }
            Text("${t.score}% in sync", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text(syncLine(t.score), color = Gold, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            StatRow(Icons.Rounded.Check, PickGreen, "${t.kept}", "stay in the album")
            StatRow(Icons.Rounded.Close, RejectRed, "${t.out}", "leave the album")
            if (t.matches > 0) StatRow(Icons.Rounded.Favorite, Pink, "${t.matches}", if (t.matches == 1) "match" else "matches")
            if (t.talked > 0) StatRow(Icons.Rounded.QuestionMark, SkipBlue, "${t.talked}", "talked over" + if (t.coins > 0) ", ${plural(t.coins, "coin flip")}" else "")
            Spacer(Modifier.height(20.dp))
            if (sealers.isNotEmpty()) {
                Text("${sealers.joinToString(" and ") { room.name(it) }} sealed it. Add your seal.", color = Dim, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
            }
            HoldToSeal("Hold to seal", onSealed = {
                val now = System.currentTimeMillis()
                PlayStore.add(context, r.seal(room.me, now))
                val out = keys.filter { r.outcome(it) == Call.REJECT }
                if (out.isNotEmpty()) {
                    val store = AlbumStore(context)
                    store.save(store.load().map { if (it.id == room.album.id) Albums.remove(it, out, now) else it })
                    Duo.albumsChanged()
                }
                onSealed(now)
            })
            Spacer(Modifier.height(8.dp))
            Text("The ${plural(t.out, "shot")} that are out leave the album for everyone.", color = Dim, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

/** Right after sealing: the seal comes down. */
@Composable
private fun ReviewSealed(room: Room, at: Long, onBack: () -> Unit) {
    val r = room.review
    val last = r.rounds.lastOrNull { it.first == room.me }?.third
    TopBar("Sealed", room.album.name, onBack = onBack)
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val sealers = listOf(room.me) + room.others.filter { o -> r.rounds.any { it.first == o && it.second >= at - ROUND_SLACK_MS } }
        Stamp("SEALED", sealers.map { room.name(it).take(1).uppercase() }, at, animate = true, modifier = Modifier.size(200.dp))
        Spacer(Modifier.height(32.dp))
        if (last != null) Text("${last.kept} kept · ${last.out} out · ${last.score}% in sync", color = Color.White, fontSize = 18.sp)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Back to the album") }
    }
}

/** Seals by the others within this long of mine count as the same round's, for the stamp's initials. */
private const val ROUND_SLACK_MS = 7L * 24 * 60 * 60 * 1000
