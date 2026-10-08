package com.noop.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHOOP 5/MG R-R units and transport labels, pinned against REAL captured frames.
 *
 * Adapted from upstream ryanbr/noop 98975f99. Its two fixtures come across verbatim: an oracle generated
 * by executed Swift (every u16 conversion, plus wire frames covering truncation, zero placeholders and
 * empty frames), and a paired capture off a real 5/MG on firmware 50.41.1.0, where each native array is
 * checked against an INDEPENDENT standard-BLE parse of the same beats. That pairing is the evidence the
 * whole fix rests on: the native words only match the standard profile once read as 1/1024-second ticks.
 *
 * Adapted, not copied: this fork has no Oura channels and no registry-model policy, so those cases are
 * dropped, and its live protocol rows name the label `source` where history rows name it `srcChannel`.
 */
class Whoop5RRTest {

    private fun json(name: String) = JSONObject(
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() },
    )

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `every u16 word converts exactly as the executed Swift oracle does`() {
        val o = json("whoop5_rr_oracle.json")
        var hash = 14695981039346656037uL
        for (ticks in 0..65535) {
            val ms = Whoop5RR.milliseconds(ticks)
            for (byte in listOf(ms and 255, ms shr 8)) hash = (hash xor byte.toULong()) * 1099511628211uL
        }
        assertEquals(o.getString("milliseconds_u16le_fnv1a64"), hash.toString(16).padStart(16, '0'))
        val cases = o.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getInt("milliseconds"), Whoop5RR.milliseconds(c.getInt("ticks")))
        }
    }

    @Test
    fun `native conversion rounds exactly like the standard-profile decode`() {
        // Same rounding on both paths means the same beat over two transports yields the SAME value — which
        // is what lets a duplicate collide on the primary key and be promoted rather than double-counted.
        for (ticks in 0..65535) {
            assertEquals(ticks.toString(), Math.round(ticks / 1024.0 * 1000.0).toInt(), Whoop5RR.milliseconds(ticks))
        }
    }

    @Test
    fun `real wire frames decode to milliseconds, keep raw ticks, and carry their transport`() {
        val cases = json("whoop5_rr_oracle.json").getJSONArray("wire_cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            fun ints(key: String): List<Int> = c.getJSONArray(key).let { a -> (0 until a.length()).map { a.getInt(it) } }
            val bytes = hex(c.getString("hex"))
            val frame = Framing.parseFrame(bytes, DeviceFamily.WHOOP5)
            assertTrue(name, frame.ok)
            assertEquals(name, true, frame.crcOk)
            val historical = c.getInt("channel") == RrSourceChannel.WHOOP5_HISTORICAL.code
            val parsed = if (historical) decodeHistorical(bytes, DeviceFamily.WHOOP5)!! else frame.parsed
            assertEquals(name, ints("raw"), parsed["rr_raw_ticks"])
            assertEquals(name, ints("ms"), parsed["rr_intervals"])
            assertEquals(name, c.getInt("channel"), parsed["rr_source_channel"])

            // …and the label survives extraction onto the rows that get stored.
            val rows: List<Pair<Int, Int?>> = if (historical) {
                extractHistoricalStreams(listOf(bytes), 0, 0, DeviceFamily.WHOOP5).rr.map { it.rrMs to it.srcChannel?.code }
            } else {
                extractStreams(listOf(frame), 0, 0).rr.map { it.rrMs to it.source?.code }
            }
            assertEquals(name, ints("ms"), rows.map { it.first })
            assertEquals(name, ints("ms").map { c.getInt("channel") }, rows.map { it.second })
        }
    }

    @Test
    fun `captured native words match an independent standard-BLE parse only once read as ticks`() {
        val fixture = json("whoop5_rr_paired_capture.json")
        assertEquals("50.41.1.0", fixture.getString("firmware"))
        val cases = fixture.getJSONArray("cases")
        assertEquals(2, cases.length())
        for (i in 0 until cases.length()) {
            val pair = cases.getJSONObject(i)
            val standardBytes = hex(pair.getString("standard_hex"))
            assertEquals(0x10, standardBytes[0].toInt())
            val raw = (2 until standardBytes.size step 2).map {
                (standardBytes[it].toInt() and 255) or ((standardBytes[it + 1].toInt() and 255) shl 8)
            }
            assertEquals(pair.getInt("rr_count"), raw.size)
            val standard = com.noop.ble.StandardHeartRate.parse(standardBytes)!!

            val nativeBytes = hex(pair.getString("native_hex"))
            val frame = Framing.parseFrame(nativeBytes, DeviceFamily.WHOOP5)
            assertTrue(frame.ok)
            assertEquals(true, frame.crcOk)
            val historical = pair.getString("kind") == "v18"
            val parsed = if (historical) decodeHistorical(nativeBytes, DeviceFamily.WHOOP5)!! else frame.parsed

            // The native WORDS are the standard profile's raw ticks…
            assertEquals(raw, parsed["rr_raw_ticks"])
            // …so reading them as milliseconds (the old decode) disagrees with the independent parse,
            assertNotEquals(raw, standard.rr)
            // …and converting them agrees with it exactly.
            assertEquals(standard.rr, parsed["rr_intervals"])
        }
    }

    @Test
    fun `transport codes are durable storage values and only two are scorable`() {
        assertEquals(listOf(5, 6, 7), RrSourceChannel.entries.map { it.code })
        assertEquals(
            listOf(RrSourceChannel.WHOOP5_HISTORICAL, RrSourceChannel.WHOOP5_STANDARD),
            RrSourceChannel.entries.filter { it.isScorable },
        )
        assertNull("an unknown code is not silently mapped", RrSourceChannel.fromCode(2))
        assertNull(RrSourceChannel.fromCode(null))
    }
}
