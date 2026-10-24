package app.rallynav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

private val Bg = Color(0xFF0E0E0E)
private val Panel = Color(0xFF1B1B1D)
private val Red = Color(0xFFE53935)
private val TextMain = Color(0xFFF2F2F2)
private val TextDim = Color(0xFF9A9A9F)
private val Amber = Color(0xFFFFB300)
private val RU: Locale = Locale.forLanguageTag("ru-RU")

@Composable
fun RallyNavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Red,
            onPrimary = Color.White,
            background = Bg,
            onBackground = TextMain,
            surface = Panel,
            onSurface = TextMain,
        ),
        content = content,
    )
}

private fun fmtDistance(m: Int): String =
    if (m >= 1000) String.format(RU, "%.1f км", m / 1000.0) else "${(m / 10) * 10} м"

@Composable
fun MainScreen(
    vm: MainViewModel,
    onAskLocation: () -> Unit,
    onStartNav: () -> Unit,
    onOpenTtsSettings: () -> Unit,
) {
    val s by vm.state.collectAsState()
    val view = LocalView.current
    SideEffect { view.keepScreenOn = s.phase == Phase.NAVIGATING }
    var showSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Hud(s, onSettings = { showSettings = true })

        MapPanel(
            state = s,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
        )

        s.message?.let { msg ->
            Text(
                msg,
                color = Red,
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel)
                    .clickable { vm.dismissMessage() }
                    .padding(12.dp),
            )
        }

        val voice = s.voice
        if (voice is VoiceStatus.Unavailable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(voice.reason, color = Amber, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenTtsSettings) { Text("Речь", color = Red) }
            }
        }

        BottomPanel(s, vm, onAskLocation, onStartNav)
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Готово") } },
            title = { Text("Настройки голоса") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Голос штурмана", modifier = Modifier.weight(1f))
                        Switch(checked = s.voiceEnabled, onCheckedChange = { vm.setVoiceEnabled(it) })
                    }
                    Text("Скорость речи", color = TextDim, fontSize = 13.sp)
                    Slider(
                        value = s.speechRate,
                        onValueChange = { vm.setSpeechRate(it) },
                        valueRange = 0.6f..1.6f,
                    )
                    Text("Громкость", color = TextDim, fontSize = 13.sp)
                    Slider(
                        value = s.volume,
                        onValueChange = { vm.setVolume(it) },
                        valueRange = 0.2f..1f,
                    )
                    OutlinedButton(onClick = { vm.testVoice() }) { Text("Проверить голос") }
                }
            },
        )
    }
}

@Composable
private fun Hud(s: UiState, onSettings: () -> Unit) {
    val title: String
    val sub: String
    when (s.phase) {
        Phase.NAVIGATING -> {
            title = s.distanceToManeuverM?.let { fmtDistance(it) } ?: "—"
            sub = s.maneuver?.street ?: ""
        }
        Phase.ROUTE_READY -> {
            title = fmtDistance(s.route?.lengthM?.roundToInt() ?: 0)
            sub = "Маршрут построен"
        }
        Phase.ARRIVED -> {
            title = "Вы прибыли"
            sub = ""
        }
        Phase.IDLE -> {
            title = "Куда едем?"
            sub = ""
        }
    }
    val acc = s.fix?.accuracyM?.roundToInt()
    val gpsText = when (s.gps) {
        GpsStatus.NO_PERMISSION -> "Нет доступа к GPS"
        GpsStatus.DISABLED -> "GPS выключен в телефоне"
        GpsStatus.SEARCHING -> "Поиск GPS…"
        GpsStatus.WEAK -> "GPS слабый, ±${acc ?: "?"} м"
        GpsStatus.OK -> "GPS ±${acc ?: "?"} м"
        GpsStatus.LOST -> "GPS потерян"
    }
    val gpsColor = when (s.gps) {
        GpsStatus.OK -> TextDim
        GpsStatus.WEAK, GpsStatus.SEARCHING -> Amber
        else -> Red
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) { ManeuverIcon(s) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = if (s.phase == Phase.IDLE || s.phase == Phase.ARRIVED) 30.sp else 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                    maxLines = 1,
                )
                Text(sub, fontSize = 17.sp, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onSettings) { Text("Голос", color = Red) }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.speedKmh?.let { "$it км/ч" } ?: "— км/ч",
                color = TextMain,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(gpsText, color = gpsColor, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ManeuverIcon(s: UiState) {
    val m = s.maneuver
    if (s.phase == Phase.NAVIGATING && m != null) {
        when {
            Phrases.isDestination(m.type) -> Text("⚑", fontSize = 56.sp, color = Red)
            m.type == 26 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⟳", fontSize = 48.sp, color = Red)
                if (m.roundaboutExit != null) Text("съезд ${m.roundaboutExit}", fontSize = 12.sp, color = TextDim)
            }
            else -> ArrowShape(Phrases.arrowAngle(m.type), Red)
        }
    } else {
        ArrowShape(0f, Color(0xFF3A3A3D))
    }
}

@Composable
private fun ArrowShape(angle: Float, color: Color) {
    Canvas(Modifier.size(84.dp)) {
        rotate(degrees = angle) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.5f, h * 0.04f)
                lineTo(w * 0.88f, h * 0.46f)
                lineTo(w * 0.64f, h * 0.46f)
                lineTo(w * 0.64f, h * 0.96f)
                lineTo(w * 0.36f, h * 0.96f)
                lineTo(w * 0.36f, h * 0.46f)
                lineTo(w * 0.12f, h * 0.46f)
                close()
            }
            drawPath(path, color)
        }
    }
}

