package dev.dashcam.explorer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** File names here are real ones from the sample card. */
class DomainTest {
    private val base = "QUBO/A1B2C3"

    private fun dirs(vararg entries: Pair<String, List<String>>): (String) -> Collection<String>? {
        val m = entries.toMap()
        return { m[it] }
    }

    @Test fun parsesClipName() {
        val q = QuboName.parse("H1725_1795173536000_1795175723000.mp4")!!
        assertEquals("H1725_", q.prefix)
        assertEquals(1795173536000, q.rideMs)
        assertEquals(1795175723000, q.clipMs)
        assertEquals(".mp4", q.ext)
    }

    @Test fun parsesTimelapseAndEventNames() {
        assertEquals(1795055078283, clipTimeMs("timelapse-1795055060000_1795055078283.jpeg"))
        assertEquals(1795011563783, clipTimeMs("EVENT_03_1795011423000_1795011563783.jpeg"))
        assertNull(clipTimeMs("heartbeat27k.txt"))
        assertNull(clipTimeMs("Health-12-Nov-2026.log"))
    }

    @Test fun kindsAndTags() {
        assertEquals(FileKind.VIDEO, kindOf("L1725_1.mp4"))
        assertEquals(FileKind.PHOTO, kindOf("a.JPEG"))
        assertEquals(FileKind.LOG, kindOf("Ride_1_2.trip"))
        assertEquals(FileKind.OTHER, kindOf("VolumeConfiguration.plist"))
        assertEquals("front", tagOf("H1725_1795173536000_1795175723000.mp4"))
        assertEquals("rear", tagOf("L1725_1795173536000_1795175723000.mp4"))
        assertEquals("event", tagOf("EVENT_03_1_2.jpeg"))
        assertNull(tagOf("Health-12-Nov-2026.log"))
    }

    @Test fun naturalOrder() {
        val sorted = listOf("10", "2", "1", "b", "A", "H1948_x.mp4", "H1725_x.mp4").sortedWith(NaturalOrder)
        assertEquals(listOf("1", "2", "10", "A", "b", "H1725_x.mp4", "H1948_x.mp4"), sorted)
        assertTrue(NaturalOrder.compare("13-Nov-2026", "14-Nov-2026") < 0)
        assertTrue(NaturalOrder.compare("EVENT_03_1.jpeg", "H0813_1.mp4") < 0)
    }

    @Test fun pairsVideoByPrefixSwap() {
        val p = Pairing.counterpart(
            "$base/Emergency/Rear/L1725_1795173536000_1795175723000.mp4",
            dirs("$base/Emergency/Front" to listOf("H1725_1795173536000_1795175723000.mp4", "H1724_1795173536000_1795175663000.mp4")),
        )
        assertEquals(Camera.REAR, p.side)
        assertEquals(Match.EXACT, p.match)
        assertEquals("$base/Emergency/Front/H1725_1795173536000_1795175723000.mp4", p.otherPath)
    }

    @Test fun pairsPhotoBySameName() {
        val n = "EVENT_03_1795173536000_1795175741317.jpeg"
        val p = Pairing.counterpart("$base/Emergency/Front/$n", dirs("$base/Emergency/Rear" to listOf(n)))
        assertEquals(Camera.FRONT, p.side)
        assertEquals("$base/Emergency/Rear/$n", p.otherPath)
    }

    @Test fun pairsNestedTimelapseBySwappingNearestCameraFolder() {
        val n = "timelapse-1795055060000_1795055078283.jpeg"
        val p = Pairing.counterpart(
            "$base/19-Nov-2026/Timelapse/Front/DRIVE_MODE/1/$n",
            dirs("$base/19-Nov-2026/Timelapse/Rear/DRIVE_MODE/1" to listOf(n)),
        )
        assertEquals("$base/19-Nov-2026/Timelapse/Rear/DRIVE_MODE/1/$n", p.otherPath)
        assertEquals(Match.EXACT, p.match)
    }

