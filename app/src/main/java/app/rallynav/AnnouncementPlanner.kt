package app.rallynav

/**
 * Решает, пора ли объявлять манёвр. Расстояния зависят от скорости,
 * потому что водителю нужно примерно одно и то же время на реакцию:
 *  - «заранее» — около 30 секунд до манёвра (от 400 до 900 м);
 *  - «ближе»   — около 13 секунд (от 120 до 350 м);
 *  - «сейчас»  — около 4 секунд (от 35 до 90 м).
 * Это обычные ориентиры для голосовых подсказок, а не рекомендация скорости
 * прохождения поворота.
 */
class AnnouncementPlanner {
    fun stageFor(distanceM: Double, speedMps: Double): Stage? {
        val v = speedMps.coerceIn(0.0, 45.0)
        val now = (v * 4.0).coerceIn(35.0, 90.0)
        val near = (v * 13.0).coerceIn(120.0, 350.0)
        val far = (v * 30.0).coerceIn(400.0, 900.0)
        return when {
            distanceM <= now -> Stage.NOW
            distanceM <= near -> Stage.NEAR
            distanceM <= far -> Stage.FAR
            else -> null
        }
    }
}
