package com.vortex.player

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Encrypted (AES-256, Android Keystore-backed) storage for private playlists.
 * Playlists are kept as name -> tracks; the UI currently exposes the default "Vault" playlist.
 */
class VaultStore(context: Context) {

    private val prefs by lazy {
        val key = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "vortex_vault",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun load(playlist: String = DEFAULT_PLAYLIST): List<Track> {
        val raw = prefs.getString(KEY_PREFIX + playlist, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Track(
                id = o.getString("id"),
                title = o.getString("title"),
                artist = o.getString("artist"),
                durationSec = o.optLong("duration", 0L),
                artworkUrl = if (o.isNull("art")) null else o.optString("art")
            )
        }
    }

    fun save(tracks: List<Track>, playlist: String = DEFAULT_PLAYLIST) {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("title", t.title)
                    .put("artist", t.artist)
                    .put("duration", t.durationSec)
                    .put("art", t.artworkUrl ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY_PREFIX + playlist, arr.toString()).apply()
    }

    companion object {
        const val DEFAULT_PLAYLIST = "Vault"
        private const val KEY_PREFIX = "playlist:"
    }
}
