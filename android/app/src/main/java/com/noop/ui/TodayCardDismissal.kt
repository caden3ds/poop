package com.noop.ui

import android.content.Context

/**
 * Persisted "the user dismissed this Today info-card" flags.
 *
 * Lives on its own now: it used to sit beside the Updates inbox (which owned the restore path), and
 * that whole surface is gone. Dismissal is therefore one-way — a card stays hidden until the state it
 * describes clears on its own — so this is just a tiny keyed boolean store.
 */
internal object TodayCardDismissal {

    private fun key(cardId: String) = "today.card.dismissed.$cardId"

    fun isDismissed(context: Context, cardId: String): Boolean =
        NoopPrefs.of(context).getBoolean(key(cardId), false)

    fun setDismissed(context: Context, cardId: String, dismissed: Boolean) {
        NoopPrefs.of(context).edit().putBoolean(key(cardId), dismissed).apply()
    }
}
