// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirtwing.duomixfader.R
import com.dirtwing.duomixfader.harmony.TrackInfo
import com.dirtwing.duomixfader.harmony.TrackRecord
import com.dirtwing.duomixfader.harmony.romanNumeral
import java.text.DateFormat
import java.util.Date

/** « Rival Consoles — Odyssey », ou ce que l'on en sait. */
@Composable
fun trackLabel(track: TrackInfo?): String = when {
    track == null || !track.isKnown -> stringResource(R.string.harmony_unknown_track)
    track.artist.isBlank() -> track.title
    track.title.isBlank() -> track.artist
    else -> "${track.artist} — ${track.title}"
}

/**
 * Historique des détections : une section par morceau, le plus récent en haut, celui en
 * cours d'écoute en premier. Chaque section garde la gamme, ses séquences et la grille
 * d'accords, pour retrouver plus tard comment accompagner un morceau déjà entendu.
 */
@Composable
fun HistoryScreen(live: TrackRecord?, history: List<TrackRecord>, onClear: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(
                stringResource(R.string.harmony_history_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.harmony_history_clear)) }
            }
        }
        if (live == null && history.isEmpty()) {
            Text(stringResource(R.string.harmony_history_empty), style = MaterialTheme.typography.bodyMedium)
        }
        live?.let { TrackSection(it, isLive = true) }
        for (record in history) TrackSection(record, isLive = false)
    }
}

@Composable
private fun TrackSection(record: TrackRecord, isLive: Boolean) {
    val notes = stringArrayResource(R.array.notes_primary)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(trackLabel(record.track), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(
                    record.source.takeIf { it.isNotBlank() },
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.startedAt)),
                    stringResource(R.string.harmony_now).takeIf { isLive },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
            )
            val tonic = record.detection?.root
            // Une ligne par gamme tenue dans le morceau, dans l'ordre où elles sont apparues
            for (segment in record.segments) {
                val scale = segment.detection.scale
                val seconds = segment.startMs / 1000
                Text(
                    "%d:%02d  ".format(seconds / 60, seconds % 60) +
                        "${notes[segment.detection.root]} ${scale.popularName}  ·  " +
                        stringResource(R.string.harmony_family, scale.family) +
                        "  ·  ${scale.systematicName}  ·  ${scale.chords}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val progression = record.progression
            if (progression != null && tonic != null && progression.chords.isNotEmpty()) {
                Text(
                    progression.chords.joinToString("  –  ") { romanNumeral(it.chord, tonic) },
                    style = MaterialTheme.typography.titleMedium,
                )
                val names = progression.chords.map { chordName(it.chord) }
                Text(names.joinToString("  –  "), style = MaterialTheme.typography.bodyMedium)
                Text(
                    progression.cycleMs?.let { stringResource(R.string.harmony_cycle, ((it + 500) / 1000).toInt()) }
                        ?: stringResource(R.string.harmony_no_cycle_short),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
