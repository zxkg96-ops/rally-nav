package app.rallynav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Координаты от GPS-приёмника телефона (Android LocationManager). */
class LocationSource(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun isGpsEnabled(): Boolean = try {
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (e: Exception) {
        false
    }

    private fun Location.toFix(): Fix = Fix(
        lat = latitude,
        lon = longitude,
        accuracyM = if (hasAccuracy()) accuracy else 999f,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        timeMs = elapsedRealtimeNanos / 1_000_000L,
    )

    /** Последнее известное положение — только чтобы показать карту до первого точного замера. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Fix? = try {
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.toFix()
    } catch (e: SecurityException) {
        null
    }

    @SuppressLint("MissingPermission")
    fun fixes(): Flow<Fix> = callbackFlow {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toFix())
            }
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }
        awaitClose { lm.removeUpdates(listener) }
    }
}
