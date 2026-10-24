package app.rallynav

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Phase { IDLE, ROUTE_READY, NAVIGATING, ARRIVED }

enum class GpsStatus { NO_PERMISSION, DISABLED, SEARCHING, WEAK, OK, LOST }

data class UiState(
    val phase: Phase = Phase.IDLE,
    val hasPermission: Boolean = false,
    val gps: GpsStatus = GpsStatus.NO_PERMISSION,
    val fix: Fix? = null,
    val speedKmh: Int? = null,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<Place> = emptyList(),
    val destination: Place? = null,
    val route: Route? = null,
    val routeVersion: Int = 0,
    val maneuver: Maneuver? = null,
    val distanceToManeuverM: Int? = null,
    val remainingM: Int? = null,
    val busy: Boolean = false,
    val rerouting: Boolean = false,
    val message: String? = null,
    val voice: VoiceStatus = VoiceStatus.Starting,
    val savedDestination: Place? = null,
    val voiceEnabled: Boolean = true,
    val speechRate: Float = 1.1f,
    val volume: Float = 1.0f,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val settings = SettingsStore(ctx)
    private val locationSource = LocationSource(ctx)
    private val valhalla = ValhallaClient()
    private val nominatim = NominatimClient()
    private val voice = VoiceNavigator(ctx, settings)

    private val _state = MutableStateFlow(
        UiState(
            savedDestination = settings.loadDestination(),
            voiceEnabled = settings.voiceEnabled,
            speechRate = settings.speechRate,
            volume = settings.volume,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var engine: NavEngine? = null
    private var locationJob: Job? = null
    private var tickerJob: Job? = null
    private var hadLiveFix = false
    private var lastFixAtMs = 0L
    private var nextRerouteAtMs = 0L
    private var lastSearchAtMs = 0L
    private var rerouting = false
    private var routeCounter = 0

    init {
        viewModelScope.launch {
            voice.status.collect { v -> _state.update { it.copy(voice = v) } }
        }
    }

    private fun nowMs() = SystemClock.elapsedRealtime()

    // ---------- Разрешения и GPS ----------

    fun onLocationPermission(fine: Boolean) {
        _state.update {
            it.copy(
                hasPermission = fine,
                gps = if (fine) (if (it.gps == GpsStatus.NO_PERMISSION) GpsStatus.SEARCHING else it.gps) else GpsStatus.NO_PERMISSION,
            )
        }
        if (fine) startLocation()
    }

    fun onForeground() {
        if (_state.value.hasPermission) startLocation()
    }

    fun onBackground() {
        // Без навигации GPS в фоне не нужен: экономим батарею.
        if (_state.value.phase != Phase.NAVIGATING) stopLocation()
    }

    private fun startLocation() {
        if (locationJob?.isActive == true) return
        if (!_state.value.hasPermission) return
        if (_state.value.fix == null) {
            locationSource.lastKnown()?.let { last -> _state.update { it.copy(fix = last) } }
        }
        locationJob = viewModelScope.launch {
            locationSource.fixes()
                .catch { e -> _state.update { it.copy(message = "Не удалось включить GPS: ${e.message}") } }
                .collect { handleFix(it) }
        }
        if (tickerJob?.isActive != true) {
            tickerJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    evaluateGps()
                }
            }
        }
    }

    private fun stopLocation() {
        locationJob?.cancel()
        tickerJob?.cancel()
        locationJob = null
        tickerJob = null
        hadLiveFix = false
        _state.update { it.copy(gps = if (it.hasPermission) GpsStatus.SEARCHING else GpsStatus.NO_PERMISSION) }
    }

    private fun evaluateGps() {
        val s = _state.value
        val newStatus = when {
            !s.hasPermission -> GpsStatus.NO_PERMISSION
            !locationSource.isGpsEnabled() -> GpsStatus.DISABLED
            !hadLiveFix -> GpsStatus.SEARCHING
            nowMs() - lastFixAtMs > 8_000 -> GpsStatus.LOST
            (s.fix?.accuracyM ?: 999f) > 40f -> GpsStatus.WEAK
            else -> GpsStatus.OK
        }
        if (newStatus == s.gps) return
        _state.update { it.copy(gps = newStatus) }
        if (s.phase != Phase.NAVIGATING) return
        val bad = newStatus == GpsStatus.LOST || newStatus == GpsStatus.WEAK || newStatus == GpsStatus.DISABLED
        if (bad && s.gps == GpsStatus.OK) {
            voice.say("Сигнал GPS нестабилен. Проверь маршрут", Priority.HIGH, "gps_bad", dedupeMs = 30_000L)
        } else if (newStatus == GpsStatus.OK && (s.gps == GpsStatus.LOST || s.gps == GpsStatus.WEAK)) {
            voice.say("Сигнал GPS восстановлен", Priority.NORMAL, "gps_ok", dedupeMs = 30_000L)
        }
    }

    private fun handleFix(fix: Fix) {
        hadLiveFix = true
        lastFixAtMs = nowMs()
        val reliable = fix.speedMps != null && fix.accuracyM <= 30f
        val kmh = if (reliable) (fix.speedMps!! * 3.6f).roundToInt() else null
        _state.update { it.copy(fix = fix, speedKmh = kmh) }
        val eng = engine ?: return
        if (_state.value.phase != Phase.NAVIGATING) return
        applyStep(eng, eng.onFix(fix))
    }

    // ---------- Навигация ----------

    private fun applyStep(eng: NavEngine, step: Step) {
        if (!step.accepted) return
        val m = eng.route.maneuvers[step.maneuverIndex]
        _state.update {
            it.copy(
                maneuver = m,
                distanceToManeuverM = step.distanceToManeuverM.roundToInt(),
                remainingM = step.remainingM.roundToInt(),
            )
        }
        for (a in step.announcements) {
            voice.say(
                a.text,
                Priority.NORMAL,
                key = "m$routeCounter:${a.maneuverIndex}:${a.stage}",
                dedupeMs = 0L,
                maxAgeMs = 7_000L,
                tag = TAG_MANEUVER,
            )
        }
        if (step.arrived) {
            arrive()
            return
        }
        if (step.offRoute) onOffRoute()
    }

    private fun onOffRoute() {
        voice.say(
            "Ты отклонился от маршрута. Перестраиваю маршрут",
            Priority.HIGH,
            "offroute",
            dedupeMs = 20_000L,
        )
        if (rerouting || nowMs() < nextRerouteAtMs) return
        reroute()
    }

    private fun reroute() {
        val dest = _state.value.destination ?: return
        val origin = _state.value.fix ?: return
        rerouting = true
        nextRerouteAtMs = nowMs() + 10_000L // сервер просит не чаще раза в секунду, берём с запасом
        _state.update { it.copy(rerouting = true) }
        viewModelScope.launch {
            when (val r = valhalla.route(origin.pos, dest.pos)) {
                is ApiResult.Ok -> {
                    if (_state.value.phase == Phase.NAVIGATING) {
                        routeCounter++
                        val eng = NavEngine(r.value)
                        engine = eng
                        voice.clearTag(TAG_MANEUVER) // старые подсказки больше не нужны
                        val cur = eng.current()
                        _state.update {
                            it.copy(
                                route = r.value,
                                routeVersion = it.routeVersion + 1,
                                maneuver = r.value.maneuvers[cur.maneuverIndex],
                                distanceToManeuverM = cur.distanceToManeuverM.roundToInt(),
                                remainingM = cur.remainingM.roundToInt(),
                                rerouting = false,
                                message = null,
                            )
                        }
                        voice.say("Маршрут перестроен", Priority.HIGH, "rerouted$routeCounter", dedupeMs = 0L)
                    }
                }
                is ApiResult.Err -> {
                    _state.update { it.copy(rerouting = false, message = r.text) }
                    voice.say(
                        "Не могу перестроить маршрут. Проверь интернет",
                        Priority.NORMAL,
                        "reroute_fail",
                        dedupeMs = 30_000L,
                    )
                }
            }
            rerouting = false
        }
    }

    private fun arrive() {
        engine = null
        _state.update { it.copy(phase = Phase.ARRIVED, maneuver = null, distanceToManeuverM = null) }
        voice.clearTag(TAG_MANEUVER)
        voice.say("Вы прибыли", Priority.HIGH, "arrived$routeCounter", dedupeMs = 0L)
        settings.clearDestination()
        NavigationService.stop(ctx)
    }

    private fun buildRoute(dest: Place, autoStart: Boolean) {
        val origin = _state.value.fix
        if (!hadLiveFix || origin == null) {
            _state.update { it.copy(message = "Жду сигнал GPS. Выйди на открытое место.") }
            return
        }
        _state.update { it.copy(busy = true, message = null, destination = dest, results = emptyList()) }
        viewModelScope.launch {
            when (val r = valhalla.route(origin.pos, dest.pos)) {
                is ApiResult.Ok -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            route = r.value,
                            routeVersion = it.routeVersion + 1,
                            phase = Phase.ROUTE_READY,
                            remainingM = r.value.lengthM.roundToInt(),
                        )
                    }
                    if (autoStart) startNavigation()
                }
                is ApiResult.Err -> _state.update { it.copy(busy = false, message = r.text, destination = null) }
            }
        }
    }

    fun selectPlace(p: Place) = buildRoute(p, autoStart = false)

    fun resumeSaved() {
        val saved = _state.value.savedDestination ?: return
        buildRoute(saved, autoStart = true)
    }

    fun startNavigation() {
        val s = _state.value
        val route = s.route ?: return
        val dest = s.destination ?: return
        if (s.phase == Phase.NAVIGATING) return
        routeCounter++
        val eng = NavEngine(route)
        engine = eng
        settings.saveDestination(dest)
        val cur = eng.current()
        _state.update {
            it.copy(
                phase = Phase.NAVIGATING,
                message = null,
                maneuver = route.maneuvers[cur.maneuverIndex],
                distanceToManeuverM = cur.distanceToManeuverM.roundToInt(),
                remainingM = cur.remainingM.roundToInt(),
                savedDestination = null,
            )
        }
        voice.say("Маршрут построен. Начинаем движение", Priority.NORMAL, "start$routeCounter", dedupeMs = 0L)
        if (!NavigationService.start(ctx)) {
            _state.update { it.copy(message = "Не удалось включить работу в фоне. Держи приложение открытым.") }
        }
    }

    fun cancelRoute() {
        _state.update {
            it.copy(phase = Phase.IDLE, route = null, destination = null, maneuver = null, remainingM = null, message = null)
        }
    }

    fun stopNavigation() {
        engine = null
        rerouting = false
        voice.clearAll()
        settings.clearDestination()
        NavigationService.stop(ctx)
        _state.update {
            it.copy(
                phase = Phase.IDLE,
                route = null,
                destination = null,
                maneuver = null,
                distanceToManeuverM = null,
                remainingM = null,
                rerouting = false,
                message = null,
                savedDestination = null,
                routeVersion = it.routeVersion + 1,
            )
        }
    }

    // ---------- Поиск ----------

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    fun search() {
        val q = _state.value.query.trim()
        if (q.length < 3) {
            _state.update { it.copy(message = "Введи адрес или название, от трёх букв.") }
            return
        }
        val t = nowMs()
        if (t - lastSearchAtMs < 1_100L) return // правило сервера: не чаще раза в секунду
        lastSearchAtMs = t
        _state.update { it.copy(searching = true, message = null, results = emptyList()) }
        viewModelScope.launch {
            when (val r = nominatim.search(q, _state.value.fix?.pos.takeIf { hadLiveFix })) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        searching = false,
                        results = r.value,
                        message = if (r.value.isEmpty()) "Ничего не найдено. Попробуй уточнить адрес." else null,
                    )
                }
                is ApiResult.Err -> _state.update { it.copy(searching = false, message = r.text) }
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun setMessage(text: String) = _state.update { it.copy(message = text) }

    // ---------- Голос ----------

    fun setVoiceEnabled(v: Boolean) {
        settings.voiceEnabled = v
        _state.update { it.copy(voiceEnabled = v) }
    }

    fun setSpeechRate(v: Float) {
        settings.speechRate = v
        _state.update { it.copy(speechRate = v) }
    }

    fun setVolume(v: Float) {
        settings.volume = v
        _state.update { it.copy(volume = v) }
    }

    fun testVoice() {
        voice.say("Проверка голоса. Через пятьсот метров поворот направо", Priority.HIGH, "test", dedupeMs = 0L)
    }

    override fun onCleared() {
        locationJob?.cancel()
        tickerJob?.cancel()
        voice.shutdown()
        NavigationService.stop(ctx)
        super.onCleared()
    }

    private companion object {
        const val TAG_MANEUVER = "maneuver"
    }
}
