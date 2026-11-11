package com.moyou.isponsorblocktv.mobile

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.moyou.isponsorblocktv.data.SponsorBlockRepository
import com.moyou.isponsorblocktv.domain.MonitorConfig
import com.moyou.isponsorblocktv.domain.SkipSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

private const val YOUTUBE_HOME = "https://m.youtube.com/"

/**
 * Independent mobile browser, NOT a controller for the installed YouTube application.
 * Site markup is not stable and some Android WebView versions may refuse playback.
 */
@Composable
fun MobileYouTubeScreen(
    config: MonitorConfig,
    sponsorBlock: SponsorBlockRepository,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("افتح فيديو داخل التطبيق لتخطي مقاطع SponsorBlock.") }
    var skipCount by remember { mutableIntStateOf(0) }

    val browser = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request?.isForMainFrame != true) return false
                    val destination = request.url
                    if (destination.scheme != "https") return true
                    val host = destination.host?.lowercase().orEmpty()
                    val onYouTube = host == "youtube.com" ||
                        host.endsWith(".youtube.com") || host == "youtu.be"
                    if (onYouTube) return false
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, destination)) }
                    catch (_: Exception) { notice = "تعذر فتح الرابط الخارجي." }
                    return true
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        notice = "تعذر تحميل YouTube. تحقق من الإنترنت."
                    }
                }
            }
            loadUrl(YOUTUBE_HOME)
        }
    }

    DisposableEffect(browser) {
        onDispose {
            browser.stopLoading()
            browser.loadUrl("about:blank")
            browser.destroy()
        }
    }

    BackHandler {
        if (browser.canGoBack()) browser.goBack() else onClose()
    }

    LaunchedEffect(browser, config.categories, config.minSkipSeconds, config.skipAds, config.trackSkips) {
        var currentVideoId = ""
        var segments = emptyList<SkipSegment>()
        val completedKeys = mutableSetOf<String>()
        var fetchJob: Job? = null
        try {
            while (isActive) {
                try {
                    val snapshot = decodeSnapshot(runJavascript(browser, observationScript(config.skipAds)))
                    if (snapshot != null) {
                        val id = MobilePlaybackRules.videoId(snapshot.url).orEmpty()
                        if (id != currentVideoId) {
                            currentVideoId = id
                            completedKeys.clear()
                            segments = emptyList()
                            fetchJob?.cancel()
                            fetchJob = null
                            if (id.isNotEmpty() && config.categories.isNotEmpty()) {
                                notice = "جارٍ البحث عن مقاطع SponsorBlock..."
                                fetchJob = launch {
                                    while (isActive && currentVideoId == id) {
                                        try {
                                            val latest = sponsorBlock.segments(id, config.categories, config.minSkipSeconds)
                                            if (currentVideoId == id) {
                                                segments = latest
                                                notice = if (latest.isEmpty()) "لا توجد مقاطع لهذا الفيديو."
                                                    else "الحماية جاهزة: " + latest.size + " مقطع."
                                            }
                                            break
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            notice = "تعذر الوصول إلى SponsorBlock. إعادة المحاولة..."
                                            delay(10_000)
                                        }
                                    }
                                }
                            } else {
                                notice = if (id.isEmpty()) "تصفح YouTube واختر فيديو."
                                    else "فعّل فئة واحدة على الأقل في إعدادات التطبيق."
                            }
                        }

                        if (id.isNotEmpty() && id == currentVideoId) {
                            val segment = MobilePlaybackRules.segmentAt(
                                segments, snapshot.position, snapshot.playing, snapshot.advertisement
                            )
                            if (segment != null) {
                                val key = segment.uuids.joinToString(",")
                                if (key !in completedKeys) {
                                    val result = runJavascript(browser, seekScript(id, segment.end))
                                    if (result == "true") {
                                        completedKeys.add(key)
                                        skipCount += 1
                                        notice = "تم تجاوز مقطع SponsorBlock."
                                        if (config.trackSkips) launch {
                                            sponsorBlock.markViewed(segment.uuids)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // DOM changes during navigation must not crash the app.
                }
                delay(800)
            }
        } finally {
            fetchJob?.cancel()
        }
    }

    fun searchYouTube() {
        val term = query.trim()
        if (term.isNotEmpty()) {
            browser.loadUrl("https://m.youtube.com/results?search_query=" + Uri.encode(term))
        }
    }

    Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("YouTube على الهاتف", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onClose) { Text("وضع التلفاز") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("ابحث عن فيديو") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searchYouTube() }),
                )
                Button(onClick = { searchYouTube() }, enabled = query.isNotBlank()) {
                    Text("بحث")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { browser.loadUrl(YOUTUBE_HOME) }) { Text("الرئيسية") }
                OutlinedButton(onClick = { if (browser.canGoBack()) browser.goBack() }) { Text("رجوع") }
                Spacer(Modifier.weight(1f))
                Text("التخطي: " + skipCount, modifier = Modifier.padding(top = 10.dp))
            }
            Text(notice, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AndroidView(factory = { browser }, modifier = Modifier.fillMaxWidth().weight(1f))
            Text(
                "تخطي المقاطع حسب توفر SponsorBlock وزر تخطي الإعلان عند إتاحته. لا يغير تطبيق YouTube الرسمي ولا يضمن حجب الإعلانات.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class BrowserSnapshot(
    val url: String, val position: Double, val playing: Boolean, val advertisement: Boolean,
)

private fun decodeSnapshot(raw: String?): BrowserSnapshot? {
    if (raw.isNullOrBlank()) return null
    return try {
        val json = JSONTokener(raw).nextValue() as? String ?: return null
        val array = JSONArray(json)
        BrowserSnapshot(
            url = array.optString(0),
            position = array.optDouble(1, -1.0),
            playing = array.optBoolean(2, false),
            advertisement = array.optBoolean(3, false),
        )
    } catch (_: Exception) { null }
}

private suspend fun runJavascript(view: WebView, script: String): String? =
    suspendCancellableCoroutine { continuation ->
        try {
            view.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        } catch (_: Exception) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

private val AD_SKIP_JS = """
const selectors = [
 '.ytp-ad-skip-button', '.ytp-ad-skip-button-modern',
 '.ytp-skip-ad-button', '.ytp-ad-skip-button-slot button'
];
for (const selector of selectors) {
 const button = document.querySelector(selector);
 if (button && !button.disabled && button.getClientRects().length) {
   button.click(); break;
 }
}
""".trimIndent()

private val OBSERVE_JS = """
(function(){
  try {
    __AD_ACTION__
    const video = document.querySelector('video');
    const player = document.querySelector('.html5-video-player');
    const ad = Boolean((player && player.classList.contains('ad-showing')) ||
      document.querySelector('.ad-showing'));
    return JSON.stringify([location.href,
      video && Number.isFinite(video.currentTime) ? video.currentTime : -1,
      video ? !video.paused : false, ad]);
  } catch(e) { return JSON.stringify(['', -1, false, false]); }
})()
""".trimIndent()

private fun observationScript(skipAds: Boolean): String =
    OBSERVE_JS.replace("__AD_ACTION__", if (skipAds) AD_SKIP_JS else "")

private fun seekScript(videoId: String, target: Double): String = """
(function(){
 try {
   const u = new URL(location.href);
   const parts = u.pathname.split('/');
   const current = u.searchParams.get('v') ||
      ((parts[1] === 'shorts' || parts[1] === 'live') ? parts[2] : '');
   if (current !== __VIDEO_ID__) return false;
   const video = document.querySelector('video');
   const ad = document.querySelector('.ad-showing');
   if (!video || video.paused || ad || video.currentTime >= __TARGET__) return false;
   video.currentTime = __TARGET__;
   return video.currentTime >= __TARGET_MINUS__;
 } catch(e) { return false; }
})()
""".trimIndent()
    .replace("__VIDEO_ID__", JSONObject.quote(videoId))
    .replace("__TARGET_MINUS__", (target - 0.5).toString())
    .replace("__TARGET__", target.toString())
