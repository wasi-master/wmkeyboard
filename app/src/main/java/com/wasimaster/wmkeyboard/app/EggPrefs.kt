package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.core.content.edit

/**
 * The little state the easter eggs keep: which year's install anniversary has
 * already been celebrated, and the best score in the hidden mini game.
 *
 * Its own `SharedPreferences` file rather than fields on `KeyboardSettings`,
 * for the same two reasons as `UpdatePrefs`. Nothing here is a keyboard
 * setting — the IME never reads it, and a high score has no business in a
 * settings export or the config backup. And `KeyboardSettings` is a data
 * class within a handful of fields of the JVM's 255-slot ceiling on
 * `copy$default`, so its remaining room belongs to things that actually
 * shape typing.
 *
 * The game's lifetime figures live here too, for the Statistics screen's
 * keycap catcher section. That section stays hidden until [gameFound]: a
 * secret that shows up in Statistics before it is found is a spoiler.
 *
 * The celebration marker is the year itself, not a boolean: it re-arms on
 * its own every January without anyone having to clear it.
 */
internal class EggPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The year whose anniversary confetti has already fired, or 0. */
    var celebratedYear: Int
        get() = prefs.getInt(KEY_CELEBRATED_YEAR, 0)
        set(value) = prefs.edit { putInt(KEY_CELEBRATED_YEAR, value) }

    /** Best score in the keycap catcher, 0 until the game is found. */
    var gameHighScore: Int
        get() = prefs.getInt(KEY_GAME_HIGH_SCORE, 0)
        set(value) = prefs.edit { putInt(KEY_GAME_HIGH_SCORE, value) }

    /** Whether the game has ever been opened. Statistics shows its section only after this. */
    var gameFound: Boolean
        get() = prefs.getBoolean(KEY_GAME_FOUND, false) || gameHighScore > 0
        set(value) = prefs.edit { putBoolean(KEY_GAME_FOUND, value) }

    /** Rounds started, counting every Play and Play again. */
    var gameRounds: Int
        get() = prefs.getInt(KEY_GAME_ROUNDS, 0)
        set(value) = prefs.edit { putInt(KEY_GAME_ROUNDS, value) }

    /** Time spent in play, paused time and cards left out. */
    var gamePlayMs: Long
        get() = prefs.getLong(KEY_GAME_PLAY_MS, 0L)
        set(value) = prefs.edit { putLong(KEY_GAME_PLAY_MS, value) }

    /** Keys caught across every round, special keys included and ⌫ left out. */
    var gameKeysCaught: Long
        get() = prefs.getLong(KEY_GAME_KEYS_CAUGHT, 0L)
        set(value) = prefs.edit { putLong(KEY_GAME_KEYS_CAUGHT, value) }

    /** Words spelled out to the last letter, across every round. */
    var gameWordsSpelled: Int
        get() = prefs.getInt(KEY_GAME_WORDS, 0)
        set(value) = prefs.edit { putInt(KEY_GAME_WORDS, value) }

    /** The highest level any round reached. */
    var gameBestLevel: Int
        get() = prefs.getInt(KEY_GAME_BEST_LEVEL, 0)
        set(value) = prefs.edit { putInt(KEY_GAME_BEST_LEVEL, value) }

    private companion object {
        const val FILE_NAME = "egg_prefs"
        const val KEY_CELEBRATED_YEAR = "anniversary_celebrated_year"
        const val KEY_GAME_HIGH_SCORE = "game_high_score"
        const val KEY_GAME_FOUND = "game_found"
        const val KEY_GAME_ROUNDS = "game_rounds"
        const val KEY_GAME_PLAY_MS = "game_play_ms"
        const val KEY_GAME_KEYS_CAUGHT = "game_keys_caught"
        const val KEY_GAME_WORDS = "game_words_spelled"
        const val KEY_GAME_BEST_LEVEL = "game_best_level"
    }
}
