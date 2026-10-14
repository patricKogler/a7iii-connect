package dev.kogler.a7iiiconnect.develop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kogler.a7iiiconnect.develop.engine.DevelopSource
import dev.kogler.a7iiiconnect.develop.engine.Profile
import dev.kogler.a7iiiconnect.develop.engine.Renderer
import dev.kogler.a7iiiconnect.develop.engine.Settings
import dev.kogler.a7iiiconnect.develop.engine.WhiteBalance
import dev.kogler.a7iiiconnect.develop.engine.histogram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class Tool(val label: String, val icon: ImageVector) {
    LIGHT("Light", Icons.Outlined.WbSunny),
    COLOR("Color", Icons.Outlined.Palette),
}

/** Undo/redo history of one photo's settings. */
private class History(initial: Settings) {
    private val steps = mutableListOf(initial)
    private var at = 0
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

private class Rendered(val image: ImageBitmap, val histogram: Array<IntArray>)

@Composable
fun EditorScreen(
    photos: List<LibraryPhoto>,
    startIndex: Int,
    store: EditStore,
    onBack: () -> Unit,
    onPhotoChanged: (LibraryPhoto) -> Unit,
) {
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
    var tool by remember { mutableStateOf<Tool?>(Tool.LIGHT) }
    var menu by remember { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf<Float?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // Open the photo: show the camera's embedded JPEG at once, then the real render.
    LaunchedEffect(photo.id) {
        opened = null; rendered = null; before = null; loadError = null
        settings = store.load(photo.id)
        history = History(settings)
        placeholder = Photos.cachedThumbnail(photo)?.asImageBitmap()
        if (photo.isRaw) {
            withContext(Dispatchers.IO) { runCatching { Photos.embeddedPreview(context, photo.uri, 1600) }.getOrNull() }
                ?.let { placeholder = it.asImageBitmap() }
        }
        val result = withContext(Dispatchers.Default) { runCatching { Photos.open(context, photo) } }
        result.onSuccess { opened = it }.onFailure { loadError = it.message ?: it.javaClass.simpleName }
    }

    // Re-render on every change; while a slider moves, use the smaller draft for speed.
    LaunchedEffect(opened) {
        val o = opened ?: return@LaunchedEffect
        val pool = BitmapPool()
        snapshotFlow { settings to dragging }.collectLatest { (s, d) ->
            val src = if (d) o.draft else o.preview
            rendered = withContext(Dispatchers.Default) { pool.render(src, s) }
        }
    }

    LaunchedEffect(showBefore, opened) {
        val o = opened ?: return@LaunchedEffect
        if (showBefore && before == null) {
            before = withContext(Dispatchers.Default) { BitmapPool().render(o.preview, Settings()).image }
        }
    }

    fun commit(s: Settings) {
        settings = s
        history.push(s)
        store.save(photo.id, s)
    }

    fun go(i: Int) {
        if (i == index || i !in photos.indices) return
        store.save(photo.id, settings)
        index = i
        onPhotoChanged(photos[i])
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Top bar
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { store.save(photo.id, settings); onBack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                }
                Text(
                    photo.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { settings = history.undo(); store.save(photo.id, settings) }, enabled = history.canUndo) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", tint = if (history.canUndo) Color.White else Color.Gray)
                }
                IconButton(onClick = { settings = history.redo(); store.save(photo.id, settings) }, enabled = history.canRedo) {
                    Icon(Icons.AutoMirrored.Rounded.Redo, "Redo", tint = if (history.canRedo) Color.White else Color.Gray)
                }
                Box {
                    IconButton(onClick = { shareMenu = true }, enabled = opened != null && exportProgress == null) {
                        Icon(Icons.Rounded.Share, "Export", tint = Color.White)
                    }
                    DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                        for (share in listOf(false, true)) {
                            DropdownMenuItem(
                                text = { Text(if (share) "Share JPEG" else "Save JPEG to gallery") },
                                onClick = {
                                    shareMenu = false
                                    val o = opened ?: return@DropdownMenuItem
                                    val s = settings
                                    exportProgress = 0f
                                    scope.launch {
                                        val result = withContext(Dispatchers.Default) {
                                            runCatching { Photos.export(context, o, s) { p -> exportProgress = p } }
                                        }
                                        exportProgress = null
                                        result.onSuccess { uri ->
                                            if (share) shareImage(context, uri)
                                            else {
                                                val r = snackbar.showSnackbar(
                                                    "Saved to Pictures/${Photos.EXPORT_ALBUM}", "Share",
                                                    duration = SnackbarDuration.Short,
                                                )
                                                if (r == SnackbarResult.ActionPerformed) shareImage(context, uri)
                                            }
                                        }.onFailure { snackbar.showSnackbar("Export failed: ${it.message}") }
                                    }
                                },
                            )
                        }
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
                            onClick = { menu = false; store.clipboard?.let { commit(it) } },
                        )
                        DropdownMenuItem(text = { Text("Reset all") }, onClick = { menu = false; commit(Settings()) })
                    }
                }
            }
            exportProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }

            // Photo
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp))) {
                val shown = if (showBefore) before ?: rendered?.image else rendered?.image ?: placeholder
                ZoomableImage(shown, onHold = { showBefore = it })
                if (opened == null && loadError == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White)
                }
                loadError?.let {
                    Text("Can't open this photo: $it", color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                }
                rendered?.histogram?.let { Histogram(it, Modifier.align(Alignment.TopEnd).padding(8.dp).size(120.dp, 56.dp)) }
                if (showBefore) {
                    Text(
                        "Before", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(44.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = { showBefore = true; tryAwaitRelease(); showBefore = false })
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Compare, "Hold to see the original", tint = Color.White) }
            }

            Filmstrip(photos, index, onSelect = { go(it) })

            // Adjustment panel
            val o = opened
            if (tool != null) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    val change: (Settings) -> Unit = { settings = it; dragging = true }
                    val done: () -> Unit = { dragging = false; commit(settings) }
                    when (tool) {
                        Tool.LIGHT -> LightPanel(settings, change, done, commit = { commit(it) })
                        Tool.COLOR -> ColorPanel(settings, o?.preview, change, done, commit = { commit(it) })
                        null -> {}
                    }
                }
            }

            // Tool bar
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                for (t in Tool.entries) {
                    val selected = tool == t
                    Column(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { tool = if (selected) null else t }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val c = if (selected) MaterialTheme.colorScheme.primary else Color.White
                        Icon(t.icon, null, tint = c)
                        Text(t.label, color = c, fontSize = 12.sp)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.padding(bottom = 120.dp))
    }
}

