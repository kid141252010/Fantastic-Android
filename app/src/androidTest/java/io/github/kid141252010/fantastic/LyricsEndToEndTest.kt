package io.github.kid141252010.fantastic

import android.Manifest
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.kid141252010.fantastic.data.Source
import io.github.kid141252010.fantastic.data.Track
import io.github.kid141252010.fantastic.lyrics.LyricsController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 端到端：在真实的 MainActivity 里播一首 QQ 歌（服务器换成本地 MockWebServer，歌词用测试资源，CI 在海外访问不了 QQ），点开迷你播放条进入全屏播放页，
 * 打开歌词，检查 WebView 里有可见歌词，并截图到 /data/local/tmp/lyrics-e2e.png。
 * 和 LyricsPageTest 不同，这里经过 LyricsController、Compose 的 AndroidView 与播放页的布局。
 */
@RunWith(AndroidJUnit4::class)
class LyricsEndToEndTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(
            if (android.os.Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
            else GrantPermissionRule.grant(),
        )
        .around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<FantasticApp>()

    private fun screencap(path: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("screencap -p $path"),
        ).use { it.readBytes() }
    }

    private fun js(script: String): String {
        val result = LinkedBlockingQueue<String>()
        compose.runOnUiThread { app.lyrics.webView.evaluateJavascript(script) { result.put(it ?: "null") } }
        return result.poll(10, TimeUnit.SECONDS) ?: "timeout"
    }

    private val server = MockWebServer()
    private var oldBaseUrl: String? = null

    @After
    fun tearDown() {
        oldBaseUrl?.let { app.prefs.baseUrl = it }
        server.shutdown()
    }

    @Test
    fun lyricsVisibleInNowPlaying() {
        val lyricsJson = instrumentation.context.assets.open("qq-lyrics.json").bufferedReader().readText()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().contains("/lyrics")) {
                    MockResponse().setHeader("Content-Type", "application/json").setBody(lyricsJson)
                } else {
                    MockResponse().setResponseCode(404)
                }
        }
        server.start()
        oldBaseUrl = app.prefs.baseUrl
        app.prefs.baseUrl = server.url("/").toString()
        val title = "端到端测试"
        compose.runOnUiThread {
            app.player.playQueue(listOf(Track("002Agcje4enxFb", Source.QQ, title, "测试", "测试", null, 0)), 0)
        }
        compose.waitUntil(30_000) { app.player.state.value.mediaId == "qq:002Agcje4enxFb" }
        compose.waitUntil(60_000) {
            app.lyrics.status.value.let { it != LyricsController.Status.Loading && it != LyricsController.Status.None }
        }
        Log.i("FantasticLyrics", "e2e status=${app.lyrics.status.value} error=${app.lyrics.error.value}")

        Thread.sleep(1500)
        screencap("/data/local/tmp/ui-home.png")
        compose.onAllNodesWithText(title).onFirst().performClick()
        compose.waitForIdle()
        Thread.sleep(1500)
        screencap("/data/local/tmp/ui-player.png")
        compose.onNodeWithContentDescription("歌词").performClick()
        compose.waitForIdle()
        Thread.sleep(4000)

        // 行从下方弹簧滑入，给最多 10 秒
        var visible = "0"
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            visible = countVisible()
            if ((visible.toIntOrNull() ?: 0) > 5) break
            Thread.sleep(500)
        }
        val dbg = js("fantastic.debug()")
        val web = app.lyrics.webView
        val info: String = "debug=$dbg " + "status=${app.lyrics.status.value} error=${app.lyrics.error.value} visible=$visible " +
            "viewport=${js("innerWidth+'x'+innerHeight")} view=${web.width}x${web.height} " +
            "attached=${web.isAttachedToWindow} shown=${web.isShown} diag=${app.lyrics.diag.value}"
        Log.i("FantasticLyrics", "e2e $info")
        screencap("/data/local/tmp/lyrics-e2e.png")
        assertEquals(info, LyricsController.Status.Ready, app.lyrics.status.value)
        assertTrue(info, (visible.toIntOrNull() ?: 0) > 5)
    }

    private fun countVisible(): String = js(
        """(() => {
          const h = innerHeight;
          return [...document.querySelectorAll('#lyrics *')].filter((e) => {
            if (e.childElementCount || !e.textContent.trim()) return false;
            const r = e.getBoundingClientRect();
            return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < h;
          }).length;
        })()""",
    )
}
