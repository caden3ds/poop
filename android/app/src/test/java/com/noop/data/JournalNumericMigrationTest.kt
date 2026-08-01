package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the additive v13 -> v14 Room migration (the `journal.numericValue` column, #322 / task #53), 
 * the Android twin of the Swift WhoopStore v20 migration. This environment has no Robolectric /
 * Room-testing, so the migration's SQL is exposed as an internal constant
 * ([WhoopDatabase.JOURNAL_NUMERIC_MIGRATION_SQL]) and pinned here to Room's generated shape:
 *
 *  - one ALTER ... ADD COLUMN statement, a nullable REAL (a `Double?` field): no NOT NULL, no DEFAULT.
 *  - ADDITIVE: only ALTER ADD COLUMN; no DROP/DELETE/UPDATE/INSERT/CREATE on existing data.
 *
 * The write+read round-trip of the value itself is pinned at the entity boundary: a numeric log stores
 * answeredYes=true AND the value, and a plain answer (or an imported row) carries numericValue == null.
 * The DAO read is `SELECT *`, so once the column exists the entity carries it back automatically.
 */
class JournalNumericMigrationTest {

    @Test
    fun migration_isAdditive_onlyAddColumnStatement() {
        val sql = WhoopDatabase.JOURNAL_NUMERIC_MIGRATION_SQL
        assertEquals("one ADD COLUMN statement", 1, sql.size)
        for (s in sql) {
            val up = s.trimStart().uppercase()
            assertTrue("only ALTER ADD COLUMN allowed, got: $s",
                up.startsWith("ALTER TABLE") && up.contains("ADD COLUMN"))
            for (banned in listOf("DROP ", "DELETE ", "UPDATE ", "INSERT ", "CREATE ", "NOT NULL", "DEFAULT")) {
                assertTrue("additive nullable migration must not contain '$banned': $s", !up.contains(banned))
            }
        }
    }

    @Test
    fun migration_addsExactColumn() {
        assertEquals(
            listOf("ALTER TABLE `journal` ADD COLUMN `numericValue` REAL"),
            WhoopDatabase.JOURNAL_NUMERIC_MIGRATION_SQL,
        )
    }

    @Test
    fun migration_versionPair_is13to14() {
        assertEquals(13, WhoopDatabase.MIGRATION_13_14.startVersion)
        assertEquals(14, WhoopDatabase.MIGRATION_13_14.endVersion)
    }

}
