package com.noop.protocol

/**
 * WHOOP 5/MG R-R interval units.
 *
 * The type-40 realtime and v18 history interval words carry 1/1024-second ticks — the same unit as the
 * standard BLE heart-rate profile (0x2A37) — and were decoded as milliseconds, inflating every interval
 * by 2.4%. Ported from upstream ryanbr/noop 98975f99, where the evidence is discriminating rather than a
 * single match: on firmware 50.41.1.0, 131 native-live arrays matched 131 distinct standard-BLE arrays,
 * and 94 historical arrays matched the raw words while matching none of the already-converted ones.
 *
 * WHOOP 4.0 is unaffected; its words stay milliseconds.
 */
object Whoop5RR {
    /**
     * Rounded milliseconds from a tick count. Rounds half up exactly like the standard-profile decode
     * (`Math.round(raw / 1024.0 * 1000.0)`), so the same beat over two transports yields the same value
     * — which matters, because equal values are what let a duplicate collide and be recognised.
     */
    fun milliseconds(ticks: Int): Int {
        require(ticks in 0..65535) { "R-R ticks out of u16 range: $ticks" }
        return (ticks * 1000 + 512) / 1024
    }
}
