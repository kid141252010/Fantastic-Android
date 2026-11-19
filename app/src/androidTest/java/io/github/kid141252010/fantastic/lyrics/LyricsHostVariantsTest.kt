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
        results["compose-plain-activityCtx"] = variant("compose-plain-activityCtx", false) { Plain(it) }
        results["compose-plain-appCtx"] = variant("compose-plain-appCtx", true) { Plain(it) }
        results["graphicsLayer-clip-shape"] = variant("graphicsLayer-clip-shape", true) { web ->
            Box(Modifier.fillMaxSize().graphicsLayer { shape = RoundedCornerShape(1.dp); clip = true }.background(androidx.compose.ui.graphics.Color(0xFF1C1C1E))) { Plain(web) }
        }
        results["blur-sibling"] = variant("blur-sibling", true) { web ->
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().blur(80.dp).background(androidx.compose.ui.graphics.Color.DarkGray))
                Plain(web)
            }
        }
        results["animatedContent"] = variant("animatedContent", true) { web ->
            AnimatedContent(targetState = true, label = "t") { if (it) Plain(web) }
        }
        results["graphicsLayer-alpha"] = variant("graphicsLayer-alpha", true) { web ->
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.99f }) { Plain(web) }
        }
        results["wider-offset"] = variant("wider-offset", true) { web ->
            Plain(web, Modifier.fillMaxSize().layout { m, c ->
                val extra = 64.dp.roundToPx()
                val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
                layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
            })
        }
        Log.i("FantasticLyrics", "variants $results")
        assertTrue("variants $results", results.values.all { it > 200 })
    }
}
