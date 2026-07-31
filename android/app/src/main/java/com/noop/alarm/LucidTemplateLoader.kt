package com.noop.alarm

import com.noop.analytics.LiveRemEstimator
import com.noop.data.WhoopRepository
import com.noop.ui.parsePersistedSegments

/**
 * Builds the personal REM template from scored nights.
 *
 * THE ONE implementation, shared by the overnight service and the Lucid screen's test mode. It lived
 * inline in the service, and the moment a second caller needed it there would have been two copies to
 * keep in step — which is precisely how this feature's worst bug happened: the inline version addressed
 * a hardcoded "my-whoop" while the strap was registered as "whoop-<mac>", so it silently learned
 * nothing and the night half could never fire.
 */
object LucidTemplateLoader {

    /** How far back to look for scored nights. */
    const val LOOKBACK_DAYS = 30L

    /** Cap on nights folded in — recent enough to reflect current physiology. */
    const val MAX_NIGHTS = 14

    /** What a load attempt found, so a caller can explain a null rather than just showing nothing. */
    data class Result(
        val template: LiveRemEstimator.RemTemplate?,
        /** Sleep sessions carrying a hypnogram in the window. */
        val sessionsFound: Int,
        /** Sessions that also yielded enough clean REM and non-REM heart rate to be usable. */
        val sessionsUsable: Int,
    )

    /**
     * @param strapId the ACTIVE strap id. Never hardcode a source here — a strap paired through the
     *   wizard registers as "whoop-<mac>", and reading the wrong id returns nothing at all.
     */
    suspend fun load(repo: WhoopRepository, strapId: String, nowMs: Long = System.currentTimeMillis()): Result {
        val nowS = nowMs / 1000L
        val fromS = nowS - LOOKBACK_DAYS * 86_400L
        val sessions = repo.sleepSessionsMerged(strapId, fromS, nowS, limit = 200)
            .filter { !it.stagesJSON.isNullOrBlank() }
            .takeLast(MAX_NIGHTS)
        if (sessions.isEmpty()) return Result(null, 0, 0)

        val samples = ArrayList<LiveRemEstimator.NightSample>(sessions.size)
        for (session in sessions) {
            val segments = parsePersistedSegments(session.stagesJSON) ?: continue
            // Union read: live-BLE and offloaded rows can sit under sibling source ids.
            val hr = repo.hrSamplesUnion(strapId, session.startTs, session.endTs, limit = 20_000)
            if (hr.isEmpty()) continue

            val rem = ArrayList<Double>()
            val nonRem = ArrayList<Double>()
            for (sample in hr) {
                val seg = segments.firstOrNull { sample.ts >= it.start && sample.ts < it.end } ?: continue
                when (seg.stage) {
                    "rem" -> rem.add(sample.bpm.toDouble())
                    // "wake" is deliberately dropped, not bucketed as non-REM: an awake stretch is high
                    // and unstable, which is exactly what REM looks like on these two features.
                    "light", "deep" -> nonRem.add(sample.bpm.toDouble())
                }
            }
            val floor = (rem + nonRem).minOrNull() ?: continue
            samples.add(LiveRemEstimator.NightSample(remHr = rem, nonRemHr = nonRem, floorBpm = floor))
        }
        val template = LiveRemEstimator.learnTemplate(samples)
        return Result(template, sessions.size, samples.size)
    }
}
