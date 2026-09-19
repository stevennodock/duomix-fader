// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import com.dirtwing.duomixfader.harmony.Chord
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

// Colonnes communes à toutes les lignes d'une section : les degrés, les tonalités et les pavés
// se lisent alors de haut en bas, d'une gamme à la suivante.
private val TIME_COLUMN = 44.dp
private val DEGREE_COLUMN = 44.dp
private val TONIC_COLUMN = 48.dp
private val CHORD_CELL = 58.dp

/**
 * Une section par morceau, en trois étages séparés par un trait :
 *  1. sous le titre, la gamme principale : tonalité, nom, pavés ;
 *  2. les gammes tenues tour à tour — heure, degré en chiffres romains par rapport à la
 *     tonalité principale, tonalité, pavés — pour lire le passage d'un degré à l'autre ;
 *  3. en pied, la grille d'accords du morceau.
 */
@Composable
private fun TrackSection(record: TrackRecord, isLive: Boolean) {
    val notes = stringArrayResource(R.array.notes_primary)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(trackLabel(record.track), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(
                    record.source.takeIf { it.isNotBlank() },
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.startedAt)),
                    stringResource(R.string.harmony_now).takeIf { isLive },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
            )
            val main = record.detection ?: return@Column

            // 1. La gamme principale du morceau, et sa tonalité
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notes[main.root],
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(TIME_COLUMN + DEGREE_COLUMN),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(main.scale.popularName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    ScaleTiles(main, tile = 20.dp)
                    Text(
                        stringResource(R.string.harmony_family, main.scale.family) +
                            "  ·  ${main.scale.systematicName}  ·  ${main.scale.chords}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            // 2. Les gammes tenues tour à tour, un trait entre chacune
            for (segment in record.segments) {
                HorizontalDivider(thickness = 0.5.dp)
                val detection = segment.detection
                val seconds = segment.startMs / 1000
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "%d:%02d".format(seconds / 60, seconds % 60),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(TIME_COLUMN),
                    )
                    Text(
                        romanNumeral(Chord(detection.root, detection.scale.tonicTriad), main.root),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(DEGREE_COLUMN),
                    )
                    Text(
                        notes[detection.root],
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(TONIC_COLUMN),
                    )
                    ScaleTiles(detection, tile = 15.dp)
                }
                Text(
                    "${detection.scale.popularName}  ·  " +
                        stringResource(R.string.harmony_family, detection.scale.family) +
                        "  ·  ${detection.scale.systematicName}  ·  ${detection.scale.chords}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = TIME_COLUMN + DEGREE_COLUMN),
                )
            }

            // 3. En pied de section : la grille d'accords, une case par accord
            val progression = record.progression
            if (progression != null && progression.chords.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    stringResource(R.string.harmony_progression) + "  ·  " +
                        (progression.cycleMs?.let { stringResource(R.string.harmony_cycle, ((it + 500) / 1000).toInt()) }
                            ?: stringResource(R.string.harmony_no_cycle_short)),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (span in progression.chords) ChordCell(romanNumeral(span.chord, main.root), chordName(span.chord))
                }
            }
        }
    }
}

/** Une case de la grille : le degré au-dessus, le nom de l'accord dessous, un petit trait à gauche. */
@Composable
private fun ChordCell(degree: String, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(1.dp).height(34.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.width(CHORD_CELL), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(degree, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(name, style = MaterialTheme.typography.bodySmall)
        }
    }
}