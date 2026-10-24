package com.vortex.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sqrt

// ───────────────────────────── Models ─────────────────────────────

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val artworkUrl: String?
)

fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(artworkUrl?.let { Uri.parse(it) })
                .build()
        )
        .build()

// ─────────────────────── InnerTube extraction layer ───────────────────────

private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

/** OkHttp-backed Downloader required by NewPipeExtractor. */
private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: NpRequest): NpResponse {
        val method = request.httpMethod()
        val payload = request.dataToSend()
        val body = when {
            payload != null -> payload.toRequestBody()
            method == "POST" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(method, body)
            .header("User-Agent", BROWSER_UA)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) {
                throw ReCaptchaException("reCAPTCHA challenge requested", request.url())
            }
            return NpResponse(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                resp.body?.string(),
                resp.request.url.toString()
            )
        }
    }
}

/** Search + audio stream URL resolution on top of NewPipeExtractor (InnerTube under the hood). */
object InnerTubeClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var ready = false

    private data class Cached(val url: String, val expiresAtMs: Long)
    private val urlCache = ConcurrentHashMap<String, Cached>()
    private const val URL_TTL_MS = 4 * 60 * 60 * 1000L // stream URLs expire after ~6h

    fun ensureInit() {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                NewPipe.init(OkHttpDownloader(http), Localization("en", "US"), ContentCountry("US"))
                ready = true
            }
        }
    }

    /** YouTube Music "Songs" search. Blocking. */
    fun search(query: String): List<Track> {
        ensureInit()
        val extractor = ServiceList.YouTube.getSearchExtractor(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
            ""
        )
        extractor.fetchPage()
        return extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val id = runCatching {
                    YoutubeStreamLinkHandlerFactory.getInstance().getId(item.url)
                }.getOrNull() ?: return@mapNotNull null
                val art = item.thumbnails.maxByOrNull { it.height }?.url?.let(::upscaleArtwork)
                Track(
                    id = id,
                    title = item.name,
                    artist = item.uploaderName.orEmpty().removeSuffix(" - Topic").ifBlank { "Unknown artist" },
                    durationSec = item.duration,
                    artworkUrl = art
                )
            }
    }

    private fun upscaleArtwork(url: String): String =
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com"))
            url.replace(Regex("=w\\d+-h\\d+.*$"), "=w600-h600-l90-rj")
        else url

    /** Best progressive audio stream URL for a video id (prefers AAC/M4A). Blocking. */
    fun streamUrl(videoId: String): String {
        val now = System.currentTimeMillis()
        urlCache[videoId]?.takeIf { it.expiresAtMs > now }?.let { return it.url }

        ensureInit()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val candidates = info.audioStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        val best = candidates.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: candidates.maxByOrNull { it.averageBitrate }
            ?: throw IOException("No playable audio stream for $videoId")

        urlCache[videoId] = Cached(best.content, now + URL_TTL_MS)
        return best.content
    }
}

/** Turns vortex://stream?id=VIDEO_ID into a real googlevideo URL right before ExoPlayer opens it. */
@OptIn(UnstableApi::class)
private object StreamResolver : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != "vortex") return dataSpec
        val id = dataSpec.uri.getQueryParameter("id") ?: throw IOException("Missing track id")
        val url = try {
            InnerTubeClient.streamUrl(id)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Stream resolution failed: ${e.message}", e)
        }
        return dataSpec.withUri(Uri.parse(url))
    }
}

// ───────────────────────── Loudness tap for the visualizer ─────────────────────────

object AudioLevels {
    /** 0f..1f, updated from the decoded PCM stream. */
    val level = MutableStateFlow(0f)
}

@OptIn(UnstableApi::class)
private class LevelAudioProcessor : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        var sum = 0.0
        var count = 0
        var i = inputBuffer.position()
        val end = inputBuffer.limit() - 1
        while (i < end) {
            val s = inputBuffer.getShort(i).toDouble()
            sum += s * s
            count++
            i += 16
        }
        if (count > 0) {
            val rms = sqrt(sum / count) / 32768.0
            AudioLevels.level.value = min(1f, (rms * 3.5).toFloat())
        }

        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    override fun onReset() {
        AudioLevels.level.value = 0f
    }
}

// ───────────────────────── Background playback service ─────────────────────────

@OptIn(UnstableApi::class)
class InnerTubeAudioService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        InnerTubeClient.ensureInit()

        // Set up the notification provider so the service stays active in the background
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this))

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(BROWSER_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val resolvingFactory = ResolvingDataSource.Factory(httpFactory, StreamResolver)

        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(LevelAudioProcessor()))
                    .build()
        }

        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        // Only this app and system UI (notification, Bluetooth) may control the vault player.
        if (controllerInfo.packageName == packageName || controllerInfo.isTrusted) session else null

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val routed = mediaItems.map { item ->
                item.buildUpon()
                    .setUri(Uri.parse("vortex://stream?id=${item.mediaId}"))
                    .build()
            }.toMutableList()
            return Futures.immediateFuture(routed)
        }
    }
}
