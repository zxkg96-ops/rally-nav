package app.rallynav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhrasesTest {
    private fun m(type: Int, exit: Int? = null) =
        Maneuver(type, "", "", null, 0.0, 0, 0.0, exit)

    @Test
    fun farPhraseRoundsDistanceTo50() {
        assertEquals("Через 500 метров поворот направо", Phrases.text(m(10), Stage.FAR, 520.0))
        assertEquals("Через 150 метров держись правее", Phrases.text(m(23), Stage.NEAR, 140.0))
    }

    @Test
    fun longDistanceBecomesKilometer() {
        assertEquals("Через километр поворот направо", Phrases.text(m(10), Stage.FAR, 980.0))
    }

    @Test
    fun nowPhraseIsShort() {
        assertEquals("Налево", Phrases.text(m(15), Stage.NOW, 30.0))
        assertEquals("Пункт назначения", Phrases.text(m(4), Stage.NOW, 20.0))
    }

    @Test
    fun roundaboutMentionsExitNumber() {
        assertEquals(
            "Через 300 метров круговое движение, съезд номер 2",
            Phrases.text(m(26, exit = 2), Stage.FAR, 300.0),
        )
    }

    @Test
    fun onlyRealManeuversAreAnnounced() {
        assertFalse(Phrases.isActionable(1))
        assertFalse(Phrases.isActionable(8))
        assertTrue(Phrases.isActionable(10))
        assertTrue(Phrases.isActionable(4))
    }

    @Test
    fun polylineDecodesKnownExample() {
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-9)
        assertEquals(-120.2, pts[0].lon, 1e-9)
        assertEquals(43.252, pts[2].lat, 1e-9)
        assertEquals(-126.453, pts[2].lon, 1e-9)
    }
}
