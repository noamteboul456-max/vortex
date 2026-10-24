package com.vortex.player

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUi(
    val query: String = "",
    val results: List<Track> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lyrics: List<LyricLine> = emptyList(),
    val plainLyrics: String? = null,
    val lyricsLoading: Boolean = false,
    val history: List<Track> = emptyList(),
    val vault: List<Track> = emptyList()
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private val vaultStore = VaultStore(app)

    val ghost = GhostModeController(
        onLocked = { wipeSession() },
        onUnlocked = { loadVault() }
    )

    private var controller: MediaController? = null
    private var queue: List<Track> = emptyList()
    private var searchJob: Job? = null
    private var lyricsJob: Job? = null

    init {
        connect()
        startTicker()
        loadVault()
    }

    // ───────── Connection to InnerTubeAudioService ─────────

    private fun connect() {
        val context = getApplication<Application>()
        val token = SessionToken(context, ComponentName(context, InnerTubeAudioService::class.java))
        viewModelScope.launch {
            val c = MediaController.Builder(context, token).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _ui.update { it.copy(isPlaying = isPlaying) }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    onTrackChanged(mediaItem)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && c.duration > 0) {
                        _ui.update { it.copy(durationMs = c.duration) }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _ui.update { it.copy(error = "Playback failed (${error.errorCodeName}). The stream may have expired; try again.") }
                }
            })
            _ui.update { it.copy(isPlaying = c.isPlaying) }
        }
    }

    private fun startTicker() {
        viewModelScope.launch {
            while (true) {
                delay(250)
                val c = controller ?: continue
                if (c.isPlaying && ghost.state.value == GhostState.UNLOCKED) {
                    _ui.update {
                        it.copy(
                            positionMs = c.currentPosition,
                            durationMs = if (c.duration > 0) c.duration else it.durationMs
                        )
                    }
                }
            }
        }
    }

    private fun onTrackChanged(item: MediaItem?) {
        if (ghost.state.value == GhostState.LOCKED) return
        val track = item?.let { i -> queue.firstOrNull { it.id == i.mediaId } }
        if (track == null) {
            _ui.update { it.copy(current = null, positionMs = 0L, durationMs = 0L, lyrics = emptyList(), plainLyrics = null, lyricsLoading = false) }
            return
        }
        _ui.update {
            it.copy(
                current = track,
                positionMs = 0L,
                durationMs = track.durationSec * 1000L,
                lyrics = emptyList(),
                plainLyrics = null,
                lyricsLoading = true,
                history = (listOf(track) + it.history.filter { h -> h.id != track.id }).take(50)
            )
        }
        loadLyrics(track)
    }

    // ───────── Search & playback ─────────

    fun onQueryChange(value: String) = _ui.update { it.copy(query = value) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun search() {
        val q = _ui.value.query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _ui.update { it.copy(searching = true, error = null) }
            val result = withContext(Dispatchers.IO) { runCatching { InnerTubeClient.search(q) } }
            ensureActive()
            if (ghost.state.value == GhostState.LOCKED) return@launch
            result
                .onSuccess { list -> _ui.update { it.copy(results = list, searching = false) } }
                .onFailure { e ->
                    _ui.update { it.copy(searching = false, error = "Search failed: ${e.message ?: e::class.java.simpleName}") }
                }
        }
    }

    fun play(track: Track, from: List<Track>) {
        val c = controller ?: return
        if (ghost.state.value == GhostState.LOCKED) return
        val list = from.ifEmpty { listOf(track) }
        val index = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        queue = list
        _ui.update { it.copy(error = null) }
        c.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3_000L) c.seekTo(0L) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        _ui.update { it.copy(positionMs = ms) }
    }

    // ───────── Lyrics ─────────

    private fun loadLyrics(track: Track) {
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val lyrics = withContext(Dispatchers.IO) {
                runCatching { LyricsRepository.fetch(track.title, track.artist, track.durationSec) }.getOrNull()
            }
            ensureActive()
            if (ghost.state.value == GhostState.LOCKED) return@launch
            _ui.update {
                if (it.current?.id != track.id) it
                else it.copy(
                    lyrics = lyrics?.synced.orEmpty(),
                    plainLyrics = lyrics?.plain,
                    lyricsLoading = false
                )
            }
        }
    }

    // ───────── Private playlist (Vault) ─────────

    fun toggleVault(track: Track) {
        if (ghost.state.value == GhostState.LOCKED) return
        val updated = _ui.value.vault.let { list ->
            if (list.any { it.id == track.id }) list.filter { it.id != track.id } else list + track
        }
        _ui.update { it.copy(vault = updated) }
        viewModelScope.launch(Dispatchers.IO) { runCatching { vaultStore.save(updated) } }
    }

    private fun loadVault() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { vaultStore.load() }.getOrDefault(emptyList()) }
            if (ghost.state.value == GhostState.UNLOCKED) {
                _ui.update { it.copy(vault = list) }
            }
        }
    }

    // ───────── Ghost Mode wipe ─────────

    /** Runs the instant Ghost Mode locks: nothing playable, searchable or readable stays in memory/notification. */
    private fun wipeSession() {
        searchJob?.cancel()
        lyricsJob?.cancel()
        controller?.run {
            pause()
            stop()
            clearMediaItems() // drops the media notification / lock-screen metadata
        }
        queue = emptyList()
        _ui.value = PlayerUi()
    }

    override fun onCleared() {
        controller?.release()
        controller = null
        super.onCleared()
    }
}
