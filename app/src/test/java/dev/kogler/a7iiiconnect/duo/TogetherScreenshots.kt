package dev.kogler.a7iiiconnect.duo

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kogler.a7iiiconnect.develop.Album
import dev.kogler.a7iiiconnect.develop.Albums
import dev.kogler.a7iiiconnect.develop.EditStore
import dev.kogler.a7iiiconnect.develop.Library
import dev.kogler.a7iiiconnect.develop.LibraryPhoto
import dev.kogler.a7iiiconnect.develop.Shot
import dev.kogler.a7iiiconnect.develop.TestScenes
import dev.kogler.a7iiiconnect.develop.engine.P
import dev.kogler.a7iiiconnect.develop.engine.Settings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/**
 * Together's screens on the JVM, driven the way two phones would: this phone taps, the other
 * phone's facts are added as if they had just come over. Renders to app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class TogetherScreenshots {

    @get:Rule
    val compose = createComposeRule()

    private val me = "phone-me"
    private val her = "phone-her"
    private lateinit var context: Context
    private lateinit var shots: List<Shot>

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Duo.init(context)
        val file = File(context.cacheDir, "DSC09412.JPG")
        val bmp = TestScenes.landscape(1200, 800)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        val t = 1_760_000_000_000L
        // An hour apart, so the review doesn't stack them as similar.
        shots = Library.shots((0 until 6).map { i -> LibraryPhoto(700L + i, Uri.fromFile(file), "DSC098${10 + i}.JPG", "image/jpeg", t - i * 3_600_000L) })
        compose.mainClock.autoAdvance = false
    }

    private fun album(id: String): Album =
        Albums.invite(Albums.create("Hallstatt", shots.map { it.key }, now = 1, id = id, owner = me), me, "Patrick", her, "Anna")

    /** A screen of Together over [album], redrawn whenever a fact is added, like the real one. */
    private fun show(album: Album, content: @Composable ColumnScope.(Room) -> Unit) {
        compose.setContent {
            val version = PlayStore.version
            val facts = remember(version) { PlayStore.facts(context) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(Modifier.fillMaxSize().background(Color.Black)) {
                    content(Room(album, me, "Patrick", shots.associateBy { it.key }, facts))
                }
            }
        }
    }

    private fun now() = System.currentTimeMillis()
    private fun edit(exposure: Float) = Facts.packSettings(Settings().with(P.EXPOSURE, exposure).encode())

    private fun review(album: Album) = TogetherReview(album.id, listOf(her, me), album.keys, PlayStore.facts(context))
    private fun battle(album: Album, id: String) = TogetherBattle(album.id, id, listOf(her, me), PlayStore.facts(context))

    @Test
    fun hubWithBattles() {
        val a = album("hub")
        val r = review(a)
        PlayStore.add(context, a.keys.take(4).map { r.voteFact(her, it, Call.PICK, now()) })
        PlayStore.add(context, BattleRules.start(a.id, her, "won", Mode.CLASSIC, listOf(a.keys[0]), now(), Random(1)))
        battle(a, "won").let { b ->
            PlayStore.add(context, b.editFact(me, 0, edit(0.4f), now()), b.editFact(her, 0, edit(-0.3f), now()))
            PlayStore.add(context, b.pickFact(me, 0, me, now()), b.pickFact(her, 0, me, now()), b.sealFact(me, now()))
        }
        PlayStore.add(context, BattleRules.start(a.id, her, "bo3", Mode.BEST_OF_3, a.keys.take(3), now(), Random(2)))
        PlayStore.add(context, BattleRules.start(a.id, me, "relay", Mode.RELAY, listOf(a.keys[1]), now(), Random(3)))
        show(a) { room -> Hub(room, onBack = {}, onReview = {}, onNewBattle = {}, onBattle = {}, onShareApp = {}) }
        settle()
        check(battle(a, "won").status(me) == BattleStatus.SEALED)
        compose.onNodeWithText("Patrick won").assertExists()
        check(compose.onAllNodesWithText("Your turn").fetchSemanticsNodes().size == 2) { "Best of 3 and the relay wait on me" }
        shot("60-together-hub")
    }

    @Test
    fun reviewFromDeckToSeal() {
        val a = album("review")
        val keys = a.keys
        PlayStore.add(context, keys.mapIndexed { i, k -> review(a).voteFact(her, k, if (i < 4) Call.PICK else Call.REJECT, now()) })
        show(a) { room -> ReviewScreen(room, onBack = {}) }
        settle(30)
        shot("61-review-deck")
        compose.onNodeWithTag("duo-pick").performClick()
        settle(20)
        check(review(a).vote(me, keys.first()) != null) { "the first card wasn't voted on" }

        // The rest, as if swiped: two disagree with Anna.
        PlayStore.add(context, keys.drop(1).mapIndexed { i, k -> review(a).voteFact(me, k, if (i < 2) Call.REJECT else if (i < 3) Call.PICK else Call.REJECT, now()) })
        settle(30)
        compose.onNodeWithText("in sync").assertExists()
        shot("62-review-reveal")
        compose.onNodeWithText("Talk about", substring = true).performClick()
        settle()
        shot("63-review-debate")

        // Talk both over: Anna says keep, I tap keep.
        repeat(review(a).disputes.size) {
            val key = review(a).disputes.first()
            PlayStore.add(context, review(a).sayFacts(her, key, Call.PICK, now()))
            settle(4)
            compose.onNodeWithTag("debate-keep").performClick()
            settle(6)
        }
        check(review(a).disputes.isEmpty()) { "still to talk over: ${review(a).disputes}" }
        settle(10)
        compose.onNodeWithText("All sorted!").assertExists()
        shot("64-review-finale")
        holdToSeal()
        check(review(a).toSeal(me).isEmpty()) { "not sealed" }
        compose.onNodeWithTag("seal").assertExists()
        shot("65-review-sealed")
    }

    @Test
    fun blindDuelToTheSeal() {
        val a = album("duel")
        PlayStore.add(context, BattleRules.start(a.id, her, "duel", Mode.CLASSIC, listOf(a.keys[2]), now(), Random(4)))
        show(a) { room -> BattleScreen(room, "duel", onBack = {}) }
        settle()
        compose.onNodeWithTag("start-editing").assertExists()
        shot("66-battle-start")

        // Both edits locked in, as if made in the editor.
        battle(a, "duel").let { b -> PlayStore.add(context, b.editFact(me, 0, edit(0.6f), now()), b.editFact(her, 0, edit(-0.6f), now())) }
        awaitRenders("vote-0", "vote-1")
        shot("67-battle-vote")
        compose.onNodeWithTag("vote-0").performClick()
        settle(4)
        // Anna votes the same way, so there's no coin.
        battle(a, "duel").let { b -> PlayStore.add(context, b.pickFact(her, 0, b.pick(me, 0)!!, now())) }
        settle(30)
        check(battle(a, "duel").done)
        compose.onNodeWithText("Everyone voted for it").assertExists()
        shot("68-battle-result")
        holdToSeal()
        check(battle(a, "duel").sealedBy(me)) { "not sealed" }
        compose.onNodeWithTag("seal").assertExists()
        val winner = shots.first { it.key == a.keys[2] }.primary
        check(EditStore(context).versions(winner).list.size >= 2) { "the winning edit wasn't kept as a version" }
        shot("69-battle-sealed")
    }

    @Test
    fun splitVoteFlipsACoin() {
        val a = album("split")
        PlayStore.add(context, BattleRules.start(a.id, her, "split", Mode.CLASSIC, listOf(a.keys[3]), now(), Random(5)))
        battle(a, "split").let { b ->
            PlayStore.add(context, b.editFact(me, 0, edit(0.3f), now()), b.editFact(her, 0, edit(-0.3f), now()))
            PlayStore.add(context, b.pickFact(me, 0, me, now()), b.pickFact(her, 0, her, now()))
        }
        show(a) { room -> BattleScreen(room, "split", onBack = {}) }
        settle(6)
        compose.onNodeWithText("Split vote!").assertExists()
        shot("70-battle-coin")
        settle(90)
        compose.onNodeWithText("The coin has spoken").assertExists()
    }

    @Test
    fun newThemeBattle() {
        val a = album("new")
        var started by mutableStateOf<String?>(null)
        show(a) { room ->
            val id = started
            if (id == null) NewBattle(room, preset = null, onBack = {}, onStarted = { started = it })
            else BattleScreen(room, id, onBack = {})
        }
        settle()
        shot("71-battle-modes")
        compose.onNodeWithTag("mode-THEME").performClick()
        settle()
        compose.onNodeWithText("1 minute").performClick()
        compose.onNodeWithTag("pick-${a.keys[0]}").performClick()
        settle()
        shot("72-battle-pick")
        compose.onNodeWithTag("start-battle").performClick()
        settle(20)
        val b = battle(a, started!!)
        check(b.mode == Mode.THEME && b.rounds.single().twist.seconds == 60) { "${b.mode} ${b.rounds}" }
        compose.onNodeWithTag("theme-card").assertExists()
        compose.onNodeWithText("Start the clock").assertExists()
        shot("73-theme-card")
    }

    @Test
    fun copycatResult() {
        val a = album("copy")
        PlayStore.add(context, BattleRules.start(a.id, me, "cat", Mode.COPYCAT, listOf(a.keys[4]), now(), Random(6)))
        battle(a, "cat").let { b ->
            PlayStore.add(context, b.lookFact(me, edit(0.8f), now()), b.lookFact(her, edit(-0.8f), now()))
            PlayStore.add(context, b.copyFact(me, 91, edit(-0.7f), now()), b.copyFact(her, 74, edit(0.5f), now()))
        }
        show(a) { room -> BattleScreen(room, "cat", onBack = {}) }
        awaitRenders()
        settle(80)
        compose.onNodeWithText("Patrick copied best!").assertExists()
        shot("74-copycat-result")
    }

    @Test
    fun relayTurnsAndTheEnd() {
        val a = album("relay")
        PlayStore.add(context, BattleRules.start(a.id, me, "relay", Mode.RELAY, listOf(a.keys[5]), now(), Random(7)))
        show(a) { room -> BattleScreen(room, "relay", onBack = {}) }
        settle()
        val first = battle(a, "relay").holder(0)
        check(first == me) { "the starter goes first" }
        compose.onNodeWithText("Start editing").assertExists()
        shot("75-relay-first-leg")
        PlayStore.add(context, battle(a, "relay").legFact(me, 0, edit(0.3f), now()))
        settle(10)
        compose.onNodeWithText("Anna's turn: Color").assertExists()
        shot("76-relay-waiting")
        for (n in 1 until BattleRules.LEGS.size) {
            val b = battle(a, "relay")
            PlayStore.add(context, b.legFact(b.holder(n), n, edit(0.3f + n * 0.1f), now()))
        }
        awaitRenders()
        settle(40)
        check(compose.onAllNodesWithText("Made together").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Hold to seal").assertExists()
        shot("77-relay-done")
    }

    /** Press and hold the seal button until it's full, then let go. */
    private fun holdToSeal() {
        compose.onNodeWithTag("hold-to-seal").performTouchInput { down(center) }
        repeat(40) { step() }
        compose.onNodeWithTag("hold-to-seal").performTouchInput { up() }
        settle(20)
    }

    /** Steps until no "Developing…" spinner is left (and [tags] are there). */
    private fun awaitRenders(vararg tags: String, timeoutMs: Long = 120_000) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            step()
            val developing = compose.onAllNodesWithText("Developing…").fetchSemanticsNodes().isNotEmpty()
            if (!developing && tags.all { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }) break
        }
        settle()
    }

    private fun shot(name: String) {
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private fun step() {
        Thread.sleep(60)
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
    }

    private fun settle(n: Int = 8) = repeat(n) { step() }
}
