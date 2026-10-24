package app.rallynav

import kotlin.math.max

/**
 * Сердце навигации. Принимает измерения GPS по одному и отвечает:
 * где мы на маршруте, какой манёвр следующий, что пора сказать,
 * не съехали ли мы с маршрута. Здесь нет ничего от Android, поэтому
 * всё проверяется обычными тестами.
 */
class NavEngine(
    val route: Route,
    private val planner: AnnouncementPlanner = AnnouncementPlanner(),
) {
    companion object {
        const val MAX_ACCURACY_M = 50f
        const val JUMP_MIN_M = 150.0
        const val MAX_SPEED_MPS = 70.0
        const val JUMP_ACCEPT_AFTER = 3
        const val OFF_ROUTE_MIN_M = 35.0
        const val OFF_ROUTE_CONFIRM_FIXES = 3
        const val BACKTRACK_TOL_M = 30.0
        const val PASS_M = 12.0
        const val ARRIVE_M = 25.0
        const val DEFAULT_SPEED_MPS = 12.0
    }

    private val actionable: List<Int> =
        route.maneuvers.indices
            .filter { Phrases.isActionable(route.maneuvers[it].type) }
            .ifEmpty { listOf(route.maneuvers.lastIndex) }

    private var ptr = 0
    private var along = 0.0
    private var last: Fix? = null
    private var jumpStreak = 0
    private var offCount = 0
    private var speedEstimate: Double? = null
    private var arrived = false
    private val spoken = HashMap<Int, MutableSet<Stage>>()

    private var snapshot: Step = makeStep(true, null, 0.0, false, emptyList())

    /** Текущее состояние без нового измерения. */
    fun current(): Step = snapshot

    private fun makeStep(
        accepted: Boolean,
        reject: Reject?,
        crossM: Double,
        offRoute: Boolean,
        announcements: List<Announcement>,
    ): Step {
        val idx = actionable[ptr]
        val m = route.maneuvers[idx]
        return Step(
            accepted = accepted,
            reject = reject,
            alongM = along,
            crossTrackM = crossM,
            maneuverIndex = idx,
            distanceToManeuverM = max(0.0, m.alongM - along),
            remainingM = max(0.0, route.lengthM - along),
            offRoute = offRoute,
            arrived = arrived,
            announcements = announcements,
        )
    }

    private fun rejected(reason: Reject): Step =
        snapshot.copy(accepted = false, reject = reason, announcements = emptyList())

    private fun effectiveSpeed(fix: Fix): Double {
        val gps = fix.speedMps?.takeIf { fix.accuracyM <= 30f }?.toDouble()
        return (gps ?: speedEstimate ?: DEFAULT_SPEED_MPS).coerceIn(0.0, 45.0)
    }

    fun onFix(fix: Fix): Step {
        // 1. Слишком неточное измерение не используем.
        if (fix.accuracyM > MAX_ACCURACY_M) return rejected(Reject.LOW_ACCURACY)

        val prev = last
        var measuredSpeed: Double? = null
        if (prev != null) {
            val dt = (fix.timeMs - prev.timeMs) / 1000.0
            val d = Geo.distance(prev.pos, fix.pos)
            // 2. Повтор того же измерения.
            if (dt <= 0.0 || (dt < 0.5 && d < 1.0)) return rejected(Reject.DUPLICATE)
            val v = d / dt
            // 3. Скачок: слишком далеко за слишком короткое время.
            if (d > JUMP_MIN_M && v > MAX_SPEED_MPS) {
                jumpStreak++
                // Если «скачок» повторился подряд — значит, мы правда там (например, после тоннеля).
                if (jumpStreak < JUMP_ACCEPT_AFTER) return rejected(Reject.JUMP)
            } else {
                measuredSpeed = v
            }
        }
        jumpStreak = 0
        last = fix
        val ms = measuredSpeed
        if (ms != null) {
            speedEstimate = speedEstimate?.let { 0.7 * it + 0.3 * ms } ?: ms
        }

        // 4. Где мы на маршруте. Сначала ищем рядом с прошлым положением.
        val threshold = max(OFF_ROUTE_MIN_M, 2.0 * fix.accuracyM)
        val p = fix.pos
        var proj = Geo.project(route, p, route.segmentAt(along - 150.0), route.segmentAt(along + 800.0))
        if (proj == null || proj.crossM > threshold) {
            val global = Geo.project(route, p, 0, route.segmentCount - 1)
            if (global != null && (proj == null || global.crossM < proj.crossM)) proj = global
        }
        if (proj == null) return rejected(Reject.JUMP)

        // 5. Отклонение от маршрута: несколько подряд измерений далеко от линии.
        val onRoute = proj.crossM <= threshold
        if (onRoute) {
            offCount = 0
            if (proj.alongM >= along - BACKTRACK_TOL_M) along = max(along, proj.alongM)
        } else {
            offCount++
        }
        val offRoute = offCount >= OFF_ROUTE_CONFIRM_FIXES

        // 6. Переход к следующему манёвру — только когда его точно проехали.
        if (fix.accuracyM <= 30f) {
            while (ptr < actionable.lastIndex &&
                along >= route.maneuvers[actionable[ptr]].alongM + PASS_M
            ) {
                ptr++
            }
        }

        if (!arrived && onRoute && route.lengthM - along <= ARRIVE_M) arrived = true

        // 7. Что сказать. Каждая ступень каждого манёвра звучит не больше одного раза.
        val anns = ArrayList<Announcement>()
        if (onRoute && !arrived) {
            val idx = actionable[ptr]
            val m = route.maneuvers[idx]
            val dist = max(0.0, m.alongM - along)
            val stage = planner.stageFor(dist, effectiveSpeed(fix))
            if (stage != null) {
                val done = spoken.getOrPut(idx) { mutableSetOf() }
                if (stage !in done) {
                    for (s in Stage.entries) if (s.ordinal <= stage.ordinal) done.add(s)
                    anns += Announcement(stage, idx, Phrases.text(m, stage, dist))
                }
            }
        }

        snapshot = makeStep(true, null, proj.crossM, offRoute, anns)
        return snapshot
    }
}