/** Two bitmaps per size, alternated, so the one on screen is never being written. */
private class BitmapPool {
    private val bitmaps = HashMap<Long, Array<Bitmap>>()
    private var flip = 0

    fun render(src: DevelopSource, s: Settings): Rendered {
        val img = src.image
        val key = (img.width.toLong() shl 32) or img.height.toLong()
        val pair = bitmaps.getOrPut(key) {
            Array(2) { Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888) }
        }
        flip = 1 - flip
        val bmp = pair[flip]
        val px = IntArray(img.width * img.height)
        Renderer(src, s).render(img, px)
        bmp.setPixels(px, 0, img.width, 0, 0, img.width, img.height)
        return Rendered(bmp.asImageBitmap(), histogram(px))
    }
}

@Composable
private fun ZoomableImage(image: ImageBitmap?, onHold: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 3f },
                    onLongPress = { onHold(true) },
                    onPress = { tryAwaitRelease(); onHold(false) },
                )
            },
    ) {
        if (image != null) {
            Image(
                image, null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
        }
    }
}

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
                p, edited = false, badges = false,
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

// ---------------------------------------------------------------------------------------------
// Panels

@Composable
private fun LightPanel(s: Settings, change: (Settings) -> Unit, done: () -> Unit, commit: (Settings) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Profile", color = Color.White, fontSize = 13.sp)
        for (p in Profile.entries) {
            FilterChip(selected = s.profile == p, onClick = { commit(s.copy(profile = p)) }, label = { Text(p.label) })
        }
    }
    AdjustSlider("Exposure", s.exposure, -5f..5f, 0f, { signed(it, 2) }, null, { change(s.copy(exposure = it)) }, done)
    AdjustSlider("Contrast", s.contrast, -100f..100f, 0f, { signed(it, 0) }, null, { change(s.copy(contrast = it)) }, done)
    AdjustSlider("Highlights", s.highlights, -100f..100f, 0f, { signed(it, 0) }, null, { change(s.copy(highlights = it)) }, done)
    AdjustSlider("Shadows", s.shadows, -100f..100f, 0f, { signed(it, 0) }, null, { change(s.copy(shadows = it)) }, done)
    AdjustSlider("Whites", s.whites, -100f..100f, 0f, { signed(it, 0) }, null, { change(s.copy(whites = it)) }, done)
    AdjustSlider("Blacks", s.blacks, -100f..100f, 0f, { signed(it, 0) }, null, { change(s.copy(blacks = it)) }, done)
}

