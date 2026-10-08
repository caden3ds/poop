package com.noop.protocol

/**
 * Which transport carried an R-R interval off a WHOOP 5/MG.
 *
 * A 5/MG reports ONE beat train over three transports, and this fork stores all three under the same
 * device id: native realtime (type 40), banked history (v18) and the standard BLE heart-rate profile
 * (0x2A37). Unlabelled, they cannot be told apart on disk — and they must be, for two reasons:
 *
 *  - The native words were decoded as milliseconds when they are 1/1024-second ticks (see [Whoop5RR]),
 *    so rows written before this label existed mix two scales.
 *  - Even at one scale, the transports observe the SAME heartbeats. Interleaving two copies of a beat
 *    train by timestamp fabricates successive differences, which is exactly the quantity RMSSD measures.
 *
 * So scoring reads one transport per window, and a row with no label is legacy and is not scored.
 *
 * WHOOP 4.0 rows carry no label: one beat source, one scale, nothing to disambiguate.
 *
 * The codes are durable storage values (`rrInterval.srcChannel`) and match upstream ryanbr/noop's, so a
 * future port of its other channels cannot collide with them.
 */
enum class RrSourceChannel(val code: Int) {
    /** WHOOP 5 v18 banked history, converted from wire ticks. The canonical copy when present. */
    WHOOP5_HISTORICAL(5),

    /** WHOOP 5 type-40 native realtime, converted from wire ticks. Stored, never scored. */
    WHOOP5_REALTIME(6),

    /** WHOOP 5 over the standard BLE heart-rate profile, already in milliseconds. */
    WHOOP5_STANDARD(7),
    ;

    val isWhoop5Transport: Boolean get() = code in 5..7

    /** Whether scoring may read this transport. Native realtime duplicates the other two. */
    val isScorable: Boolean get() = this == WHOOP5_HISTORICAL || this == WHOOP5_STANDARD

    companion object {
        /** The channel with this storage [code], or null for an absent or unknown one. */
        fun fromCode(code: Int?): RrSourceChannel? = entries.firstOrNull { it.code == code }
    }
}
