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
        read("3", "7N01AA0O01", 0.5f, 400) // single read that needed 2 corrections -> noise
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

    @Test fun specialCharactersInPasswordAreEncodedForThePlayer() {
        // '#' would otherwise start a URL fragment -> Android sees host "12" (real bug, build 2)
        val raw = "rtsp://admin:Pass#12@192.168.1.101:554/Streaming/Channels/1201"
        val fixed = RtspProbe.normalizeUrl(raw)
        assertEquals("rtsp://admin:Pass%2312@192.168.1.101:554/Streaming/Channels/1201", fixed)
        val u = java.net.URI(fixed)
        assertEquals("192.168.1.101", u.host)
        assertEquals("admin:Pass#12", u.userInfo)

        assertEquals("rtsp://admin:a%40b%2Fc%3Fd%3Ae@10.0.0.2:554/cam/realmonitor?channel=1&subtype=0",
            RtspProbe.normalizeUrl("rtsp://admin:a@b/c?d:e@10.0.0.2/cam/realmonitor?channel=1&subtype=0"))
        // already encoded stays the same, no credentials stays the same
        assertEquals("rtsp://admin:p%40ss@10.0.0.2:554/x", RtspProbe.normalizeUrl("rtsp://admin:p%40ss@10.0.0.2:554/x"))
        assertEquals("rtsp://10.0.0.2:554/x", RtspProbe.normalizeUrl("rtsp://10.0.0.2/x"))
    }

    @Test fun locatorFindsASmallTwoLinePlate() {
        // 640×360 grey scene with a 70×46 white two-line plate (5 + 4 dark characters)
        val w = 640; val h = 360
        val g = IntArray(w * h) { 95 + (it * 7919 % 13) } // mild noise
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, v: Int) {
            for (y in y0 until y1) for (x in x0 until x1) g[y * w + x] = v
        }
        fill(400, 200, 470, 246, 225)
        for (i in 0 until 5) fill(406 + i * 12, 205, 413 + i * 12, 220, 30)  // TN22D
        for (i in 0 until 4) fill(412 + i * 12, 227, 419 + i * 12, 242, 30)  // 7458
        val found = PlateLocator.locate(g, w, h)
        assertTrue("no candidates", found.isNotEmpty())
        val best = found.first()
        assertTrue("wrong place: $best", best.left in 390..410 && best.top in 190..210 && best.right in 455..480)
    }

    @Test fun acceptsOneCleanReadOfAFastVehicle() {
        val out = ArrayList<FusedVehicle<String>>()
        val eng = PlateFusionEngine<String>(FusionParams(), { out += it })
        eng.submit(PlateRead("1", "Cam 1", p("TN 73 L52E9")!!, 0.41f, 1000, null)) // 1 correction
        eng.tick(10_000)
        assertEquals(1, out.size)
        assertEquals("TN73L5289", out[0].compact)
    }
}
