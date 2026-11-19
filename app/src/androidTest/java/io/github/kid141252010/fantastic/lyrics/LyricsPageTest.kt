package io.github.kid141252010.fantastic.lyrics

import android.graphics.Color
import android.os.ParcelFileDescriptor
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
            // 读到 EOF 才算命令跑完
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/lyrics.png"),
            ).use { it.readBytes() }
            assertTrue("挂载后没有可见歌词（$visible 个文字节点，视口 $size）", visible > 5)
            assertTrue("没有页面错误", bridge.statuses.none { it.startsWith("error:") })
        }
    }

    @Test
    fun reconstructsWordMotionAfterBackwardSeekAndPause() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word"><body dur="24"><div>
          <p begin="1" end="4"><span begin="1" end="4">Hold</span></p>
          <p begin="5" end="8"><span begin="5" end="8">steady</span></p>
          <p begin="20" end="23"><span begin="20" end="23">again</span></p>
        </div></body></tt>"""
        val json = JSONObject().put("candidates", org.json.JSONArray().put(
            JSONObject().put("source", "qrc").put("text", ttml),
        )).toString()
        val bridge = Bridge()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var web: WebView
            scenario.onActivity { activity ->
                web = LyricsWebView.create(activity, bridge)
                activity.setContentView(web)
            }
            assertTrue("歌词脚本未就绪", bridge.ready.await(30, TimeUnit.SECONDS))
            scenario.onActivity {
                web.evaluateJavascript("fantastic.setLyrics(${JSONObject.quote(json)});fantastic.sync(2200,false,true)", null)
            }
            assertEquals("ready", bridge.statuses.poll(30, TimeUnit.SECONDS))
            val paintedWords = """(() => Array.from(document.querySelectorAll('.lyric-word-piece'))
              .filter(e => e.closest('.lyric-line').dataset.active === 'true')
              .map(e => [e.textContent, getComputedStyle(e).backgroundImage, getComputedStyle(e).transform]))()"""
            val before = evaluate(scenario, web, paintedWords)
            assertTrue("长音未画出可见的高亮", before.contains("Hold".first().toString()) && before.contains("gradient"))
            evaluate(scenario, web, "fantastic.sync(6200,false,true)")
            assertTrue("向前跳转后仍显示上一句高亮", before != evaluate(scenario, web, paintedWords))
            evaluate(scenario, web, "fantastic.sync(2200,false,true)")
            assertEquals("向后跳转应还原相同的渐变、上浮与长音缩放", before, evaluate(scenario, web, paintedWords))
            Thread.sleep(250)
            assertEquals("暂停后逐词动画仍在运行", before, evaluate(scenario, web, paintedWords))
            evaluate(scenario, web, "fantastic.setLyrics(null)")
            assertEquals("切歌清空后仍残留上首歌词", "\"\"", evaluate(scenario, web, "document.getElementById('lyrics').textContent"))
            assertTrue("没有页面错误", bridge.statuses.none { it.startsWith("error:") })
        }
    }

    @Test
    fun lineMovementUsesFollowingGapNotIncomingLead() {
        val bridge = Bridge()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContentView(LyricsWebView.create(activity, bridge)) }
            assertTrue("歌词脚本未就绪", bridge.ready.await(30, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val web = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as WebView
                web.evaluateJavascript("""
                  (async () => {
                    const { AppleMusicLyrics } = await import(new URL('apple-music-player.mjs', document.baseURI).href);
                    const { parseTTML } = await import(new URL('ttml.mjs', document.baseURI).href);
                    const host = document.createElement('div');
                    host.style.cssText = 'position:absolute;inset:0';
                    document.body.append(host);
                    const view = new AppleMusicLyrics(host, () => {});
                    view.setSong(parseTTML('<tt xmlns="http://www.w3.org/ns/ttml"><body dur="9"><div>' +
                      '<p begin="1" end="2"><span begin="1" end="2">First</span></p>' +
                      '<p begin="2.1" end="3.1"><span begin="2.1" end="3.1">Second</span></p>' +
                      '<p begin="5" end="6"><span begin="5" end="6">Third</span></p>' +
                      '</div></body></tt>'));
                    view.resize();
                    view.render(1200, 100, true);
                    const previous = view.element.scrollTop;
                    view.render(1800, 1000);
                    const line = host.querySelector('[data-begin="2100"]');
                    const start = line.getBoundingClientRect().top;
                    const distance = view.element.scrollTop - previous;
                    view.render(1800, 1375);
                    const moved = start - line.getBoundingClientRect().top;
                    // Independent inversion of the APK's (.4,.1,0,1) curve at half of
                    // the 750 ms FOLLOWING-gap move (incoming lead is only 480 ms).
                    let lo = 0, hi = 1;
                    for (let i = 0; i < 40; i++) {
                      const t = (lo + hi) / 2;
                      if (1.2 * (1-t)**2 * t + t**3 < .5) lo = t; else hi = t;
                    }
                    const t = (lo + hi) / 2;
                    const fraction = .3 * (1-t)**2 * t + 3 * (1-t) * t*t + t**3;
                    if (distance < 10 || Math.abs(moved - distance * fraction) > 1.5)
                      throw Error('换行位移/时序不匹配: moved=' + moved + ', expected=' + distance * fraction);
                    FantasticBridge.status('motion:pass');
                  })().catch(e => FantasticBridge.status('error:' + e.message));
                """.trimIndent(), null)
            }
            assertEquals("换行未按 APK 后续句间隙完成半程位移", "motion:pass", bridge.statuses.poll(30, TimeUnit.SECONDS))
        }
    }
}
