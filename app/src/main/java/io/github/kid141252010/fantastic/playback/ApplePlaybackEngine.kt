package io.github.kid141252010.fantastic.playback

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.Player
import io.github.kid141252010.fantastic.data.Prefs
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.SourceStatus
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.data.reportedAudioDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal data class ApplePlaybackProfile(val network: String, val quality: String, val spatial: String)

internal fun applePlaybackNetwork(context: Context): String? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = manager.activeNetwork ?: return null
    val cellular = manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    return if (cellular) "cellular" else "wifi"
}

internal fun applePlaybackProfile(context: Context, prefs: Prefs, offlineNetwork: String? = null): ApplePlaybackProfile {
    val network = applePlaybackNetwork(context) ?: if (offlineNetwork == "cellular") "cellular" else "wifi"
    val quality = if (network == "cellular") prefs.appleCellularQuality else prefs.appleWifiQuality
    return ApplePlaybackProfile(network, quality, prefs.appleSpatial)
}

internal data class AppleCacheSource(
    val occurrence: Int,
    val requestSerial: Long,
    val profile: ApplePlaybackProfile,
    val group: String,
    val codecs: String,
    val quality: String,
    val mode: String,
    val durationMs: Long,
    val server: String,
    val storefront: String,
)

/** Hidden adapter over Fantastic's own Apple web player; it deliberately adds no playback/codec implementation. */
internal class ApplePlaybackEngine(context: Context, private val prefs: Prefs) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _cacheSource = MutableStateFlow<AppleCacheSource?>(null)
    internal val cacheSource: StateFlow<AppleCacheSource?> = _cacheSource.asStateFlow()
    internal var activeCacheSource: AppleCacheSource?
        get() = _cacheSource.value
        private set(value) { _cacheSource.value = value }
    internal var completedLoops: Long = 0L
        private set

    private data class HostedScope(val server: String, val storefront: String, val configuredServer: String)
    private data class HostedRun(val server: String, val storefront: String, val profile: ApplePlaybackProfile)
    private data class QualityChange(val occurrence: Int, val revision: Long)
    private var hostedScope: HostedScope? = null
    private var hostedRun: HostedRun? = null
    private val closed = AtomicBoolean(false)
    private var webView: WebView? = null
    private var baseUrl = ""
    private var readyStartedAt = 0L
    private var poller: Runnable? = null
    private var readyPoller: Runnable? = null
    private var lastPositionMs = 0L
    private var requestSerial = 0L
    private enum class StartupStage { Page, Ready, Resolve, Prepare, Playing, Failed }
    private var startupStartedAt = 0L
    private var startupStage: StartupStage? = null
    private var startupRequestSerial = 0L
    private var songs = emptyList<QueuedSong>()
    private val sessionScript by lazy { appContext.assets.open("playback/apple-session.js").bufferedReader().use { it.readText() } }
    private var seekSerial = 0L
    private var lastHostedLoops = 0L
    private var occurrence = -1
    private var sampleTime = 0L
    private var waitingToPlay = false
    private var pendingStartupSeek = false
    private var hasHostedTrack = false
    private var qualityChange: QualityChange? = null
    private var qualitySerial = 0L
    private var variantDetailTrackId: String? = null
    private var variantDetailCodecs: String? = null
    private var variantDetailSampleRate = 0
    private var variantDetailBitDepth = 0
    private var variantDetailChannels: String? = null
    private var variantDetailBandwidth = 0L
    private var variantDetailValue: String? = null

    internal fun prewarm(server: String) = onMain {
        if (closed.get()) return@onMain
        val normalized = SourceStatus.normalizeBaseUrl(server) ?: return@onMain
        ensureCurrentServer(normalized)
    }

    fun position(): Long {
        val state = _state.value
        val elapsed = if (state.isPlaying) (android.os.SystemClock.uptimeMillis() - sampleTime).coerceIn(0, 500) else 0L
        return (lastPositionMs + elapsed).coerceAtMost(state.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
    }

    fun playQueue(
        songs: List<QueuedSong>, index: Int, startPositionMs: Long = 0L,
        playWhenReady: Boolean = true, quality: String? = null,
        server: String = prefs.baseUrl, storefront: String = prefs.storefront,
    ) = onMain {
        if (closed.get()) return@onMain
        val selected = songs.getOrNull(index) ?: return@onMain
        val track = selected.track
        require(songs.all { it.track.source == Source.Apple })
        val previous = _state.value
        val serial = ++requestSerial
        activeCacheSource = null
        val scope = HostedScope(server, storefront.lowercase().takeIf { STORE_FRONT.matches(it) } ?: "us", prefs.baseUrl)
        hostedScope = scope
        hostedRun = null
        qualityChange = null
        startupStartedAt = SystemClock.elapsedRealtime()
        startupStage = null
        startupRequestSerial = serial
        resetHostedPlayer(serial)
        this.songs = if (quality == null || selected.quality == quality) songs.toList()
        else songs.toMutableList().apply { this[index] = selected.copy(quality = quality) }
        seekSerial = 0L
        lastHostedLoops = 0L
        occurrence = index
        waitingToPlay = playWhenReady
        pendingStartupSeek = false
        hasHostedTrack = false
        lastPositionMs = startPositionMs.coerceIn(0L, track.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        _state.value = previous.copy(mediaId = "apple:${track.id}", title = track.title, artist = track.artist,
            artworkUri = track.artwork(600), durationMs = track.durationMs, isPlaying = false,
            playWhenReady = playWhenReady, buffering = playWhenReady, ended = false, bufferedPositionMs = 0L,
            error = null, quality = null, qualityLabel = null, audioDetails = null, queue = songs.map { it.track }, queueIndex = index,
            hasPrevious = true, hasNext = index < songs.lastIndex,
            seekGeneration = previous.seekGeneration + 1)
        if (!playWhenReady) return@onMain
        val normalized = SourceStatus.normalizeBaseUrl(scope.server) ?: run {
            destroyView()
            publishError("Fantastic 服务器地址无效")
            return@onMain
        }
        readyStartedAt = SystemClock.uptimeMillis()
        ensureCurrentServer(normalized)
        val run = HostedRun(baseUrl, scope.storefront, applePlaybackProfile(appContext, prefs))
        hostedRun = run
        schedulePoll()
        runWhenReady(serial) {
            val entries = hostedEntries(this.songs, run.storefront)
            val start = lastPositionMs / 1000.0
            pendingStartupSeek = false
            val repeat = repeatScript(_state.value.repeatMode)
            val script = sessionScript + "\n(function(){var p=window.AmApp&&AmApp.player;if(!p)return 'not-ready';" +
                "p.fantasticRequest=$serial;p.fantasticSeek=0;p.fantasticLoops=0;p.fantasticLoopOccurrence=null;" +
                "p.fantasticStart=$start;$repeat;" +
                "var r=p.playQueue($entries,$index,{shuffle:false});" +
                "if(!p.fantasticSessionInstalled&&$start>0)Promise.resolve(r).then(function(){" +
                "if(p.fantasticRequest===$serial&&p.current)p.transport().currentTime=$start;});return 'ok'})()"
            evaluate(script) { result ->
                if (serial == requestSerial && result != "ok") publishError("Fantastic 播放器未就绪")
            }
        }
    }

    private fun hostedEntries(songs: List<QueuedSong>, storefront: String): JSONArray = JSONArray().apply {
        songs.forEach { song ->
            val track = song.track
            put(JSONObject().apply {
                put("source", "apple")
                put("key", track.mediaId)
                put("fantasticIndex", song.uid)
                put("track", track.id)
                put("country", storefront)
                put("name", track.title)
                put("title", track.title)
                put("artist", track.artist)
                put("album", track.album)
                put("artwork", track.artwork(600) ?: "")
                put("duration", track.durationMs)
                put("href", "/$storefront/song/${Uri.encode(track.title)}/${Uri.encode(track.id)}")
            })
        }
    }

    /** Preserve the active hosted item and its audio buffer while replacing the upcoming run. */
    fun replaceUpcoming(tail: List<QueuedSong>) = onMain {
        val selected = songs.getOrNull(occurrence) ?: return@onMain
        songs = songs.take(occurrence + 1) + tail
        _state.value = _state.value.copy(queue = songs.map { it.track }, hasNext = tail.isNotEmpty())
        val run = hostedRun ?: return@onMain
        val entries = hostedEntries(tail, run.storefront)
        command("""const q=p.queue;
          if(!q||q.entries[q.pos]?.fantasticIndex!==${selected.uid})return;
          const current=new Map(q.entries.slice(q.pos+1).map(e=>[e.fantasticIndex,e]));
          q.entries.splice(q.pos+1,q.entries.length-q.pos-1,...$entries.map(e=>current.get(e.fantasticIndex)||e));
          p.renderQueue(true);p.renderModes();p.prepareNext();""")
    }

    fun pause() = onMain {
        val previous = _state.value
        if (!previous.playWhenReady) return@onMain
        activeCacheSource = null
        qualityChange = null
        lastPositionMs = position()
        waitingToPlay = false
        pendingStartupSeek = false
        stopStatePolling()
        stopReadyPolling()
        webView?.onPause()
        val serial = ++requestSerial
        if (!hasHostedTrack) {
            resetHostedPlayer(serial)
        } else {
            evaluate("(function(){var p=window.AmApp&&AmApp.player;if(!p)return null;" +
                "p.fantasticRequest=$serial;if(p.pendingKey||p.attempting){$RESET_HOSTED;return 'reset';}" +
                "p.pause();return 'paused';})()") { result ->
                if (serial == requestSerial && result != "paused") hasHostedTrack = false
            }
        }
        _state.value = previous.copy(playWhenReady = false, isPlaying = false, buffering = false)
    }


    fun seekTo(ms: Long) = onMain {
        val state = _state.value
        if (state.queueIndex !in state.queue.indices) return@onMain
        lastPositionMs = ms.coerceIn(0L, state.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        sampleTime = android.os.SystemClock.uptimeMillis()
        if (waitingToPlay) {
            // Keep the current resolve/prepare request. Only the latest scrub position matters.
            pendingStartupSeek = true
        } else if (hasHostedTrack) {
            seekHosted()
        }
        _state.value = state.copy(ended = false, seekGeneration = state.seekGeneration + 1)
    }

    private fun seekHosted() {
        val seek = ++seekSerial
        command("if(p.current){p.fantasticSeek=$seek;p.transport().currentTime=${lastPositionMs / 1000.0};}")
    }

    /** Use the already prepared seam for a skip when one exists. Otherwise select normally. */
    fun skipTo(index: Int): Boolean {
        if (!hasHostedTrack || !_state.value.isPlaying || index != occurrence + 1) return false
        val target = songs.getOrNull(index) ?: return false
        command("if(p.queue?.entries[p.queue.pos+1]?.fantasticIndex===${target.uid})p.next();")
        return true
    }

    fun setPlaying(playing: Boolean) = onMain {
        val state = _state.value
        if (!playing) {
            pause()
        } else if (state.queueIndex in state.queue.indices && !waitingToPlay && !state.isPlaying) {
            webView?.onResume()
            if (hasHostedTrack && state.error == null && !state.ended) {
                _state.value = state.copy(playWhenReady = true)
                schedulePoll()
                command("if(p.current&&p.transport().paused)p.toggle()")
            } else {
                val scope = hostedScope?.takeIf { it.configuredServer == prefs.baseUrl }
                playQueue(songs, state.queueIndex, if (state.ended) 0L else lastPositionMs,
                    server = scope?.server ?: prefs.baseUrl, storefront = scope?.storefront ?: prefs.storefront)
            }
        }
    }

    fun stop() = onMain {
        val serial = ++requestSerial
        activeCacheSource = null
        hostedScope = null
        hostedRun = null
        songs = emptyList()
        occurrence = -1
        waitingToPlay = false
        pendingStartupSeek = false
        hasHostedTrack = false
        qualityChange = null
        lastPositionMs = 0L
        resetHostedPlayer(serial)
        destroyView()
        _state.value = PlayerState(connected = false, repeatMode = _state.value.repeatMode)
    }

    private fun resetHostedPlayer(serial: Long) {
        evaluate("(function(){var p=window.AmApp&&AmApp.player;if(!p)return;" +
            "p.fantasticRequest=$serial;$RESET_HOSTED;})()")
    }
    fun setRepeatMode(mode: Int) = onMain {
        if (_state.value.repeatMode == mode) return@onMain
        _state.value = _state.value.copy(repeatMode = mode)
        command(repeatScript(mode))
    }

    private fun repeatScript(mode: Int): String {
        val target = when (mode) { Player.REPEAT_MODE_ALL -> "all"; Player.REPEAT_MODE_ONE -> "one"; else -> "off" }
        return "if(p.repeat!=='$target'){p.repeat='$target';p.renderModes();p.prepareNext()}"
    }

    fun selectQuality(group: String) = onMain {
        if (group !in CACHE_QUALITIES) return@onMain
        val song = songs.getOrNull(occurrence) ?: return@onMain
        songs = songs.toMutableList().apply { this[occurrence] = song.copy(quality = group) }
        activeCacheSource = null
        qualityChange = null
        if (hasHostedTrack && !waitingToPlay) switchQuality(song.uid, group) else ++qualitySerial
    }

    private fun switchQuality(uid: Int, group: String) {
        val id = JSONObject.quote(group)
        val request = requestSerial
        val revision = ++qualitySerial
        qualityChange = QualityChange(uid, revision)
        command("""const entry=p.queue?.entries[p.queue.pos];
        if(entry?.fantasticIndex!==$uid)return;
        p.fantasticQuality=$revision;
        p.fantasticQualityGroup=null;
        (function run(attempt){
          const player=p, current=p.current;
          if(!current||p.pendingKey||p.queue?.entries[p.queue.pos]!==entry||
             (p.fantasticSessionInstalled&&current.fantasticIndex!==$uid)||
             p.fantasticRequest!==$request||p.fantasticQuality!==$revision)return;
          const state=p.variantsOf(current);
          if(state.loading&&attempt<100){setTimeout(function(){run(attempt+1);},100);return;}
          if(state.error){player.showError(state.error);return 'failed';}
          var wanted=$id;
          var variants=state.variants||[];
          var matches=function(item){
            var g=String(item.group_id||'').toLowerCase();
            if(wanted==='alac')return g.indexOf('alac')>=0;
            if(wanted==='atmos')return g.indexOf('atmos')>=0&&g.indexOf('downmix')<0&&g.indexOf('binaural')<0;
            if(wanted==='aac')return g.includes('stereo-')&&!['alac','he-','binaural','downmix','atmos'].some(word=>g.includes(word));
            if(wanted==='aac-128')return g.includes('stereo-128')&&!['alac','binaural','downmix','he-'].some(word=>g.includes(word));
            if(wanted==='aac-he')return g.indexOf('he-stereo-')>=0&&g.indexOf('binaural')<0&&g.indexOf('downmix')<0;
            if(wanted==='aac-binaural')return g.indexOf('binaural')>=0&&g.indexOf('128')<0&&g.indexOf('he-')<0;
            if(wanted==='aac-binaural-128')return g.indexOf('binaural')>=0&&g.indexOf('128')>=0&&g.indexOf('he-')<0;
            if(wanted==='he-binaural')return g.indexOf('binaural')>=0&&g.indexOf('he-')>=0;
            if(wanted==='aac-downmix')return g.indexOf('downmix')>=0&&g.indexOf('128')<0&&g.indexOf('he-')<0;
            if(wanted==='aac-downmix-128')return g.indexOf('downmix')>=0&&g.indexOf('128')>=0&&g.indexOf('he-')<0;
            if(wanted==='he-downmix')return g.indexOf('downmix')>=0&&g.indexOf('he-')>=0;
            return false;
          };
          var exact=variants.filter(function(item){return item.group_id===wanted||item.quality===wanted;});
          var choices=exact.length?exact:variants.filter(matches);
          choices.sort(function(a,b){
            var rank=function(v){return v.sample_rate?Number(v.sample_rate)*100+Number(v.bit_depth||0):Number(v.bandwidth||0);};
            return rank(b)-rank(a);
          });
          var variant=choices[0];
          if(!variant){player.showError('该曲目没有所选音质');return;}
          p.fantasticQualityGroup=variant.group_id;
          if(p.transport().paused){
            const next={...current,id:current.track+':'+variant.group_id,variant,
              codecs:variant.codecs,m3u8Url:variant.m3u8Url||variant.url,
              label:window.AmHook?.variantLabel(variant),badge:window.AmHook?.qualityBadge(variant),duration:0};
            void player.start(next,{force:true,resumeAt:p.transport().currentTime,paused:true});
          }else void player.switchVariant(variant);
        })(0)""")
    }

    private fun playbackSettingsScript(profile: ApplePlaybackProfile): String {
        val settings = JSONObject()
            .put("quality", profile.quality)
            .put("spatial", profile.spatial)
            .put("network", profile.network)
            .put("transition", prefs.appleTransition)
            .put("crossfade", prefs.appleCrossfade)
        return "(function(){if(window.AmHook&&AmHook.usePlaybackSettings){var s=" + settings.toString() + ";" +
            "AmHook.usePlaybackSettings({get:function(){return s;},set:function(){}});}})()"
    }


    fun close() = onMain {
        if (!closed.compareAndSet(false, true)) return@onMain
        activeCacheSource = null
        hostedScope = null
        hostedRun = null
        qualityChange = null
        destroyView()
        songs = emptyList()
        _state.value = PlayerState()
    }

    private fun command(body: String) = onMain {
        if (closed.get() || !_state.value.connected || webView == null) return@onMain
        evaluate("(function(){var p=window.AmApp&&AmApp.player;if(!p)return;$body;})()")
    }

    private fun runWhenReady(serial: Long, action: () -> Unit) {
        val view = webView ?: return
        if (closed.get() || serial != requestSerial || view !== webView) {
            stopReadyPolling()
            return
        }
        view.onResume()
        if (_state.value.connected) {
            stopReadyPolling()
            if (serial == requestSerial) {
                val profile = hostedRun?.profile ?: return
                recordStartup(StartupStage.Ready)
                evaluate(playbackSettingsScript(profile))
                action()
            }
            return
        }
        if (!waitingToPlay) {
            stopReadyPolling()
            return
        }
        recordStartup(StartupStage.Page)
        if (SystemClock.uptimeMillis() - readyStartedAt > READY_TIMEOUT_MS) {
            destroyView()
            publishError("连接 Fantastic 播放器超时")
            return
        }
        if (readyPoller != null) return
        val task = object : Runnable {
            override fun run() {
                if (readyPoller !== this) return
                readyPoller = null
                if (!closed.get() && serial == requestSerial && view === webView && waitingToPlay) {
                    runWhenReady(serial, action)
                }
            }
        }
        readyPoller = task
        main.postDelayed(task, READY_POLL_MS)
    }

    private fun ensureCurrentServer(normalized: String) {
        if (normalized == baseUrl && webView != null) return
        destroyView()
        baseUrl = normalized
        _state.value = _state.value.copy(connected = false)
        recordStartup(StartupStage.Page)
        val view = newWebView(appContext, normalized)
        webView = view
        view.loadUrl("$normalized/")
    }

    private fun failPage(message: String) {
        destroyView()
        if (waitingToPlay || hostedRun != null) publishError(message)
    }

    private fun destroyView() {
        stopStatePolling()
        stopReadyPolling()
        val view = webView
        webView = null
        view?.onPause()
        view?.stopLoading()
        view?.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(context: Context, server: String): WebView = WebView(context).apply {
        visibility = View.INVISIBLE
        alpha = 0f
        isFocusable = false
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        webViewClient = object : WebViewClient() {
            private var pageFinishedLogged = false

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.isForMainFrame || !sameOrigin(Uri.parse(server), request.url)

            @Deprecated("Deprecated in Android API")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                !sameOrigin(Uri.parse(server), Uri.parse(url))

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (view === webView && !sameOrigin(Uri.parse(server), Uri.parse(url))) {
                    failPage("已阻止跳转到其他播放服务器")
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (view !== webView || !sameOrigin(Uri.parse(server), Uri.parse(url))) return
                if (!pageFinishedLogged) {
                    pageFinishedLogged = true
                    if (startupRequestSerial == requestSerial && startupStartedAt != 0L) {
                        Log.d("FantasticApple", "event=page-finished request=$requestSerial " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - startupStartedAt}")
                    }
                }
                if (shouldPollState()) pollState()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                super.onReceivedError(view, request, error)
                if (view === webView && request.isForMainFrame) {
                    failPage("Fantastic 播放页加载失败：${error.description}")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                super.onReceivedHttpError(view, request, response)
                if (view === webView && request.isForMainFrame) {
                    failPage("Fantastic 播放页加载失败：HTTP ${response.statusCode}")
                }
            }
        }
    }

    private fun pollState() {
        val view = webView ?: return
        val serial = requestSerial
        val qualityRevision = qualitySerial
        evaluate("""(function(){
          const p=window.AmApp?.player;if(!p)return null;
          const c=p.current,q=p.queue,t=p.transport();
          const position=Number(t.currentTime||0),duration=c?p.duration():0;
          let buffered=position;
          let mediaBuffering=false;
          if(c&&t===p.clock&&p.audio){
            mediaBuffering=!t.paused&&t.readyState<3;
            const time=p.audio.currentTime,ranges=p.audio.buffered;
            for(let i=0;i<ranges.length;i++)if(time>=ranges.start(i)&&time<=ranges.end(i)){
              buffered=position+ranges.end(i)-time;break;
            }
          }
          return JSON.stringify({serial:p.fantasticRequest,track:c?.track,duration,position,buffered,
            seek:p.fantasticSeek,index:q?.entries[q.pos]?.fantasticIndex,
            occurrence:c?.fantasticIndex,loops:p.fantasticLoops,loopOccurrence:p.fantasticLoopOccurrence,
            qualityRevision:p.fantasticQuality,qualityGroup:p.fantasticQualityGroup,
            playing:!!(c&&!t.paused),ended:!!(c&&(t.ended||(t===p.clock&&p.audio?.ended)||
              (t.paused&&duration>0&&position>=duration))),
            buffering:!!(p.loading||p.attempting||p.pendingKey||t.seeking||mediaBuffering),
            group:c?.variant?.group_id,badge:c?.badge,
            resolving:!!p.pendingKey,preparing:!!(p.loading||p.attempting),mode:c?.mode,
            format:c?.variant?{codecs:c.variant.codecs,sampleRate:c.variant.sample_rate,
              bitDepth:c.variant.bit_depth,channels:c.variant.channels,bandwidth:c.variant.bandwidth}:null,
            error:typeof p.errorMsg==='function'?p.errorMsg():p.errorMsg});
        })()""") { json ->
            if (view !== webView || serial != requestSerial || json == null) return@evaluate
            val data = runCatching { JSONObject(json) }.getOrNull() ?: return@evaluate
            val old = _state.value
            if (data.optLong("serial", -1L) != serial || songs.isEmpty()) {
                if (!old.connected) _state.value = old.copy(connected = true)
                return@evaluate
            }
            if (data.optLong("seek", 0L) != seekSerial || qualityRevision != qualitySerial) return@evaluate
            val issue = data.optString("error").takeIf { it.isNotBlank() && it != "null" }?.take(320)
            val playing = data.optBoolean("playing")
            val buffering = data.optBoolean("buffering")
            val ended = data.optBoolean("ended") && !buffering
            val uid = data.optInt("index", -1)
            val index = songs.indexOfFirst { it.uid == uid }
            val actualUid = data.optInt("occurrence", -1)
            val song = songs.getOrNull(index)?.takeIf { actualUid == -1 || uid == actualUid }
            val current = song?.track?.takeIf { it.id == data.optString("track") }
            val mode = data.optString("mode").takeIf { it.isNotBlank() && it != "null" }
            when {
                data.optBoolean("resolving") -> recordStartup(StartupStage.Resolve)
                data.optBoolean("preparing") -> recordStartup(StartupStage.Prepare, mode)
            }
            if (song == null || current == null || (waitingToPlay && buffering)) {
                activeCacheSource = null
                if (issue != null && !buffering) {
                    recordStartup(StartupStage.Failed, issue)
                    stopStatePolling()
                    stopReadyPolling()
                    view.onPause()
                    waitingToPlay = false
                    pendingStartupSeek = false
                    _state.value = old.copy(connected = true, buffering = false, playWhenReady = false, error = issue,
                        isPlaying = false,
                        audioDetails = null)
                } else {
                    _state.value = old.copy(connected = true, audioDetails = null)
                }
                return@evaluate
            }
            if (pendingStartupSeek && issue == null && !ended) {
                activeCacheSource = null
                pendingStartupSeek = false
                hasHostedTrack = true
                waitingToPlay = false
                seekHosted()
                return@evaluate
            }
            pendingStartupSeek = false
            val positionMs = data.millis("position")
            val loops = data.optLong("loops", 0L)
            val looped = loops > lastHostedLoops && uid == actualUid && uid == data.optInt("loopOccurrence", -1)
            if (looped) completedLoops += loops - lastHostedLoops
            if (loops > lastHostedLoops) lastHostedLoops = loops
            val jumped = looped || index != occurrence || kotlin.math.abs(positionMs - position()) > 750L
            if (index != occurrence) qualityChange = null
            hasHostedTrack = true
            occurrence = index
            waitingToPlay = false
            lastPositionMs = positionMs
            sampleTime = android.os.SystemClock.uptimeMillis()
            val format = data.optJSONObject("format")
            val audioDetails = variantAudioDetails(current.id, format)
            val group = data.optString("group")
            val quality = codecGroup(group)
            val durationMs = data.millis("duration")
            val playingNow = playing && !buffering && !ended && issue == null
            val explicitQuality = song.quality
            val change = qualityChange?.takeIf { it.occurrence == uid }
            val confirmedQuality = explicitQuality == null || quality == explicitQuality && change != null &&
                data.optLong("qualityRevision", -1L) == change.revision && data.optString("qualityGroup") == group
            val run = hostedRun
            val codecs = format?.optString("codecs")?.takeIf { it.isNotBlank() && it != "null" }
            val source = if (playingNow && old.playWhenReady && uid == actualUid && confirmedQuality &&
                run != null && quality != null && quality in CACHE_QUALITIES && CACHE_GROUP.matches(group) &&
                codecs != null && mode != null && durationMs > 0L) {
                captureSource(run, uid, serial, group, codecs, quality, mode, durationMs)
            } else null
            if (source == null || source.occurrence != activeCacheSource?.occurrence) activeCacheSource = null
            _state.value = old.copy(connected = true, mediaId = "apple:${current.id}", title = current.title,
                artist = current.artist, artworkUri = current.artwork(600), queueIndex = index,
                isPlaying = playingNow, playWhenReady = old.playWhenReady && (issue == null || playing || buffering),
                buffering = buffering, ended = ended, bufferedPositionMs = data.millis("buffered"),
                durationMs = durationMs.takeIf { it > 0L } ?: current.durationMs,
                hasPrevious = true,
                hasNext = index < songs.lastIndex || old.repeatMode == Player.REPEAT_MODE_ALL,
                error = issue, quality = quality, audioDetails = audioDetails,
                qualityLabel = when (data.optString("badge")) {
                    "hires" -> "Hi-Res 无损"
                    "lossless" -> "无损"
                    "atmos" -> "杜比全景声"
                    "spatial" -> "空间音频"
                    "lossy" -> "AAC"
                    else -> null
                }, seekGeneration = old.seekGeneration + if (jumped) 1 else 0)
            if (serial != requestSerial || view !== webView || qualityRevision != qualitySerial) return@evaluate
            if (issue != null) recordStartup(StartupStage.Failed, issue)
            else if (playingNow) recordStartup(StartupStage.Playing, mode)
            if (ended || issue != null) {
                stopStatePolling()
                stopReadyPolling()
                view.onPause()
                return@evaluate
            }
            if (playingNow && explicitQuality != null && change == null) switchQuality(uid, explicitQuality)
            else if (source != null) activeCacheSource = source
        }
    }

    private fun captureSource(
        run: HostedRun, uid: Int, serial: Long, group: String, codecs: String, quality: String, mode: String, durationMs: Long,
    ): AppleCacheSource {
        val previous = activeCacheSource
        if (previous != null && previous.occurrence == uid && previous.requestSerial == serial &&
            previous.group == group && previous.codecs == codecs && previous.mode == mode && previous.durationMs == durationMs) {
            return previous
        }
        return AppleCacheSource(uid, serial, run.profile, group, codecs, quality, mode, durationMs, run.server, run.storefront)
    }

    private fun variantAudioDetails(trackId: String, format: JSONObject?): String? {
        if (format == null) {
            variantDetailTrackId = null
            variantDetailCodecs = null
            variantDetailSampleRate = 0
            variantDetailBitDepth = 0
            variantDetailChannels = null
            variantDetailBandwidth = 0L
            variantDetailValue = null
            return null
        }
        val codecs = format.optString("codecs").takeIf { it.isNotBlank() && it != "null" }
        val sampleRate = format.optInt("sampleRate")
        val bitDepth = format.optInt("bitDepth")
        val channels = format.optString("channels").takeIf { it.isNotBlank() && it != "null" }
        val bandwidth = format.optLong("bandwidth")
        if (trackId == variantDetailTrackId && codecs == variantDetailCodecs && sampleRate == variantDetailSampleRate &&
            bitDepth == variantDetailBitDepth && channels == variantDetailChannels && bandwidth == variantDetailBandwidth) {
            return variantDetailValue
        }
        val details = reportedAudioDetails(
            codec = codecs,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            bitrate = bandwidth,
            bitrateLabel = "变体带宽",
        )
        variantDetailTrackId = trackId
        variantDetailCodecs = codecs
        variantDetailSampleRate = sampleRate
        variantDetailBitDepth = bitDepth
        variantDetailChannels = channels
        variantDetailBandwidth = bandwidth
        variantDetailValue = details
        return details
    }

    private fun JSONObject.millis(key: String): Long =
        (optDouble(key, 0.0).takeIf { it.isFinite() && it in 0.0..86_400.0 }?.times(1_000) ?: 0.0).toLong()

    private fun codecGroup(group: String): String? {
        val name = group.lowercase()
        val capped = "stereo-128" in name
        val he = "he-" in name
        return when {
            name.isEmpty() -> null
            "alac" in name -> "alac"
            "binaural" in name -> if (he) "he-binaural" else if (capped) "aac-binaural-128" else "aac-binaural"
            "downmix" in name -> if (he) "he-downmix" else if (capped) "aac-downmix-128" else "aac-downmix"
            "atmos" in name -> "atmos"
            he -> "aac-he"
            "stereo-" in name -> if (capped) "aac-128" else "aac"
            else -> group.take(64)
        }
    }

    private fun schedulePoll() {
        if (poller != null || !shouldPollState()) return
        val task = object : Runnable {
            override fun run() {
                if (poller !== this) return
                poller = null
                if (!shouldPollState()) return
                val configuredServer = hostedScope?.configuredServer
                val currentServer = prefs.baseUrl
                if (configuredServer != null && currentServer != configuredServer &&
                    SourceStatus.normalizeBaseUrl(currentServer) != SourceStatus.normalizeBaseUrl(configuredServer)) {
                    destroyView()
                    publishError("服务器已更改，点按播放重新连接")
                    return
                }
                pollState()
                if (shouldPollState()) {
                    poller = this
                    main.postDelayed(this, STATE_POLL_MS)
                }
            }
        }
        poller = task
        main.postDelayed(task, STATE_POLL_MS)
    }

    private fun shouldPollState(): Boolean = webView != null && !closed.get() &&
        (waitingToPlay || (_state.value.playWhenReady && !_state.value.ended))

    private fun stopStatePolling() {
        poller?.let(main::removeCallbacks)
        poller = null
    }

    private fun stopReadyPolling() {
        readyPoller?.let(main::removeCallbacks)
        readyPoller = null
    }

    private fun evaluate(script: String, done: ((String?) -> Unit)? = null) {
        val view = webView ?: return
        if (!sameOrigin(Uri.parse(baseUrl), Uri.parse(view.url ?: return))) return
        view.evaluateJavascript(script) { raw ->
            if (view !== webView) return@evaluateJavascript
            done?.invoke(runCatching { org.json.JSONTokener(raw.orEmpty()).nextValue() as? String }.getOrNull())
        }
    }

    private fun recordStartup(stage: StartupStage, detail: String? = null) {
        if (startupStartedAt == 0L || startupRequestSerial != requestSerial || startupStage == stage ||
            startupStage == StartupStage.Failed || (startupStage == StartupStage.Playing && stage != StartupStage.Failed)) return
        startupStage = stage
        val suffix = if (detail.isNullOrBlank()) ""
            else if (stage == StartupStage.Failed) " error=${detail.take(320)}"
            else " mode=${detail.take(320)}"
        Log.d("FantasticApple", "request=$requestSerial stage=$stage " +
            "elapsedMs=${SystemClock.elapsedRealtime() - startupStartedAt} mediaId=${_state.value.mediaId ?: "-"}$suffix")
    }

    private fun publishError(message: String) {
        recordStartup(StartupStage.Failed, message)
        activeCacheSource = null
        hostedRun = null
        qualityChange = null
        waitingToPlay = false
        pendingStartupSeek = false
        hasHostedTrack = false
        stopStatePolling()
        stopReadyPolling()
        webView?.onPause()
        _state.value = _state.value.copy(connected = false, isPlaying = false, playWhenReady = false,
            buffering = false, error = message)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun sameOrigin(expected: Uri, actual: Uri): Boolean =
        expected.scheme.equals(actual.scheme, ignoreCase = true) &&
            expected.host.equals(actual.host, ignoreCase = true) && effectivePort(expected) == effectivePort(actual)

    private fun effectivePort(uri: Uri): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private companion object {
        const val READY_TIMEOUT_MS = 20_000L
        const val READY_POLL_MS = 100L
        const val STATE_POLL_MS = 250L
        val STORE_FRONT = Regex("[a-z]{2}")
        val CACHE_GROUP = Regex("[A-Za-z0-9._-]{1,128}")
        val CACHE_QUALITIES = Prefs.APPLE_QUALITIES.mapTo(hashSetOf()) { it.first }
        // Cancel both async stages: playAt/resolveEntry and start/format preparation.
        const val RESET_HOSTED = "++p.playToken;p.pause();p.clearQueue();p.teardown();" +
            "p.current=null;p.attempting=false;p.setLoading(false);p.showError('');p.updateMediaSession()"
    }
}
