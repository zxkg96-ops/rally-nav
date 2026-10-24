package app.rallynav

import android.content.Context

/** Настройки и последний пункт назначения. Историю координат приложение не хранит. */
class SettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("rally_nav", Context.MODE_PRIVATE)

    var voiceEnabled: Boolean
        get() = sp.getBoolean("voice", true)
        set(v) = sp.edit().putBoolean("voice", v).apply()

    var speechRate: Float
        get() = sp.getFloat("rate", 1.1f)
        set(v) = sp.edit().putFloat("rate", v).apply()

    var volume: Float
        get() = sp.getFloat("volume", 1.0f)
        set(v) = sp.edit().putFloat("volume", v).apply()

    fun saveDestination(p: Place) {
        sp.edit()
            .putString("dest_name", p.name)
            .putString("dest_lat", p.lat.toString())
            .putString("dest_lon", p.lon.toString())
            .apply()
    }

    fun loadDestination(): Place? {
        val name = sp.getString("dest_name", null) ?: return null
        val lat = sp.getString("dest_lat", null)?.toDoubleOrNull() ?: return null
        val lon = sp.getString("dest_lon", null)?.toDoubleOrNull() ?: return null
        return Place(name, lat, lon)
    }

    fun clearDestination() {
        sp.edit().remove("dest_name").remove("dest_lat").remove("dest_lon").apply()
    }
}
