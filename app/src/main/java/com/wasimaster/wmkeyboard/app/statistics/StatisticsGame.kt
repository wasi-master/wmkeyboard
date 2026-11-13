package com.wasimaster.wmkeyboard.app.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.EggPrefs
import com.wasimaster.wmkeyboard.app.NavRow
import com.wasimaster.wmkeyboard.app.SectionHeader
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.chartEntrance
import com.wasimaster.wmkeyboard.app.rememberChartEntrance
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gamepad
import java.text.NumberFormat

/** The game's lifetime figures, read once per visit: the game and this screen are never open together. */
private class GameFigures(
    val best: Long,
    val bestLevel: Long,
    val rounds: Long,
    val playMs: Long,
    val caught: Long,
    val words: Long,
)

/**
 * The keycap catcher's section: best score, best level, rounds, time played,
 * keys caught and words spelled, and a row that opens the game again.
 *
 * Nothing at all until the game has been found (seven taps on About's version
 * row): before that, a section here would give the secret away. The figures
 * live in [EggPrefs], not in the typing statistics, so they are untouched by
 * the switch that stops the counting and by Delete statistics.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeycapCatcherStats(onOpenGame: () -> Unit) {
    val context = LocalContext.current
    val figures = remember {
        val prefs = EggPrefs(context)
        if (!prefs.gameFound) {
            null
        } else {
            GameFigures(
                best = prefs.gameHighScore.toLong(),
                bestLevel = prefs.gameBestLevel.toLong(),
                rounds = prefs.gameRounds.toLong(),
                playMs = prefs.gamePlayMs,
                caught = prefs.gameKeysCaught,
                words = prefs.gameWordsSpelled.toLong(),
            )
        }
    } ?: return

    SectionHeader(
        stringResource(R.string.egg_game_title),
        info = stringResource(R.string.statistics_game_info),
    )
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val entrance = rememberChartEntrance(ready = true)
    SettingsGroup {
        if (figures.rounds > 0L) {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    maxItemsInEachRow = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .chartEntrance(entrance),
                ) {
                    val seen = entrance.seen
                    StatTile(figures.best, stringResource(R.string.statistics_game_best_label), seen, 0) {
                        numbers.format(it)
                    }
                    StatTile(figures.bestLevel, stringResource(R.string.statistics_game_level_label), seen, 1) {
                        numbers.format(it)
                    }
                    StatTile(figures.rounds, stringResource(R.string.statistics_game_rounds_label), seen, 2) {
                        numbers.format(it)
                    }
                    StatTile(figures.playMs, stringResource(R.string.statistics_game_time_label), seen, 3) {
                        // Rounds are short: seconds until the first whole minute.
                        if (it < MINUTE_MS) {
                            stringResource(R.string.statistics_game_seconds, (it / SECOND_MS).toInt())
                        } else {
                            formatDuration(it)
                        }
                    }
                    StatTile(figures.caught, stringResource(R.string.statistics_game_caught_label), seen, 4) {
                        numbers.format(it)
                    }
                    StatTile(figures.words, stringResource(R.string.statistics_game_words_label), seen, 5) {
                        numbers.format(it)
                    }
                }
            }
        }
        item {
            NavRow(
                title = stringResource(R.string.statistics_game_play_title),
                icon = Icons.Outlined.Gamepad,
                onClick = onOpenGame,
            )
        }
    }
}

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60_000L
