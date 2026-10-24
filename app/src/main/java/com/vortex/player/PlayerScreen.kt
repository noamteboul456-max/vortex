@file:OptIn(ExperimentalMaterial3Api::class)

package com.vortex.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

// ───────────────────────────── Root ─────────────────────────────

@Composable
fun PlayerScreen(vm: PlayerViewModel, onUnlockRequest: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ghost by vm.ghost.state.collectAsStateWithLifecycle()
    val ghostMessage by vm.ghost.message.collectAsStateWithLifecycle()
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    val thresholdPx = with(LocalDensity.current) { 140.dp.toPx() }

    LaunchedEffect(ghost) { if (ghost == GhostState.LOCKED) showPlayer = false }

    Surface(modifier = Modifier.fillMaxSize(), color = Obsidian) {
        Box(
            Modifier
                .fillMaxSize()
                .twoFingerSwipeDown(thresholdPx) { vm.ghost.lock() }
        ) {
            if (ghost == GhostState.LOCKED) {
                GhostOverlay(message = ghostMessage, onUnlockRequest = onUnlockRequest)
            } else {
                BackHandler(enabled = showPlayer) { showPlayer = false }
                if (showPlayer && ui.current != null) {
                    FullPlayer(ui = ui, vm = vm, onClose = { showPlayer = false })
                } else {
                    Library(ui = ui, vm = vm, onOpenPlayer = { showPlayer = true })
                }
            }
        }
    }
}

/**
 * Two-finger downward swipe detector. Watches the Initial pass so it never steals
 * normal one-finger scrolling/taps; once it fires it consumes the gesture.
 */
private fun Modifier.twoFingerSwipeDown(thresholdPx: Float, onTrigger: () -> Unit): Modifier = composed {
    val current by rememberUpdatedState(onTrigger)
    pointerInput(thresholdPx) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var startX = 0f
            var startY = 0f
            var tracking = false
            var fired = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.filter { it.pressed }
                if (down.size == 2) {
                    val x = (down[0].position.x + down[1].position.x) / 2f
                    val y = (down[0].position.y + down[1].position.y) / 2f
                    if (!tracking) {
                        tracking = true
                        startX = x
                        startY = y
                    } else if (!fired) {
                        val dy = y - startY
                        val dx = abs(x - startX)
                        if (dy > thresholdPx && dy > dx * 1.5f) {
                            fired = true
                            current()
                        }
                    }
                    if (fired) event.changes.forEach { it.consume() }
                } else {
                    tracking = false
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

// ───────────────────────────── Ghost overlay ─────────────────────────────

@Composable
private fun GhostOverlay(message: String?, onUnlockRequest: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onUnlockRequest
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.Fingerprint,
                contentDescription = "Unlock",
                tint = Color.White.copy(alpha = 0.22f),
                modifier = Modifier.size(72.dp)
            )
            if (message != null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = message,
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
            }
        }
    }
}

// ───────────────────────────── Library ─────────────────────────────

@Composable
private fun Library(ui: PlayerUi, vm: PlayerViewModel, onOpenPlayer: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val tabs = listOf("Search", "History", "Vault")
    val list = when (tab) {
        0 -> ui.results
        1 -> ui.history
        else -> ui.vault
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("VORTEX", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.ghost.lock() }) {
                Icon(Icons.Rounded.Lock, contentDescription = "Ghost Mode", tint = Muted)
            }
        }

        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::onQueryChange,
            placeholder = { Text("Search songs, artists", color = Muted) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Muted) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focus.clearFocus()
                tab = 0
                vm.search()
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = PanelHigh,
                unfocusedContainerColor = PanelHigh,
                focusedBorderColor = Accent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = Accent
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )

        TabRow(
            selectedTabIndex = tab,
            containerColor = Color.Transparent,
            contentColor = Accent
        ) {
            tabs.forEachIndexed { i, title ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(title, color = if (tab == i) Accent else Muted) }
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.searching && tab == 0 ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
                list.isEmpty() ->
                    Text(
                        text = when (tab) {
                            0 -> "Search for a song to start"
                            1 -> "Nothing played yet"
                            else -> "Tap the heart on a track to keep it here"
                        },
                        color = Muted,
                        modifier = Modifier.align(Alignment.Center)
                    )
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(list) { _, track ->
                        TrackRow(
                            track = track,
                            inVault = ui.vault.any { it.id == track.id },
                            isCurrent = ui.current?.id == track.id,
                            onClick = {
                                vm.play(track, list)
                                onOpenPlayer()
                            },
                            onToggleVault = { vm.toggleVault(track) }
                        )
                    }
                }
            }
        }

        ui.error?.let { message ->
            Text(
                text = message,
                color = Color(0xFFFF8A80),
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.dismissError() }
                    .background(PanelHigh)
                    .padding(12.dp)
            )
        }

        ui.current?.let { MiniPlayer(ui = ui, track = it, vm = vm, onClick = onOpenPlayer) }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun TrackRow(
    track: Track,
    inVault: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onToggleVault: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(PanelHigh)
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                track.title,
                color = if (isCurrent) Accent else TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${track.artist} · ${formatTime(track.durationSec * 1000L)}",
                color = Muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onToggleVault) {
            Icon(
                if (inVault) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = "Save to Vault",
                tint = if (inVault) Accent else Muted
            )
        }
    }
}

