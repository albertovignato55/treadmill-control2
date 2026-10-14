package com.example.tapiscontrol

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Video su file del telefono: la velocita' di riproduzione segue quella del tapis roulant (continua). */
@Composable
fun LocalVideo(
    uri: Uri,
    rate: Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onPosition: (Double) -> Unit = {},
) {
    val ctx = LocalContext.current
    val posCb by rememberUpdatedState(onPosition)
    val exo = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }
    var ratio by remember(uri) { mutableFloatStateOf(16f / 9f) }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }
    LaunchedEffect(exo, rate) { exo.playbackParameters = PlaybackParameters(rate.coerceIn(0.25f, 3f)) }
    LaunchedEffect(exo, playing) { exo.playWhenReady = playing }
    LaunchedEffect(exo) {
        while (true) {
            posCb(exo.currentPosition / 1000.0)
            delay(500)
        }
    }

    AndroidView(
        factory = { c -> SurfaceView(c) },
        update = { exo.setVideoSurfaceView(it) },
        modifier = modifier.aspectRatio(ratio),
    )
}

private class YtBridge(
    private val onError: (String) -> Unit,
    private val onPos: (Double) -> Unit,
) {
    @JavascriptInterface
    fun error(code: String) {
        Handler(Looper.getMainLooper()).post { onError(code) }
    }

    @JavascriptInterface
    fun pos(sec: String) {
        val v = sec.toDoubleOrNull() ?: return
        Handler(Looper.getMainLooper()).post { onPos(v) }
    }
}

private fun youtubeHtml(id: String) = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000}#player{width:100%;height:100%}</style>
</head><body><div id="player"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player, ready = false, wantRate = 1, wantPlaying = false;
function onYouTubeIframeAPIReady() {
  player = new YT.Player('player', {
    width: '100%', height: '100%', videoId: '$id',
    playerVars: { playsinline: 1, rel: 0, controls: 1 },
    events: {
      onReady: function() { ready = true; player.setPlaybackRate(wantRate); if (wantPlaying) player.playVideo(); },
      onStateChange: function(e) { if (e.data === 1) player.setPlaybackRate(wantRate); },
      onError: function(e) { Android.error(String(e.data)); }
    }
  });
}
setInterval(function() {
  if (ready && player && player.getCurrentTime) Android.pos(String(player.getCurrentTime()));
}, 500);
function setRate(r) { wantRate = r; if (ready) player.setPlaybackRate(r); }
function setPlaying(p) { wantPlaying = p; if (ready) { if (p) player.playVideo(); else player.pauseVideo(); } }
</script></body></html>
""".trimIndent()

/** Video YouTube col lettore ufficiale incorporato: velocita' a scatti (0.25 ... 2). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeVideo(
    videoId: String,
    rate: Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit,
    onPosition: (Double) -> Unit = {},
) {
    val ctx = LocalContext.current
    val posCb by rememberUpdatedState(onPosition)
    var loaded by remember(videoId) { mutableStateOf(false) }
    val wv = remember(videoId) {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(android.graphics.Color.BLACK)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded = true
                }
            }
            addJavascriptInterface(YtBridge(onError) { v -> posCb(v) }, "Android")
            loadDataWithBaseURL("https://www.youtube.com", youtubeHtml(videoId), "text/html", "utf-8", null)
        }
    }
    DisposableEffect(wv) { onDispose { wv.destroy() } }
    LaunchedEffect(wv, rate, loaded) { if (loaded) wv.evaluateJavascript("setRate($rate)", null) }
    LaunchedEffect(wv, playing, loaded) { if (loaded) wv.evaluateJavascript("setPlaying($playing)", null) }

    AndroidView(factory = { wv }, modifier = modifier)
}

/** Estrae l'ID di 11 caratteri da un link YouTube (watch, youtu.be, embed, shorts, live) o da un ID nudo. */
fun youtubeId(input: String): String? {
    val t = input.trim()
    if (Regex("^[A-Za-z0-9_-]{11}$").matches(t)) return t
    return Regex("(?:v=|youtu\\.be/|embed/|shorts/|live/)([A-Za-z0-9_-]{11})")
        .find(t)?.groupValues?.get(1)
}

private val YT_RATES = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Velocita' supportata da YouTube piu' vicina a quella richiesta. */
fun youtubeRate(r: Float): Float = YT_RATES.minByOrNull { abs(it - r) } ?: 1f