    @Test fun pairsNearestWithinFiveSeconds() {
        val lister = dirs("$base/d/Event/Rear" to listOf("EVENT_01_1795055060000_1795055063000.jpeg", "EVENT_01_1795055060000_1795055099000.jpeg"))
        val p = Pairing.counterpart("$base/d/Event/Front/EVENT_01_1795055060000_1795055060000.jpeg", lister)
        assertEquals(Match.NEAREST, p.match)
        assertEquals("$base/d/Event/Rear/EVENT_01_1795055060000_1795055063000.jpeg", p.otherPath)

        val far = Pairing.counterpart("$base/d/Event/Front/EVENT_01_1795055060000_1795055080000.jpeg", lister)
        assertEquals(Match.NONE, far.match)
        assertNull(far.otherPath)
    }

    @Test fun noPartnerCases() {
        // front-only event photo
        val p = Pairing.counterpart("$base/d/Event/Front/EVENT_02_1795083041000_1795086262000.jpeg", dirs("$base/d/Event/Rear" to emptyList()))
        assertEquals(Camera.FRONT, p.side)
        assertNull(p.otherPath)
        // other camera's folder missing
        assertNull(Pairing.counterpart("$base/d/Event/Front/x.jpeg", dirs()).otherPath)
        // not under Front or Rear at all
        val log = Pairing.counterpart("$base/DeviceHealth/12-Nov-2026/Health-12-Nov-2026.log", dirs())
        assertNull(log.side)
        // a file literally named Front is not a camera folder
        assertNull(Pairing.counterpart("Front", dirs()).side)
    }

    @Test fun formatters() {
        assertEquals("0 B", Fmt.size(0))
        assertEquals("693 B", Fmt.size(693))
        assertEquals("22.0 KB", Fmt.size(22_528))
        assertEquals("88.3 MB", Fmt.size(92_625_595))
        assertEquals("232 GB", Fmt.size(249_108_103_168))
        assertEquals("1:00", Fmt.duration(60_039))
        assertEquals("0:35", Fmt.duration(35_400))
        assertFalse(Fmt.isRealDate(315_532_800_000)) // 1980-01-01
        assertFalse(Fmt.isRealDate(null))
        assertTrue(Fmt.isRealDate(1_795_175_723_000))
    }

    // The real log's format, with a made-up secret value.
    private val log = """

S.No	Event	Module	Timestamp		Private Data Info
2	57	BOOT	12-11 12:10:03.645	0
1	58	REBOOT	12-11 12:10:03.555	ReBoot :: hard R2
3	48	SDcard	12-11 12:10:03.582	SDCard-Mount1 :: ErrorFormatSdCard :: 1794465490388
4	15	Setting	12-11 12:11:10.233	Local :: wifiPassword::Sample@Secret1
5	15	Setting	12-11 12:13:09.402	Local :: rotation::180
6	15	Setting	12-11 12:13:22.055	Local :: speakerVolume::high

S.No	Event	Module	Timestamp		Private Data Info
2	57	BOOT	12-11 12:15:08.487	0
1	58	REBOOT	12-11 12:15:08.441	ReBoot :: cold

S.No	Event	Module	Timestamp		Private Data Info
2	57	BOOT	12-11 18:44:57.394	40
1	58	REBOOT	12-11 18:44:57.320	ReBoot :: cold R6
9	99	Mystery	12-11 19:00:00.000	something new
"""

    @Test fun parsesHealthLog() {
        val d = HealthLogParser.parse("12-Nov-2026", log)
        assertEquals(3, d.restarts)
        assertEquals(2, d.cold)
        assertEquals(1, d.hard)
        assertEquals(1, d.sdCard.size)
        assertEquals(listOf("wifiPassword", "rotation", "speakerVolume"), d.settings.map { it.name })
        assertEquals("180", d.settings[1].value)
        assertEquals("high", d.settings[2].value)
        assertEquals(40, d.events.filterIsInstance<HealthEvent.Boot>().last().counter)
        assertEquals(1, d.events.count { it is HealthEvent.Unknown })
        assertEquals("12:10:03", d.events.first().time)
    }

