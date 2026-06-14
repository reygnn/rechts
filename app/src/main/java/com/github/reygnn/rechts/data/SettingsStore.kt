package com.github.reygnn.rechts.data

import android.content.Context

/**
 * Tiny persistent settings holder. Only one value so far — the chosen browsing
 * mode — so SharedPreferences is the right tool (DataStore would be overkill;
 * see ~/.claude/CLAUDE.md persistence baseline).
 *
 * Reads/writes are synchronous; with a single boolean that's fine to touch on
 * the main thread at startup. Kept thin and logic-free on purpose — it wraps an
 * Android API, so it isn't JVM-unit-tested.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether private (isolated, session-only) browsing is currently selected. */
    var privateMode: Boolean
        get() = prefs.getBoolean(KEY_PRIVATE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_PRIVATE_MODE, value).apply()

    private companion object {
        const val PREFS_NAME = "rechts_settings"
        const val KEY_PRIVATE_MODE = "private_mode"
    }
}
