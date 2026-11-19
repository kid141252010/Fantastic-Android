package io.github.kid141252010.fantastic.lyrics

import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 在真 WebView 里按 App 的实际顺序跑歌词页：先在后台（未挂到窗口、宽高为 0）加载页面并灌入歌词，
 * 再挂到界面上，检查歌词行确实排版出来、有可见文字，并截图到 /data/local/tmp/lyrics.png。
 */
@RunWith(AndroidJUnit4::class)
class LyricsPageTest {
    class Bridge {
        val ready = CountDownLatch(1)
        val statuses = LinkedBlockingQueue<String>()

        @JavascriptInterface fun ready() = ready.countDown()
        @JavascriptInterface fun seek(ms: Double) {}
        @JavascriptInterface fun status(value: String) { statuses.put(value) }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun evaluate(scenario: ActivityScenario<ComponentActivity>, web: WebView, script: String): String {
        val result = LinkedBlockingQueue<String>()
        scenario.onActivity { web.evaluateJavascript(script) { result.put(it ?: "null") } }
        return result.poll(10, TimeUnit.SECONDS) ?: error("evaluateJavascript timed out: $script")
    }

    @Test
    fun rendersQqLyricsAfterAttach() {
        val json = instrumentation.context.assets.open("qq-lyrics.json").bufferedReader().readText()
        val bridge = Bridge()
        val loadErrors = LinkedBlockingQueue<String>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var web: WebView
            scenario.onActivity { activity ->
                web = LyricsWebView.create(activity, bridge) { loadErrors.put(it) }
            }
            assertTrue("页面脚本没有运行：${loadErrors.peek()}", bridge.ready.await(30, TimeUnit.SECONDS))

            // 未挂载时灌入歌词，同 App
            scenario.onActivity {
                web.evaluateJavascript("fantastic.setLyrics(${JSONObject.quote(json)});fantastic.sync(46000,true,true)", null)
            }
            assertEquals("ready", bridge.statuses.poll(30, TimeUnit.SECONDS))

            scenario.onActivity { activity ->
                activity.window.decorView.setBackgroundColor(Color.BLACK)
                activity.setContentView(
                    web,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            }
            Thread.sleep(4000)

            val visible = evaluate(
                scenario, web,
                """(() => {
                  const h = innerHeight;
                  return [...document.querySelectorAll('#lyrics *')].filter((e) => {
                    if (e.childElementCount || !e.textContent.trim()) return false;
                    const r = e.getBoundingClientRect();
                    return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < h;
                  }).length;
                })()""",
            ).toInt()
            val size = evaluate(scenario, web, "innerWidth + 'x' + innerHeight")
            android.util.Log.i("FantasticLyrics", "visible text nodes=$visible viewport=$size ua=${evaluate(scenario, web, "navigator.userAgent")}")
            instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/lyrics.png").close()
            Thread.sleep(1500)
            assertTrue("挂载后没有可见歌词（$visible 个文字节点，视口 $size）", visible > 5)
            assertTrue("没有页面错误", bridge.statuses.none { it.startsWith("error:") })
        }
    }
}
