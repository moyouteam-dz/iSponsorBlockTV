package com.moyou.isponsorblocktv.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moyou.isponsorblocktv.data.SponsorBlockRepository
import com.moyou.isponsorblocktv.mobile.MobileYouTubeScreen
import javax.inject.Inject
import com.moyou.isponsorblocktv.domain.LinkStatus
import com.moyou.isponsorblocktv.service.MonitorService
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    @Inject lateinit var sponsorBlock: SponsorBlockRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.uiState.collectAsStateWithLifecycle()
            var mobileMode by rememberSaveable { mutableStateOf(false) }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                // Permission may be denied: foreground service still displays its task notification.
                startMonitor()
            }
            val onStart = {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else startMonitor()
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = appColors) {
                    if (mobileMode) {
                        MobileYouTubeScreen(
                            config = state.config,
                            sponsorBlock = sponsorBlock,
                            onClose = { mobileMode = false },
                        )
                    } else {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Button(
                                onClick = { mobileMode = true },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                Text("مشاهدة YouTube على هذا الهاتف • بدون تلفاز")
                            }
                            Box(Modifier.weight(1f)) {
                                SponsorScreen(
                        state = state,
                        pair = vm::pair,
                        start = onStart,
                        stop = ::stopMonitor,
                        forget = vm::forgetDevice,
                        setCategory = vm::setCategory,
                        setMuteAds = vm::setMuteAds,
                        setSkipAds = vm::setSkipAds,
                        setTracking = vm::setTracking,
                        setAutoPlay = vm::setAutoPlay,
                        setMinimum = vm::setMinimum,
                        setOffset = vm::setOffset,
                        openSource = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dmunozv04/iSponsorBlockTV"))) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startMonitor() {
        ContextCompat.startForegroundService(
            this, Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_START)
        )
    }

    private fun stopMonitor() {
        startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))
    }
}

private val appColors = darkColorScheme(
    primary = Color(0xFF57E0BA),
    onPrimary = Color(0xFF082A27),
    secondary = Color(0xFFA4C2FF),
    background = Color(0xFF0C1320),
    surface = Color(0xFF162135),
    surfaceVariant = Color(0xFF24344B),
    onSurface = Color(0xFFEAF2FF),
    onSurfaceVariant = Color(0xFFD4DFEF),
)

private val categories = listOf(
    "sponsor" to "الإعلانات المدمجة",
    "intro" to "المقدمات",
    "outro" to "النهايات",
    "selfpromo" to "الترويج الذاتي",
    "interaction" to "طلب الإعجاب والاشتراك",
    "preview" to "الملخصات",
    "music_offtopic" to "المقاطع الموسيقية",
    "filler" to "الحشو",
    "hook" to "الترحيب",
    "exclusive_access" to "الوصول الحصري",
)

