package app.rallynav

/** Манёвр так, как его вернул сервер маршрутов (до расчёта расстояний). */
class RawManeuver(
    val type: Int,
    val instruction: String,
    val verbal: String,
    val street: String?,
    val lengthM: Double,
    val beginShapeIndex: Int,
    val roundaboutExit: Int?,
)

/** Манёвр с расстоянием от начала маршрута до места, где он выполняется. */
class Maneuver(
    val type: Int,
    val instruction: String,
    val verbal: String,
    val street: String?,
    val lengthM: Double,
    val shapeIndex: Int,
    val alongM: Double,
    val roundaboutExit: Int?,
)

class Route private constructor(
    val points: List<LatLon>,
    /** cum[i] — расстояние от начала маршрута до точки i. */
    val cum: DoubleArray,
    val maneuvers: List<Maneuver>,
    val timeS: Double,
) {
    val lengthM: Double get() = cum[cum.size - 1]
    val segmentCount: Int get() = points.size - 1

    /** Номер отрезка линии, на котором лежит отметка d метров от начала. */
    fun segmentAt(d: Double): Int {
        var lo = 0
        var hi = segmentCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= d) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        fun build(points: List<LatLon>, raws: List<RawManeuver>, timeS: Double): Route {
            require(points.size >= 2) { "В маршруте меньше двух точек" }
            require(raws.isNotEmpty()) { "В маршруте нет манёвров" }
            val cum = DoubleArray(points.size)
            for (i in 1 until points.size) {
                cum[i] = cum[i - 1] + Geo.distance(points[i - 1], points[i])
            }
            val ms = raws.map { r ->
                val idx = r.beginShapeIndex.coerceIn(0, points.size - 1)
                Maneuver(r.type, r.instruction, r.verbal, r.street, r.lengthM, idx, cum[idx], r.roundaboutExit)
            }
            return Route(points, cum, ms, timeS)
        }
    }
}
