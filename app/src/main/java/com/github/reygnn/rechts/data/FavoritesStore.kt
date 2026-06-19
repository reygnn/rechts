package com.github.reygnn.rechts.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.reygnn.rechts.domain.Favorite
import com.github.reygnn.rechts.domain.FavoritesOps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Process-wide DataStore for the favorites list. The delegate guarantees a
 * single instance per file, so [FavoritesStore] can be constructed freely.
 */
private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

/**
 * Persists the favorites list as one JSON blob in DataStore Preferences. A list
 * is more than the single boolean [SettingsStore] keeps in SharedPreferences,
 * so DataStore is the right tool here (family persistence baseline). All list
 * logic lives in the pure, JVM-tested [FavoritesOps]; this class is only the
 * thin Android wrapper that reads/writes the blob.
 *
 * Favorites are shared across normal and private browsing — they're user data,
 * not browsing history, so they're never wiped with the private profile.
 */
class FavoritesStore(context: Context) {

    private val dataStore = context.applicationContext.favoritesDataStore

    /**
     * The current favorites, recomputed on every write. An [IOException] from a
     * corrupt store surfaces as empty preferences (→ empty list) rather than
     * crashing the collector.
     */
    val favorites: Flow<List<Favorite>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> FavoritesOps.decode(prefs[KEY] ?: "") }

    /** Add the page if absent, remove it if already saved (matched by URL). */
    suspend fun toggle(url: String, title: String) = update { current ->
        FavoritesOps.toggle(current, Favorite(url, title))
    }

    /** Remove the favorite with [url], if any. */
    suspend fun remove(url: String) = update { current ->
        FavoritesOps.remove(current, url)
    }

    /** Persist a reordered list verbatim (the result of a drag-and-drop drop). */
    suspend fun saveOrder(ordered: List<Favorite>) = update { ordered }

    /**
     * Read-modify-write the blob atomically inside [edit]: decode the stored
     * list, transform it, re-encode. Decoding inside the transaction (rather
     * than off the [favorites] flow) keeps concurrent toggles from racing on a
     * stale snapshot.
     */
    private suspend fun update(transform: (List<Favorite>) -> List<Favorite>) {
        dataStore.edit { prefs ->
            val current = FavoritesOps.decode(prefs[KEY] ?: "")
            prefs[KEY] = FavoritesOps.encode(transform(current))
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("favorites_json")
    }
}
