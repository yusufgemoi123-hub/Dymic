package com.example.island

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Baca musik yang lagi diputar (Spotify / YouTube Music) lewat MediaSession,
 * lalu ambil lirik tersinkron dari LRCLIB (https://lrclib.net) dan kirim baris
 * lirik yang sesuai posisi lagu ke IslandService.
 *
 * Catatan: Spotify/YT Music tidak ngasih lirik lewat MediaSession,
 * jadi lirik dicari sendiri berdasarkan judul + artis.
 */
class MusicListener : NotificationListenerService() {

    private val pkgs = setOf("com.spotify.music", "com.google.android.apps.youtube.music")
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var msm: MediaSessionManager
    private var controller: MediaController? = null
    private var lines: List<Pair<Long, String>> = emptyList()
    private var lastTrack: String? = null
    private var fallback: String = "♪"

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = loadTrack()
        override fun onPlaybackStateChanged(state: PlaybackState?) = tickNow()
    }

    private val tick = object : Runnable {
        override fun run() {
            val c = controller
            val st = c?.playbackState
            if (c != null && st != null && st.state == PlaybackState.STATE_PLAYING) {
                val pos = st.position +
                    ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() + 250L // lirik sedikit lebih awal biar terasa sinkron
                val text = if (lines.isEmpty()) {
                    fallback
                } else {
                    lines.lastOrNull { it.first <= pos }?.second?.ifBlank { "♪" } ?: "♪"
                }
                IslandService.instance?.setMusic(c.packageName, text)
            } else {
                IslandService.instance?.setMusic(null, null)
            }
            handler.postDelayed(this, 150)
        }
    }

    private fun tickNow() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onListenerConnected() {
        msm = getSystemService(MediaSessionManager::class.java)
        val cn = ComponentName(this, MusicListener::class.java)
        msm.addOnActiveSessionsChangedListener(sessionsListener, cn)
        pick(msm.getActiveSessions(cn))
        handler.post(tick)
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller?.unregisterCallback(callback)
        if (::msm.isInitialized) msm.removeOnActiveSessionsChangedListener(sessionsListener)
        super.onDestroy()
    }

    private fun pick(list: List<MediaController>?) {
        val c = list?.firstOrNull {
            it.packageName in pkgs && it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: list?.firstOrNull { it.packageName in pkgs }

        if (c?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = c
        c?.registerCallback(callback)
        loadTrack()
    }

    private fun loadTrack() {
        val meta = controller?.metadata ?: return
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val durSec = meta.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000

        val key = "$artist|$title"
        if (key == lastTrack) return
        lastTrack = key
        lines = emptyList()
        fallback = if (artist.isBlank()) title else "$artist – $title"
        fetchLyrics(artist, title, durSec, key)
    }

    private fun fetchLyrics(artist: String, title: String, durSec: Long, key: String) {
        Thread {
            val parsed: List<Pair<Long, String>> = try {
                val q = "artist_name=${enc(artist)}&track_name=${enc(title)}" +
                    if (durSec > 0) "&duration=$durSec" else ""
                val c = URL("https://lrclib.net/api/get?$q").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "IslandDemo/1.0")
                if (c.responseCode == 200) {
                    val j = JSONObject(c.inputStream.bufferedReader().readText())
                    if (j.isNull("syncedLyrics")) emptyList() else parseLrc(j.getString("syncedLyrics"))
                } else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            handler.post { if (key == lastTrack) lines = parsed }
        }.start()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun parseLrc(lrc: String): List<Pair<Long, String>> {
        val re = Regex("""\[(\d+):(\d+)(?:\.(\d+))?](.*)""")
        return lrc.lines().mapNotNull { line ->
            val m = re.matchEntire(line.trim()) ?: return@mapNotNull null
            val (mm, ss, frac, text) = m.destructured
            val ms = (frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L)
            val t = mm.toLong() * 60_000 + ss.toLong() * 1000 + ms
            t to text.trim()
        }.sortedBy { it.first }
    }
}
