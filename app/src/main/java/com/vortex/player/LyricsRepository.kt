package com.vortex.player

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val synced: List<LyricLine>, val plain: String?)

/** Synced (LRC) lyrics from the open LRCLIB database. Blocking: call from Dispatchers.IO. */
object LyricsRepository {

    private val client = OkHttpClient()
    private val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val noise = Regex(
        """\s*[(\[][^)\]]*(official|video|audio|lyrics|visuali[sz]er|remaster)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE
    )

    fun fetch(title: String, artist: String, durationSec: Long): Lyrics? {
        val cleanTitle = title.replace(noise, "").trim()

        val direct = HttpUrl.Builder()
            .scheme("https").host("lrclib.net").addPathSegments("api/get")
            .addQueryParameter("track_name", cleanTitle)
            .addQueryParameter("artist_name", artist)
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        get(direct)?.let { parse(JSONObject(it)) }?.let { return it }

        val search = HttpUrl.Builder()
            .scheme("https").host("lrclib.net").addPathSegments("api/search")
            .addQueryParameter("q", "$artist $cleanTitle")
            .build()
        val body = get(search) ?: return null
        val arr = JSONArray(body)
        for (i in 0 until arr.length()) {
            parse(arr.getJSONObject(i))?.let { return it }
        }
        return null
    }

    private fun get(url: HttpUrl): String? {
        val request = Request.Builder().url(url).header("User-Agent", "VORTEX/1.0 (private player)").build()
        client.newCall(request).execute().use { resp ->
            return if (resp.isSuccessful) resp.body?.string() else null
        }
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun parse(o: JSONObject): Lyrics? {
        val synced = o.str("syncedLyrics")?.let { parseLrc(it) } ?: emptyList()
        val plain = o.str("plainLyrics")
        return if (synced.isEmpty() && plain == null) null else Lyrics(synced, plain)
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        lrc.lineSequence().forEach { raw ->
            val matches = stamp.findAll(raw).toList()
            if (matches.isEmpty()) return@forEach
            val text = raw.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            matches.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val ms = m.groupValues[3].padEnd(3, '0').take(3).toLong()
                out.add(LyricLine(min * 60_000L + sec * 1_000L + ms, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
