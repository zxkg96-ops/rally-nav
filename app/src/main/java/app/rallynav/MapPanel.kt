package app.rallynav

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

private const val EMPTY_JSON = """{"type":"FeatureCollection","features":[]}"""

private fun pointJson(p: LatLon?): String =
    if (p == null) EMPTY_JSON
    else """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}"""

private fun routeJson(route: Route?): String {
    if (route == null) return EMPTY_JSON
    val sb = StringBuilder("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""")
    route.points.forEachIndexed { i, p ->
        if (i > 0) sb.append(',')
        sb.append('[').append(p.lon).append(',').append(p.lat).append(']')
    }
    sb.append("]}}")
    return sb.toString()
}

private fun setupLayers(style: Style) {
    style.addSource(GeoJsonSource("route-src", EMPTY_JSON))
    style.addSource(GeoJsonSource("dest-src", EMPTY_JSON))
    style.addSource(GeoJsonSource("me-src", EMPTY_JSON))

    style.addLayer(
        LineLayer("route-casing", "route-src").withProperties(
            PropertyFactory.lineColor("#000000"),
            PropertyFactory.lineWidth(11f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        LineLayer("route-line", "route-src").withProperties(
            PropertyFactory.lineColor("#E53935"),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        CircleLayer("dest-dot", "dest-src").withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor("#E53935"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    style.addLayer(
        CircleLayer("me-halo", "me-src").withProperties(
            PropertyFactory.circleRadius(16f),
            PropertyFactory.circleColor("#E53935"),
            PropertyFactory.circleOpacity(0.28f),
        ),
    )
    style.addLayer(
        CircleLayer("me-dot", "me-src").withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor("#FFFFFF"),
            PropertyFactory.circleStrokeColor("#E53935"),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}

private fun fitRoute(map: MapLibreMap?, route: Route) {
    val m = map ?: return
    try {
        val b = LatLngBounds.Builder()
        val stepN = maxOf(1, route.points.size / 200)
        var i = 0
        while (i < route.points.size) {
            b.include(LatLng(route.points[i].lat, route.points[i].lon))
            i += stepN
        }
        val lastP = route.points.last()
        b.include(LatLng(lastP.lat, lastP.lon))
        m.easeCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100), 800)
    } catch (e: Exception) {
        // Слишком короткий маршрут для рамки: оставляем камеру как есть.
    }
}

private fun follow(map: MapLibreMap, fix: Fix) {
    val moving = (fix.speedMps ?: 0f) > 2f
    val bearing = if (moving && fix.bearingDeg != null) fix.bearingDeg.toDouble() else map.cameraPosition.bearing
    val pos = CameraPosition.Builder()
        .target(LatLng(fix.lat, fix.lon))
        .zoom(16.5)
        .tilt(45.0)
        .bearing(bearing)
        .build()
    map.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
}

@Composable
fun MapPanel(state: UiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var problem by remember { mutableStateOf(false) }
    var centered by remember { mutableStateOf(false) }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(mapView) {
        mapView.addOnDidFailLoadingMapListener { problem = true }
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.setStyle(Style.Builder().fromJson(MapStyle.JSON)) { st ->
                setupLayers(st)
                style = st
                problem = false
            }
        }
    }

    // Линия маршрута и пункт назначения.
    LaunchedEffect(style, state.routeVersion) {
        val st = style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>("route-src")?.setGeoJson(routeJson(state.route))
        st.getSourceAs<GeoJsonSource>("dest-src")?.setGeoJson(pointJson(state.destination?.pos))
        val route = state.route
        if (route != null && state.phase != Phase.NAVIGATING) fitRoute(map, route)
    }

    // Своё положение и камера.
    LaunchedEffect(style, state.fix, state.phase) {
        val st = style ?: return@LaunchedEffect
        val fix = state.fix ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>("me-src")?.setGeoJson(pointJson(fix.pos))
        val m = map ?: return@LaunchedEffect
        if (state.phase == Phase.NAVIGATING) {
            follow(m, fix)
        } else if (!centered) {
            centered = true
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lon), 15.0))
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == Phase.IDLE) centered = false
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (problem) {
            Text(
                "Карта не загрузилась. Проверь интернет. Подсказки и маршрут продолжат работать.",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(Color(0xCC000000))
                    .padding(8.dp),
            )
        }
    }
}
