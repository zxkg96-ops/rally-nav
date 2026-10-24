package app.rallynav

import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private fun simpleRoute() = T.route(3000, 1 to 0, 10 to 1000, 4 to 3000)

    @Test
    fun normalDrivingAnnouncesEachStageOnce() {
        val e = NavEngine(simpleRoute())
        val anns = ArrayList<Announcement>()
        var last: Step? = null
        for (i in 0..78) {
            val s = e.onFix(T.fix(i * 14.0, i * 1000L))
            anns += s.announcements
            last = s
        }
        val turn = anns.filter { it.maneuverIndex == 1 }
        assertEquals(listOf(Stage.FAR, Stage.NEAR, Stage.NOW), turn.map { it.stage })
        assertTrue(turn[0].text.startsWith("Через"))
        assertTrue(turn[0].text.endsWith("поворот направо"))
        assertEquals("Направо", turn[2].text)
        // Поворот пройден, следующий манёвр — пункт назначения.
        assertEquals(2, last!!.maneuverIndex)
    }

    @Test
    fun duplicateFixIsIgnored() {
        val e = NavEngine(simpleRoute())
        assertTrue(e.onFix(T.fix(100.0, 1000)).accepted)
        val again = e.onFix(T.fix(100.0, 1000))
        assertFalse(again.accepted)
        assertEquals(Reject.DUPLICATE, again.reject)
    }

    @Test
    fun lowAccuracyFixIsIgnored() {
        val e = NavEngine(simpleRoute())
        val s = e.onFix(T.fix(100.0, 0, acc = 80f))
        assertFalse(s.accepted)
        assertEquals(Reject.LOW_ACCURACY, s.reject)
    }

    @Test
    fun gpsJumpIsRejectedUntilItRepeats() {
        val e = NavEngine(simpleRoute())
        assertTrue(e.onFix(T.fix(100.0, 0)).accepted)

        val j1 = e.onFix(T.fix(2000.0, 1000))
        assertFalse(j1.accepted)
        assertEquals(Reject.JUMP, j1.reject)
        // Прежнее положение сохранилось: до поворота всё ещё около 900 м.
        assertEquals(900.0, j1.distanceToManeuverM, 1.0)

        assertFalse(e.onFix(T.fix(2000.0, 2000)).accepted)

        // Третье такое же измерение подряд: считаем, что мы правда там.
        val j3 = e.onFix(T.fix(2000.0, 3000))
        assertTrue(j3.accepted)
        assertEquals(2, j3.maneuverIndex)
    }

    @Test
    fun offRouteIsConfirmedAfterThreeFixes() {
        val e = NavEngine(simpleRoute())
        assertFalse(e.onFix(T.fix(200.0, 0)).offRoute)
        assertFalse(e.onFix(T.fix(210.0, 5000, crossM = 100.0)).offRoute)
        assertFalse(e.onFix(T.fix(220.0, 10000, crossM = 100.0)).offRoute)
        val third = e.onFix(T.fix(230.0, 15000, crossM = 100.0))
        assertTrue(third.offRoute)
        // Вне маршрута подсказки о манёврах не звучат.
        assertTrue(third.announcements.isEmpty())
    }

    @Test
    fun backOnRouteResetsOffRouteCounter() {
        val e = NavEngine(simpleRoute())
        e.onFix(T.fix(200.0, 0))
        e.onFix(T.fix(210.0, 5000, crossM = 100.0))
        e.onFix(T.fix(220.0, 10000, crossM = 100.0))
        assertFalse(e.onFix(T.fix(230.0, 15000)).offRoute)
        assertFalse(e.onFix(T.fix(240.0, 20000, crossM = 100.0)).offRoute)
    }

    @Test
    fun noStageIsEverRepeatedWhenGpsWobbles() {
        val e = NavEngine(simpleRoute())
        val seen = HashMap<Int, Int>() // номер манёвра -> последняя озвученная ступень
        for (k in 0..190) {
            val along = k * 8.0 + 25.0 * sin(k.toDouble())
            val s = e.onFix(T.fix(along, k * 1000L))
            for (a in s.announcements) {
                val prev = seen[a.maneuverIndex]
                assertTrue("ступень повторилась: $a", prev == null || a.stage.ordinal > prev)
                seen[a.maneuverIndex] = a.stage.ordinal
            }
        }
        assertNotNull(seen[1])
    }

    @Test
    fun closeManeuversSkipStaleStages() {
        val e = NavEngine(T.route(2000, 1 to 0, 10 to 1000, 15 to 1060, 4 to 2000))
        val anns = ArrayList<Announcement>()
        for (i in 0..80) anns += e.onFix(T.fix(i * 14.0, i * 1000L)).announcements
        assertEquals(
            listOf(Stage.FAR, Stage.NEAR, Stage.NOW),
            anns.filter { it.maneuverIndex == 1 }.map { it.stage },
        )
        // Второй поворот совсем рядом: сразу короткая команда, без «Через 400 метров».
        val second = anns.filter { it.maneuverIndex == 2 }
        assertEquals(listOf(Stage.NOW), second.map { it.stage })
        assertEquals("Налево", second[0].text)
    }

    @Test
    fun arrivalIsDetectedNearTheEnd() {
        val e = NavEngine(T.route(2000, 1 to 0, 4 to 2000))
        val s = e.onFix(T.fix(1985.0, 0))
        assertTrue(s.arrived)
        assertTrue(s.announcements.isEmpty())
    }

    @Test
    fun noArrivalFarFromTheEnd() {
        val e = NavEngine(T.route(2000, 1 to 0, 4 to 2000))
        assertFalse(e.onFix(T.fix(500.0, 0)).arrived)
    }

    @Test
    fun plannerThresholdsFollowSpeed() {
        val p = AnnouncementPlanner()
        assertNull(p.stageFor(500.0, 14.0))
        assertEquals(Stage.FAR, p.stageFor(400.0, 14.0))
        assertEquals(Stage.NEAR, p.stageFor(150.0, 14.0))
        assertEquals(Stage.NOW, p.stageFor(40.0, 14.0))
        // На месте пороги минимальные.
        assertEquals(Stage.FAR, p.stageFor(130.0, 0.0))
        // Быстрее едем — предупреждаем раньше.
        assertEquals(Stage.FAR, p.stageFor(800.0, 30.0))
        assertNull(p.stageFor(800.0, 10.0))
    }
}