private val TEMP_TRACK = Brush.horizontalGradient(listOf(Color(0xFF3D7BFF), Color(0xFFE0E0E0), Color(0xFFFFC42E)))
private val TINT_TRACK = Brush.horizontalGradient(listOf(Color(0xFF3BC14A), Color(0xFFE0E0E0), Color(0xFFE040C8)))
private val SAT_TRACK = Brush.horizontalGradient(listOf(Color(0xFF888888), Color(0xFFFF4040), Color(0xFFFFD000), Color(0xFF40C040), Color(0xFF4080FF)))

@Composable
private fun ColorPanel(s: Settings, source: DevelopSource?, change: (Settings) -> Unit, done: () -> Unit, commit: (Settings) -> Unit) {
    val (k0, t0) = source?.asShot ?: (5500.0 to 0.0)
    val kelvin = s.temperature ?: k0.toFloat()
    val tint = s.tint ?: t0.toFloat()
    val preset = WhiteBalance.entries.firstOrNull { it.kelvin == s.temperature && it.tint == s.tint }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(selected = s.temperature == null, onClick = { commit(s.copy(temperature = null, tint = null)) }, label = { Text("As Shot") })
        if (source?.isRaw != false) {
            for (wb in WhiteBalance.entries) {
                FilterChip(selected = preset == wb, onClick = { commit(s.copy(temperature = wb.kelvin, tint = wb.tint)) }, label = { Text(wb.label) })
            }
        }
        if (s.temperature != null && preset == null) FilterChip(selected = true, onClick = {}, label = { Text("Custom") })
    }
    // Temperature moves in mireds so the slider feels even from tungsten to shade.
    val pos = ((500f - 1e6f / kelvin) / 460f).coerceIn(0f, 1f)
    AdjustSlider(
        "Temp", pos, 0f..1f, ((500f - 1e6f / k0.toFloat()) / 460f).coerceIn(0f, 1f),
        { "${(1e6f / (500f - it * 460f) / 50f).roundToInt() * 50}" }, TEMP_TRACK,
        { change(s.copy(temperature = 1e6f / (500f - it * 460f), tint = tint)) }, done,
        reset = { commit(s.copy(temperature = null, tint = null)) },
    )
    AdjustSlider(
        "Tint", tint, -150f..150f, t0.toFloat(), { signed(it, 0) }, TINT_TRACK,
        { change(s.copy(temperature = kelvin, tint = it)) }, done,
        reset = { commit(s.copy(temperature = null, tint = null)) },
    )
    Spacer(Modifier.height(8.dp))
    AdjustSlider("Vibrance", s.vibrance, -100f..100f, 0f, { signed(it, 0) }, SAT_TRACK, { change(s.copy(vibrance = it)) }, done)
    AdjustSlider("Saturation", s.saturation, -100f..100f, 0f, { signed(it, 0) }, SAT_TRACK, { change(s.copy(saturation = it)) }, done)
}

private fun signed(v: Float, decimals: Int): String {
    val r = if (decimals == 0) "${v.roundToInt()}" else "%.${decimals}f".format(v)
    return if (v > 0.0005f) "+$r" else if (decimals == 0 && v.roundToInt() == 0) "0" else r
}

/** A Lightroom-style slider row. Double-tap the label to reset it. */
@Composable
private fun AdjustSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    format: (Float) -> String,
    track: Brush?,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    reset: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().pointerInput(label) {
                detectTapGestures(onDoubleTap = { if (reset != null) reset() else { onChange(default); onDone() } })
            }.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(format(value), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Box(contentAlignment = Alignment.Center) {
            if (track != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(track))
            }
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onChange,
                onValueChangeFinished = onDone,
                valueRange = range,
                colors = if (track != null) SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                ) else SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF9A9A9A),
                    inactiveTrackColor = Color(0xFF4A4A4A),
                ),
            )
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
