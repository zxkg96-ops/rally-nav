package app.rallynav

/** Точка на карте. */
data class LatLon(val lat: Double, val lon: Double)

/** Одно измерение GPS. timeMs — монотонное время в миллисекундах. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val timeMs: Long,
) {
    val pos: LatLon get() = LatLon(lat, lon)
}

/** Найденный адрес или пункт назначения. */
data class Place(val name: String, val lat: Double, val lon: Double) {
    val pos: LatLon get() = LatLon(lat, lon)
}

/** Три ступени предупреждения о манёвре: заранее, ближе, сейчас. */
enum class Stage { FAR, NEAR, NOW }

/** Почему измерение GPS не было принято. */
enum class Reject { LOW_ACCURACY, DUPLICATE, JUMP }

data class Announcement(val stage: Stage, val maneuverIndex: Int, val text: String)

/** Результат обработки одного измерения GPS. */
data class Step(
    val accepted: Boolean,
    val reject: Reject?,
    val alongM: Double,
    val crossTrackM: Double,
    val maneuverIndex: Int,
    val distanceToManeuverM: Double,
    val remainingM: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
    val announcements: List<Announcement>,
)