@Composable
private fun SponsorScreen(
    state: ScreenUi,
    pair: (String) -> Unit,
    start: () -> Unit,
    stop: () -> Unit,
    forget: () -> Unit,
    setCategory: (String, Boolean) -> Unit,
    setMuteAds: (Boolean) -> Unit,
    setSkipAds: (Boolean) -> Unit,
    setTracking: (Boolean) -> Unit,
    setAutoPlay: (Boolean) -> Unit,
    setMinimum: (Int) -> Unit,
    setOffset: (Int) -> Unit,
    openSource: () -> Unit,
) {
    val cfg = state.config
    val active = state.monitor.status != LinkStatus.STOPPED && state.monitor.status != LinkStatus.ERROR
    var code by rememberSaveable { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().widthIn(max = 800.dp)) {
                    Text("iSponsorBlockTV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("لأندرويد • تحكم ذكي في YouTube على التلفاز", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                StatusCard(state, Modifier.fillMaxWidth().widthIn(max = 800.dp))
            }
            item {
                Card(
                    Modifier.fillMaxWidth().widthIn(max = 800.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Heading("١. ربط تطبيق YouTube بالتلفاز")
                        Text("افتح YouTube على التلفاز ← الإعدادات ← الربط برمز التلفاز، ثم أدخل الرمز هنا.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (cfg.isPaired) {
                            Text("التلفاز: ${cfg.screenName.ifBlank { "YouTube TV" }}", fontWeight = FontWeight.SemiBold)
                            if (!active) OutlinedButton(onClick = forget, modifier = Modifier.fillMaxWidth()) {
                                Text("حذف الربط وإضافة تلفاز آخر")
                            }
                        } else {
                            OutlinedTextField(
                                value = code, onValueChange = { code = it.filter { char -> char.isDigit() || char == ' ' || char == '-' }.take(22) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("رمز التلفاز") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Button(onClick = { pair(code) }, enabled = !state.pairing && code.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Text(if (state.pairing) "جارٍ الربط…" else "ربط التلفاز")
                            }
                        }
                        if (state.message.isNotBlank()) Text(state.message, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            item {
                Card(
                    Modifier.fillMaxWidth().widthIn(max = 800.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Heading("٢. تشغيل الحماية")
                        Text("يجب أن يبقى هذا الجهاز متصلًا بالإنترنت. ستستمر المراقبة بإشعار دائم بعد مغادرة الشاشة.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = start, enabled = cfg.isPaired && !active,
                                modifier = Modifier.weight(1f).height(52.dp)) { Text("تشغيل") }
                            OutlinedButton(onClick = stop, enabled = active,
                                modifier = Modifier.weight(1f).height(52.dp)) { Text("إيقاف") }
                        }
                        Text("عدد المقاطع المتجاوزة: ${state.monitor.skippedSegments}")
                    }
                }
            }
            item {
                Card(
                    Modifier.fillMaxWidth().widthIn(max = 800.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Heading("٣. ما المقاطع التي تريد تجاوزها؟")
                        Text("يمكن اختيار عدة فئات. تغيير الإعدادات يؤثر في المقاطع التالية.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            categories.forEach { (key, label) ->
                                FilterChip(selected = key in cfg.categories,
                                    onClick = { setCategory(key, key !in cfg.categories) },
                                    label = { Text(label) })
                                Spacer(Modifier.width(6.dp))
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        SettingSwitch("تخطي الإعلانات القابلة للتخطي", cfg.skipAds, setSkipAds)
                        SettingSwitch("كتم صوت الإعلانات مؤقتًا", cfg.muteAds, setMuteAds)
                        SettingSwitch("إحصاء التخطي لدى SponsorBlock", cfg.trackSkips, setTracking)
                        SettingSwitch("التشغيل التلقائي للفيديو التالي", cfg.autoPlay, setAutoPlay)
                        Text("أقل مدة للتخطي: ${cfg.minSkipSeconds} ثانية", fontWeight = FontWeight.Medium)
                        Slider(value = cfg.minSkipSeconds.toFloat(), onValueChange = { setMinimum(it.roundToInt()) },
                            valueRange = 0f..30f, steps = 29)
                        Text("تقديم/تأخير التنفيذ: ${cfg.offsetMillis} مللي ثانية", fontWeight = FontWeight.Medium)
                        Slider(value = cfg.offsetMillis.toFloat(), onValueChange = { setOffset(it.roundToInt()) },
                            valueRange = -1000f..1000f, steps = 19)
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().widthIn(max = 800.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("تطبيق مستقل غير تابع لـYouTube أو SponsorBlock. تعتمد الوظائف على واجهات Lounge المتاحة في YouTube TV.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = openSource, modifier = Modifier.fillMaxWidth()) { Text("المشروع الأصلي • GPL-3.0") }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: ScreenUi, modifier: Modifier = Modifier) {
    val (label, tint) = when (state.monitor.status) {
        LinkStatus.STOPPED -> "متوقف" to Color(0xFFABBAD0)
        LinkStatus.CONNECTING -> "جارٍ الاتصال" to Color(0xFF71B5FF)
        LinkStatus.CONNECTED -> "متصل" to Color(0xFF57E0BA)
        LinkStatus.RETRYING -> "إعادة المحاولة" to Color(0xFFFFCF79)
        LinkStatus.ERROR -> "خطأ" to Color(0xFFFF9D95)
    }
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2B40)),
        border = BorderStroke(1.dp, Color(0xFF36516A)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(11.dp).background(tint, CircleShape))
                Text(label, fontWeight = FontWeight.Bold, color = tint, fontSize = 19.sp)
            }
            Text(state.monitor.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.monitor.currentVideoId.isNotBlank()) {
                Text("الفيديو الحالي: ${state.monitor.currentVideoId}", style = MaterialTheme.typography.bodySmall)
            }
            if (state.monitor.status == LinkStatus.CONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() })
}

@Composable
private fun SettingSwitch(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}