@Composable
private fun MiniPlayer(ui: PlayerUi, track: Track, vm: PlayerViewModel, onClick: () -> Unit) {
    val frac = if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().background(Panel).clickable(onClick = onClick)) {
        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Accent,
            trackColor = PanelHigh
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(PanelHigh)
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = vm::togglePlayPause) {
                Icon(
                    if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = TextPrimary
                )
            }
        }
    }
}

// ───────────────────────────── Full player ─────────────────────────────

@Composable
private fun FullPlayer(ui: PlayerUi, vm: PlayerViewModel, onClose: () -> Unit) {
    val track = ui.current ?: return
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val level by AudioLevels.level.collectAsStateWithLifecycle()
    val inVault = ui.vault.any { it.id == track.id }
    val frac = if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", tint = TextPrimary)
            }
            Spacer(Modifier.weight(1f))
            Text("NOW PLAYING", color = Muted, fontSize = 12.sp, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.ghost.lock() }) {
                Icon(Icons.Rounded.Lock, contentDescription = "Ghost Mode", tint = Muted)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showLyrics) {
                LyricsView(ui)
            } else {
                ArtworkWithAmbient(track = track, level = level, active = ui.isPlaying)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(track.title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artist, color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

        Slider(
            value = drag ?: frac,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { vm.seekTo((it * ui.durationMs).toLong()) }
                drag = null
            },
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Color(0xFF26262B)
            )
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(((drag ?: frac) * ui.durationMs).toLong()), color = Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(formatTime(ui.durationMs), color = Muted, fontSize = 12.sp)
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { showLyrics = !showLyrics }) {
                Icon(Icons.Rounded.Notes, contentDescription = "Lyrics", tint = if (showLyrics) Accent else Muted)
            }
            IconButton(onClick = vm::previous, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = TextPrimary, modifier = Modifier.size(36.dp))
            }
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Accent, AccentAlt)))
                    .clickable(onClick = vm::togglePlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.Black,
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = vm::next, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = TextPrimary, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { vm.toggleVault(track) }) {
                Icon(
                    if (inVault) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Save to Vault",
                    tint = if (inVault) Accent else Muted
                )
            }
        }
    }
}

@Composable
private fun ArtworkWithAmbient(track: Track, level: Float, active: Boolean) {
    val glow by animateFloatAsState(if (active) level else 0f, tween(140), label = "glow")
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.82f)
                .aspectRatio(1f)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Accent.copy(alpha = 0.10f + 0.38f * glow), Color.Transparent),
                            radius = size.minDimension * (0.72f + 0.12f * glow)
                        )
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize(0.88f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(PanelHigh)
            )
        }
        Spacer(Modifier.height(20.dp))
        AmbientVisualizer(level = level, active = active, modifier = Modifier.fillMaxWidth().height(56.dp))
    }
}

/** Bars driven by the real loudness of the decoded audio (see LevelAudioProcessor), with animated shape. */
@Composable
private fun AmbientVisualizer(level: Float, active: Boolean, modifier: Modifier = Modifier) {
    val smooth by animateFloatAsState(if (active) level else 0f, tween(110), label = "level")
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        while (active) {
            withFrameMillis { time = it / 1000f }
        }
    }
    Canvas(modifier) {
        val bars = 36
        val gap = 4.dp.toPx()
        val barWidth = (size.width - gap * (bars - 1)) / bars
        repeat(bars) { i ->
            val raw = sin(i * 12.9898f) * 43758.547f
            val seed = raw - floor(raw)
            val wobble = abs(sin(time * (1.1f + seed * 2.4f) + i * 0.55f))
            val h = size.height * (0.06f + smooth * (0.25f + 0.75f * wobble)).coerceAtMost(1f)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(AccentAlt, Accent),
                    startY = size.height - h,
                    endY = size.height
                ),
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

@Composable
private fun LyricsView(ui: PlayerUi) {
    when {
        ui.lyricsLoading -> CenterNote("Fetching lyrics…")
        ui.lyrics.isNotEmpty() -> {
            val position = ui.positionMs + 250L
            val active = remember(ui.lyrics, position) { ui.lyrics.indexOfLast { it.timeMs <= position } }
            val listState = rememberLazyListState()
            LaunchedEffect(active) {
                if (active >= 0) listState.animateScrollToItem(active, scrollOffset = -220)
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 140.dp), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(ui.lyrics) { index, line ->
                    Text(
                        text = line.text,
                        fontSize = if (index == active) 24.sp else 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (index == active) Color.White else Color(0xFF55555C),
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
        }
        ui.plainLyrics != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(ui.plainLyrics, color = Color(0xFFB8B8C0), fontSize = 17.sp, lineHeight = 26.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
        else -> CenterNote("No lyrics found for this track")
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Muted)
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60, total % 60)
}
