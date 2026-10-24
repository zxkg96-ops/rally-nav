package app.rallynav

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    const val R = 6371008.8
    const val M_PER_DEG = R * PI / 180.0

    /** Расстояние между точками по формуле гаверсинусов, в метрах. */
    fun distance(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    /** Проекция точки на маршрут: сколько метров от начала и как далеко от линии. */
    class Projection(val alongM: Double, val crossM: Double, val segment: Int)

    fun project(route: Route, p: LatLon, fromSeg: Int, toSeg: Int): Projection? {
        val pts = route.points
        val first = max(0, fromSeg)
        val last = min(route.segmentCount - 1, toSeg)
        if (first > last) return null
        val kx = cos(Math.toRadians(p.lat)) * M_PER_DEG
        val ky = M_PER_DEG
        var best: Projection? = null
        for (i in first..last) {
            val a = pts[i]
            val b = pts[i + 1]
            val ax = (a.lon - p.lon) * kx
            val ay = (a.lat - p.lat) * ky
            val bx = (b.lon - p.lon) * kx
            val by = (b.lat - p.lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-9) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val cross = sqrt(cx * cx + cy * cy)
            if (best == null || cross < best.crossM) {
                best = Projection(route.cum[i] + t * (route.cum[i + 1] - route.cum[i]), cross, i)
            }
        }
        return best
    }
}

/** Разбор линии маршрута, закодированной как «encoded polyline». */
object Polyline {
    fun decode(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<LatLon>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)

            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)

            out.add(LatLon(lat / factor, lon / factor))
        }
        return out
    }
}
