package dev.kogler.a7iiiconnect.develop

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lens
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kogler.a7iiiconnect.develop.engine.Curve
import dev.kogler.a7iiiconnect.develop.engine.DevelopSource
import dev.kogler.a7iiiconnect.develop.engine.Group
import dev.kogler.a7iiiconnect.develop.engine.Guide
import dev.kogler.a7iiiconnect.develop.engine.ImageSampler
import dev.kogler.a7iiiconnect.develop.engine.LinearImage
import dev.kogler.a7iiiconnect.develop.engine.Look
import dev.kogler.a7iiiconnect.develop.engine.MaskImage
import dev.kogler.a7iiiconnect.develop.engine.P
import dev.kogler.a7iiiconnect.develop.engine.Param
import dev.kogler.a7iiiconnect.develop.engine.Preset
import dev.kogler.a7iiiconnect.develop.engine.Profile
import dev.kogler.a7iiiconnect.develop.engine.Region
import dev.kogler.a7iiiconnect.develop.engine.Settings
import dev.kogler.a7iiiconnect.develop.engine.WhiteBalance
import dev.kogler.a7iiiconnect.develop.engine.autoTone
import dev.kogler.a7iiiconnect.develop.engine.histogram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The editor's panels. Each opens from its [Section] in the bottom bar; Auto is a button in the
 * Light panel. [groups] are what the panel edits, for the "changed" dot and per-panel compare.
 */
private enum class Tool(val label: String, val icon: ImageVector, val groups: List<Group>) {
    PRESETS("Presets", Icons.Outlined.Style, emptyList()),
    CROP("Crop", Icons.Outlined.Crop, listOf(Group.CROP)),
    LIGHT("Light", Icons.Outlined.WbSunny, listOf(Group.LIGHT, Group.CURVE)),
    COLOR("Color", Icons.Outlined.Palette, listOf(Group.COLOR, Group.MIXER, Group.GRADING)),
    EFFECTS("Effects", Icons.Outlined.BlurOn, listOf(Group.EFFECTS)),
    DETAIL("Detail", Icons.Outlined.Grain, listOf(Group.DETAIL)),
    OPTICS("Optics", Icons.Outlined.Lens, listOf(Group.OPTICS)),
    GEOMETRY("Geometry", Icons.Outlined.Transform, listOf(Group.GEOMETRY)),
    MASKS("Masks", Icons.Outlined.Layers, emptyList()),
    /** Opened from the top bar, not the strip. */
    VERSIONS("Versions", Icons.Outlined.History, emptyList()),
}

/**
 * The bottom bar: four big targets instead of nine small ones. A section with more than one
 * panel shows them as tabs along the top of the panel.
 */
private enum class Section(val label: String, val icon: ImageVector, val tools: List<Tool>) {
    LOOKS("Looks", Icons.Outlined.Style, listOf(Tool.PRESETS)),
    ADJUST("Adjust", Icons.Outlined.Tune, listOf(Tool.LIGHT, Tool.COLOR, Tool.EFFECTS, Tool.DETAIL)),
    FRAME("Frame", Icons.Outlined.Crop, listOf(Tool.CROP, Tool.GEOMETRY, Tool.OPTICS)),
    MASKS("Masks", Icons.Outlined.Layers, listOf(Tool.MASKS)),
}

private val Tool.section get() = Section.entries.firstOrNull { this in it.tools }

/** Whether [t]'s panel has changed anything (the dot on its tab). The profile is picked in Looks, so it marks Looks, not Light. */
private fun Tool.changed(s: Settings) = when (this) {
    Tool.MASKS -> s.masks.isNotEmpty()
    Tool.PRESETS -> s.profile != Profile.COLOR || s.lut != null
    else -> s.withoutProfile().let { plain -> groups.any { !plain.isDefault(it) } }
}

/** Checkpoints made before presets are capped; saved ones are kept until deleted. */
private const val MAX_AUTO_CHECKPOINTS = 10

/** Groups that move pixels around; a before/after split keeps them so both halves line up. */
private val FRAMING = setOf(Group.CROP, Group.GEOMETRY, Group.OPTICS)

/** The "before" side of the split: without [panel]'s edits, or without any look edits. */
private fun compareBase(s: Settings, panel: Tool?): Settings {
    if (panel == Tool.MASKS) return s.copy(masks = emptyList())
    val own = panel?.groups?.filter { it !in FRAMING }?.takeIf { it.isNotEmpty() }
    return (own ?: (Group.entries - FRAMING)).fold(if (own == null) s.copy(masks = emptyList()) else s) { acc, g -> acc.reset(g) }
}

/** Tools whose sliders are in swipe mode's menu; the menu's top-level ids are their names. */
private val SWIPE_TOOLS = listOf(Tool.LIGHT, Tool.COLOR, Tool.EFFECTS, Tool.DETAIL, Tool.OPTICS, Tool.GEOMETRY)
private val Tool.swipeId get() = name.lowercase()

/** Swipe mode's gesture callbacks: [onMove] gets the finger's motion in px and the photo's width. */
private class SwipeAdjust(
    val onStart: (horizontal: Boolean) -> Unit,
    /** The finger held still at ([u], [v]) of the photo: true if that targets something to drag. */
    val onTarget: (u: Float, v: Float) -> Boolean,
    /**
     * Two thumbs are about to start: true when the one that went down first should be the "left"
     * (holding) thumb wherever it is, as on a wheel or pad, rather than the one on the left.
     */
    val firstHolds: () -> Boolean,
    /** Two thumbs went down on opposite halves; the left (holding) one at ([u], [v]) of the photo (-1 off it). */
    val onThumbs: (u: Float, v: Float) -> Unit,
    /** The left thumb moved by (dx, dy) px to ([u], [v]) of the photo (-1 off it). */
    val onLeft: (dx: Float, dy: Float, u: Float, v: Float, height: Float) -> Unit,
    /** The left thumb held still at ([u], [v]) of the photo (-1 off it). */
    val onLeftHold: (u: Float, v: Float) -> Unit,
    /**
     * The right thumb went down at ([x], [y]) px of the photo area ([width] × [height]). It may lift
     * and land again while the left one holds.
     */
    val onRightDown: (x: Float, y: Float, width: Float, height: Float) -> Unit,
    /** That right thumb lifted while the left one still holds. */
    val onRightUp: () -> Unit,
    /** The right (moving) thumb moved by (dx, dy) px. */
    val onRight: (dx: Float, dy: Float, width: Float, height: Float) -> Unit,
    val onMove: (dx: Float, dy: Float, width: Float) -> Unit,
    val onEnd: () -> Unit,
)

/** Undo/redo history of one version's settings, kept by [EditStore.saveHistory]. */
private class History(saved: List<Settings>, start: Int) {
    constructor(initial: Settings) : this(listOf(initial), 0)

    val steps = saved.toMutableList()
    var at = start.coerceIn(0, steps.size - 1)
        private set
    var version by mutableIntStateOf(0)
        private set

    val canUndo get() = version >= 0 && at > 0
    val canRedo get() = version >= 0 && at < steps.size - 1

    fun push(s: Settings) {
        if (s == steps[at]) return
        while (steps.size > at + 1) steps.removeAt(steps.size - 1)
        steps += s
        at++
        version++
    }

    fun undo(): Settings { if (at > 0) at--; version++; return steps[at] }
    fun redo(): Settings { if (at < steps.size - 1) at++; version++; return steps[at] }
}

/** What a preview render depends on; [subject] counts arrivals of the subject map. */
private data class RenderKey(val settings: Settings, val dragging: Boolean, val crop: Boolean, val subject: Int)

private class Rendered(val layer: PhotoLayer, val histogram: Array<IntArray>?, val settings: Settings, val cropMode: Boolean)

/** What the photo area draws: a rendered bitmap, or the GPU preview's current frame. */
internal sealed interface PhotoLayer {
    val width: Int
    val height: Int
}

internal class BitmapLayer(val image: ImageBitmap) : PhotoLayer {
    override val width get() = image.width
    override val height get() = image.height
}

/** [version] changes with every frame so Compose redraws. */
internal class GpuLayer(val gpu: GpuPreview, override val width: Int, override val height: Int, val version: Int) : PhotoLayer

internal fun DrawScope.drawLayer(layer: PhotoLayer, r: Rect) {
    when (layer) {
        is BitmapLayer -> drawImage(
            layer.image, dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
            dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()), filterQuality = FilterQuality.Medium,
        )
        is GpuLayer -> drawIntoCanvas { layer.gpu.draw(it.nativeCanvas, r.left, r.top, r.width, r.height) }
    }
}

/** A sharp full-resolution render of part of the photo, placed by its fraction of the frame. */
private class Tile(val image: ImageBitmap, val area: Rect, val settings: Settings)

/** The visible part of the photo (fractions of the frame) and its size on screen in pixels. */
private data class Viewport(val area: Rect, val screenWidth: Float)

