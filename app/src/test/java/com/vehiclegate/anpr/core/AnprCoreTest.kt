package com.vehiclegate.anpr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnprCoreTest {

    private fun p(s: String) = IndianPlateParser.parse(s)

    @Test fun parsesAndCorrectsIndianPlates() {
        assertEquals("TN09AB1234", p("TN 09 AB 1234")?.compact)
        assertEquals("TN 09 AB 1234", p("tn09ab1234")?.formatted)
        assertEquals("TN09AB1234", p("IND TN 09 AB 1234")?.compact)
        assertEquals("TN09AB1234", p("TN O9 AB 1234")?.compact)   // O -> 0
        assertEquals("TN09AB1234", p("TN 09 A8 1234")?.compact)   // 8 -> B
        assertEquals("TN09AB1234", p("7N09AB1234")?.compact)      // 7 -> T
        assertEquals("TN09AB1234", p("TM09AB1234")?.compact)      // M -> N
        assertEquals("KA01A1234", p("KA01A1234")?.compact)
        assertEquals("DL3CAB1234", p("DL3CAB1234")?.compact)
        assertEquals("22BH1234AA", p("22BH1234AA")?.compact)
    }

    @Test fun rejectsNonPlates() {
        assertNull(p("HOSPITAL"))
        assertNull(p("9876543210"))
        assertNull(p("TN09"))
        assertNull(p("XX09AB1234"))
    }

    @Test fun fusesMultipleCamerasIntoOneVehicle() {
        val out = ArrayList<FusedVehicle<String>>()
        val eng = PlateFusionEngine<String>(FusionParams(), { out += it })
        fun read(cam: String, raw: String, conf: Float, t: Long) =
            eng.submit(PlateRead(cam, "Cam $cam", p(raw)!!, conf, t, "snap-$cam"))

        read("1", "TN09AB1234", 0.90f, 1000)
        read("2", "TN09A81234", 0.70f, 1200)
        read("3", "TN09AB7234", 0.60f, 1300)
        read("1", "TN09AB1234", 0.85f, 1500)
        eng.tick(2000)
        assertTrue(out.isEmpty())
        eng.tick(5000)
        assertEquals(1, out.size)
        assertEquals("TN09AB1234", out[0].compact)
        assertEquals(1000L, out[0].firstSeenMs)
        assertEquals(3, out[0].cameraVotes.size)

        // Lingering at the gate -> no duplicate entry
        read("1", "TN09AB1234", 0.9f, 10_000); read("2", "TN09AB1234", 0.9f, 10_500)
        eng.tick(20_000)
        assertEquals(1, out.size)

        // Back after the cooldown -> new entry
        read("1", "TN09AB1234", 0.9f, 300_000); read("2", "TN09AB1234", 0.9f, 300_100)
        eng.tick(310_000)
        assertEquals(2, out.size)
    }

    @Test fun keepsDifferentVehiclesApartAndDropsNoise() {
        val out = ArrayList<FusedVehicle<String>>()
        val eng = PlateFusionEngine<String>(FusionParams(), { out += it })
        fun read(cam: String, raw: String, conf: Float, t: Long) =
            eng.submit(PlateRead(cam, "Cam $cam", p(raw)!!, conf, t, null))
        read("1", "TN22CD5678", 0.9f, 0); read("2", "MH12EF9012", 0.9f, 100)
        read("1", "TN22CD5678", 0.9f, 200); read("2", "MH12EF9012", 0.9f, 300)
        read("3", "TN01AA0001", 0.5f, 400) // single weak read
        eng.tick(10_000)
        assertEquals(setOf("TN22CD5678", "MH12EF9012"), out.map { it.compact }.toSet())
    }

    @Test fun rtspProbeParsesUrlsAndStreamHeaders() {
        val t = RtspProbe.parseUrl("rtsp://admin:p%40ss%2B1@10.0.0.5/cam/realmonitor?channel=1&subtype=0")
        assertEquals("admin", t.user)
        assertEquals("p@ss+1", t.password)
        assertEquals(554, t.port)
        assertEquals("/cam/realmonitor?channel=1&subtype=0", t.pathQuery)
        assertEquals("YWRtaW46cEBzcysx", RtspProbe.base64Encode("admin:p@ss+1".toByteArray()))
        // Real x264 SPS headers -> resolution
        assertEquals(1920 to 1080, RtspProbe.H264Sps.parse(RtspProbe.base64Decode("Z2QAKKzZQHgCJ+XARAAAAwAEAAADAMg8YMZY")))
        assertEquals(1280 to 720, RtspProbe.H264Sps.parse(RtspProbe.base64Decode("Z0LAH9oBQBbsBEAAAAMAQAAADIPGDKg=")))
        assertEquals("rtsp://h/a/trackID=1", RtspProbe.resolveControl("rtsp://h/a/", "trackID=1"))
    }
}
