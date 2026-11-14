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

    @Test fun prefersTheStatesSeenAtTheSite() {
        assertEquals("TN09AB1234", p("IN09AB1234")?.compact)   // I->T
        assertEquals("KA01AB1234", p("K401AB1234")?.compact)   // 4->A
        assertEquals("TN01AB1234", p("XN01AB1234")?.compact)   // unreadable 1st char, only TN ends in N
        assertNull(p("KH01AB1234"))                            // KA or KL? ambiguous -> no guess
        assertEquals("TS09AB1234", p("T509AB1234")?.compact)   // 5->S, Telangana
        assertEquals("KL07AB1234", p("KL07AB1234")?.compact)
        assertTrue(p("MH12AB1234")!!.formatScore < p("TN12AB1234")!!.formatScore) // other states still valid, lower trust
    }

    @Test fun strictModeRejectsOtherStatesAndShortNumbers() {
        IndianPlateParser.strict = true
        try {
            assertNull(p("CH8A06"))        // not a site state, 1-digit district, 2-digit number
            assertNull(p("BR9046"))
            assertNull(p("MH12AB1234"))    // valid plate but not a site state
            assertNull(p("TN09XY12"))      // 2-digit number
            assertNull(p("TN010021"))      // no series letters
            assertEquals("TN01AB0021", p("TN01AB0021")?.compact)
            assertEquals("TN22DV1297", p("TN22DV1297")?.compact)
            assertEquals("TN09AB0313", p("TN09AB0313")?.compact)
            assertEquals("KA01A1234", p("KA01A1234")?.compact)
        } finally {
            IndianPlateParser.strict = false
        }
    }

    @Test fun parkingFeeIsPerStartedPeriod() {
        val h = 3_600_000L
        assertEquals(10, ParkingFee.amount(0, 2 * h, 10, 24))      // 2 h
        assertEquals(10, ParkingFee.amount(0, 24 * h, 10, 24))     // exactly 24 h
        assertEquals(20, ParkingFee.amount(0, 25 * h, 10, 24))     // 25 h -> 2 periods
        assertEquals(10, ParkingFee.amount(0, 0, 10, 24))          // minimum one period
        assertEquals(45, ParkingFee.amount(0, 30 * h, 15, 12))     // ₹15 per 12 h, 30 h -> 3 periods
        assertEquals("2h 05m", ParkingFee.duration(0, 2 * h + 5 * 60_000))
        assertEquals("1d 3h", ParkingFee.duration(0, 27 * h))
    }

    @Test
    fun captureMergesMisreadsOfOneVehicle() {
        // same bike read several ways: letters confused, digits right
        assertTrue(CaptureLogic.similar("TN23BR7627", "TN23BR7627"))
        assertTrue(CaptureLogic.similar("TN23BR7627", "TN28BR7627"))   // 1 char
        assertTrue(CaptureLogic.similar("TN23BR7627", "TN23HR7621"))   // 2 chars
        assertTrue(CaptureLogic.similar("TN23BR7627", "KA01AB7627"))   // same 4-digit number
        assertTrue(!CaptureLogic.similar("TN23BR7627", "TN09AB1234"))
        var list = listOf(Reading("TN23BR7627", "TN 23 BR 7627", 0.8f, 1))
        list = CaptureLogic.merge(list, Reading("TN28BR7627", "TN 28 BR 7627", 0.9f, 1))
        list = CaptureLogic.merge(list, Reading("TN23BR7627", "TN 23 BR 7627", 0.7f, 2))
        assertEquals("TN23BR7627", list.first().compact)                 // evidence adds up
        assertEquals(3, list.first().count)
        val round = CaptureLogic.decode(CaptureLogic.encode(list))
        assertEquals(list.map { it.compact }, round.map { it.compact })
        assertEquals(2, round.size)
    }

    @Test
    fun searchPrefersTokenForDigits() {
        // "12" typed: token 12 first, then token 123, then plate ending in 12
        val rows = listOf(
            Triple("TN09AB1212", "Ravi", ""),
            Triple("TN01CD4455", "123", ""),
            Triple("TN22EF9999", "12", "Activa")
        )
        val ranked = rows.mapNotNull { (p, w, m) -> CaptureLogic.rank("12", p, w, m)?.let { it to p } }
            .sortedBy { it.first }.map { it.second }
        assertEquals(listOf("TN22EF9999", "TN01CD4455", "TN09AB1212"), ranked)
        assertEquals(1, CaptureLogic.rank("tn 01 cd", "TN01CD4455", "Ravi", null))
        assertEquals(2, CaptureLogic.rank("rav", "TN09AB1212", "Ravi", null))
        assertNull(CaptureLogic.rank("xyz", "TN09AB1212", "Ravi", "Activa"))
    }

    @Test
    fun shiftedReadsAreRelatedAndStandingPlatesStopBeingRead() {
        assertTrue(CaptureLogic.related("TN21A0597", "TN24U5973"))   // shares "597"
        assertTrue(!CaptureLogic.related("TN21A0597", "TN09AB1234"))
        val f = StationaryFilter(maxStandMs = 10_000, breakMs = 5_000, radius = 0.05f)
        assertTrue(f.accept("cam1", 0.5f, 0.5f, 0))
        assertTrue(f.accept("cam1", 0.51f, 0.5f, 4_000))
        assertTrue(f.accept("cam1", 0.5f, 0.51f, 8_000))
        assertTrue(!f.accept("cam1", 0.5f, 0.5f, 12_000))           // standing > 10 s
        assertTrue(f.accept("cam1", 0.2f, 0.5f, 12_500))            // elsewhere = other vehicle
        assertTrue(f.accept("cam2", 0.5f, 0.5f, 12_500))            // other camera
        assertTrue(f.accept("cam1", 0.5f, 0.5f, 30_000))            // left and a new one stopped
    }

    @Test
    fun voiceInEnglishTamilAndTanglish() {
        val models = VehicleModels.COMMON
        val en = VoiceParse.parse(listOf("TN 23 AU 5973 token 29 Activa"), models)!!
        assertEquals("TN23AU5973", en.plate?.compact)
        assertEquals("29", en.who)
        assertTrue(en.models.first().contains("Activa"))

        val ta = VoiceParse.parse(listOf("டி என் இருபத்தி மூணு ஏ யு அஞ்சு ஒம்போது ஏழு மூணு டோக்கன் பதினொன்னு"), models)!!
        assertEquals("TN23AU5973", ta.plate?.compact)
        assertEquals("11", ta.who)

        val tg = VoiceParse.parse(listOf("tn irubathi moonu a u fifty nine seventy three token pathinonu"), models)!!
        assertEquals("TN23AU5973", tg.plate?.compact)
        assertEquals("11", tg.who)

        // joined Tamil numbers and "double"
        val j = VoiceParse.parse(listOf("tn 09 ab இருபத்தொன்னு double five"), models)!!
        assertEquals("TN09AB2155", j.plate?.compact)

        // first alternative without a plate is skipped
        val alt = VoiceParse.parse(listOf("hello", "TN 01 AB 1234"), models)!!
        assertEquals("TN01AB1234", alt.plate?.compact)

        assertEquals("11", VoiceParse.searchText(listOf("token pathinonu")))
        assertEquals("29", VoiceParse.searchText(listOf("இருபத்தி ஒன்பது")))
        assertTrue(VoiceParse.matchModels("ஆக்டிவா", models).first().contains("Activa"))
        assertTrue(VoiceParse.matchModels("splendor", models).first().contains("Splendor"))
        assertTrue(VoiceParse.matchModels("jupiter", models).first().contains("Jupiter"))
    }
}