@Composable
fun EditorScreen(
    photos: List<LibraryPhoto>,
    startIndex: Int,
    store: EditStore,
    onBack: () -> Unit,
    onPhotoChanged: (LibraryPhoto) -> Unit,
    /** Start an edit battle over the photo in a shared album it's in; null hides the option. */
    onBattle: ((LibraryPhoto) -> Unit)? = null,
    /** Whether [onBattle] applies to this photo: only shots in a shared album can be battled. */
    canBattle: (LibraryPhoto) -> Boolean = { true },
    /** Put the photo's shot in an album; null hides the option. */
    onAddToAlbum: ((LibraryPhoto) -> Unit)? = null,
    /** Only these panels (by [Tool] name), for an edit battle's Limited tools; null for all. */
    tools: Set<String>? = null,
    /** Shown under the top bar: an edit battle's theme, clock or target. */
    header: (@Composable () -> Unit)? = null,
) {
    val allowed = { t: Tool -> tools == null || t.name in tools || t == Tool.VERSIONS }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(startIndex) }
    val photo = photos[index]

    var opened by remember { mutableStateOf<OpenedPhoto?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var placeholder by remember { mutableStateOf<ImageBitmap?>(null) }
    var settings by remember { mutableStateOf(Settings()) }
    var history by remember { mutableStateOf(History(Settings())) }
    var dragging by remember { mutableStateOf(false) }
    var rendered by remember { mutableStateOf<Rendered?>(null) }
    var before by remember { mutableStateOf<ImageBitmap?>(null) }
    var showBefore by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf<Tool?>(Tool.LIGHT.takeIf(allowed) ?: Tool.entries.firstOrNull { it != Tool.VERSIONS && allowed(it) }) }
    // The tab last open in each section, so the bottom bar goes back to it.
    val lastTool = remember { mutableMapOf<Section, Tool>() }
    var menu by remember { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var exportProgress by remember { mutableStateOf<Float?>(null) }
    var viewport by remember { mutableStateOf<Viewport?>(null) }
    var tile by remember { mutableStateOf<Tile?>(null) }
    var presets by remember { mutableStateOf(store.userPresets()) }
    var versions by remember { mutableStateOf(Versions(listOf(Version(0, "Version 1")), 0)) }
    var checkpoints by remember { mutableStateOf(emptyList<Checkpoint>()) }

    // This version's undo history from last time, carried on to the edit as it is now.
    fun loadHistory(): History {
        checkpoints = store.checkpoints(photo, versions.active)
        val (steps, at) = store.history(photo, versions.active) ?: return History(settings)
        return History(steps, at).also { it.push(settings) }
    }
    fun saveHistory() = store.saveHistory(photo, versions.active, history.steps, history.at)
    var split by remember { mutableStateOf(false) }
    var splitAt by remember { mutableFloatStateOf(0.5f) }
    var splitPanelOnly by remember { mutableStateOf(false) }
    var compareImage by remember { mutableStateOf<ImageBitmap?>(null) }
    // Swipe mode's menu holds every panel, so it's off while only some panels are allowed.
    var swipeMode by remember { mutableStateOf(store.swipeAdjust && tools == null) }
    // Where swipe mode is in its menu (see SwipeMenu.kt); the panel's chips follow it and set it.
    var swipePath by remember { mutableStateOf(listOf("light", P.EXPOSURE.key)) }
    val swipeNav = remember { SwipeNav() }
    var lightCurve by remember { mutableStateOf(false) }
    var colorMode by remember { mutableIntStateOf(0) }
    var mixBand by remember { mutableIntStateOf(0) }
    var gradeZone by remember { mutableIntStateOf(0) }
    var curveChannel by remember { mutableIntStateOf(-1) }
    // Swipe mode's curve rows: the input of the point last grabbed per channel, and the one being dragged.
    val curveGrab = remember { FloatArray(4) { -1f } }
    var curvePoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    // The dragged curve point is pushed past the top or bottom: letting go removes it.
    var curveRemoving by remember { mutableStateOf(false) }
    // A small render with the curves off, so a long-press can read the tone or color under the finger.
    var probe by remember { mutableStateOf<Probe?>(null) }
    var swipeAxis by remember { mutableIntStateOf(0) }
    val swipeState = remember { SwipeState() }
    // The up/down or 2D edit just let go of: the next drag picks it up again, like lifting a finger on a trackpad.
    var clutch by remember { mutableStateOf<Clutch?>(null) }
    LaunchedEffect(clutch) { if (clutch != null) { delay(CLUTCH_MS); clutch = null } }
    // Swipe mode's gesture hint shows for a while after entering swipe mode or picking another row.
    var hintPing by remember { mutableIntStateOf(0) }
    var hintShown by remember { mutableStateOf(false) }
    LaunchedEffect(hintPing, swipeMode) {
        hintShown = swipeMode
        delay(HINT_MS)
        hintShown = false
    }
    // Saved, so full screen survives anything that rebuilds the screen.
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // Full screen's controls: hidden until the photo is tapped, then hidden again after a while.
    var chrome by remember { mutableStateOf(false) }
    var chromePing by remember { mutableIntStateOf(0) }
    LaunchedEffect(fullscreen, chrome, chromePing) {
        if (fullscreen && chrome) { delay(3500); chrome = false }
    }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val snackbar = remember { SnackbarHostState() }
    val guides = remember { GuideCache() }
    val cropMode = tool == Tool.CROP
    var maskIndex by remember { mutableIntStateOf(0) }
    var maskOverlay by remember { mutableStateOf(false) }
    var subjectStatus by remember { mutableStateOf<SubjectStatus>(SubjectStatus.Ready) }
    // Bumped when the subject map arrives, so the photo re-renders with it.
    var subjectVersion by remember { mutableIntStateOf(0) }
    var subjectTry by remember { mutableIntStateOf(0) }
    var skyStatus by remember { mutableStateOf<SubjectStatus>(SubjectStatus.Ready) }
    var skyTry by remember { mutableIntStateOf(0) }

    // Open the photo: show the camera's embedded JPEG at once, then the real render.
    LaunchedEffect(photo.id) {
        opened = null; rendered = null; before = null; compareImage = null; loadError = null; tile = null
        maskIndex = 0; subjectStatus = SubjectStatus.Ready; skyStatus = SubjectStatus.Ready
        guides.clear()
        versions = store.versions(photo)
        settings = store.load(photo)
        history = loadHistory()
        placeholder = Photos.cachedThumbnail(photo)?.asImageBitmap()
        if (photo.isRaw) {
            withContext(Dispatchers.IO) { runCatching { Photos.embeddedPreview(context, photo.uri, 1600) }.getOrNull() }
                ?.let { placeholder = it.asImageBitmap() }
        }
        val result = withContext(Dispatchers.Default) { runCatching { Photos.open(context, photo) } }
        result.onSuccess { opened = it }.onFailure { loadError = it.message ?: it.javaClass.simpleName }
    }

    // Subject masks need the photo's subject: find it once (the store keeps it) when one is used.
    val needsSubject = settings.masks.any { it.usesSubject }
    LaunchedEffect(opened, needsSubject, subjectTry) {
        val o = opened ?: return@LaunchedEffect
        if (!needsSubject || o.preview.maps.subject != null) return@LaunchedEffect
        subjectStatus = SubjectStatus.Finding
        val result = withContext(Dispatchers.IO) { runCatching { SubjectFinder.find(context, store, o) } }
        subjectStatus = result.exceptionOrNull()?.let { SubjectStatus.Failed(it.message ?: it.javaClass.simpleName) } ?: SubjectStatus.Ready
        if (result.isSuccess) { tile = null; subjectVersion++ }
    }
    // Sky masks likewise need the photo's sky.
    val needsSky = settings.masks.any { it.usesSky }
    LaunchedEffect(opened, needsSky, skyTry) {
        val o = opened ?: return@LaunchedEffect
        if (!needsSky || o.preview.maps.sky != null) return@LaunchedEffect
        skyStatus = SubjectStatus.Finding
        val result = withContext(Dispatchers.Default) { runCatching { SkyFinder.find(store, o) } }
        skyStatus = result.exceptionOrNull()?.let { SubjectStatus.Failed(it.message ?: it.javaClass.simpleName) } ?: SubjectStatus.Ready
        if (result.isSuccess) { tile = null; subjectVersion++ }
    }

    // Re-render on every change. The GPU runs the develop shader on the window's canvas, so only
    // geometry and noise reduction changes touch the CPU. A software-drawn window can't draw
    // shaders, so it gets offscreen renders instead, from the smaller draft while a slider moves.
    // The crop tool shows the whole frame so the crop box can be moved over it.
    val hardware = LocalView.current.isHardwareAccelerated
    LaunchedEffect(opened) {
        val o = opened ?: return@LaunchedEffect
        val gpu = if (hardware && gpuPreviewEnabled) runCatching { GpuPreview() }.getOrNull() else null
        val pool = BitmapPool()
        val scenes = if (gpu != null) SceneCache() else null
        var version = 0
        snapshotFlow { RenderKey(settings, dragging, tool == Tool.CROP, subjectVersion) }.collectLatest { (s, d, crop) ->
            if (gpu != null && scenes != null) {
                val frame = withContext(Dispatchers.Default) { scenes.frame(o, s, d, crop, guides) }
                gpu.set(frame)
                val layer = GpuLayer(gpu, frame.scene.width, frame.scene.height, ++version)
                rendered = Rendered(layer, rendered?.histogram, s, crop)
                renderCount++
                if (!d) {
                    delay(120)
                    val h = withContext(Dispatchers.Default) { histogramOf(o, s, crop, guides) }
                    rendered = Rendered(layer, h, s, crop)
                }
            } else {
                val src = if (d) o.draft else o.preview
                rendered = withContext(Dispatchers.Default) { pool.render(o, src, s, crop, guides) }
            }
        }
    }

    LaunchedEffect(showBefore, opened) {
        val o = opened ?: return@LaunchedEffect
        if (showBefore && before == null) {
            before = withContext(Dispatchers.Default) { (BitmapPool().render(o, o.preview, Settings(), false, GuideCache()).layer as BitmapLayer).image }
        }
    }

    // Swipe mode's probe: re-rendered (small, offscreen) once the settings settle.
    LaunchedEffect(opened, swipeMode) {
        val o = opened ?: return@LaunchedEffect
        if (!swipeMode) { probe = null; return@LaunchedEffect }
        snapshotFlow { if (dragging) null else settings.copy(curves = List(4) { Curve() }) }
            .filterNotNull().distinctUntilChanged().collectLatest { s ->
                delay(300)
                probe = withContext(Dispatchers.Default) { runCatching { probeOf(o, s, guides) }.getOrNull() }
            }
    }

    // Split view: render the "before" half offscreen whenever it would look different.
    val compareGuides = remember { GuideCache() }
    LaunchedEffect(split, opened) {
        val o = opened ?: return@LaunchedEffect
        if (!split) { compareImage = null; return@LaunchedEffect }
        val pool = BitmapPool()
        compareGuides.clear()
        snapshotFlow { if (dragging) null else compareBase(settings, if (splitPanelOnly) tool else null) }
            .filterNotNull().distinctUntilChanged().collectLatest { base ->
                compareImage = withContext(Dispatchers.Default) { (pool.render(o, o.preview, base, false, compareGuides).layer as BitmapLayer).image }
            }
    }

    // Sharp zoom: once the view settles while zoomed in, render the visible part from the raw.
    LaunchedEffect(viewport, rendered, dragging) {
        val o = opened ?: return@LaunchedEffect
        val r = rendered ?: return@LaunchedEffect
        val v = viewport
        if (v == null || dragging || r.cropMode || o.raw == null) { tile = null; return@LaunchedEffect }
        if (tile?.let { it.settings == r.settings && it.area == v.area } == true) return@LaunchedEffect
        delay(250)
        val s = r.settings
        tile = withContext(Dispatchers.Default) {
            runCatching {
                val geom = o.geometry(s)
                val fullW = v.area.width * geom.outW
                val scale = minOf(1.0, v.screenWidth / fullW)
                val previewScale = r.layer.width / geom.outW
                if (scale < previewScale * 1.2) return@runCatching null
                val fw = geom.outW * scale; val fh = geom.outH * scale
                val x0 = (v.area.left * fw).toInt(); val y0 = (v.area.top * fh).toInt()
                val w = ((v.area.right * fw).toInt() - x0).coerceIn(1, 3000)
                val h = ((v.area.bottom * fh).toInt() - y0).coerceIn(1, 3000)
                val guide = guides.get(o, Look(o.preview, s), s, false)
                val bmp = Photos.renderTile(o, s, guide, Region(scale, x0, y0, w, h)) ?: return@runCatching null
                val fwi = fw.toInt().toFloat(); val fhi = fh.toInt().toFloat()
                Tile(bmp.asImageBitmap(), Rect(x0 / fwi, y0 / fhi, (x0 + w) / fwi, (y0 + h) / fhi), s)
            }.getOrNull()
        }
    }

    fun commit(s: Settings) {
        settings = s
        history.push(s)
        store.save(photo, s)
    }

    // Undo history is written once the edits pause (and on leaving the photo or version).
    LaunchedEffect(photo.id, history) {
        snapshotFlow { history.version }.drop(1).collectLatest {
            delay(1500)
            withContext(Dispatchers.IO) { saveHistory() }
        }
    }

    fun switchVersion(change: () -> Unit) {
        store.save(photo, settings)
        saveHistory()
        change()
        versions = store.versions(photo)
        settings = store.load(photo)
        history = loadHistory()
        tile = null
    }

    fun addCheckpoint(name: String, auto: Boolean) {
        val autos = checkpoints.filter { it.auto }
        val kept = if (auto && autos.size >= MAX_AUTO_CHECKPOINTS) checkpoints - autos.last() else checkpoints
        checkpoints = listOf(Checkpoint(name, System.currentTimeMillis(), settings, auto)) + kept
        store.saveCheckpoints(photo, versions.active, checkpoints)
    }

    fun undo() { settings = history.undo(); store.save(photo, settings) }
    fun redo() { settings = history.redo(); store.save(photo, settings) }

    fun auto() {
        val src = opened?.preview ?: return
        scope.launch {
            val a = withContext(Dispatchers.Default) { autoTone(src, settings) }
            commit(a)
            val r = snackbar.showSnackbar("Auto applied", "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed && settings == a) undo()
        }
    }

    // Full screen: just the photo (with swipe mode, edits still work), system bars hidden too.
    // Keyed on the orientation too, so the bars stay hidden after the phone turns.
    val view = LocalView.current
    val orientation = LocalConfiguration.current.orientation
    DisposableEffect(fullscreen, orientation) {
        val window = view.context.findActivity()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler(enabled = fullscreen) { fullscreen = false }

    // Swipe mode (Snapseed): up/down on the photo opens the menu and picks a row, sliding right
    // opens a folder and left goes back; left/right then changes the picked row. Holding still
    // targets what's under the finger: a curve point, a mixer band, a grading zone.
    val menuTree = swipeTree(
        settings,
        opened?.preview?.asShot?.let { it.first.toFloat() to it.second.toFloat() } ?: (5500f to 0f),
        isRaw = opened?.raw != null, hasLensProfile = opened?.preview?.lens != null,
    )
    fun navigate(path: List<String>) {
        if (path != swipePath) hintPing++
        swipePath = path
        swipeNav.remember(path, menuTree)
        // The open panel follows the menu: its tool, then its chips.
        val t = SWIPE_TOOLS.firstOrNull { it.swipeId == path[0] } ?: return
        if (tool != null && tool in SWIPE_TOOLS && tool != t) { tool = t; t.section?.let { lastTool[it] = t } }
        when (t) {
            Tool.LIGHT -> {
                lightCurve = path.getOrNull(1) == "curve"
                if (lightCurve) path.getOrNull(2)?.let { curveChannel = CURVE_IDS.indexOf(it) }
            }
            Tool.COLOR -> when (path.getOrNull(1)) {
                "mix" -> {
                    colorMode = 1
                    P.MIX_BANDS.indexOfFirst { it.first == path.getOrNull(2) }.takeIf { it >= 0 }?.let { mixBand = it }
                }
                "grading" -> {
                    colorMode = 2
                    val row = path.getOrNull(2)
                    P.GRADE_ZONES.indexOfFirst { row == it || row == "grade.$it.lum" }.takeIf { it >= 0 }?.let { gradeZone = it }
                }
                null -> {}
                else -> colorMode = 0
            }
            else -> {}
        }
    }
    fun selectTool(t: Tool?) {
        tool = t
        t?.section?.let { lastTool[it] = t }
        if (t != null && t in SWIPE_TOOLS) navigate(swipeNav.descend(menuTree, listOf(t.swipeId)))
    }
    val swipeRowStep = with(density) { 36.dp.toPx() }
    val swipeDrill = with(density) { 40.dp.toPx() }

    /** The probe's color (0..1 RGB) at ([u], [v]) of the photo, or null before it's rendered. */
    fun probeAt(u: Float, v: Float): FloatArray? {
        val p = probe ?: return null
        val c = p.pixels[(v * p.height).toInt().coerceIn(0, p.height - 1) * p.width + (u * p.width).toInt().coerceIn(0, p.width - 1)]
        return floatArrayOf(((c shr 16) and 0xff) / 255f, ((c shr 8) and 0xff) / 255f, (c and 0xff) / 255f)
    }
    fun menuReset() {
        swipeState.acc = 0f
        swipeState.sideways = 0f
        swipeState.startIndex = swipeNav.folderAt(menuTree, swipePath.dropLast(1)).children.indexOfFirst { it.id == swipePath.last() }.coerceAtLeast(0)
    }
    fun startCurve(channel: Int, x: Float) {
        val (pts, i) = grabCurvePoint(settings.curves[channel], x)
        swipeState.mode = SwipeMode.CURVE
        swipeState.curve = pts
        swipeState.curveSel = i
        swipeState.curveY = Float.NaN
        curvePoint = pts.getOrNull(i)
        if (i >= 0) curveGrab[channel] = pts[i].first
    }
    /** Starts changing the row at [path]: sideways, or up/down when [vertical]. */
    fun startRow(path: List<String>, vertical: Boolean) {
        swipeState.vertical = vertical
        when (val node = swipeNav.node(menuTree, path)) {
            is SwipeLeaf -> {
                swipeState.mode = SwipeMode.SLIDER
                swipeState.detent = Detent(node.min, node.max, node.default).apply { start(node.get(settings)) }
            }
            is Swipe2D -> {
                swipeState.mode = SwipeMode.PAD
                swipeState.pad = node.xy(settings)
            }
            is SwipeCurve -> startCurve(node.channel, curveGrab[node.channel].takeIf { it >= 0f } ?: 0.5f)
            else -> swipeState.mode = SwipeMode.NONE
        }
    }
    /** Moves through the menu: up/down picks a row, sideways opens a folder (right) or goes back (left). */
    fun menuMove(dx: Float, dy: Float) {
        val folderPath = swipePath.dropLast(1)
        val rows = swipeNav.folderAt(menuTree, folderPath).children
        swipeState.acc += dy
        swipeState.sideways += dx
        val i = (swipeState.startIndex + (swipeState.acc / swipeRowStep).toInt()).coerceIn(0, rows.size - 1)
        if (rows[i].id != swipePath.last()) {
            navigate(folderPath + rows[i].id)
            swipeState.sideways = 0f
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        val into = swipeState.sideways > swipeDrill && swipeNav.node(menuTree, swipePath) is SwipeFolder
        val out = swipeState.sideways < -swipeDrill && swipePath.size > 1
        if (into || out) {
            val next = if (into) swipeNav.into(menuTree, swipePath) else folderPath
            navigate(next)
            menuReset()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    /** Removes the point pushed off the curve (the photo already shows the curve without it). */
    fun dropRemovedPoint() {
        val pts = swipeState.curve
        val i = swipeState.curveSel
        if (curveRemoving && pts != null && i in pts.indices) {
            val x = pts.removeAt(i).first
            // A later sideways swipe would otherwise add it back.
            for (c in curveGrab.indices) if (curveGrab[c] == x) curveGrab[c] = -1f
            swipeState.curveSel = (i - 1).coerceAtLeast(0)
            curvePoint = pts.getOrNull(swipeState.curveSel)
        }
        curveRemoving = false
        swipeState.curveY = Float.NaN
    }
    /**
     * Moves the selected point of [channel]'s curve by [dx] across (between its neighbours) and
     * [dy] up. Pushed well past the top or bottom, a middle point turns into "release to remove",
     * like dragging it off the graph in the panel.
     */
    fun moveCurvePoint(channel: Int, dx: Float, dy: Float) {
        val pts = swipeState.curve ?: return
        val i = swipeState.curveSel
        if (i !in pts.indices) return
        if (swipeState.curveY.isNaN()) swipeState.curveY = pts[i].second
        swipeState.curveY += dy
        val y = swipeState.curveY
        val out = i != 0 && i != pts.size - 1 && (y < -CURVE_REMOVE || y > 1 + CURVE_REMOVE)
        if (out != curveRemoving) {
            curveRemoving = out
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        val lo = if (i == 0) 0f else pts[i - 1].first + 0.01f
        val hi = if (i == pts.size - 1) 1f else pts[i + 1].first - 0.01f
        pts[i] = (pts[i].first + dx).coerceIn(lo, maxOf(lo, hi)) to y.coerceIn(0f, 1f)
        curveGrab[channel] = pts[i].first
        curvePoint = pts[i]
        dragging = true
        settings = settings.withCurve(channel, Curve(if (out) pts.filterIndexed { j, _ -> j != i } else pts.toList()))
    }
    /**
     * Two-thumb mode on a curve, with the left thumb holding: the right thumb landed at ([x], [y]) px
     * of a [width] × [height] photo area. Like the panel's graph (drawn big over the photo), it grabs
     * the point under it or adds one on the curve there. Landing away from both carries on with the
     * point moved last, so the thumb can lift and land again where there's room, as on a trackpad.
     */
    fun grabOverlayPoint(channel: Int, x: Float, y: Float, width: Float, height: Float) {
        dropRemovedPoint()
        val r = curveOverlayRect(width, height)
        val pts = settings.curves[channel].points.sortedBy { it.first }.toMutableList()
        val reach = with(density) { 36.dp.toPx() }
        fun dist(i: Int) = hypot(r.left + pts[i].first * r.width - x, r.top + (1 - pts[i].second) * r.height - y)
        var i = pts.indices.minByOrNull { dist(it) }?.takeIf { dist(it) < reach } ?: -1
        var added = false
        val u = (x - r.left) / r.width
        val onLine = u in 0f..1f && settings.curves[channel].table(1024).let { t ->
            abs(r.top + (1 - t[(u * 1023).roundToInt()]) * r.height - y) < reach
        }
        if (i < 0 && !onLine) i = pts.indexOfFirst { it.first == swipeState.thumbPoint }
        if (i < 0) {
            if (onLine) {
                val (grabbed, j) = grabCurvePoint(settings.curves[channel], u)
                if (j >= 0) { pts.clear(); pts += grabbed; i = j; added = grabbed.size > settings.curves[channel].points.size }
            }
        }
        swipeState.curve = pts
        swipeState.curveSel = i
        swipeState.curveY = Float.NaN
        curvePoint = pts.getOrNull(i)
        if (i < 0) return
        curveGrab[channel] = pts[i].first
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (added) {
            dragging = true
            settings = settings.withCurve(channel, Curve(pts.toList()))
        }
    }
    /** Picks the edit just let go of up again where it was. */
    fun resume(c: Clutch) {
        startRow(c.path, c.vertical)
        clutch = null
        chrome = false
        swipeAxis = 4
    }
    // Kept while the original shows: a held finger shows it, and a second thumb then starts two-thumb editing.
    val swipe = if (swipeMode && tool != Tool.CROP && tool != Tool.MASKS && opened != null) SwipeAdjust(
        onStart = { horizontal ->
            chrome = false
            // A 2D edit carries on whichever way the drag starts, an up/down one when it starts up or down.
            val c = clutch?.takeIf { it.path == swipePath && (it.twoD || !horizontal) }
            clutch = null
            if (c != null) resume(c)
            else if (horizontal) {
                val path = swipeNav.descend(menuTree, swipePath)
                if (path != swipePath) navigate(path)
                startRow(path, vertical = false)
                swipeAxis = 1
            } else {
                val path = swipeNav.resolve(menuTree, swipePath)
                if (path != swipePath) navigate(path)
                swipeState.mode = SwipeMode.MENU
                swipeState.acc = 0f
                swipeState.sideways = 0f
                swipeState.startIndex = swipeNav.folderAt(menuTree, path.dropLast(1)).children.indexOfFirst { it.id == path.last() }.coerceAtLeast(0)
                swipeAxis = 2
            }
        },
        onTarget = target@{ u, v ->
            // Resting the finger before carrying on keeps the same edit rather than targeting anew.
            val c = clutch?.takeIf { it.path == swipePath }
            if (c != null) { resume(c); return@target true }
            val rgb = probeAt(u, v)
            val path = swipeNav.resolve(menuTree, swipePath)
            val lum = rgb?.let { 0.2126f * it[0] + 0.7152f * it[1] + 0.0722f * it[2] } ?: 0f
            val targeted = when {
                rgb == null -> false
                // Tone curve: grab (or add) the point for this pixel's tone, then drag it up or down.
                path[0] == "light" && path.getOrNull(1) == "curve" -> {
                    val ch = (swipeNav.node(menuTree, path) as? SwipeCurve)?.channel ?: 0
                    navigate(listOf("light", "curve", CURVE_IDS[ch]))
                    startCurve(ch, if (ch == 0) lum else rgb[ch - 1])
                    swipeState.vertical = true
                    swipeState.curveSel >= 0
                }
                // Mixer: the band of this pixel's color, then up/down changes its hue, saturation or luminance.
                path[0] == "color" && path.getOrNull(1) == "mix" && settings.profile != Profile.MONOCHROME -> {
                    val hsv = FloatArray(3)
                    android.graphics.Color.RGBToHSV((rgb[0] * 255).toInt(), (rgb[1] * 255).toInt(), (rgb[2] * 255).toInt(), hsv)
                    val p = swipeNav.descend(menuTree, listOf("color", "mix", P.MIX_BANDS[bandOf(hsv[0])].first))
                    navigate(p)
                    startRow(p, vertical = true)
                    true
                }
                // A wheel or the white balance pad picked in the menu: holding shows that one, then this
                // finger (or a second thumb) moves the dot.
                swipeNav.node(menuTree, path) is Swipe2D -> {
                    navigate(path)
                    startRow(path, vertical = false)
                    true
                }
                // Grading on another row: the zone of this pixel's brightness, then drag its wheel.
                path[0] == "color" && path.getOrNull(1) == "grading" -> {
                    val p = listOf("color", "grading", P.GRADE_ZONES[zoneOf(lum)])
                    navigate(p)
                    startRow(p, vertical = false)
                    true
                }
                else -> false
            }
            if (targeted) {
                chrome = false
                swipeAxis = 4
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            targeted
        },
        firstHolds = {
            val path = swipeNav.resolve(menuTree, swipePath)
            val n = swipeNav.node(menuTree, path)
            n is Swipe2D || n is SwipeCurve || (path[0] == "color" && path.getOrNull(1) == "grading")
        },
        onThumbs = { u, v ->
            // Two thumbs: where the left one lands picks the target (a tone for the curve, a color
            // for Mix, a tone for Grading unless a wheel is already picked); then it moves through the menu (or aims the curve), and
            // the right one changes the value. On a wheel or pad the first thumb holds it up, the
            // other moves its dot.
            chrome = false
            swipeState.mode = SwipeMode.THUMBS
            swipeState.thumbDetent = null
            swipeState.pad = null
            swipeState.curve = null
            swipeState.thumbPoint = -1f
            val path = swipeNav.resolve(menuTree, swipePath)
            val rgb = if (u >= 0f) probeAt(u, v) else null
            when {
                path[0] == "light" && path.getOrNull(1) == "curve" -> {
                    val ch = (swipeNav.node(menuTree, path) as? SwipeCurve)?.channel ?: 0
                    // The left thumb holds the curve up, drawn big over the photo; the right one edits it.
                    navigate(listOf("light", "curve", CURVE_IDS[ch]))
                    swipeState.curveSel = -1
                    curvePoint = null
                }
                rgb != null && path[0] == "color" && path.getOrNull(1) == "mix" && settings.profile != Profile.MONOCHROME -> {
                    val hsv = FloatArray(3)
                    android.graphics.Color.RGBToHSV((rgb[0] * 255).toInt(), (rgb[1] * 255).toInt(), (rgb[2] * 255).toInt(), hsv)
                    navigate(swipeNav.descend(menuTree, listOf("color", "mix", P.MIX_BANDS[bandOf(hsv[0])].first)))
                }
                // A wheel picked in the menu stays; only from another Grading row does the tone pick the zone.
                rgb != null && path[0] == "color" && path.getOrNull(1) == "grading" && swipeNav.node(menuTree, path) !is SwipeWheel ->
                    navigate(listOf("color", "grading", P.GRADE_ZONES[zoneOf(0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2])]))
                path != swipePath -> navigate(path)
            }
            menuReset()
            swipeAxis = 6
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        },
        onLeft = { dx, dy, _, _, _ ->
            // On a wheel, pad or curve the left thumb just holds it up; sliding it moves to another row.
            menuMove(dx, dy)
        },
        onLeftHold = { _, _ -> },
        onRightDown = { x, y, width, height ->
            val node = swipeNav.node(menuTree, swipePath)
            if (node is SwipeCurve) grabOverlayPoint(node.channel, x, y, width, height)
        },
        onRightUp = {
            val removed = curveRemoving
            dropRemovedPoint()
            swipeState.thumbPoint = if (removed) -1f else curvePoint?.first ?: -1f
            if (dragging) { dragging = false; commit(settings) }
            swipeState.curveSel = -1
            curvePoint = null
        },
        onRight = { dx, dy, width, height ->
            val up = if (height > 0f) -dy / height else 0f
            when (val node = swipeNav.node(menuTree, swipePath)) {
                is SwipeLeaf -> {
                    // A new detent whenever the left thumb has moved to another row.
                    val d = swipeState.thumbDetent?.takeIf { swipeState.thumbRow == node.id }
                        ?: Detent(node.min, node.max, node.default).apply { start(node.get(settings)) }.also {
                            swipeState.thumbDetent = it
                            swipeState.thumbRow = node.id
                        }
                    // The photo's height is half the slider's range.
                    val next = d.move(up * (node.max - node.min) / 2)
                    if (next != node.get(settings)) {
                        if (next == node.default) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        dragging = true
                        settings = node.set(settings, next)
                    }
                }
                is Swipe2D -> {
                    // The right thumb moves the dot in 2D, the photo's width spanning the wheel or pad.
                    val start = swipeState.pad?.takeIf { swipeState.thumbRow == node.id } ?: node.xy(settings)
                    swipeState.thumbRow = node.id
                    if (width > 0f) {
                        val (x, y) = pad2D(node, start.first + dx / width * 2, start.second - dy / width * 2)
                        swipeState.pad = x to y
                        val next = node.moveTo(settings, x, y)
                        if (next != settings) {
                            if (node.neutral(next) && !node.neutral(settings)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            dragging = true
                            settings = next
                        }
                    }
                }
                is SwipeCurve -> {
                    // The right thumb moves its point by its own motion over the big curve, as in the panel.
                    val r = curveOverlayRect(width, height)
                    if (r.width > 0f) moveCurvePoint(node.channel, dx / r.width, -dy / r.height)
                }
                else -> {}
            }
        },
        onMove = { dx, dy, width ->
            val node = swipeNav.node(menuTree, swipePath)
            when (swipeState.mode) {
                SwipeMode.MENU -> menuMove(dx, dy)
                SwipeMode.PAD -> {
                    // The dot follows the finger, the photo's width spanning the wheel or pad.
                    val start = swipeState.pad
                    if (node is Swipe2D && start != null && width > 0f) {
                        val (x, y) = pad2D(node, start.first + dx / width * 2, start.second - dy / width * 2)
                        swipeState.pad = x to y
                        val next = node.moveTo(settings, x, y)
                        if (next != settings) {
                            if (node.neutral(next) && !node.neutral(settings)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            dragging = true
                            settings = next
                        }
                    }
                }
                SwipeMode.CURVE -> {
                    if (node is SwipeCurve && width > 0f) moveCurvePoint(node.channel, 0f, (if (swipeState.vertical) -dy else dx) / width)
                }
                SwipeMode.SLIDER -> {
                    val d = swipeState.detent
                    if (node is SwipeLeaf && d != null && width > 0f) {
                        // The photo's width is half the slider's range.
                        val next = d.move((if (swipeState.vertical) -dy else dx) / width * (node.max - node.min) / 2)
                        if (next != node.get(settings)) {
                            if (next == node.default) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            dragging = true
                            settings = node.set(settings, next)
                        }
                    }
                }
                SwipeMode.NONE, SwipeMode.THUMBS -> {}
            }
        },
        onEnd = {
            // A sideways edit already carries on with the next sideways drag; up/down and 2D edits
            // would otherwise turn into the menu, so the next drag keeps them going for a moment.
            val held = swipeState.mode == SwipeMode.PAD || (swipeState.vertical && swipeState.mode == SwipeMode.CURVE && swipeState.curveSel >= 0) ||
                (swipeState.vertical && swipeState.mode == SwipeMode.SLIDER)
            clutch = if (held && !curveRemoving) Clutch(swipePath, swipeState.vertical, swipeState.mode == SwipeMode.PAD) else null
            dropRemovedPoint()
            if (dragging) { dragging = false; commit(settings) }
            // Letting go on a folder picks its remembered (or first) row.
            if ((swipeState.mode == SwipeMode.MENU || swipeState.mode == SwipeMode.THUMBS) && swipeNav.node(menuTree, swipePath) is SwipeFolder) navigate(swipeNav.descend(menuTree, swipePath))
            swipeState.mode = SwipeMode.NONE
            curvePoint = null
            swipeAxis = 0
        },
    ) else null

    fun go(i: Int) {
        if (i == index || i !in photos.indices) return
        store.save(photo, settings)
        saveHistory()
        index = i
        onPhotoChanged(photos[i])
    }

    fun export(share: Boolean) {
        val o = opened ?: return
        val s = settings
        val options = store.exportOptions
        exportProgress = 0f
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    if (s.masks.any { it.usesSubject }) SubjectFinder.find(context, store, o)
                    if (s.masks.any { it.usesSky }) SkyFinder.find(store, o)
                    Photos.export(context, o, s, options, versions.active) { p -> exportProgress = p }
                }
            }
            exportProgress = null
            result.onSuccess { uri ->
                if (share) shareImage(context, uri)
                else {
                    val r = snackbar.showSnackbar("Saved to Pictures/${Photos.EXPORT_ALBUM}", "Share", duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) shareImage(context, uri)
                }
            }.onFailure { snackbar.showSnackbar("Export failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        val landscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Top bar
            if (!fullscreen) Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { store.save(photo, settings); saveHistory(); onBack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(photo.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    if (versions.list.size > 1) {
                        Text(versions.current.name, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { selectTool(if (tool == Tool.VERSIONS) null else Tool.VERSIONS) }) {
                    Icon(Icons.Outlined.History, "Versions and checkpoints", tint = if (tool == Tool.VERSIONS) MaterialTheme.colorScheme.primary else Color.White)
                }
                Box {
                    IconButton(onClick = { shareMenu = true }, enabled = opened != null && exportProgress == null) {
                        Icon(Icons.Rounded.Share, "Export", tint = Color.White)
                    }
                    DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                        DropdownMenuItem(text = { Text("Save JPEG to gallery") }, onClick = { shareMenu = false; export(false) })
                        DropdownMenuItem(text = { Text("Share JPEG") }, onClick = { shareMenu = false; export(true) })
                        DropdownMenuItem(text = { Text("Export options…") }, onClick = { shareMenu = false; dialog = "export" })
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Copy settings") }, onClick = {
                            menu = false
                            store.clipboard = settings
                            scope.launch { snackbar.showSnackbar("Settings copied") }
                        })
                        DropdownMenuItem(
                            text = { Text("Paste settings") },
                            enabled = store.clipboard != null,
                            onClick = { menu = false; store.clipboard?.let { commit(settings.pasteFrom(it, store.pasteGroups)) } },
                        )
                        DropdownMenuItem(
                            text = { Text("Choose what to paste…") },
                            enabled = store.clipboard != null,
                            onClick = { menu = false; dialog = "paste" },
                        )
                        DropdownMenuItem(text = { Text("Create preset…") }, onClick = { menu = false; dialog = "preset" })
                        DropdownMenuItem(text = { Text("Reset all") }, onClick = { menu = false; commit(Settings()) })
                        if (onAddToAlbum != null) {
                            DropdownMenuItem(text = { Text("Add to album…") }, onClick = { menu = false; onAddToAlbum(photo) })
                        }
                        if (onBattle != null && canBattle(photo)) {
                            DropdownMenuItem(text = { Text("Edit battle…") }, onClick = { menu = false; onBattle(photo) })
                        }
                    }
                }
            }
            exportProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            header?.invoke()

            // Photo and panel: stacked in portrait, side by side in landscape.
            val photoArea: @Composable (Modifier) -> Unit = { m -> Box(
                m.clip(RoundedCornerShape(0.dp)).pointerInput(Unit) {
                    // Any touch keeps full screen's controls up a little longer.
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); chromePing++ }
                },
            ) {
                val r = rendered
                val shown: PhotoLayer? = if (showBefore) before?.let { BitmapLayer(it) } ?: r?.layer
                else r?.layer ?: placeholder?.let { BitmapLayer(it) }
                val o = opened
                if (cropMode && r != null && o != null) {
                    CropView(
                        layer = r.layer,
                        settings = settings,
                        aspect = cropAspect,
                        onChange = { settings = it },
                        onDone = { commit(settings) },
                    )
                } else if (tool == Tool.MASKS && r != null && o != null && !showBefore) {
                    MaskView(
                        layer = r.layer,
                        opened = o,
                        settings = settings,
                        selected = maskIndex,
                        overlay = maskOverlay && !dragging,
                        onChange = { settings = it; dragging = true },
                        onDone = { dragging = false; commit(settings) },
                        onTap = { clutch = null; if (fullscreen) chrome = !chrome },
                    )
                } else {
                    ZoomableImage(
                        layer = shown,
                        tile = if (showBefore) null else tile?.takeIf { it.settings == r?.settings },
                        grid = tool == Tool.GEOMETRY && dragging,
                        compare = if (split && !showBefore) compareImage?.let { BitmapLayer(it) } else null,
                        splitAt = splitAt,
                        onSplit = { splitAt = it },
                        swipe = swipe,
                        onHold = { showBefore = it },
                        onTap = { clutch = null; if (fullscreen) chrome = !chrome },
                        onViewport = { viewport = it },
                    )
                }
                if (opened == null && loadError == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White)
                }
                loadError?.let {
                    Text("Can't open this photo: $it", color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                }
                if (!cropMode && !split && (!fullscreen || chrome)) r?.histogram?.let { Histogram(it, Modifier.align(Alignment.TopEnd).padding(8.dp).size(120.dp, 56.dp)) }
                if (split && !cropMode && !showBefore) {
                    // Tap "Before" to compare against just this panel's edits.
                    val panel = tool?.takeIf { t -> t.groups.any { it !in FRAMING } }
                    OverlayLabel(
                        if (splitPanelOnly && panel != null) "Before · no ${panel.label} edits ▾" else if (panel != null) "Before · all edits ▾" else "Before",
                        Modifier.align(Alignment.TopStart).then(if (panel != null) Modifier.clickable { splitPanelOnly = !splitPanelOnly } else Modifier),
                    )
                    OverlayLabel("After", Modifier.align(Alignment.TopEnd))
                }
                if (swipe != null && !cropMode) {
                    val path = swipeNav.resolve(menuTree, swipePath)
                    val node = swipeNav.node(menuTree, path)
                    fun valueOf(n: SwipeNode) = when (n) {
                        is SwipeFolder -> "›"
                        is SwipeWheel -> if (settings[n.sat] == 0f) "–" else "${settings[n.hue].roundToInt()}° · ${settings[n.sat].roundToInt()}"
                        is SwipeWbPad -> if (n.neutral(settings)) "As shot" else
                            "${((settings.temperature ?: 0f) / 50f).roundToInt() * 50} · ${(settings.tint ?: 0f).roundToInt()}"
                        is SwipeCurve -> if (curveRemoving) "Release to remove" else curvePoint?.takeIf { swipeAxis == 1 || swipeAxis == 4 || swipeAxis == 6 }
                            ?.let { (x, y) -> "in ${(x * 255).roundToInt()} → out ${(y * 255).roundToInt()}" }
                            ?: settings.curves[n.channel].let { if (it.isIdentity) "–" else "${it.points.size - 2} pts" }
                        is SwipeLeaf -> n.get(settings).let { v -> n.format?.invoke(v) ?: n.param?.let { formatParam(it, v) } ?: "" }
                    }
                    // Two thumbs on a slider: the menu sits by the left thumb, the value on top.
                    val thumbMenu = swipeAxis == 6 && (node is SwipeLeaf || node is SwipeFolder)
                    if (swipeAxis == 2 || thumbMenu) {
                        // The open folder's rows, at most seven around the highlighted one.
                        val folder = swipeNav.folderAt(menuTree, path.dropLast(1))
                        val rows = folder.children
                        val sel = rows.indexOfFirst { it.id == path.last() }.coerceAtLeast(0)
                        val first = (sel - 3).coerceIn(0, maxOf(0, rows.size - 7))
                        Column(
                            Modifier.align(if (thumbMenu) Alignment.CenterStart else Alignment.Center).padding(start = if (thumbMenu) 12.dp else 0.dp)
                                .width(if (thumbMenu) 200.dp else 240.dp).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                if (path.size > 1) "‹  ${folder.label}" else folder.label,
                                color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            if (first > 0) Text("⋯", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                            for (i in first until minOf(rows.size, first + 7)) {
                                val q = rows[i]
                                val on = i == sel
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 6.dp)
                                        .background(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                ) {
                                    val c = if (on) Color.White else Color.Gray
                                    val w = if (on) FontWeight.SemiBold else FontWeight.Normal
                                    Text(q.label, color = c, fontWeight = w, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                    Text(valueOf(q), color = c, fontWeight = w, fontSize = 15.sp)
                                }
                            }
                            if (first + 7 < rows.size) Text("⋯", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                    if (node != null && swipeAxis != 2) {
                        // Just let go but still carried on by the next drag: it stays shown as active.
                        val active = swipeAxis == 1 || swipeAxis == 4 || swipeAxis == 6 || (swipeAxis == 0 && clutch != null)
                        // While a curve is worked on, it shows big over the photo, where the right thumb edits it.
                        if (active && node is SwipeCurve) Canvas(Modifier.fillMaxSize()) {
                            drawCurveOverlay(
                                curveOverlayRect(size.width, size.height), settings.curves[node.channel],
                                CURVE_COLORS[node.channel], curvePoint, curveRemoving,
                            )
                        }
                        // While dragging a wheel or the pad, a small copy of it shows under the label.
                        if (active && node is Swipe2D) {
                            Canvas(Modifier.align(Alignment.TopCenter).padding(top = if (split) 76.dp else 44.dp).size(
                                    when {
                                        // Held up by a thumb, the wheel or pad shows big enough to aim at.
                                        swipeAxis == 4 || swipeAxis == 6 -> 200.dp
                                        else -> 96.dp
                                    },
                                )) {
                                when (node) {
                                    is SwipeWheel -> drawHueWheel(settings[node.hue], settings[node.sat])
                                    is SwipeWbPad -> node.xy(settings).let { (x, y) -> drawWbPad(x, y) }
                                    else -> {}
                                }
                            }
                        }
                        // Below the top level, name the folder too ("Red · Saturation").
                        val parent = path.dropLast(1).takeIf { it.size > 1 }?.let { swipeNav.node(menuTree, it)?.label }
                        val label = listOfNotNull(parent, node.label).joinToString(" · ")
                        Text(
                            "$label  ${valueOf(node)}",
                            color = Color.White, fontSize = if (active) 16.sp else 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = if (split) 40.dp else 8.dp)
                                .background(Color.Black.copy(alpha = if (active) 0.7f else 0.45f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    // How to work the row: after picking it, while an edit can be carried on, and while the
                    // right thumb is still to land on a held-up curve.
                    val hint = when {
                        swipeAxis == 0 && clutch != null -> "Drag again to keep going · tap to stop"
                        swipeAxis == 6 && node is SwipeCurve && curvePoint == null ->
                            "Right thumb: land on a point to move it, or on the curve to add one"
                        swipeAxis == 0 && hintShown && node != null -> swipeHint(node, path, settings.profile == Profile.MONOCHROME)
                        else -> null
                    }
                    if (hint != null) {
                        Text(
                            hint, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .padding(start = 24.dp, end = 24.dp, bottom = 64.dp + if (fullscreen && chrome) 56.dp else 0.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                if (showBefore) {
                    Text(
                        "Before", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (!cropMode && (!fullscreen || chrome)) {
                    // Full screen: the tool icons pick which sliders swipe mode cycles through (Masks shows the mask handles).
                    if (fullscreen) {
                        Row(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp)).padding(horizontal = 4.dp),
                        ) {
                            for (t in Tool.entries.filter { it != Tool.CROP && it != Tool.PRESETS && it != Tool.VERSIONS && allowed(it) }) {
                                val sel = t == tool || (tool == null && t == Tool.LIGHT)
                                val c = if (sel) MaterialTheme.colorScheme.primary else Color.White
                                Box(
                                    Modifier.size(48.dp).clip(CircleShape).clickable { selectTool(t); chromePing++ },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(t.icon, t.label, tint = c)
                                    if (t.changed(settings)) ChangedDot(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp))
                                }
                            }
                        }
                    }
                    val lift = if (fullscreen) 56.dp else 0.dp
                    // Thumb-reach controls along the bottom of the photo.
                    Row(Modifier.align(Alignment.BottomStart).padding(8.dp).padding(bottom = lift), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverlayButton(Icons.AutoMirrored.Rounded.Undo, "Undo", enabled = history.canUndo) { undo() }
                        OverlayButton(Icons.AutoMirrored.Rounded.Redo, "Redo", enabled = history.canRedo) { redo() }
                    }
                    Row(Modifier.align(Alignment.BottomEnd).padding(8.dp).padding(bottom = lift), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverlayButton(
                            if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            if (fullscreen) "Exit full screen" else "Full screen",
                            modifier = Modifier.testTag("fullscreen"),
                        ) {
                            fullscreen = !fullscreen
                            chrome = false
                            if (fullscreen) scope.launch { snackbar.showSnackbar("Tap the photo to show the controls") }
                        }
                        if (tools == null) OverlayButton(Icons.Outlined.Swipe, "Swipe on the photo to adjust", selected = swipeMode) {
                            swipeMode = !swipeMode
                            store.swipeAdjust = swipeMode
                            if (swipeMode) scope.launch { snackbar.showSnackbar("Swipe up or down on the photo to pick a slider (slide right into a folder, left to go back), then left or right to change it. On curves, Mix and Grading, hold a spot on the photo to target it. Or use two thumbs: left picks, right changes.") }
                        }
                        // Tap for a split view, hold for the original.
                        OverlayButton(
                            Icons.Rounded.Compare, "Compare: tap for split view, hold for the original", selected = split,
                            modifier = Modifier.testTag("compare").pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { split = !split },
                                    onLongPress = { showBefore = true },
                                    onPress = { tryAwaitRelease(); showBefore = false },
                                )
                            },
                        )
                    }
                }
            } }

            val panelArea: @Composable (Modifier) -> Unit = { m -> val o = opened
            val open = tool
            if (open != null) Column(m) {
            // Adjust and Frame show their panels as tabs, with the open panel's reset beside them.
            val tabs = open.section?.takeIf { it == Section.ADJUST || it == Section.FRAME }?.tools?.filter(allowed)
            if (tabs != null) PanelTabs(
                tabs, open, settings, onSelect = { selectTool(it) },
                onReset = {
                    val reset = open.groups.fold(settings) { acc, g -> acc.reset(g) }
                    // The profile belongs to Looks, so Light's reset leaves it unless Looks is hidden.
                    commit(if (allowed(Tool.PRESETS)) reset.withProfileOf(settings) else reset)
                },
            )
            // The panel's visible height reaches the curve editor, so its graph fits on screen.
            BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()) { val viewport = maxHeight
            CompositionLocalProvider(LocalAdjusting provides dragging, LocalPanelHeight provides viewport) {
                Column(
                    Modifier.verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    val ctl = Controls(
                        s = settings,
                        change = { settings = it; dragging = true },
                        done = { dragging = false; commit(settings) },
                        commit = { commit(it) },
                    )
                    when (tool) {
                        Tool.PRESETS -> PresetsPanel(
                            ctl,
                            presets = Preset.BUILT_IN + presets,
                            current = settings,
                            onApply = { p ->
                                // A checkpoint keeps your own work before a look replaces it (not when
                                // swapping one preset for another, which undo already covers).
                                val next = p.applyTo(settings)
                                if (next != settings && (Preset.BUILT_IN + presets).none { it.isApplied(settings) }) {
                                    addCheckpoint("Before ${p.name}", auto = true)
                                }
                                commit(next)
                            },
                            onDelete = { store.deletePreset(it.name); presets = store.userPresets() },
                            onCreate = { dialog = "preset" },
                        )
                        Tool.VERSIONS -> VersionsPanel(
                            versions = versions,
                            checkpoints = checkpoints,
                            current = settings,
                            onSwitch = { id -> switchVersion { store.switchVersion(photo, id) } },
                            onAdd = { copy -> switchVersion { store.addVersion(photo, if (copy) settings else Settings()) } },
                            onRename = { id, name -> store.renameVersion(photo, id, name); versions = store.versions(photo) },
                            onDelete = { id -> switchVersion { store.deleteVersion(photo, id) } },
                            onSaveCheckpoint = { name -> addCheckpoint(name, auto = false) },
                            onRestore = { commit(it.settings) },
                            onDeleteCheckpoint = { c ->
                                checkpoints = checkpoints - c
                                store.saveCheckpoints(photo, versions.active, checkpoints)
                            },
                        )
                        Tool.CROP -> CropPanel(ctl, o, aspect = cropAspect, onAspect = { a ->
                            cropAspect = a
                            if (o != null) commit(fitAspect(settings, a, o))
                        })
                        Tool.LIGHT -> LightPanel(
                            ctl, onAuto = if (o != null) ({ auto() }) else null,
                            curve = lightCurve, channel = curveChannel, showProfile = !allowed(Tool.PRESETS),
                            onCurve = { c -> navigate(if (c) swipeNav.descend(menuTree, listOf("light", "curve")) else swipeNav.ownLeaf(menuTree, listOf("light"))) },
                            onChannel = { ch ->
                                navigate(if (ch < 0) swipeNav.ownLeaf(menuTree, listOf("light", "curve")) else listOf("light", "curve", CURVE_IDS[ch]))
                            },
                        )
                        Tool.COLOR -> ColorPanel(
                            ctl, o?.preview, mode = colorMode, band = mixBand, zone = gradeZone,
                            onMode = { m ->
                                navigate(when (m) {
                                    1 -> swipeNav.descend(menuTree, listOf("color", "mix"))
                                    2 -> swipeNav.descend(menuTree, listOf("color", "grading"))
                                    else -> swipeNav.ownLeaf(menuTree, listOf("color"))
                                })
                            },
                            onBand = { b -> navigate(swipeNav.descend(menuTree, listOf("color", "mix", P.MIX_BANDS[b].first))) },
                            onZone = { z -> navigate(listOf("color", "grading", P.GRADE_ZONES[z])) },
                        )
                        Tool.EFFECTS -> EffectsPanel(ctl)
                        Tool.DETAIL -> DetailPanel(ctl)
                        Tool.OPTICS -> OpticsPanel(ctl, hasLensProfile = o?.preview?.lens != null, isRaw = o?.raw != null)
                        Tool.GEOMETRY -> GeometryPanel(ctl)
                        Tool.MASKS -> MaskPanel(
                            ctl, maskIndex, onSelect = { maskIndex = it },
                            aspect = o?.preview?.let { it.fullWidth.toFloat() / it.fullHeight } ?: 1.5f,
                            subject = subjectStatus, onRetrySubject = { subjectTry++ },
                            sky = skyStatus, onRetrySky = { skyTry++ },
                            overlay = maskOverlay, onOverlay = { maskOverlay = it },
                        )
                        else -> {}
                    }
                }
            } } } }

            if (fullscreen) {
                photoArea(Modifier.weight(1f).fillMaxWidth())
            } else if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    photoArea(Modifier.weight(1f).fillMaxHeight())
                    panelArea(Modifier.width(360.dp).fillMaxHeight())
                }
                if (!cropMode && tool == null) Filmstrip(photos, index, onSelect = { go(it) })
            } else if (tool == null || cropMode) {
                photoArea(Modifier.weight(1f).fillMaxWidth())
                // The filmstrip only shows with no panel open, so editing gets the room.
                if (!cropMode && tool == null) Filmstrip(photos, index, onSelect = { go(it) })
                panelArea(Modifier.fillMaxWidth().heightIn(max = 320.dp))
            } else {
                // The photo area is only as tall as the photo (no black bars), and the panel gets
                // the rest, so a landscape shot leaves room for more sliders.
                val layer = rendered?.layer ?: placeholder?.let { BitmapLayer(it) }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val fit = layer?.let { maxWidth * (it.height.toFloat() / it.width) } ?: maxHeight
                    val photoHeight = minOf(fit, maxHeight - 320.dp).coerceAtLeast(maxHeight * 0.35f)
                    Column(Modifier.fillMaxSize()) {
                        photoArea(Modifier.fillMaxWidth().height(photoHeight))
                        panelArea(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }

            // Bottom bar: a section opens its last tab, and tapping the open one closes the panel.
            // A dot marks the sections with edits.
            if (!fullscreen) Row(Modifier.fillMaxWidth().background(Color(0xFF0E0E0E)).padding(vertical = 8.dp, horizontal = 4.dp)) {
                for (s in Section.entries) {
                    val own = s.tools.filter(allowed)
                    if (own.isEmpty()) continue
                    val selected = tool?.section == s
                    val changed = own.any { it.changed(settings) }
                    val primary = MaterialTheme.colorScheme.primary
                    Column(
                        Modifier.weight(1f).testTag("section-${s.name}").clip(RoundedCornerShape(12.dp))
                            .clickable { selectTool(if (selected) null else lastTool[s]?.takeIf { it in own } ?: own.first()) }
                            .padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(width = 60.dp, height = 32.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (selected) primary else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(s.icon, null, tint = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White)
                            if (changed) Box(
                                Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 15.dp).size(6.dp)
                                    .background(if (selected) MaterialTheme.colorScheme.onPrimary else primary, CircleShape),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(s.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.padding(bottom = 120.dp))
    }

    when (dialog) {
        "export" -> ExportDialog(store.exportOptions, onDismiss = { dialog = null }, onSave = { store.exportOptions = it; dialog = null })
        "paste" -> GroupsDialog(
            title = "Paste settings",
            confirm = "Paste",
            initial = store.pasteGroups,
            onDismiss = { dialog = null },
            onConfirm = { groups, _ ->
                store.pasteGroups = groups
                store.clipboard?.let { commit(settings.pasteFrom(it, groups)) }
                dialog = null
            },
        )
        "preset" -> GroupsDialog(
            title = "Create preset",
            confirm = "Save",
            initial = Group.entries.toSet() - Group.CROP - Group.GEOMETRY - Group.OPTICS,
            askName = true,
            onDismiss = { dialog = null },
            onConfirm = { groups, name ->
                store.savePreset(name, settings, groups)
                presets = store.userPresets()
                dialog = null
                scope.launch { snackbar.showSnackbar("Preset \"$name\" saved") }
            },
        )
    }
}

// The crop tool's aspect lock (width / height in pixels, null = free) lives outside the composable
// so it survives switching tools, like Lightroom's.
private var cropAspect by mutableStateOf<Float?>(null)

/** The settings that move pixels around (lens, geometry, crop): a new scene when they change. */
private fun geometryKey(o: OpenedPhoto, s: Settings, crop: Boolean) = listOf(
    o.photo.id, crop,
    s.values.filterKeys { k -> P.byKey[k]?.group.let { it == Group.OPTICS || it == Group.GEOMETRY || it == Group.CROP } },
)

/**
 * Guides are slow to build (a few hundred ms), so keep the last one while only non-guide sliders
 * move. Exposure doesn't change it (see Look.guide); while any slider (white balance, geometry,
 * lens) is being dragged the previous one is close enough.
 */
private class GuideCache {
    private var geoKey: Any? = null
    private var colorKey: Any? = null
    private var cropMode: Boolean? = null
    private var guide: Guide? = null
    private var bitmap: Bitmap? = null

    @Synchronized
    fun clear() { geoKey = null; colorKey = null; cropMode = null; guide = null; bitmap = null }

    @Synchronized
    fun get(o: OpenedPhoto, look: Look, s: Settings, crop: Boolean, allowStale: Boolean = false): Guide? {
        val gk = geometryKey(o, s, crop)
        val ck = listOf(s.profile, s.temperature, s.tint)
        // While a slider moves, any recent guide will do (the shader samples it by position, so
        // a slightly different geometry only blurs the local tone briefly); it's rebuilt on release.
        if (guide != null && ((gk == geoKey && ck == colorKey) || (allowStale && crop == cropMode))) return guide
        val g = look.guide(o.preview.image, o.geometry(s, crop)) ?: return null
        geoKey = gk; colorKey = ck; cropMode = crop; guide = g; bitmap = null
        return g
    }

    /** The guide as a GPU texture. */
    @Synchronized
    fun bitmapOf(g: Guide): Bitmap {
        if (g !== guide) return GpuPreview.guideBitmap(g)
        return bitmap ?: GpuPreview.guideBitmap(g).also { bitmap = it }
    }
}

/**
 * The GPU preview's input: the photo's camera pixels with lens, geometry and crop applied, and
 * noise reduction and defringe done ([Look.prepare]). Built from the small draft while geometry
 * or those sliders move, and from the full preview otherwise.
 */
private class SceneCache {
    private var key: Any? = null
    private var fromPreview = false
    private var filled: LinearImage? = null
    private var prepKey: Any? = null
    private var scene: Bitmap? = null
    private var scale = 1.0
    private var coords: Bitmap? = null
    private var subjectOf: MaskImage? = null
    private var skyOf: MaskImage? = null
    private var subject: Bitmap? = null

    fun frame(o: OpenedPhoto, s: Settings, dragging: Boolean, crop: Boolean, guides: GuideCache): GpuPreview.Frame {
        val k = geometryKey(o, s, crop)
        val geom = o.geometry(s, crop)
        val look = Look(o.preview, s)
        if (k != key || filled == null || (!fromPreview && !dragging)) {
            val src = if (dragging) o.draft else o.preview
            val sampler = ImageSampler(src.image, geom)
            val img = LinearImage(sampler.width, sampler.height)
            sampler.fill(0, sampler.height, img)
            filled = img
            scale = sampler.scale
            key = k
            fromPreview = !dragging
            coords = null
            scene = null
        }
        val pk = if (look.prepares) look.prepareKey else null
        if (scene == null || pk != prepKey) {
            val img = filled!!
            scene = GpuPreview.sceneBitmap(if (pk == null) img else img.copy().also { look.prepare(it, scale) })
            prepKey = pk
        }
        val guide = guides.get(o, look, s, crop, allowStale = dragging)
        val params = look.shaderParams(scale)
        val sc = scene!!
        if (params.maskCount > 0 && coords == null) coords = GpuPreview.coordsBitmap(geom, scale, sc.width, sc.height)
        if (params.subject !== subjectOf || params.sky !== skyOf) {
            subjectOf = params.subject
            skyOf = params.sky
            subject = if (params.subject != null || params.sky != null) GpuPreview.mapsBitmap(params.subject, params.sky) else null
        }
        return GpuPreview.prepare(sc, guide, guide?.let { guides.bitmapOf(it) }, params, coords, subject)
    }
}

/** Swipe mode's probe: a small render, for reading the photo's colors under a finger. */
private class Probe(val pixels: IntArray, val width: Int, val height: Int)

private fun probeOf(o: OpenedPhoto, s: Settings, guides: GuideCache): Probe {
    val look = Look(o.preview, s)
    val sampler = ImageSampler(o.preview.image, o.geometry(s, false), 240)
    val px = IntArray(sampler.width * sampler.height)
    renderBands(look, sampler, guides.get(o, look, s, false), 256) { y, rows, band ->
        System.arraycopy(band, 0, px, y * sampler.width, rows * sampler.width)
    }
    return Probe(px, sampler.width, sampler.height)
}

/** Histogram of a small offscreen render, for the overlay (the live frame isn't read back). */
private fun histogramOf(o: OpenedPhoto, s: Settings, crop: Boolean, guides: GuideCache): Array<IntArray> {
    val look = Look(o.preview, s)
    val sampler = ImageSampler(o.preview.image, o.geometry(s, crop), 360)
    val px = IntArray(sampler.width * sampler.height)
    renderBands(look, sampler, guides.get(o, look, s, crop), 256) { y, rows, band ->
        System.arraycopy(band, 0, px, y * sampler.width, rows * sampler.width)
    }
    return histogram(px, 1)
}

/** Finished preview renders, so screenshot tests know when the photo is on screen. */
@Volatile
internal var renderCount = 0

/** Off in JVM screenshot tests, whose software canvas can't draw shaders. */
@Volatile
internal var gpuPreviewEnabled = true

/** Two bitmaps per size, alternated, so the one on screen is never being written. */
private class BitmapPool {
    private val bitmaps = HashMap<Long, Array<Bitmap>>()
    private var flip = 0

    fun render(o: OpenedPhoto, src: DevelopSource, s: Settings, crop: Boolean, guides: GuideCache): Rendered {
        val look = Look(src, s)
        val geom = o.geometry(s, crop)
        val guide = guides.get(o, look, s, crop)
        val sampler = ImageSampler(src.image, geom)
        val w = sampler.width; val h = sampler.height
        val key = (w.toLong() shl 32) or h.toLong()
        val pair = bitmaps.getOrPut(key) { Array(2) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } }
        flip = 1 - flip
        val bmp = pair[flip]
        val px = IntArray(w * h)
        renderBands(look, sampler, guide, 256) { y, rows, band -> System.arraycopy(band, 0, px, y * w, rows * w) }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        renderCount++
        return Rendered(BitmapLayer(bmp.asImageBitmap()), histogram(px), s, crop)
    }
}

/** Where a [w] x [h] image sits when fitted (centred) into a [box]. */
internal fun fitRect(box: Size, w: Int, h: Int): Rect {
    val f = minOf(box.width / w, box.height / h)
    val dw = w * f; val dh = h * f
    val l = (box.width - dw) / 2; val t = (box.height - dh) / 2
    return Rect(l, t, l + dw, t + dh)
}

@Composable
private fun ZoomableImage(
    layer: PhotoLayer?,
    tile: Tile?,
    grid: Boolean,
    compare: PhotoLayer?,
    splitAt: Float,
    onSplit: (Float) -> Unit,
    swipe: SwipeAdjust?,
    onHold: (Boolean) -> Unit,
    onTap: () -> Unit,
    onViewport: (Viewport?) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(Size.Zero) }
    val report by rememberUpdatedState(onViewport)
    val img by rememberUpdatedState(layer)
    val adjust by rememberUpdatedState(swipe)
    val holding by rememberUpdatedState(onHold)
    val tapped by rememberUpdatedState(onTap)
    val split by rememberUpdatedState(splitAt)
    val splitChanged by rememberUpdatedState(onSplit)

    fun clampOffset(o: Offset, s: Float): Offset {
        val i = img ?: return Offset.Zero
        val r = fitRect(box, i.width, i.height)
        val mx = maxOf(0f, (r.width * s - box.width) / 2)
        val my = maxOf(0f, (r.height * s - box.height) / 2)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    // Tell the editor which part of the photo is on screen, for sharp zoom.
    LaunchedEffect(scale, offset, box, layer?.width, layer?.height) {
        val i = layer
        if (i == null || scale < 1.5f || box == Size.Zero) { report(null); return@LaunchedEffect }
        val r = fitRect(box, i.width, i.height)
        val c = Offset(box.width / 2, box.height / 2)
        fun content(p: Offset) = c + (p - c - offset) / scale
        val a = content(Offset.Zero); val b = content(Offset(box.width, box.height))
        val area = Rect(
            ((a.x - r.left) / r.width).coerceIn(0f, 1f), ((a.y - r.top) / r.height).coerceIn(0f, 1f),
            ((b.x - r.left) / r.width).coerceIn(0f, 1f), ((b.y - r.top) / r.height).coerceIn(0f, 1f),
        )
        if (area.width <= 0f || area.height <= 0f) { report(null); return@LaunchedEffect }
        report(Viewport(area, area.width * r.width * scale))
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(swipe != null) {
                fun transform(pan: Offset, zoom: Float) {
                    scale = (scale * zoom).coerceIn(1f, 12f)
                    offset = if (scale == 1f) Offset.Zero else clampOffset(offset + pan, scale)
                }
                if (adjust == null) {
                    detectTransformGestures { _, pan, zoom, _ -> transform(pan, zoom) }
                    return@pointerInput
                }
                /** A point on screen as 0..1 of the photo, or null off it. */
                fun toImage(p: Offset): Offset? {
                    val i = img ?: return null
                    val r = fitRect(box, i.width, i.height)
                    val c = Offset(box.width / 2, box.height / 2)
                    val q = c + (p - c - offset) / scale
                    val u = (q.x - r.left) / r.width; val v = (q.y - r.top) / r.height
                    return if (u in 0f..1f && v in 0f..1f) Offset(u, v) else null
                }
                // Swipe mode: one finger adjusts (the first move picks the axis), two fingers zoom and
                // pan, and holding still targets what's under the finger (or shows the original).
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var axis = 0 // 0 undecided, 1 sideways, 2 up/down, 3 zoom, 4 long-press target, 5 original
                    var moved = Offset.Zero
                    // Where the finger was when holding still showed the original: moving on from there edits again.
                    var heldAt = Offset.Zero
                    var last = down.uptimeMillis
                    var leftId: PointerId? = null
                    var rightId: PointerId? = null
                    // Two thumbs: where the left one is, since when it has been still, and whether that hold fired.
                    var leftAt = Offset.Zero
                    var leftStill = 0L
                    var leftDrift = 0f
                    var leftHeld = false
                    while (true) {
                        val ev = if (axis == 0) {
                            val left = viewConfiguration.longPressTimeoutMillis - (last - down.uptimeMillis)
                            withTimeoutOrNull(left.coerceAtLeast(1L)) { awaitPointerEvent() }
                        } else if (axis == 6 && !leftHeld) {
                            val left = viewConfiguration.longPressTimeoutMillis - (last - leftStill)
                            withTimeoutOrNull(left.coerceAtLeast(1L)) { awaitPointerEvent() }
                        } else awaitPointerEvent()
                        if (ev == null && axis == 6) {
                            // The left thumb held still: that adds (on a curve, a point at its tone).
                            leftHeld = true
                            val at = toImage(leftAt)
                            adjust?.onLeftHold?.invoke(at?.x ?: -1f, at?.y ?: -1f)
                            continue
                        }
                        if (ev == null) {
                            val at = toImage(down.position + moved)
                            axis = if (at != null && adjust?.onTarget?.invoke(at.x, at.y) == true) 4 else { holding(true); heldAt = moved; 5 }
                            continue
                        }
                        ev.changes.firstOrNull()?.let { last = it.uptimeMillis }
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        // Any one-finger state can become two thumbs: the first thumb often rests (showing the
                        // original after a moment) or drifts a little before the second lands.
                        if (pressed.size > 1 && axis != 3 && axis != 6 && adjust != null) {
                            // Two thumbs on opposite halves edit (left picks, right changes); otherwise it's a pinch.
                            val a = pressed[0]; val b = pressed[1]
                            val half = size.width / 2f
                            if ((a.position.x < half) != (b.position.x < half) && abs(a.position.x - b.position.x) > size.width * 0.3f) {
                                if (axis == 1 || axis == 2 || axis == 4) adjust?.onEnd?.invoke()
                                if (axis == 5) holding(false)
                                // On a wheel or pad the thumb that was down first holds it, on either side.
                                val first = pressed.firstOrNull { it.id == down.id }
                                val (l, r) = when {
                                    first != null && adjust?.firstHolds?.invoke() == true -> first to (if (first === a) b else a)
                                    a.position.x < b.position.x -> a to b
                                    else -> b to a
                                }
                                leftId = l.id; rightId = r.id
                                leftAt = l.position; leftStill = last; leftDrift = 0f; leftHeld = false
                                axis = 6
                                val at = toImage(l.position)
                                adjust?.onThumbs?.invoke(at?.x ?: -1f, at?.y ?: -1f)
                                adjust?.onRightDown?.invoke(r.position.x, r.position.y, size.width.toFloat(), size.height.toFloat())
                                pressed.forEach { it.consume() }
                                continue
                            } else if (axis == 0) axis = 3
                        } else if (pressed.size > 1 && axis == 0) axis = 3
                        when (axis) {
                            6 -> {
                                val l = ev.changes.firstOrNull { it.id == leftId }
                                // A right thumb landing again while the left one still holds.
                                if (rightId == null) ev.changes.firstOrNull { it.pressed && !it.previousPressed && it.id != leftId }?.let { n ->
                                    rightId = n.id
                                    leftHeld = true
                                    adjust?.onRightDown?.invoke(n.position.x, n.position.y, size.width.toFloat(), size.height.toFloat())
                                    n.consume()
                                }
                                val r = ev.changes.firstOrNull { it.id == rightId }
                                if (l != null && l.pressed) {
                                    val d = l.positionChange()
                                    leftAt = l.position
                                    leftDrift += d.getDistance()
                                    if (leftDrift > viewConfiguration.touchSlop) {
                                        leftDrift = 0f; leftStill = last; leftHeld = false
                                    }
                                    if (d != Offset.Zero) {
                                        val at = toImage(l.position)
                                        adjust?.onLeft?.invoke(d.x, d.y, at?.x ?: -1f, at?.y ?: -1f, size.height.toFloat())
                                    }
                                    l.consume()
                                }
                                if (r != null && r.pressed) {
                                    val d = r.positionChange()
                                    if (d != Offset.Zero) {
                                        // Working with the right thumb disarms the left thumb's hold until it slides again.
                                        leftHeld = true
                                        adjust?.onRight?.invoke(d.x, d.y, size.width.toFloat(), size.height.toFloat())
                                    }
                                    r.consume()
                                }
                                // The right thumb lifted: the left one keeps holding, so the right one can land
                                // anywhere and carry on, like lifting a finger on a trackpad.
                                if (r != null && !r.pressed && l != null && l.pressed) {
                                    adjust?.onRightUp?.invoke()
                                    rightId = null
                                }
                            }
                            3 -> {
                                transform(ev.calculatePan(), ev.calculateZoom())
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            0 -> {
                                moved += pressed[0].positionChange()
                                if (abs(moved.x) > viewConfiguration.touchSlop || abs(moved.y) > viewConfiguration.touchSlop) {
                                    axis = if (abs(moved.x) > abs(moved.y)) 1 else 2
                                    adjust?.onStart?.invoke(axis == 1)
                                    pressed[0].consume()
                                }
                            }
                            5 -> {
                                // The original only shows while the finger rests; dragging on adjusts as usual.
                                moved += pressed[0].positionChange()
                                val d = moved - heldAt
                                if (abs(d.x) > viewConfiguration.touchSlop || abs(d.y) > viewConfiguration.touchSlop) {
                                    holding(false)
                                    axis = if (abs(d.x) > abs(d.y)) 1 else 2
                                    adjust?.onStart?.invoke(axis == 1)
                                    pressed[0].consume()
                                }
                            }
                            else -> {
                                val d = pressed[0].positionChange()
                                adjust?.onMove?.invoke(d.x, d.y, size.width.toFloat())
                                pressed[0].consume()
                            }
                        }
                    }
                    if (axis == 1 || axis == 2 || axis == 4 || axis == 6) adjust?.onEnd?.invoke()
                    if (axis == 5) holding(false)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 4f },
                    // Swipe mode handles holding itself (it may target what's under the finger).
                    onLongPress = { if (adjust == null) holding(true) },
                    onPress = { tryAwaitRelease(); onHold(false) },
                    onTap = { tapped() },
                )
            },
    ) {
        if (layer != null) {
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            ) {
                val r = fitRect(size, layer.width, layer.height)
                drawLayer(layer, r)
                if (tile != null) {
                    val a = tile.area
                    val l = r.left + a.left * r.width; val t = r.top + a.top * r.height
                    drawImage(
                        tile.image, dstOffset = IntOffset(l.roundToInt(), t.roundToInt()),
                        dstSize = IntSize((a.width * r.width).roundToInt(), (a.height * r.height).roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
                if (compare != null) {
                    // The divider is in screen space; this canvas is zoomed and panned.
                    val cx = size.width / 2 + (splitAt * size.width - size.width / 2 - offset.x) / scale
                    clipRect(right = cx) { drawLayer(compare, fitRect(size, compare.width, compare.height)) }
                }
                if (grid) drawGrid(r, 8, Color.White.copy(alpha = 0.35f))
            }
        }
        if (compare != null) {
            Canvas(Modifier.fillMaxSize()) {
                val x = splitAt * size.width
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                val c = Offset(x, size.height / 2)
                drawCircle(Color.Black.copy(alpha = 0.5f), 16.dp.toPx(), c)
                drawCircle(Color.White, 16.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                val a = 5.dp.toPx()
                for (sgn in listOf(-1f, 1f)) {
                    val tip = c + Offset(sgn * 10.dp.toPx(), 0f)
                    val path = Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(tip.x - sgn * a, tip.y - a)
                        lineTo(tip.x - sgn * a, tip.y + a)
                        close()
                    }
                    drawPath(path, Color.White)
                }
            }
            // A wide grab strip over the divider.
            Box(
                Modifier.fillMaxHeight().width(48.dp)
                    .offset { IntOffset((split * box.width - 24.dp.toPx()).roundToInt(), 0) }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dx ->
                            change.consume()
                            if (box.width > 0f) splitChanged((split + dx / box.width).coerceIn(0.02f, 0.98f))
                        }
                    },
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class SwipeMode { NONE, MENU, SLIDER, PAD, CURVE, THUMBS }

/** ([x], [y]) kept on a wheel (inside the circle) or pad (inside the square). */
private fun pad2D(node: Swipe2D, x: Float, y: Float): Pair<Float, Float> =
    if (node.round) hypot(x, y).let { r -> if (r > 1f) x / r to y / r else x to y }
    else x.coerceIn(-1f, 1f) to y.coerceIn(-1f, 1f)

/** Swipe mode's gesture state, kept across one gesture's callbacks. */
private class SwipeState {
    var mode = SwipeMode.NONE
    /** Rows change with up/down rather than sideways (a long-pressed target). */
    var vertical = false
    var acc = 0f
    /** Sideways motion while the menu is open, toward opening a folder or going back. */
    var sideways = 0f
    var startIndex = 0
    var detent: Detent? = null
    /** A wheel's or pad's dot, -1..1 (y up). */
    var pad: Pair<Float, Float>? = null
    /** A curve row's points while one is dragged, and which. */
    var curve: MutableList<Pair<Float, Float>>? = null
    var curveSel = -1
    /** The dragged point's output, unclamped, so pushing it past the top or bottom can remove it. */
    var curveY = Float.NaN
    /** Two-thumb mode: the right thumb's slider detent, and its row. */
    var thumbDetent: Detent? = null
    var thumbRow: String? = null
    /** Two-thumb mode on a curve: the input of the point the right thumb moved before it lifted, or -1. */
    var thumbPoint = -1f
}

/**
 * What to do on the photo for swipe mode's row [node] at [path]: where to slide, and where holding
 * a spot targets something (otherwise holding still shows the original).
 */
private fun swipeHint(node: SwipeNode, path: List<String>, mono: Boolean): String = when {
    node is SwipeFolder -> "Slide right to open ${node.label} · up or down for another row"
    node is SwipeCurve -> "Hold a tone on the photo, then drag up or down · or hold with your left thumb and edit the curve with your right"
    path.getOrNull(1) == "mix" && !mono -> "Hold a color on the photo to pick its band, then drag up or down"
    node is Swipe2D -> "Slide to move the dot · or hold the photo, then drag"
    path.getOrNull(1) == "grading" -> "Slide to adjust · or hold a tone on the photo to pick its zone, then drag its wheel"
    else -> "Slide left or right to adjust · up or down for another row · hold still to see the original"
}

/** An up/down or 2D ([twoD]) edit just let go of, at [path]; the next drag carries it on. */
private class Clutch(val path: List<String>, val vertical: Boolean, val twoD: Boolean)

/** How long after letting go the next drag still carries the same edit on. */
private const val CLUTCH_MS = 1500L

/** How long swipe mode's gesture hint shows. */
private const val HINT_MS = 5000L

@Composable
private fun OverlayButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f))
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = when { selected -> MaterialTheme.colorScheme.onPrimary; enabled -> Color.White; else -> Color.Gray })
    }
}

@Composable
internal fun OverlayLabel(text: String, modifier: Modifier) {
    Text(
        text, color = Color.White, fontSize = 12.sp,
        modifier = modifier.padding(8.dp).clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Marks a tool, tab or slider that differs from its default. */
@Composable
internal fun ChangedDot(modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
}

private fun DrawScope.drawGrid(r: Rect, n: Int, color: Color) {
    for (i in 1 until n) {
        val x = r.left + r.width * i / n; val y = r.top + r.height * i / n
        drawLine(color, Offset(x, r.top), Offset(x, r.bottom), 1f)
        drawLine(color, Offset(r.left, y), Offset(r.right, y), 1f)
    }
}

// ---------------------------------------------------------------------------------------------
// Crop

private enum class Handle { TL, TR, BL, BR, L, R, T, B, MOVE }

/**
 * The whole frame (straightened, rotated and flipped) with the crop box over it. Corners and
 * edges resize, inside moves; an aspect lock keeps the box's shape.
 */
@Composable
private fun CropView(layer: PhotoLayer, settings: Settings, aspect: Float?, onChange: (Settings) -> Unit, onDone: () -> Unit) {
    var box by remember { mutableStateOf(Size.Zero) }
    val s by rememberUpdatedState(settings)
    val lock by rememberUpdatedState(aspect)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val density = LocalDensity.current
    val touch = with(density) { 28.dp.toPx() }

    Box(
        Modifier.fillMaxSize().padding(20.dp)
            .onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(layer.width, layer.height) {
                var handle = Handle.MOVE
                var start = floatArrayOf(0f, 0f, 1f, 1f)
                var moved = Offset.Zero
                detectDragGestures(
                    onDragStart = { p ->
                        val r = fitRect(box, layer.width, layer.height)
                        start = floatArrayOf(s[P.CROP_LEFT], s[P.CROP_TOP], s[P.CROP_RIGHT], s[P.CROP_BOTTOM])
                        val l = r.left + start[0] * r.width; val t = r.top + start[1] * r.height
                        val rr = r.left + start[2] * r.width; val b = r.top + start[3] * r.height
                        fun near(x: Float, y: Float) = hypot(p.x - x, p.y - y) < touch * 1.4f
                        handle = when {
                            near(l, t) -> Handle.TL
                            near(rr, t) -> Handle.TR
                            near(l, b) -> Handle.BL
                            near(rr, b) -> Handle.BR
                            abs(p.x - l) < touch && p.y in t..b -> Handle.L
                            abs(p.x - rr) < touch && p.y in t..b -> Handle.R
                            abs(p.y - t) < touch && p.x in l..rr -> Handle.T
                            abs(p.y - b) < touch && p.x in l..rr -> Handle.B
                            else -> Handle.MOVE
                        }
                        moved = Offset.Zero
                    },
                    onDrag = { c, d ->
                        c.consume()
                        moved += d
                        val r = fitRect(box, layer.width, layer.height)
                        val crop = dragCrop(start, handle, moved.x / r.width, moved.y / r.height, lock?.let { it * layer.height / layer.width })
                        change(s.with(P.CROP_LEFT to crop[0], P.CROP_TOP to crop[1], P.CROP_RIGHT to crop[2], P.CROP_BOTTOM to crop[3]))
                    },
                    onDragEnd = { done() },
                    onDragCancel = { done() },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = fitRect(size, layer.width, layer.height)
            drawLayer(layer, r)
            val c = Rect(
                r.left + s[P.CROP_LEFT] * r.width, r.top + s[P.CROP_TOP] * r.height,
                r.left + s[P.CROP_RIGHT] * r.width, r.top + s[P.CROP_BOTTOM] * r.height,
            )
            val shade = Color.Black.copy(alpha = 0.6f)
            drawRect(shade, Offset(r.left, r.top), Size(r.width, c.top - r.top))
            drawRect(shade, Offset(r.left, c.bottom), Size(r.width, r.bottom - c.bottom))
            drawRect(shade, Offset(r.left, c.top), Size(c.left - r.left, c.height))
            drawRect(shade, Offset(c.right, c.top), Size(r.right - c.right, c.height))
            drawGrid(c, 3, Color.White.copy(alpha = 0.5f))
            drawRect(Color.White, c.topLeft, c.size, style = Stroke(2f))
            val k = 18.dp.toPx(); val w = 4.dp.toPx()
            for ((x, y, sx, sy) in listOf(
                listOf(c.left, c.top, 1f, 1f), listOf(c.right, c.top, -1f, 1f),
                listOf(c.left, c.bottom, 1f, -1f), listOf(c.right, c.bottom, -1f, -1f),
            )) {
                drawLine(Color.White, Offset(x, y), Offset(x + sx * k, y), w)
                drawLine(Color.White, Offset(x, y), Offset(x, y + sy * k), w)
            }
        }
    }
}

/** The crop after dragging [h] by (dx, dy) frame fractions; [ratio] is width/height in fractions. */
private fun dragCrop(c: FloatArray, h: Handle, dx: Float, dy: Float, ratio: Float?): FloatArray {
    val min = 0.05f
    var l = c[0]; var t = c[1]; var r = c[2]; var b = c[3]
    if (h == Handle.MOVE) {
        val mx = dx.coerceIn(-l, 1 - r); val my = dy.coerceIn(-t, 1 - b)
        return floatArrayOf(l + mx, t + my, r + mx, b + my)
    }
    if (ratio != null) {
        // Locked: corners only (edges act on the nearest corner), anchored at the opposite one.
        val corner = when (h) {
            Handle.L, Handle.T -> Handle.TL
            Handle.R -> Handle.BR
            Handle.B -> Handle.BR
            else -> h
        }
        val left = corner == Handle.TL || corner == Handle.BL
        val top = corner == Handle.TL || corner == Handle.TR
        val ax = if (left) r else l; val ay = if (top) b else t
        var w = (if (left) ax - (l + dx) else (r + dx) - ax).coerceAtLeast(min)
        // Fit inside the frame from the anchor.
        val maxW = if (left) ax else 1 - ax
        val maxH = if (top) ay else 1 - ay
        w = minOf(w, maxW, maxH * ratio)
        val hh = w / ratio
        l = if (left) ax - w else ax; r = l + w
        t = if (top) ay - hh else ay; b = t + hh
        return floatArrayOf(l, t, r, b)
    }
    when (h) {
        Handle.TL -> { l += dx; t += dy }
        Handle.TR -> { r += dx; t += dy }
        Handle.BL -> { l += dx; b += dy }
        Handle.BR -> { r += dx; b += dy }
        Handle.L -> l += dx
        Handle.R -> r += dx
        Handle.T -> t += dy
        Handle.B -> b += dy
        Handle.MOVE -> {}
    }
    l = l.coerceIn(0f, c[2] - min); r = r.coerceIn(c[0] + min, 1f)
    if (h == Handle.TL || h == Handle.TR || h == Handle.L || h == Handle.R || h == Handle.BL || h == Handle.BR) {
        l = l.coerceIn(0f, r - min); r = r.coerceIn(l + min, 1f)
    }
    t = t.coerceIn(0f, b - min); b = b.coerceIn(t + min, 1f)
    return floatArrayOf(l, t, r, b)
}

/** The biggest crop of [aspect] (pixels w/h) centred on the current one. */
private fun fitAspect(s: Settings, aspect: Float?, o: OpenedPhoto): Settings {
    if (aspect == null) return s
    val g = o.geometry(s, ignoreCrop = true)
    val ratio = (aspect * g.outH / g.outW).toFloat()
    val cx = (s[P.CROP_LEFT] + s[P.CROP_RIGHT]) / 2; val cy = (s[P.CROP_TOP] + s[P.CROP_BOTTOM]) / 2
    var w = 1f; var h = w / ratio
    if (h > 1f) { h = 1f; w = h * ratio }
    val l = (cx - w / 2).coerceIn(0f, 1 - w); val t = (cy - h / 2).coerceIn(0f, 1 - h)
    return s.with(P.CROP_LEFT to l, P.CROP_TOP to t, P.CROP_RIGHT to l + w, P.CROP_BOTTOM to t + h)
}

@Composable
private fun CropPanel(c: Controls, o: OpenedPhoto?, aspect: Float?, onAspect: (Float?) -> Unit) {
    val s = c.s
    val original = o?.let { val g = it.geometry(s.with(P.STRAIGHTEN to 0f), ignoreCrop = true); (g.outW / g.outH).toFloat() }
    val options = listOf<Pair<String, Float?>>(
        "Free" to null, "Original" to original, "1:1" to 1f, "4:5" to 0.8f, "5:4" to 1.25f,
        "3:2" to 1.5f, "2:3" to 2f / 3f, "16:9" to 16f / 9f, "9:16" to 9f / 16f,
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, a) in options) {
            FilterChip(selected = aspect == a && !(label == "Original" && aspect == null), onClick = { onAspect(a) }, label = { Text(label) })
        }
    }
    ParamSlider(P.STRAIGHTEN, c)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        val full = arrayOf(P.CROP_LEFT to 0f, P.CROP_TOP to 0f, P.CROP_RIGHT to 1f, P.CROP_BOTTOM to 1f)
        IconButton(onClick = { c.commit(s.with(P.ROTATE_90 to (s[P.ROTATE_90] + 1) % 4, *full)) }) {
            Icon(Icons.Rounded.RotateRight, "Rotate", tint = Color.White)
        }
        IconButton(onClick = { c.commit(s.with(P.FLIP_H to 1f - s[P.FLIP_H], P.CROP_LEFT to 1 - s[P.CROP_RIGHT], P.CROP_RIGHT to 1 - s[P.CROP_LEFT])) }) {
            Icon(Icons.Rounded.Flip, "Flip horizontally", tint = Color.White)
        }
        IconButton(onClick = { c.commit(s.with(P.FLIP_V to 1f - s[P.FLIP_V], P.CROP_TOP to 1 - s[P.CROP_BOTTOM], P.CROP_BOTTOM to 1 - s[P.CROP_TOP])) }) {
            Icon(Icons.Rounded.Flip, "Flip vertically", tint = Color.White, modifier = Modifier.graphicsLayer { rotationZ = 90f })
        }
        TextButton(onClick = { c.commit(s.reset(Group.CROP)) }, enabled = !s.isDefault(Group.CROP)) { Text("Reset") }
    }
}

// ---------------------------------------------------------------------------------------------
// Panels

/** Settings plus the three ways a control changes them: live, finished, and in one go. */
internal class Controls(val s: Settings, val change: (Settings) -> Unit, val done: () -> Unit, val commit: (Settings) -> Unit)

@Composable
private fun SectionHeader(title: String, c: Controls, vararg groups: Group, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).fadeWhileAdjusting(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        actions()
        if (groups.isNotEmpty() && groups.any { !c.s.isDefault(it) }) {
            TextButton(onClick = { c.commit(groups.fold(c.s) { acc, g -> acc.reset(g) }) }) { Text("Reset", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ParamSlider(p: Param, c: Controls, label: String = p.label, track: Brush? = null) {
    AdjustSlider(
        label, c.s[p], p.min..p.max, p.default, { formatParam(p, it) }, track,
        { c.change(c.s.with(p, it)) }, c.done,
        changed = p.key in c.s.values,
    )
}

internal fun formatParam(p: Param, v: Float) = if (p.min < 0f) signed(v, p.decimals) else plain(v, p.decimals)

@Composable
private fun ParamSwitch(p: Param, c: Controls, label: String = p.label) {
    Row(Modifier.fillMaxWidth().fadeWhileAdjusting(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = c.s.on(p), onCheckedChange = { c.commit(c.s.with(p, if (it) 1f else 0f)) })
    }
}

@Composable
private fun Chips(options: List<String>, selected: Int, marked: List<Boolean> = emptyList(), onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp).fadeWhileAdjusting(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { i, label ->
            FilterChip(
                selected = i == selected, onClick = { onSelect(i) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label)
                        if (marked.getOrElse(i) { false }) ChangedDot(Modifier.padding(start = 6.dp))
                    }
                },
            )
        }
    }
}

/**
 * A row of mutually exclusive views or values, as one pill (Sliders | Curve, the profiles). Unlike
 * [Chips] it never scrolls, so every option stays in sight. A dot marks options with edits.
 */
@Composable
private fun Segmented(
    options: List<String>, selected: Int, modifier: Modifier = Modifier,
    /** Share the full width equally, rather than each option as wide as its label. */
    fill: Boolean = false,
    marked: List<Boolean> = emptyList(), onSelect: (Int) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(18.dp)
    Row(modifier.height(36.dp).clip(shape).border(1.dp, Color(0xFF5A5A5A), shape).fadeWhileAdjusting()) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF5A5A5A)))
            Row(
                (if (fill) Modifier.weight(1f) else Modifier).fillMaxHeight().background(if (on) primary else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = if (on) MaterialTheme.colorScheme.onPrimary else Color.White
                Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                if (marked.getOrElse(i) { false }) Box(Modifier.padding(start = 6.dp).size(6.dp).background(if (on) ink else primary, CircleShape))
            }
        }
    }
}

/** The tabs of the open section, with a reset for the open panel at the end. */
@Composable
private fun PanelTabs(tabs: List<Tool>, open: Tool, s: Settings, onSelect: (Tool) -> Unit, onReset: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().height(48.dp).drawBehind {
            drawLine(Color(0xFF262626), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in tabs) {
            val on = t == open
            Row(
                Modifier.weight(1f).fillMaxHeight().testTag("tool-${t.name}").clickable { onSelect(t) }
                    .drawBehind { if (on) drawRect(primary, Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx())) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t.label, color = if (on) primary else Color(0xFFBDBDBD), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                if (t.changed(s)) ChangedDot(Modifier.padding(start = 5.dp))
            }
        }
        IconButton(onClick = onReset, enabled = open.changed(s)) {
            Icon(Icons.Outlined.RestartAlt, "Reset ${open.label}", tint = if (open.changed(s)) Color.White else Color(0xFF5A5A5A))
        }
    }
}

/** Color, Vivid, Neutral or B&W: the look everything else builds on. */
@Composable
private fun ProfilePicker(c: Controls) {
    Text("Profile", color = Color(0xFFA8A8A8), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
    Segmented(
        Profile.entries.map { if (it == Profile.MONOCHROME) "B&W" else it.label }, Profile.entries.indexOf(c.s.profile),
        Modifier.fillMaxWidth(), fill = true,
    ) { c.commit(c.s.copy(profile = Profile.entries[it])) }
}

/** [showProfile] when Looks isn't there to pick it (an edit battle's limited tools). */
@Composable
private fun LightPanel(c: Controls, onAuto: (() -> Unit)?, curve: Boolean, channel: Int, showProfile: Boolean, onCurve: (Boolean) -> Unit, onChannel: (Int) -> Unit) {
    val s = c.s
    if (showProfile) ProfilePicker(c)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Segmented(
            listOf("Sliders", "Curve"), if (curve) 1 else 0,
            marked = listOf(!s.withoutProfile().isDefault(Group.LIGHT), !s.isDefault(Group.CURVE)),
        ) { onCurve(it == 1) }
        Spacer(Modifier.weight(1f))
        if (onAuto != null && !curve) {
            FilledTonalButton(onClick = onAuto, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(36.dp).fadeWhileAdjusting()) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Auto", fontSize = 13.sp)
            }
        }
    }
    if (!curve) {
        for (p in listOf(P.EXPOSURE, P.CONTRAST, P.HIGHLIGHTS, P.SHADOWS, P.WHITES, P.BLACKS)) ParamSlider(p, c)
    } else {
        CurvePanel(c, channel, onChannel)
    }
}

private val CURVE_COLORS = listOf(Color.White, Color(0xFFFF5050), Color(0xFF50E050), Color(0xFF5090FF))

/** How far past the top or bottom (in output, 0..1) a swiped curve point is pushed before letting go removes it. */
private const val CURVE_REMOVE = 0.15f

@Composable
private fun CurvePanel(c: Controls, channel: Int, onChannel: (Int) -> Unit) {
    Chips(listOf("Parametric", "RGB", "Red", "Green", "Blue"), channel + 1) { onChannel(it - 1) }
    if (channel < 0) {
        for (p in listOf(P.CURVE_HIGHLIGHTS, P.CURVE_LIGHTS, P.CURVE_DARKS, P.CURVE_SHADOWS)) ParamSlider(p, c)
    } else {
        CurveEditor(
            curve = c.s.curves[channel],
            color = CURVE_COLORS[channel],
            onChange = { c.change(c.s.withCurve(channel, it)) },
            onDone = c.done,
            onReset = { c.commit(c.s.withCurve(channel, Curve())) },
        )
    }
}

/**
 * Point curve editor: a square graph, input along x, output up y. Built for fingers: a tap adds
 * a point on the curve (so nothing jumps), points move by the finger's motion rather than
 * snapping under it, the grab radius is generous, and dragging a point off the top or bottom
 * removes it.
 */
@Composable
private fun CurveEditor(curve: Curve, color: Color, onChange: (Curve) -> Unit, onDone: () -> Unit, onReset: () -> Unit) {
    val current by rememberUpdatedState(curve)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val grab = with(density) { 36.dp.toPx() }
    val removeAt = with(density) { 40.dp.toPx() }
    // The point under the finger (for the readout and highlight), and whether letting go removes it.
    var active by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var removing by remember { mutableStateOf(false) }

    fun nearest(pts: List<Pair<Float, Float>>, px: Float, py: Float, w: Float, h: Float, endpoints: Boolean = true): Int {
        val i = pts.indices.filter { endpoints || (it != 0 && it != pts.size - 1) }
            .minByOrNull { hypot(pts[it].first * w - px, (1 - pts[it].second) * h - py) } ?: return -1
        return if (hypot(pts[i].first * w - px, (1 - pts[i].second) * h - py) < grab) i else -1
    }

    /** Adds a point at input [x] on the current curve; returns the new list and its index. */
    fun addOnCurve(pts: MutableList<Pair<Float, Float>>, x: Float): Int {
        val cx = x.coerceIn(0.02f, 0.98f)
        if (pts.size >= 16 || pts.any { abs(it.first - cx) < 0.02f }) return -1
        val t = current.table(1024)
        val pt = cx to t[(cx * 1023).roundToInt()]
        pts += pt
        pts.sortBy { it.first }
        return pts.indexOf(pt)
    }

    val readout = active?.let { (x, y) -> if (removing) "Release to remove this point" else "Input ${(x * 255).roundToInt()}  →  Output ${(y * 255).roundToInt()}" }
    Text(
        readout ?: "Tap the curve to add a point. Drag to move it, or drag it off the graph to remove it.",
        color = if (removing) Color(0xFFFF8080) else Color.Gray, fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    // The graph fits in the panel with its readout, and leaves a strip on each side to scroll by.
    val side = (LocalPanelHeight.current - 60.dp).coerceIn(160.dp, 380.dp)
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 40.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.widthIn(max = side).fillMaxWidth().aspectRatio(1f)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { p ->
                            val pts = current.points.toMutableList()
                            if (nearest(pts, p.x, p.y, size.width.toFloat(), size.height.toFloat()) >= 0) return@detectTapGestures
                            if (addOnCurve(pts, p.x / size.width) >= 0) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                change(Curve(pts)); done()
                            }
                        },
                        onDoubleTap = { p ->
                            val pts = current.points.toMutableList()
                            val i = nearest(pts, p.x, p.y, size.width.toFloat(), size.height.toFloat(), endpoints = false)
                            if (i >= 0) {
                                pts.removeAt(i)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                change(Curve(pts)); done()
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    // The gesture keeps its own copy of the points: the curve passed in only
                    // catches up after recomposition, and indexing that stale list moved the wrong point.
                    var pts = mutableListOf<Pair<Float, Float>>()
                    var sel = -1
                    var px = 0f; var py = 0f
                    fun finish() {
                        if (sel >= 0 && removing) {
                            pts.removeAt(sel)
                            change(Curve(pts))
                        }
                        sel = -1; active = null; removing = false
                        done()
                    }
                    detectDragGestures(
                        onDragStart = { p ->
                            pts = current.points.toMutableList()
                            sel = nearest(pts, p.x, p.y, size.width.toFloat(), size.height.toFloat())
                            if (sel < 0) {
                                sel = addOnCurve(pts, p.x / size.width)
                                if (sel >= 0) change(Curve(pts))
                            }
                            if (sel >= 0) {
                                px = pts[sel].first; py = pts[sel].second
                                active = pts[sel]
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDrag = { ch, delta ->
                            ch.consume()
                            if (sel < 0) return@detectDragGestures
                            // Follow the finger's motion, so the point stays visible beside it.
                            px += delta.x / size.width
                            py -= delta.y / size.height
                            val endpoint = sel == 0 || sel == pts.size - 1
                            val margin = removeAt / size.height
                            val out = !endpoint && (py < -margin || py > 1 + margin)
                            if (out != removing) {
                                removing = out
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            val lo = if (sel == 0) 0f else pts[sel - 1].first + 0.01f
                            val hi = if (sel == pts.size - 1) 1f else pts[sel + 1].first - 0.01f
                            pts[sel] = px.coerceIn(lo, maxOf(lo, hi)) to py.coerceIn(0f, 1f)
                            active = pts[sel]
                            change(Curve(if (out) pts.filterIndexed { i, _ -> i != sel } else pts.toList()))
                        },
                        onDragEnd = { finish() },
                        onDragCancel = { finish() },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                for (i in 1..3) {
                    drawLine(Color(0xFF333333), Offset(w * i / 4, 0f), Offset(w * i / 4, h), 1f)
                    drawLine(Color(0xFF333333), Offset(0f, h * i / 4), Offset(w, h * i / 4), 1f)
                }
                drawLine(Color(0xFF444444), Offset(0f, h), Offset(w, 0f), 1f)
                val a = active
                if (a != null && !removing) {
                    val ax = a.first * w; val ay = (1 - a.second) * h
                    drawLine(color.copy(alpha = 0.35f), Offset(ax, 0f), Offset(ax, h), 1.dp.toPx())
                    drawLine(color.copy(alpha = 0.35f), Offset(0f, ay), Offset(w, ay), 1.dp.toPx())
                }
                val t = curve.table(256)
                val path = Path()
                t.forEachIndexed { i, v -> val x = w * i / 255f; val y = h * (1 - v); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                drawPath(path, color, style = Stroke(2.5.dp.toPx()))
                for (pt in curve.points) {
                    val o = Offset(pt.first * w, (1 - pt.second) * h)
                    val on = a != null && pt == a
                    drawCircle(color, (if (on) 10 else 7).dp.toPx(), o)
                    drawCircle(Color.Black, (if (on) 6 else 4).dp.toPx(), o)
                }
                if (a != null && removing) {
                    drawCircle(Color(0xFFFF8080), 10.dp.toPx(), Offset(a.first * w, (1 - a.second) * h), style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
    if (!curve.isIdentity) TextButton(onClick = onReset) { Text("Reset this curve") }
}

internal val TEMP_TRACK = Brush.horizontalGradient(listOf(Color(0xFF3D7BFF), Color(0xFFE0E0E0), Color(0xFFFFC42E)))
internal val TINT_TRACK = Brush.horizontalGradient(listOf(Color(0xFF3BC14A), Color(0xFFE0E0E0), Color(0xFFE040C8)))
internal val SAT_TRACK = Brush.horizontalGradient(listOf(Color(0xFF888888), Color(0xFFFF4040), Color(0xFFFFD000), Color(0xFF40C040), Color(0xFF4080FF)))

private fun hueColor(h: Float, s: Float = 0.85f, v: Float = 0.95f) = Color.hsv(((h % 360f) + 360f) % 360f, s, v)

@Composable
private fun ColorPanel(
    c: Controls, source: DevelopSource?,
    mode: Int, band: Int, zone: Int,
    onMode: (Int) -> Unit, onBand: (Int) -> Unit, onZone: (Int) -> Unit,
) {
    val s = c.s
    Segmented(
        listOf("Basic", "Mix", "Grading"), mode, Modifier.padding(top = 10.dp, bottom = 4.dp),
        marked = listOf(!s.isDefault(Group.COLOR), !s.isDefault(Group.MIXER), !s.isDefault(Group.GRADING)), onSelect = onMode,
    )
    when (mode) {
        0 -> BasicColor(c, source)
        1 -> MixPanel(c, band, onBand)
        else -> GradingPanel(c, zone, onZone)
    }
}

@Composable
private fun BasicColor(c: Controls, source: DevelopSource?) {
    val s = c.s
    val (k0, t0) = source?.asShot ?: (5500.0 to 0.0)
    val kelvin = s.temperature ?: k0.toFloat()
    val tint = s.tint ?: t0.toFloat()
    val preset = WhiteBalance.entries.firstOrNull { it.kelvin == s.temperature && it.tint == s.tint }
    SectionHeader("White balance", c)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp).fadeWhileAdjusting(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(selected = s.temperature == null, onClick = { c.commit(s.withWhiteBalance(null, null)) }, label = { Text("As Shot") })
        if (source?.isRaw != false) {
            for (wb in WhiteBalance.entries) {
                FilterChip(selected = preset == wb, onClick = { c.commit(s.withWhiteBalance(wb.kelvin, wb.tint)) }, label = { Text(wb.label) })
            }
        }
        if (s.temperature != null && preset == null) FilterChip(selected = true, onClick = {}, label = { Text("Custom") })
    }
    // Temperature moves in mireds so the slider feels even from tungsten to shade.
    val pos = ((500f - 1e6f / kelvin) / 460f).coerceIn(0f, 1f)
    AdjustSlider(
        "Temp", pos, 0f..1f, ((500f - 1e6f / k0.toFloat()) / 460f).coerceIn(0f, 1f),
        { "${(1e6f / (500f - it * 460f) / 50f).roundToInt() * 50}" }, TEMP_TRACK,
        { c.change(s.withWhiteBalance(1e6f / (500f - it * 460f), tint)) }, c.done,
        reset = { c.commit(s.withWhiteBalance(null, null)) },
        parse = { t -> parseNumber(t)?.takeIf { it > 0f }?.let { k -> (500f - 1e6f / k) / 460f } },
        changed = s.temperature != null,
    )
    AdjustSlider(
        "Tint", tint, -150f..150f, t0.toFloat(), { signed(it, 0) }, TINT_TRACK,
        { c.change(s.withWhiteBalance(kelvin, it)) }, c.done,
        reset = { c.commit(s.withWhiteBalance(null, null)) },
        changed = s.tint != null,
    )
    Spacer(Modifier.height(8.dp))
    ParamSlider(P.VIBRANCE, c, track = SAT_TRACK)
    ParamSlider(P.SATURATION, c, track = SAT_TRACK)
}

@Composable
private fun MixPanel(c: Controls, band: Int, onBand: (Int) -> Unit) {
    SectionHeader(if (c.s.profile == Profile.MONOCHROME) "B&W mix" else "Color mix", c, Group.MIXER)
    if (c.s.profile == Profile.MONOCHROME) {
        P.MIX_BANDS.forEachIndexed { i, (name, hue) ->
            ParamSlider(P.MIX_LUM[i], c, name.replaceFirstChar { it.uppercase() }, Brush.horizontalGradient(listOf(Color.Black, hueColor(hue), Color.White)))
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).fadeWhileAdjusting(), horizontalArrangement = Arrangement.SpaceEvenly) {
        P.MIX_BANDS.forEachIndexed { i, (_, hue) ->
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(hueColor(hue))
                    .then(if (i == band) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                    .clickable { onBand(i) },
            )
        }
    }
    val hue = P.MIX_BANDS[band].second
    ParamSlider(P.MIX_HUE[band], c, "Hue", Brush.horizontalGradient(listOf(hueColor(hue - 30), hueColor(hue), hueColor(hue + 30))))
    ParamSlider(P.MIX_SAT[band], c, "Saturation", Brush.horizontalGradient(listOf(hueColor(hue, 0f, 0.7f), hueColor(hue))))
    ParamSlider(P.MIX_LUM[band], c, "Luminance", Brush.horizontalGradient(listOf(hueColor(hue, 0.9f, 0.25f), hueColor(hue, 0.5f, 1f))))
}

@Composable
private fun GradingPanel(c: Controls, zone: Int, onZone: (Int) -> Unit) {
    SectionHeader("Color grading", c, Group.GRADING)
    Chips(
        listOf("Shadows", "Midtones", "Highlights", "Global"), zone,
        List(4) { z -> listOf(P.GRADE_HUE[z], P.GRADE_SAT[z], P.GRADE_LUM[z]).any { it.key in c.s.values } },
        onZone,
    )
    ColorWheel(
        hue = c.s[P.GRADE_HUE[zone]], sat = c.s[P.GRADE_SAT[zone]],
        onChange = { h, sat -> c.change(c.s.with(P.GRADE_HUE[zone] to h, P.GRADE_SAT[zone] to sat)) },
        onDone = c.done,
    )
    ParamSlider(P.GRADE_HUE[zone], c)
    ParamSlider(P.GRADE_SAT[zone], c)
    ParamSlider(P.GRADE_LUM[zone], c)
    ParamSlider(P.GRADE_BLENDING, c)
    ParamSlider(P.GRADE_BALANCE, c)
}

/** Hue around, strength outwards; drag the dot. */
@Composable
private fun ColorWheel(hue: Float, sat: Float, onChange: (Float, Float) -> Unit, onDone: () -> Unit) {
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(170.dp).pointerInput(Unit) {
                fun set(p: Offset) {
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val dx = p.x - cx; val dy = cy - p.y
                    val h = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() % 360f) + 360f) % 360f
                    val s = (hypot(dx, dy) / cx * 100f).coerceIn(0f, 100f)
                    change(h, s)
                }
                detectDragGestures(
                    onDragStart = { set(it) },
                    onDrag = { ch, _ -> ch.consume(); set(ch.position) },
                    onDragEnd = { done() },
                    onDragCancel = { done() },
                )
            },
        ) {
            Canvas(Modifier.fillMaxSize()) { drawHueWheel(hue, sat) }
        }
    }
}

/** White balance pad: temperature across (blue to yellow), tint up (magenta) and down (green); dot at ([x], [y]) in -1..1. */
private fun DrawScope.drawWbPad(x: Float, y: Float) {
    drawRect(TEMP_TRACK)
    drawRect(Brush.verticalGradient(listOf(Color(0xAAE040C8), Color(0x00808080), Color(0xAA3BC14A))))
    drawRect(Color.White.copy(alpha = 0.6f), style = Stroke(1f))
    val p = Offset((x + 1) / 2 * size.width, (1 - y) / 2 * size.height)
    drawCircle(Color.Black, 8.dp.toPx(), p)
    drawCircle(Color.White, 6.dp.toPx(), p)
}

/** Where swipe mode draws its big curve over a [w] × [h] photo area, and where the right thumb edits it. */
private fun curveOverlayRect(w: Float, h: Float): Rect {
    val side = minOf(w, h) * 0.8f
    return Rect((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
}

/** Swipe mode's big curve in [r], over the photo: the point being dragged ([active]) highlighted, and ringed red when [removing]. */
private fun DrawScope.drawCurveOverlay(r: Rect, curve: Curve, color: Color, active: Pair<Float, Float>?, removing: Boolean) {
    val w = r.width; val h = r.height
    fun at(x: Float, y: Float) = Offset(r.left + x * w, r.top + (1 - y) * h)
    drawRect(Color.Black.copy(alpha = 0.45f), r.topLeft, r.size)
    for (i in 1..3) {
        drawLine(Color.White.copy(alpha = 0.15f), at(i / 4f, 0f), at(i / 4f, 1f), 1f)
        drawLine(Color.White.copy(alpha = 0.15f), at(0f, i / 4f), at(1f, i / 4f), 1f)
    }
    drawLine(Color.White.copy(alpha = 0.25f), at(0f, 0f), at(1f, 1f), 1f)
    if (active != null && !removing) {
        drawLine(color.copy(alpha = 0.35f), at(active.first, 0f), at(active.first, 1f), 1.dp.toPx())
        drawLine(color.copy(alpha = 0.35f), at(0f, active.second), at(1f, active.second), 1.dp.toPx())
    }
    val t = curve.table(256)
    val path = Path()
    t.forEachIndexed { i, v -> val p = at(i / 255f, v); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    drawPath(path, color, style = Stroke(2.5.dp.toPx()))
    for (pt in curve.points) {
        val on = active != null && pt == active
        drawCircle(color, (if (on) 10 else 7).dp.toPx(), at(pt.first, pt.second))
        drawCircle(Color.Black, (if (on) 6 else 4).dp.toPx(), at(pt.first, pt.second))
    }
    if (active != null && removing) drawCircle(Color(0xFFFF8080), 10.dp.toPx(), at(active.first, active.second), style = Stroke(2.dp.toPx()))
}

/** The color wheel with its dot: hue around (counter-clockwise from red at 3 o'clock), saturation 0..100 outwards. */
private fun DrawScope.drawHueWheel(hue: Float, sat: Float) {
    val r = size.minDimension / 2
    // Sweep runs clockwise on screen; hue runs counter-clockwise, so mirror it.
    val sweep = (0..12).map { hueColor(360f - it * 30f, 1f, 1f) }
    drawCircle(Brush.sweepGradient(sweep, center), r)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF808080), Color(0x00808080)), center, r), r)
    drawCircle(Color.White.copy(alpha = 0.6f), r, style = Stroke(1f))
    val a = hue / 180f * PI.toFloat(); val d = sat / 100f * r
    val p = Offset(center.x + cos(a) * d, center.y - sin(a) * d)
    drawCircle(Color.Black, 8.dp.toPx(), p)
    drawCircle(Color.White, 6.dp.toPx(), p)
}

@Composable
private fun EffectsPanel(c: Controls) {
    ParamSlider(P.TEXTURE, c)
    ParamSlider(P.CLARITY, c)
    ParamSlider(P.DEHAZE, c)
    ParamSlider(P.VIGNETTE, c)
    if (c.s[P.VIGNETTE] != 0f) {
        for (p in listOf(P.VIGNETTE_MIDPOINT, P.VIGNETTE_FEATHER, P.VIGNETTE_ROUNDNESS, P.VIGNETTE_HIGHLIGHTS)) ParamSlider(p, c, "   ${p.label}")
    }
    ParamSlider(P.GRAIN, c)
    if (c.s[P.GRAIN] != 0f) {
        ParamSlider(P.GRAIN_SIZE, c, "   Size")
        ParamSlider(P.GRAIN_ROUGHNESS, c, "   Roughness")
    }
}

@Composable
private fun DetailPanel(c: Controls) {
    ParamSlider(P.SHARPEN, c)
    if (c.s[P.SHARPEN] != 0f) {
        ParamSlider(P.SHARPEN_RADIUS, c, "   Radius")
        ParamSlider(P.SHARPEN_DETAIL, c, "   Detail")
        ParamSlider(P.SHARPEN_MASKING, c, "   Masking")
    }
    ParamSlider(P.NOISE, c)
    if (c.s[P.NOISE] != 0f) ParamSlider(P.NOISE_DETAIL, c, "   Detail")
    ParamSlider(P.COLOR_NOISE, c)
    Text("Zoom in to judge sharpening and noise at full resolution.", color = Color.Gray, fontSize = 11.sp)
}

@Composable
private fun OpticsPanel(c: Controls, hasLensProfile: Boolean, isRaw: Boolean) {
    if (isRaw) {
        ParamSwitch(P.REMOVE_CA, c)
        ParamSwitch(P.LENS_PROFILE, c)
        if (!hasLensProfile) {
            Text("This raw has no lens correction data (manual lens or corrections off in camera).", color = Color.Gray, fontSize = 11.sp)
        } else if (c.s.on(P.LENS_PROFILE)) {
            ParamSlider(P.LENS_DISTORTION, c, "   Distortion")
            ParamSlider(P.LENS_VIGNETTING, c, "   Vignetting")
        }
    }
    ParamSlider(P.DEFRINGE, c)
    SectionHeader("Manual", c)
    ParamSlider(P.MANUAL_DISTORTION, c)
    ParamSlider(P.MANUAL_VIGNETTING, c)
    if (c.s[P.MANUAL_VIGNETTING] != 0f) ParamSlider(P.MANUAL_VIGNETTING_MIDPOINT, c, "   Midpoint")
}

@Composable
private fun GeometryPanel(c: Controls) {
    for (p in listOf(P.VERTICAL, P.HORIZONTAL, P.ROTATE, P.SCALE, P.OFFSET_X, P.OFFSET_Y)) ParamSlider(p, c)
    Text("A grid shows while you drag, to line up verticals.", color = Color.Gray, fontSize = 11.sp)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun PresetsPanel(c: Controls, presets: List<Preset>, current: Settings, onApply: (Preset) -> Unit, onDelete: (Preset) -> Unit, onCreate: () -> Unit) {
    var confirm by remember { mutableStateOf<Preset?>(null) }
    ProfilePicker(c)
    Text("Film stocks", color = Color(0xFFA8A8A8), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in presets.filter { it.film }) LookChip(p.name, p.isApplied(current), { onApply(p) })
    }
    LutPicker(c)
    Text("Presets", color = Color(0xFFA8A8A8), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
    Text("Tap to apply. A look replaces the last one and keeps your exposure, white balance, crop and masks. Long-press your own presets to delete them.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in presets.filter { !it.film }) {
            val on = p.isApplied(current)
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (p.builtIn) Color(0xFF2A2A2A) else Color(0xFF263A4A))
                    .then(if (on) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                    .combinedClickable(onClick = { onApply(p) }, onLongClick = { if (!p.builtIn) confirm = p })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(p.name, color = Color.White, fontSize = 13.sp) }
        }
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                .clickable { onCreate() }.padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text("+ Create preset", color = Color.White, fontSize = 13.sp) }
    }
    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete \"${p.name}\"?") },
            confirmButton = { TextButton(onClick = { onDelete(p); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LookChip(label: String, on: Boolean, onClick: () -> Unit, onLong: () -> Unit = {}) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2A))
            .then(if (on) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(label, color = Color.White, fontSize = 13.sp) }
}

/**
 * Film LUTs imported on this phone (.cube, Hald PNG, or a .zip of them), grouped by the folder
 * they came from, with the amount once one is on. Long-press one to delete it or its folder.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LutPicker(c: Controls) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var luts by remember { mutableStateOf(LutLibrary.ensure(context)) }
    var importing by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<LutLibrary.Entry?>(null) }
    val folders = luts.map { it.folder }.distinct()
    var folder by remember { mutableStateOf(luts.firstOrNull { it.id == c.s.lut }?.folder ?: folders.firstOrNull()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = 0
        message = null
        scope.launch {
            val added = ArrayList<LutLibrary.Entry>()
            var skipped = 0
            withContext(Dispatchers.IO) {
                for (u in uris) {
                    val before = added.size
                    val r = runCatching { LutLibrary.import(context, u) { n -> importing = before + n } }.getOrNull()
                    if (r == null) skipped++ else { added += r.added; skipped += r.skipped }
                }
            }
            luts = LutLibrary.ensure(context)
            importing = null
            added.firstOrNull()?.let { folder = it.folder }
            message = when {
                added.isEmpty() -> "No LUTs found. Pick .cube files, Hald PNGs, or a .zip of them."
                skipped > 0 -> "Added ${added.size} looks; $skipped files weren't LUTs."
                else -> "Added ${added.size} looks."
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp).fadeWhileAdjusting(), verticalAlignment = Alignment.CenterVertically) {
        Text("Film LUTs", color = Color(0xFFA8A8A8), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        TextButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = importing == null) { Text("+ Import", fontSize = 12.sp) }
    }
    importing?.let { n ->
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        Text(if (n == 0) "Importing…" else "Importing… $n looks so far", color = Color.Gray, fontSize = 11.sp)
    }
    message?.let { Text(it, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp)) }
    if (luts.isEmpty()) {
        if (importing == null) Text(
            "Import film looks as .cube files or Hald PNGs, or a whole pack as a .zip (RawTherapee's free film simulation pack works).",
            color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp),
        )
    } else {
        if (folders.size > 1) Chips(folders, folders.indexOf(folder).coerceAtLeast(0), marked = folders.map { f -> luts.any { it.folder == f && it.id == c.s.lut } }) { folder = folders[it] }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp).fadeWhileAdjusting()) {
            LookChip("None", c.s.lut == null, { c.commit(c.s.copy(lut = null)) })
            for (e in luts.filter { it.folder == (folder ?: folders.first()) }) {
                LookChip(e.name, e.id == c.s.lut, { c.commit(c.s.copy(lut = if (e.id == c.s.lut) null else e.id)) }, { confirm = e })
            }
        }
    }
    if (c.s.lut != null) {
        if (luts.none { it.id == c.s.lut }) {
            Text("This edit uses a LUT that isn't on this phone.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
        } else {
            ParamSlider(P.LUT_AMOUNT, c)
        }
    }
    confirm?.let { e ->
        val inFolder = luts.count { it.folder == e.folder }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete \"${e.name}\"?") },
            text = { Text("Photos that use it go back to no LUT.") },
            confirmButton = {
                Row {
                    if (inFolder > 1) TextButton(onClick = {
                        LutLibrary.deleteFolder(context, e.folder); luts = LutLibrary.ensure(context); folder = luts.firstOrNull()?.folder; confirm = null
                    }) { Text("All $inFolder in ${e.folder}") }
                    TextButton(onClick = {
                        LutLibrary.delete(context, e.id); luts = LutLibrary.ensure(context)
                        if (luts.none { it.folder == folder }) folder = luts.firstOrNull()?.folder
                        confirm = null
                    }) { Text("Delete") }
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Dialogs

@Composable
private fun GroupsDialog(
    title: String,
    confirm: String,
    initial: Set<Group>,
    askName: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (Set<Group>, String) -> Unit,
) {
    var groups by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (askName) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                }
                for (g in Group.entries) {
                    Row(
                        Modifier.fillMaxWidth().clickable { groups = if (g in groups) groups - g else groups + g },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = g in groups, onCheckedChange = { groups = if (it) groups + g else groups - g })
                        Text(g.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(groups, name.trim()) }, enabled = groups.isNotEmpty() && (!askName || name.isNotBlank())) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExportDialog(initial: ExportOptions, onDismiss: () -> Unit, onSave: (ExportOptions) -> Unit) {
    var options by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export options") },
        text = {
            Column {
                Text("Size", fontWeight = FontWeight.Medium)
                Chips(ExportOptions.SIZES.map { it.first }, ExportOptions.SIZES.indexOfFirst { it.second == options.maxEdge }) {
                    options = options.copy(maxEdge = ExportOptions.SIZES[it].second)
                }
                Spacer(Modifier.height(8.dp))
                Text("JPEG quality", fontWeight = FontWeight.Medium)
                Chips(ExportOptions.QUALITIES.map { it.first }, ExportOptions.QUALITIES.indexOfFirst { it.second == options.quality }) {
                    options = options.copy(quality = ExportOptions.QUALITIES[it].second)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(options) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun Filmstrip(photos: List<LibraryPhoto>, index: Int, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, index - 2))
    LaunchedEffect(index) { state.animateScrollToItem(maxOf(0, index - 2)) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
            val sel = i == index
            Thumb(
                p,
                modifier = Modifier.size(48.dp)
                    .then(if (sel) Modifier.border(2.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier)
                    .clickable { onSelect(i) },
            )
        }
    }
}

@Composable
private fun Histogram(h: Array<IntArray>, modifier: Modifier) {
    val colors = listOf(Color(0xFFFF5050), Color(0xFF50FF50), Color(0xFF5080FF))
    Canvas(modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))) {
        // Ignore the end bins when scaling so clipping doesn't flatten the rest.
        val peak = h.maxOf { ch -> (1..254).maxOf { ch[it] } }.coerceAtLeast(1)
        for (c in 0..2) {
            val path = Path()
            path.moveTo(0f, size.height)
            for (i in 0..255) {
                val x = i / 255f * size.width
                val y = size.height * (1f - minOf(1f, h[c][i].toFloat() / peak))
                path.lineTo(x, y)
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, colors[c].copy(alpha = 0.45f))
        }
    }
}

internal fun signed(v: Float, decimals: Int): String {
    val r = if (decimals == 0) "${v.roundToInt()}" else "%.${decimals}f".format(v)
    return if (v > 0.0005f && (decimals > 0 || v.roundToInt() != 0)) "+$r" else if (decimals == 0 && v.roundToInt() == 0) "0" else r
}

internal fun plain(v: Float, decimals: Int) = if (decimals == 0) "${v.roundToInt()}" else "%.${decimals}f".format(v)

/**
 * A Lightroom-style slider row. Drag the slider (slower as your finger moves away from it), tap
 * the value to type one, double-tap the slider or double-tap/long-press the label to reset. A dot
 * marks a change.
 */
@Composable
internal fun AdjustSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    format: (Float) -> String,
    track: Brush?,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    reset: (() -> Unit)? = null,
    parse: (String) -> Float? = ::parseNumber,
    changed: Boolean = abs(value - default) > 1e-4f,
) {
    var active by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var typing by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val fade by animateFloatAsState(if (LocalAdjusting.current && !active) 0f else 1f, label = "fade")
    val resetNow by rememberUpdatedState({ if (reset != null) reset() else { onChange(default); onDone() } })
    // One line: label, slider, value, so a whole panel fits without scrolling. A label that starts
    // with spaces belongs to the slider above it and is indented.
    val sub = label.startsWith(" ")
    Row(Modifier.fillMaxWidth().height(44.dp).graphicsLayer { alpha = fade }, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.width(104.dp).fillMaxHeight().pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { resetNow() },
                    onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); resetNow() },
                )
            }.padding(start = if (sub) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // While scrubbing slowly, the label shows the speed.
            val text = if (speed < 1f) speedLabel(speed) else label.trim()
            Text(
                text, color = if (speed < 1f || sub) Color(0xFFBDBDBD) else Color.White, fontSize = 13.sp,
                lineHeight = 15.sp, maxLines = 2, modifier = Modifier.weight(1f, fill = false),
            )
            if (changed) ChangedDot(Modifier.padding(start = 5.dp))
        }
        PrecisionSlider(
            value = value, range = range, default = default, track = track,
            onChange = onChange, onDone = onDone,
            onActive = { active = it }, onSpeed = { speed = it },
            onDoubleTap = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); resetNow() },
            modifier = Modifier.weight(1f),
        )
        Text(
            format(value), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp).clip(RoundedCornerShape(4.dp)).clickable { typing = true }.padding(vertical = 8.dp),
        )
    }
    if (typing) {
        ValueDialog(label, format(value), onDismiss = { typing = false }) { text ->
            parse(text)?.let { onChange(it.coerceIn(range.start, range.endInclusive)); onDone() }
            typing = false
        }
    }
}

private fun shareImage(context: Context, uri: Uri) {
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share photo",
        ),
    )
}
