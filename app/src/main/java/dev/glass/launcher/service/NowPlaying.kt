package dev.glass.launcher.service

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Çalan medya (müzik, podcast, video): başlık, sanatçı, kapak ve çalıyor mu. */
data class NowPlayingState(
    val pkg: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
)

/**
 * "Şimdi Çalıyor" widget'ı: telefondaki etkin medya oturumları (Spotify, YouTube Music vb.). Android bunları yalnızca
 * bildirim erişimi verilmiş uygulamalara açar (rozetler için zaten veriliyor). Hiçbir şey kaydedilmez.
 */
object NowPlaying {
    private val _state = MutableStateFlow<NowPlayingState?>(null)
    val state: StateFlow<NowPlayingState?> = _state
    private val main = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var ctrl: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Başka bir oturum çalmaya başladıysa ona geç.
            if (state?.state != PlaybackState.STATE_PLAYING) runCatching { msm?.let { pick(it.getActiveSessions(component)) } }
            publish()
        }
        override fun onSessionDestroyed() { runCatching { msm?.let { pick(it.getActiveSessions(component)) } } }
    }
    private var component: android.content.ComponentName? = null
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    /** Dinlemeye başlar (bildirim erişimi varsa). Birden çok kez çağrılabilir. */
    fun start(ctx: Context) {
        if (msm != null || !BadgeListener.isGranted(ctx)) return
        runCatching {
            val m = ctx.getSystemService(MediaSessionManager::class.java) ?: return
            val comp = BadgeListener.component(ctx)
            m.addOnActiveSessionsChangedListener(listener, comp, main)
            msm = m; component = comp
            pick(m.getActiveSessions(comp))
        }
    }

    private fun pick(list: List<MediaController>) {
        val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: ctrl?.takeIf { cur -> list.any { it.sessionToken == cur.sessionToken } } ?: list.firstOrNull()
        if (c?.sessionToken != ctrl?.sessionToken) {
            runCatching { ctrl?.unregisterCallback(callback) }
            ctrl = c
            runCatching { c?.registerCallback(callback, main) }
        }
        publish()
    }

    private fun publish() {
        val c = ctrl ?: run { _state.value = null; return }
        val m = c.metadata
        val title = (m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).orEmpty()
        if (title.isBlank()) { _state.value = null; return }
        val artist = (m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).orEmpty()
        val raw = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val prev = _state.value
        // Kapak yalnızca değişince küçültülür (büyük görseller widget'ı yavaşlatmasın).
        val art = if (prev != null && prev.title == title && prev.pkg == c.packageName && prev.art != null && raw != null) prev.art
        else raw?.let { b -> runCatching { val s = 360f / maxOf(b.width, b.height); if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b }.getOrNull() }
        val st = c.playbackState?.state
        _state.value = NowPlayingState(c.packageName, title, artist, art, st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING)
    }

    fun toggle() {
        val c = ctrl ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }
    fun next() { ctrl?.transportControls?.skipToNext() }
    fun previous() { ctrl?.transportControls?.skipToPrevious() }

    /** Çalan uygulamayı açar (oturumun kendi ekranı, yoksa uygulama). */
    fun open(ctx: Context): Boolean {
        val c = ctrl ?: return false
        val pi = c.sessionActivity
        if (pi != null) {
            val ok = runCatching {
                val opts = android.app.ActivityOptions.makeBasic().apply {
                    if (android.os.Build.VERSION.SDK_INT >= 34) setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                pi.send(null, 0, null, null, null, null, opts.toBundle()); true
            }.getOrDefault(false)
            if (ok) return true
        }
        val i = ctx.packageManager.getLaunchIntentForPackage(c.packageName) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }
}
