package dev.kogler.a7iiiconnect.develop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Filter(val label: String) { ALL("All"), RAW("RAW"), JPEG("JPEG"), EDITED("Edited") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    photos: List<LibraryPhoto>,
    edited: Set<Long>,
    onOpen: (LibraryPhoto) -> Unit,
    onBack: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    val shown = when (filter) {
        Filter.ALL -> photos
        Filter.RAW -> photos.filter { it.isRaw }
        Filter.JPEG -> photos.filter { !it.isRaw }
        Filter.EDITED -> photos.filter { it.id in edited }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit photos") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (f in Filter.entries) {
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (photos.isEmpty()) "Photos from the camera will show up here" else "Nothing here",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.id }) { photo ->
                        Thumb(photo, photo.id in edited, Modifier.aspectRatio(1f).clickable { onOpen(photo) })
                    }
                }
            }
        }
    }
}

@Composable
fun Thumb(photo: LibraryPhoto, edited: Boolean, modifier: Modifier, badges: Boolean = true) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(Photos.cachedThumbnail(photo)?.asImageBitmap(), photo.id) {
        if (value == null) value = withContext(Dispatchers.IO) { Photos.thumbnail(context, photo)?.asImageBitmap() }
    }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        bitmap?.let { Image(it, photo.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (badges) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (photo.isRaw) {
                    Text(
                        "RAW",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                if (edited) {
                    Icon(
                        Icons.Rounded.Tune, "Edited", tint = Color.White,
                        modifier = Modifier.size(16.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(2.dp),
                    )
                }
            }
        }
    }
}
