package io.github.kid141252010.fantastic.lyrics

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.remember
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import io.github.kid141252010.fantastic.ui.player.nowPlayingEnter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 真机（新 WebView）上歌词在播放页里不显示，但单独放进 Activity 能显示。
 * 这里把歌词 WebView 放进几种不同的宿主里，各自截图数 WebView 区域里的亮像素，找出是哪一层让它画不出来。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(AndroidJUnit4::class)
class LyricsHostVariantsTest {
    class Bridge {
        val ready = CountDownLatch(1)
        @JavascriptInterface fun ready() = ready.countDown()
        @JavascriptInterface fun seek(ms: Double) {}
        @JavascriptInterface fun status(value: String) {}
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val json by lazy { instrumentation.context.assets.open("qq-lyrics.json").bufferedReader().readText() }

    private fun variant(name: String, appContext: Boolean, host: @Composable (WebView) -> Unit): Int {
        val bridge = Bridge()
        var bright = -1
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var web: WebView
            scenario.onActivity { activity ->
                val ctx: Context = if (appContext) activity.applicationContext else activity
                web = LyricsWebView.create(ctx, bridge)
            }
            assertTrue("$name: page not ready", bridge.ready.await(30, TimeUnit.SECONDS))
            scenario.onActivity {
                web.evaluateJavascript("fantastic.setLyrics(${JSONObject.quote(json)});fantastic.sync(46000,true,true)", null)
            }
            Thread.sleep(500)
            scenario.onActivity { activity ->
                activity.window.decorView.setBackgroundColor(Color.BLACK)
                activity.setContent { host(web) }
            }
            Thread.sleep(4000)
            val loc = IntArray(2)
            scenario.onActivity { web.getLocationOnScreen(loc) }
            val shot = instrumentation.uiAutomation.takeScreenshot()
            bright = 0
            if (shot != null && web.width > 0) {
                var y = loc[1].coerceAtLeast(0)
                while (y < (loc[1] + web.height).coerceAtMost(shot.height)) {
                    var x = loc[0].coerceAtLeast(0)
                    while (x < (loc[0] + web.width).coerceAtMost(shot.width)) {
                        val c = shot.getPixel(x, y)
                        if ((Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10 > 170) bright++
                        x += 2
                    }
                    y += 2
                }
            }
            Log.i("FantasticLyrics", "variant $name: bright=$bright size=${web.width}x${web.height} loc=${loc.toList()} ua=${WebView.getCurrentWebViewPackage()?.versionName}")
            scenario.onActivity { (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        }
        return bright
    }

    @Composable
    private fun Plain(web: WebView, modifier: Modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { web.also { (it.parent as? ViewGroup)?.removeView(it) } }, modifier = modifier)
    }

    @Test
    fun hosts() {
        val results = linkedMapOf<String, Int>()
        results["plain"] = variant("plain", true) { Plain(it) }
        results["sharedTransitionLayout"] = variant("sharedTransitionLayout", true) { web ->
            SharedTransitionLayout(Modifier.fillMaxSize()) { Plain(web) }
        }
        results["animatedVisibility-enter"] = variant("animatedVisibility-enter", true) { web ->
            val state = remember { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(state, enter = nowPlayingEnter) { Plain(web) }
        }
        results["haze-sibling"] = variant("haze-sibling", true) { web ->
            val haze = remember { HazeState() }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().haze(haze).background(androidx.compose.ui.graphics.Color.DarkGray))
                Plain(web)
            }
        }
        results["shared+visibility"] = variant("shared+visibility", true) { web ->
            SharedTransitionLayout(Modifier.fillMaxSize()) {
                val state = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(state, enter = nowPlayingEnter) { Plain(web) }
            }
        }
        Log.i("FantasticLyrics", "variants $results")
        assertTrue("variants $results", results.values.all { it > 200 })
    }
}
