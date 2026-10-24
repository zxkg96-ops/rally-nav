package app.rallynav

import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParserTest {
    private fun enc(v: Int, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else v shl 1
        while (x >= 0x20) {
            sb.append(((0x20 or (x and 0x1f)) + 63).toChar())
            x = x shr 5
        }
        sb.append((x + 63).toChar())
    }

    private fun encode(points: List<LatLon>): String {
        val sb = StringBuilder()
        var pLat = 0
        var pLon = 0
        for (p in points) {
            val la = (p.lat * 1e6).roundToInt()
            val lo = (p.lon * 1e6).roundToInt()
            enc(la - pLat, sb)
            enc(lo - pLon, sb)
            pLat = la
            pLon = lo
        }
        return sb.toString()
    }

    @Test
    fun parsesValhallaResponse() {
        val pts = (0..10).map { LatLon(42.0 + it * 0.001, 74.0) }
        val m0 = JSONObject().put("type", 1).put("instruction", "Начало")
            .put("length", 0.5).put("begin_shape_index", 0)
            .put("street_names", JSONArray().put("Улица Тест"))
        val m1 = JSONObject().put("type", 10).put("instruction", "Поверните направо")
            .put("length", 0.6).put("begin_shape_index", 5)
        val m2 = JSONObject().put("type", 4).put("instruction", "Вы прибыли")
            .put("length", 0.0).put("begin_shape_index", 10)
        val leg = JSONObject().put("shape", encode(pts))
            .put("maneuvers", JSONArray().put(m0).put(m1).put(m2))
        val body = JSONObject()
            .put(
                "trip",
                JSONObject()
                    .put("summary", JSONObject().put("time", 120.5))
                    .put("legs", JSONArray().put(leg)),
            )
            .toString()

        val route = ValhallaParser.parse(body)
        assertEquals(11, route.points.size)
        assertEquals(3, route.maneuvers.size)
        assertEquals("Улица Тест", route.maneuvers[0].street)
        assertNull(route.maneuvers[1].street)
        assertEquals("Поверните направо", route.maneuvers[1].verbal)
        assertEquals(600.0, route.maneuvers[1].lengthM, 1e-6)
        assertEquals(5 * 0.001 * Geo.M_PER_DEG, route.maneuvers[1].alongM, 1.0)
        assertEquals(120.5, route.timeS, 1e-9)
    }
}
