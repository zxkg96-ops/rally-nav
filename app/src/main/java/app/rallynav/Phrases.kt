package app.rallynav

import kotlin.math.roundToInt

/**
 * Все фразы штурмана — в одном месте. Никакого ИИ: каждому типу манёвра
 * из Valhalla соответствует фиксированная короткая команда.
 * Типы манёвров взяты из описания Valhalla (DirectionsLeg.Maneuver.Type).
 */
object Phrases {
    private val ACTIONABLE = setOf(
        4, 5, 6,                // пункт назначения
        9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21,
        22, 23, 24,
        25, 26, 37, 38,
    )

    /** Объявляем только настоящие манёвры. Старт, «продолжай» и т.п. пропускаем. */
    fun isActionable(type: Int) = type in ACTIONABLE

    fun isDestination(type: Int) = type == 4 || type == 5 || type == 6

    /** Длинная форма для «Через N метров …». */
    private fun longForm(m: Maneuver): String = when (m.type) {
        4 -> "пункт назначения"
        5 -> "пункт назначения справа"
        6 -> "пункт назначения слева"
        9 -> "плавный поворот направо"
        10 -> "поворот направо"
        11 -> "резкий поворот направо"
        12, 13 -> "разворот"
        14 -> "резкий поворот налево"
        15 -> "поворот налево"
        16 -> "плавный поворот налево"
        17 -> "съезд прямо"
        18, 20 -> "съезд направо"
        19, 21 -> "съезд налево"
        22 -> "держись прямо"
        23 -> "держись правее"
        24 -> "держись левее"
        25, 37, 38 -> "слияние потоков"
        26 -> if (m.roundaboutExit != null) "круговое движение, съезд номер ${m.roundaboutExit}" else "круговое движение"
        else -> m.verbal.ifBlank { m.instruction }
    }

    /** Короткая форма для последнего предупреждения. */
    private fun shortForm(m: Maneuver): String = when (m.type) {
        4 -> "Пункт назначения"
        5 -> "Пункт назначения справа"
        6 -> "Пункт назначения слева"
        9 -> "Плавно направо"
        10 -> "Направо"
        11 -> "Резко направо"
        12, 13 -> "Разворот"
        14 -> "Резко налево"
        15 -> "Налево"
        16 -> "Плавно налево"
        17 -> "Съезд прямо"
        18, 20 -> "Съезд направо"
        19, 21 -> "Съезд налево"
        22 -> "Прямо"
        23 -> "Правее"
        24 -> "Левее"
        25, 37, 38 -> "Слияние"
        26 -> if (m.roundaboutExit != null) "Круг, съезд номер ${m.roundaboutExit}" else "Круг"
        else -> m.verbal.ifBlank { m.instruction }
    }

    /** Расстояние словами: округляем до 50 м, от 950 м — «километр». */
    fun distanceWords(meters: Double): String {
        val r = ((meters / 50.0).roundToInt() * 50).coerceAtLeast(50)
        return if (r >= 950) "километр" else "$r метров"
    }

    fun text(m: Maneuver, stage: Stage, distanceM: Double): String = when (stage) {
        Stage.NOW -> shortForm(m)
        else -> "Через ${distanceWords(distanceM)} ${longForm(m)}"
    }

    /** Угол стрелки на экране: 0 — прямо, плюс — вправо. */
    fun arrowAngle(type: Int): Float = when (type) {
        9 -> 45f
        10 -> 90f
        11 -> 135f
        12 -> 180f
        13 -> -180f
        14 -> -135f
        15 -> -90f
        16 -> -45f
        18, 20 -> 45f
        19, 21 -> -45f
        23 -> 20f
        24 -> -20f
        37 -> 15f
        38 -> -15f
        else -> 0f
    }
}
