package app.rallynav

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object AppConfig {
    /**
     * Серверы OpenStreetMap требуют, чтобы приложение представлялось.
     * ЗАМЕНИ адрес ниже на свой репозиторий или почту.
     */
    const val USER_AGENT = "RallyNav/0.1 (https://github.com/zxkg96-ops/rally-nav)"
    const val VALHALLA_URL = "https://valhalla1.openstreetmap.de/route"
    const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val text: String) : ApiResult<Nothing>
}

private class HttpReply(val code: Int, val body: String)

private object Http {
    fun get(url: String): HttpReply {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", AppConfig.USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpReply(code, body)
        } finally {
            conn.disconnect()
        }
    }
}

private fun networkError(e: Exception): ApiResult.Err = when (e) {
    is UnknownHostException -> ApiResult.Err("Нет интернета. Проверь соединение.")
    is SocketTimeoutException -> ApiResult.Err("Сервер долго не отвечает. Попробуй ещё раз.")
    is IOException -> ApiResult.Err("Ошибка связи с сервером.")
    else -> ApiResult.Err("Не удалось разобрать ответ сервера.")
}

object ValhallaParser {
    fun parse(body: String): Route {
        val trip = JSONObject(body).getJSONObject("trip")
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        val points = Polyline.decode(leg.getString("shape"), 6)
        val arr = leg.getJSONArray("maneuvers")
        val raws = ArrayList<RawManeuver>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val streets: JSONArray? = m.optJSONArray("street_names")
            val instruction = m.optString("instruction", "")
            raws += RawManeuver(
                type = m.getInt("type"),
                instruction = instruction,
                verbal = m.optString("verbal_pre_transition_instruction", instruction),
                street = if (streets != null && streets.length() > 0) streets.getString(0) else null,
                lengthM = m.optDouble("length", 0.0) * 1000.0,
                beginShapeIndex = m.getInt("begin_shape_index"),
                roundaboutExit = if (m.has("roundabout_exit_count")) m.getInt("roundabout_exit_count") else null,
            )
        }
        val time = trip.optJSONObject("summary")?.optDouble("time", 0.0) ?: 0.0
        return Route.build(points, raws, time)
    }
}

class ValhallaClient {
    suspend fun route(from: LatLon, to: LatLon): ApiResult<Route> = withContext(Dispatchers.IO) {
        try {
            val request = JSONObject()
                .put(
                    "locations",
                    JSONArray()
                        .put(JSONObject().put("lat", from.lat).put("lon", from.lon))
                        .put(JSONObject().put("lat", to.lat).put("lon", to.lon)),
                )
                .put("costing", "auto")
                .put(
                    "directions_options",
                    JSONObject().put("language", "ru-RU").put("units", "kilometers"),
                )
            val url = AppConfig.VALHALLA_URL + "?json=" + URLEncoder.encode(request.toString(), "UTF-8")
            val reply = Http.get(url)
            when {
                reply.code == 200 -> ApiResult.Ok(ValhallaParser.parse(reply.body))
                reply.code == 429 -> ApiResult.Err("Сервер маршрутов перегружен. Повтори через минуту.")
                reply.code == 400 -> ApiResult.Err("Маршрут не найден: рядом с точкой нет подходящей дороги.")
                else -> ApiResult.Err("Сервер маршрутов вернул ошибку ${reply.code}.")
            }
        } catch (e: Exception) {
            networkError(e)
        }
    }
}

class NominatimClient {
    suspend fun search(query: String, near: LatLon?): ApiResult<List<Place>> = withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder(AppConfig.NOMINATIM_URL)
            sb.append("?format=jsonv2&limit=5&accept-language=ru&q=")
            sb.append(URLEncoder.encode(query, "UTF-8"))
            if (near != null) {
                // Подсказываем серверу, что искать надо рядом (но не запрещаем искать дальше).
                sb.append("&viewbox=")
                sb.append("${near.lon - 0.5},${near.lat + 0.5},${near.lon + 0.5},${near.lat - 0.5}")
            }
            val reply = Http.get(sb.toString())
            if (reply.code != 200) {
                return@withContext ApiResult.Err("Поиск адреса недоступен (код ${reply.code}).")
            }
            val arr = JSONArray(reply.body)
            val out = ArrayList<Place>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Place(
                    name = o.optString("display_name", "Без названия"),
                    lat = o.getString("lat").toDouble(),
                    lon = o.getString("lon").toDouble(),
                )
            }
            ApiResult.Ok(out)
        } catch (e: Exception) {
            networkError(e)
        }
    }
}
