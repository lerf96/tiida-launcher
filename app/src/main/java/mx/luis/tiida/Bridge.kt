package mx.luis.tiida

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class Bridge(private val ctx: Context) {
    private val cn = ComponentName(ctx, MediaListener::class.java)
    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun hasAccess(): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true

    private fun ctl(): MediaController? = try {
        val l = msm.getActiveSessions(cn)
        l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l.firstOrNull()
    } catch (e: Exception) { null }

    // ---- Música: refleja y controla lo que suene en cualquier app (Spotify, reproductor del estéreo, etc.)
    @JavascriptInterface fun getMedia(): String {
        val o = JSONObject()
        o.put("access", hasAccess())
        val c = ctl() ?: return o.toString()
        val m = c.metadata
        val s = c.playbackState
        o.put("title", m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
        o.put("artist", m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
        o.put("dur", m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
        var pos = 0L
        var playing = false
        if (s != null) {
            pos = s.position
            playing = s.state == PlaybackState.STATE_PLAYING
            if (playing) pos += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        o.put("playing", playing)
        o.put("pos", pos)
        o.put("pkg", c.packageName)
        return o.toString()
    }

    @JavascriptInterface fun getArt(): String {
        val m = ctl()?.metadata ?: return ""
        val bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: return ""
        val sm = Bitmap.createScaledBitmap(bmp, 256, 256, true)
        val out = ByteArrayOutputStream()
        sm.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    @JavascriptInterface fun media(cmd: String, arg: Long) {
        val t = ctl()?.transportControls ?: return
        when (cmd) {
            "play" -> t.play(); "pause" -> t.pause()
            "next" -> t.skipToNext(); "prev" -> t.skipToPrevious()
            "seek" -> t.seekTo(arg)
        }
    }

    @JavascriptInterface fun openMediaAccess() {
        ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- Apps instaladas y lanzamiento (ZLink, Google Maps, Waze, ...)
    @JavascriptInterface fun getApps(): String {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val arr = JSONArray()
        pm.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .forEach { arr.put(JSONObject().put("n", it.loadLabel(pm).toString()).put("p", it.activityInfo.packageName)) }
        return arr.toString()
    }

    @JavascriptInterface fun launch(pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }

    @JavascriptInterface fun launchUri(uri: String): Boolean = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    // ---- Volumen del sistema
    @JavascriptInterface fun setVol(p: Int) {
        val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, p.coerceIn(0, 100) * mx / 100, 0)
    }
    @JavascriptInterface fun getVol(): Int =
        am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
}
