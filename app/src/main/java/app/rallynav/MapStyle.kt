package app.rallynav

/**
 * Тёмный стиль карты без подписей: только вода, парки, здания и дороги.
 * Данные — векторные плитки OpenFreeMap (схема OpenMapTiles, данные OpenStreetMap).
 * Названия улиц показывает верхняя панель, чтобы карта не шумела.
 */
object MapStyle {
    const val JSON = """
{
  "version": 8,
  "name": "rally-dark",
  "sources": {
    "ofm": { "type": "vector", "url": "https://tiles.openfreemap.org/planet" }
  },
  "layers": [
    { "id": "bg", "type": "background", "paint": { "background-color": "#121212" } },
    { "id": "water", "type": "fill", "source": "ofm", "source-layer": "water",
      "paint": { "fill-color": "#1c2630" } },
    { "id": "park", "type": "fill", "source": "ofm", "source-layer": "park",
      "paint": { "fill-color": "#17201a" } },
    { "id": "building", "type": "fill", "source": "ofm", "source-layer": "building", "minzoom": 14,
      "paint": { "fill-color": "#1f1f1f" } },
    { "id": "road-minor", "type": "line", "source": "ofm", "source-layer": "transportation", "minzoom": 13,
      "filter": ["in", "class", "minor", "service", "track", "path"],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "#333336",
                 "line-width": ["interpolate", ["linear"], ["zoom"], 13, 0.5, 18, 6] } },
    { "id": "road-main", "type": "line", "source": "ofm", "source-layer": "transportation",
      "filter": ["in", "class", "primary", "secondary", "tertiary"],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "#55555a",
                 "line-width": ["interpolate", ["linear"], ["zoom"], 8, 0.8, 18, 10] } },
    { "id": "road-major", "type": "line", "source": "ofm", "source-layer": "transportation",
      "filter": ["in", "class", "motorway", "trunk"],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "#8a3b3b",
                 "line-width": ["interpolate", ["linear"], ["zoom"], 6, 0.8, 18, 12] } }
  ]
}
"""
}