    @Test fun passwordNeverSurvivesParsing() {
        val d = HealthLogParser.parse("12-Nov-2026", log)
        val pw = d.settings.first()
        assertTrue(pw.masked)
        assertEquals(HealthLogParser.MASK, pw.value)
        assertFalse(d.toString().contains("Sample@Secret1"))
    }

    @Test fun healthHeuristics() {
        fun day(name: String, cold: Int, hard: Int, at2: Boolean) = HealthDay(
            name,
            List(cold) { HealthEvent.Reboot(if (at2 && it == 0) "02:00:41" else "10:0$it:00", HealthEvent.Reboot.Kind.COLD, "cold") } +
                List(hard) { HealthEvent.Reboot("11:0$it:00", HealthEvent.Reboot.Kind.HARD, "hard") },
        )
        val calm = listOf(day("a", 3, 0, true), day("b", 4, 0, true), day("c", 2, 0, false))
        assertFalse(HealthLogParser.frequentForced(calm))
        assertTrue(HealthLogParser.nightlyRestart(calm))
        assertTrue(HealthLogParser.frequentForced(listOf(day("a", 3, 6, false))))
        assertTrue(HealthLogParser.frequentForced(listOf(day("a", 6, 2, false))))
        assertFalse(HealthLogParser.nightlyRestart(listOf(day("a", 1, 0, true))))
    }

    // A real folder listing from the camera's web server (port 8080).
    private val index = """<html><head><title>Index of /QUBO/A1B2C3/</title></head><body><h1>Index of /QUBO/A1B2C3/</h1><pre><table cellpadding="0"><tr><th><a href="?nd">Name</a></th><th><a href="?dd">Modified</a></th><th><a href="?sd">Size</a></th></tr><tr><td colspan="3"><hr></td></tr><tr><td><a href="/QUBO/A1B2C3/..">Parent directory</a></td><td>&nbsp;-</td><td>&nbsp;&nbsp;-</td></tr>
<tr><td><a href="/QUBO/A1B2C3/13-Nov-2026/">13-Nov-2026/</a></td><td>&nbsp;18-Nov-2026 11:12</td><td>&nbsp;&nbsp;[DIRECTORY]</td></tr>
<tr><td><a href="/QUBO/A1B2C3/Emergency/">Emergency/</a></td><td>&nbsp;20-Nov-2026 21:52</td><td>&nbsp;&nbsp;[DIRECTORY]</td></tr>
<tr><td><a href="/QUBO/A1B2C3/H1455_1795166672000_1795166727000.mp4">H1455_1795166672000_1795166727000.mp4</a></td><td>&nbsp;20-Nov-2026 20:25</td><td>&nbsp;&nbsp;88.8M</td></tr>
<tr><td><a href="/QUBO/A1B2C3/heartbeat27k.txt">heartbeat27k.txt</a></td><td>&nbsp;20-Nov-2026 22:18</td><td>&nbsp;&nbsp;8</td></tr>
</table></body></html>"""

    @Test fun parsesCameraFolderIndex() {
        val e = dev.dashcam.explorer.data.CameraIndex.parse(index)
        assertEquals(listOf("13-Nov-2026", "Emergency", "H1455_1795166672000_1795166727000.mp4", "heartbeat27k.txt"), e.map { it.name })
        assertEquals(listOf(true, true, false, false), e.map { it.isDir })
        assertEquals((88.8 * 1048576).toLong(), e[2].approxSize)
        assertEquals(8L, e[3].approxSize)
        assertTrue(Fmt.isRealDate(e[2].modifiedMs))
    }

    @Test fun cameraSizesAndPaths() {
        val c = dev.dashcam.explorer.data.CameraIndex
        assertEquals(1536L, c.parseSize("1.5k"))
        assertEquals(2L * 1073741824, c.parseSize("2G"))
        assertEquals(0L, c.parseSize("-"))
        assertEquals("QUBO/A1B2C3/a%20b.mp4", c.encodePath("/QUBO/A1B2C3/a b.mp4"))
    }
}
