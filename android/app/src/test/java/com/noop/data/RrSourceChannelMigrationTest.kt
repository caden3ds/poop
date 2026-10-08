package com.noop.data

import com.noop.protocol.RrSourceChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Guards the v24 WHOOP 5 R-R transport label: the migration is additive, a labelled beat that collides
 * with an existing row is promoted rather than dropped, and scoring reads one transport per window while
 * a 4.0 (which never writes a label) reads exactly as before.
 */
class RrSourceChannelMigrationTest {

    @Test
    fun migrationAddsNullableLabelWithoutTouchingRows() {
        val sql = WhoopDatabase.RR_SOURCE_CHANNEL_MIGRATION_SQL
        assertEquals(listOf("ALTER TABLE `rrInterval` ADD COLUMN `srcChannel` INTEGER"), sql)
        for (statement in sql) {
            val upper = statement.uppercase()
            assertTrue(upper.startsWith("ALTER TABLE") && upper.contains("ADD COLUMN"))
            // No default: every row already on disk must read as legacy (NULL), never as a scorable transport.
            assertFalse(upper.contains("DEFAULT"))
            for (banned in listOf("DROP ", "DELETE ", "UPDATE ", "INSERT ")) {
                assertFalse("migration must not contain $banned", upper.contains(banned))
            }
        }
        assertEquals(23, WhoopDatabase.MIGRATION_23_24.startVersion)
        assertEquals(24, WhoopDatabase.MIGRATION_23_24.endVersion)
    }

    @Test
    fun unlabelledRowsStayUnlabelled() {
        assertNull(RrInterval("my-whoop", 100L, 800).srcChannel)
        assertNull(assignRrSeq("my-whoop", listOf(RrRow(100L, 800))).single().srcChannel)
    }

    @Test
    fun collidedHistoricalAndStandardBeatsArePromotedButRealtimeIsNot() = runBlocking {
        var promoted: List<RrInterval>? = null
        val dao = proxyDao { method, args ->
            when (method) {
                // Every row collides with one already stored.
                "insertRr" -> (args[0] as List<*>).map { -1L }
                "promoteWhoop5RrSources" -> {
                    @Suppress("UNCHECKED_CAST")
                    promoted = args[0] as List<RrInterval>
                    Unit
                }
                else -> throw UnsupportedOperationException(method)
            }
        }
        val rows = listOf(
            RrRow(100L, 800, RrSourceChannel.WHOOP5_HISTORICAL),
            RrRow(101L, 801, RrSourceChannel.WHOOP5_REALTIME),
            RrRow(102L, 802, RrSourceChannel.WHOOP5_STANDARD),
            RrRow(103L, 803),
        )
        WhoopRepository(dao).insert(StreamBatch(rr = rows), "my-whoop")
        assertEquals(listOf(5, 7), promoted!!.map { it.srcChannel })
        assertEquals(listOf(100L, 102L), promoted!!.map { it.ts })
    }

    @Test
    fun freshInsertsPromoteNothing() = runBlocking {
        var promoteCalls = 0
        val dao = proxyDao { method, args ->
            when (method) {
                "insertRr" -> (args[0] as List<*>).indices.map { it + 1L }
                "promoteWhoop5RrSources" -> { promoteCalls++; Unit }
                else -> throw UnsupportedOperationException(method)
            }
        }
        WhoopRepository(dao).insert(
            StreamBatch(rr = listOf(RrRow(100L, 800, RrSourceChannel.WHOOP5_HISTORICAL))),
            "my-whoop",
        )
        assertEquals(0, promoteCalls)
    }

    @Test
    fun scoringReadsOneTransportOnlyOnceADeviceHasLabelledRows() = runBlocking {
        val legacy = listOf(RrInterval("d", 1L, 800))
        val labelled = listOf(RrInterval("d", 1L, 781, srcChannel = 5))
        fun repo(hasLabel: Boolean) = WhoopRepository(proxyDao { method, _ ->
            when (method) {
                "hasWhoop5RrSource" -> hasLabel
                "whoop5RrIntervals" -> labelled
                "rrIntervals" -> legacy
                else -> throw UnsupportedOperationException(method)
            }
        })
        assertEquals("a 4.0 reads every row, as before", legacy, repo(false).rrIntervalsForScoring("d", 0L, 10L))
        assertEquals(labelled, repo(true).rrIntervalsForScoring("d", 0L, 10L))
    }

    @Test
    fun legacySnapshotIsKeptOnlyWhenTheWindowHoldsNothingButLegacyRows() = runBlocking {
        fun withheld(hasLabel: Boolean, hasLegacy: Boolean, hasScorable: Boolean) = runBlocking {
            WhoopRepository(proxyDao { method, _ ->
                when (method) {
                    "hasWhoop5RrSource" -> hasLabel
                    "hasLegacyRr" -> hasLegacy
                    "hasScorableWhoop5Rr" -> hasScorable
                    else -> throw UnsupportedOperationException(method)
                }
            }).legacyWhoop5RrWithheld("d", 0L, 10L)
        }
        assertTrue(withheld(hasLabel = true, hasLegacy = true, hasScorable = false))
        assertFalse("a 4.0 never keeps a stale value", withheld(hasLabel = false, hasLegacy = true, hasScorable = false))
        assertFalse("a scorable transport is an answer", withheld(hasLabel = true, hasLegacy = true, hasScorable = true))
        assertFalse("no R-R at all is not legacy", withheld(hasLabel = true, hasLegacy = false, hasScorable = false))
    }

    private fun proxyDao(call: (String, Array<out Any?>) -> Any?): WhoopDao =
        Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, args -> call(method.name, args ?: emptyArray()) } as WhoopDao
}