@Composable
private fun BottomPanel(
    s: UiState,
    vm: MainViewModel,
    onAskLocation: () -> Unit,
    onStartNav: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (s.phase) {
            Phase.IDLE -> {
                if (!s.hasPermission) {
                    Text(
                        "Для навигации нужна точная геолокация. Координаты не сохраняются. " +
                            "На сервер маршрутов они уходят только при построении маршрута.",
                        color = TextDim,
                        fontSize = 14.sp,
                    )
                    Button(onClick = onAskLocation, modifier = Modifier.fillMaxWidth()) {
                        Text("Разрешить геолокацию")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = s.query,
                            onValueChange = { vm.onQueryChange(it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Адрес или место") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { vm.search() }, enabled = !s.searching && !s.busy) {
                            Text(if (s.searching) "…" else "Найти")
                        }
                    }
                    if (s.busy) Text("Строю маршрут…", color = TextDim)
                    if (s.results.isNotEmpty()) {
                        LazyColumn(Modifier.heightIn(max = 180.dp)) {
                            items(s.results) { p ->
                                Text(
                                    p.name,
                                    color = TextMain,
                                    fontSize = 15.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.selectPlace(p) }
                                        .padding(vertical = 10.dp),
                                )
                            }
                        }
                    }
                    val saved = s.savedDestination
                    if (saved != null && !s.busy) {
                        OutlinedButton(onClick = { vm.resumeSaved() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Продолжить к «${saved.name.take(40)}»", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Phase.ROUTE_READY -> {
                val route = s.route
                val minutes = ((route?.timeS ?: 0.0) / 60.0).roundToInt()
                Text(
                    "Маршрут: ${fmtDistance(route?.lengthM?.roundToInt() ?: 0)}, около $minutes мин",
                    color = TextMain,
                    fontSize = 16.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartNav, modifier = Modifier.weight(1f)) { Text("Начать") }
                    OutlinedButton(onClick = { vm.cancelRoute() }, modifier = Modifier.weight(1f)) { Text("Отмена") }
                }
            }

            Phase.NAVIGATING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Осталось ${fmtDistance(s.remainingM ?: 0)}", color = TextMain, fontSize = 16.sp)
                        if (s.rerouting) Text("Перестраиваю маршрут…", color = Amber, fontSize = 14.sp)
                    }
                    Button(
                        onClick = { vm.stopNavigation() },
                        colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                    ) { Text("Завершить") }
                }
            }

            Phase.ARRIVED -> {
                Button(onClick = { vm.stopNavigation() }, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
            }
        }
    }
}
