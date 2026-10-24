package app.rallynav

/** Прямой маршрут на север: точки через 10 м, манёвры задаются как (тип, метры от начала). */
object T {
    fun route(lengthM: Int, vararg maneuvers: Pair<Int, Int>): Route {
        val step = 10
        val pts = (0..lengthM / step).map { LatLon(it * step / Geo.M_PER_DEG, 0.0) }
        val raws = maneuvers.map { (type, along) ->
            RawManeuver(type, "", "", null, 0.0, along / step, null)
        }
        return Route.build(pts, raws, 0.0)
    }

    /** Измерение GPS: alongM — метры вдоль маршрута, crossM — метры вправо от линии. */
    fun fix(alongM: Double, tMs: Long, crossM: Double = 0.0, acc: Float = 5f) =
        Fix(alongM / Geo.M_PER_DEG, crossM / Geo.M_PER_DEG, acc, null, null, tMs)
}
