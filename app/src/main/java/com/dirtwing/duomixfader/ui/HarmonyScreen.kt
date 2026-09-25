// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dirtwing.duomixfader.R
import com.dirtwing.duomixfader.harmony.Chord
import com.dirtwing.duomixfader.harmony.Detection
import com.dirtwing.duomixfader.harmony.HarmonyState
import com.dirtwing.duomixfader.harmony.ProgressionTracker
import com.dirtwing.duomixfader.harmony.ScopeFrame
import com.dirtwing.duomixfader.harmony.ToneFilter
import com.dirtwing.duomixfader.harmony.Triad
import com.dirtwing.duomixfader.harmony.romanNumeral
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

private const val PREHN_VIDEO = "https://youtu.be/Vq2xt2D3e3E"
private const val PREHN_PATREON = "https://www.patreon.com/newjazz"
private const val PREHN_SITE = "https://www.newjazz.dk"
/** Lente à dessein : une modulation est un événement rare, qui doit se voir sans surprendre. */
private const val ROTATION_MS = 900

/** Tonique dans le système de la langue puis dans l'autre : « Ré♭ (D♭) ». */
@Composable
fun tonicLabel(root: Int): String =
    "${stringArrayResource(R.array.notes_primary)[root]} (${stringArrayResource(R.array.notes_secondary)[root]})"

/**
 * Gamme courante. Quand elle change, l'ancienne sort par le haut et la nouvelle entre par
 * le bas : le « défilement par rotation » qui signale une modulation.
 *
 * Avec [source], le choix de ce que l'analyse écoute s'affiche dessous (voir [SourceChoice]).
 */
@Composable
fun CurrentScale(state: HarmonyState, modifier: Modifier = Modifier, source: SourceChoice? = null) {
    Column(modifier) {
        AnimatedContent(
            targetState = state.current,
            transitionSpec = {
                (slideInVertically(tween(ROTATION_MS)) { it } + fadeIn(tween(ROTATION_MS))) togetherWith
                    (slideOutVertically(tween(ROTATION_MS)) { -it } + fadeOut(tween(ROTATION_MS)))
            },
            label = "gamme",
        ) { detection ->
            Column {
                if (!state.supported) {
                    // L'appareil ne laisse pas capter le son d'une app : on le dit, sans faire attendre
                    Text(stringResource(R.string.harmony_off), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.harmony_unsupported), style = MaterialTheme.typography.bodyMedium)
                } else if (state.inactive) {
                    Text(stringResource(R.string.harmony_inactive), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.harmony_inactive_hint), style = MaterialTheme.typography.bodyMedium)
                } else if (detection == null) {
                    Text(
                        stringResource(if (state.listening) R.string.harmony_listening else R.string.harmony_off),
                        style = MaterialTheme.typography.titleLarge,
                    )
                } else {
                    Text(
                        "${stringArrayResource(R.array.notes_primary)[detection.root]} ${detection.scale.popularName}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.harmony_family, detection.scale.family) +
                            " · ${detection.scale.systematicName} · ${detection.scale.chords}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    ScaleTiles(detection)
                }
            }
        }
        source?.let { SourceRow(state, it) }
    }
}

/**
 * Ce que l'analyse écoute : le son de l'app de musique, capté directement (là où l'appareil le
 * permet), ou le micro du téléphone — sa propre enceinte, une chaîne hi-fi, un instrument.
 */
class SourceChoice(
    /**
     * Vrai : « Son de l'app » passe par la capture de lecture d'Android (appareils où le shell
     * ne peut pas capter lui-même), qui exige la permission d'enregistrement.
     */
    val directNeedsPermission: Boolean,
    val useDirect: () -> Unit,
    val stopMicrophone: () -> Unit,
    /** Appelé une fois la permission du micro accordée. */
    val useMicrophone: () -> Unit,
    /** Graves et aigus de l'écoute par le micro, en décibels (voir ToneFilter). */
    val bassDb: Float,
    val trebleDb: Float,
    val setTone: (bassDb: Float, trebleDb: Float) -> Unit,
    /** Amplification de l'écoute par le micro, en décibels. */
    val gainDb: Float,
    val setGain: (Float) -> Unit,
    /** Oscilloscope de l'écoute par le micro (voir MicrophoneScope). */
    val scope: StateFlow<ScopeFrame?>,
)

@Composable
private fun SourceRow(state: HarmonyState, choice: SourceChoice) {
    // Le micro n'est jamais pris d'office : ce bouton demande la permission, puis bascule
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choice.useMicrophone()
    }
    val askForDirect = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choice.useDirect()
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.harmony_source), style = MaterialTheme.typography.labelMedium)
            FilterChip(
                selected = state.supported && !state.viaMicrophone && !state.inactive,
                onClick = {
                    if (choice.directNeedsPermission) askForDirect.launch(Manifest.permission.RECORD_AUDIO) else choice.useDirect()
                },
                label = { Text(stringResource(R.string.harmony_source_app)) },
            )
            FilterChip(
                selected = state.viaMicrophone,
                // Une bascule : un second appui arrête le micro
                onClick = { if (state.viaMicrophone) choice.stopMicrophone() else askMicrophone.launch(Manifest.permission.RECORD_AUDIO) },
                label = { Text(stringResource(R.string.harmony_source_mic)) },
            )
        }
        if (state.viaMicrophone) {
            Text(stringResource(R.string.harmony_mic_note), style = MaterialTheme.typography.bodySmall)
            MicrophoneScope(choice.scope, Modifier.padding(vertical = 6.dp))
            ToneSlider(stringResource(R.string.harmony_mic_gain), choice.gainDb, 0f..MAX_GAIN_DB, choice.setGain)
            ToneSlider(stringResource(R.string.harmony_mic_bass), choice.bassDb) { choice.setTone(it, choice.trebleDb) }
            ToneSlider(stringResource(R.string.harmony_mic_treble), choice.trebleDb) { choice.setTone(choice.bassDb, it) }
        }
    }
}

/** Course du potentiomètre de gain du micro : de 0 à +40 dB. */
private const val MAX_GAIN_DB = 40f

/** Un curseur en décibels, par pas de 1 dB ; le libellé et la valeur en colonnes fixes. */
@Composable
private fun ToneSlider(
    label: String, valueDb: Float,
    range: ClosedFloatingPointRange<Float> = -ToneFilter.RANGE_DB..ToneFilter.RANGE_DB,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        Slider(
            value = valueDb,
            onValueChange = { onChange(it.roundToInt().toFloat()) },
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            "%+d dB".format(valueDb.roundToInt()),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(52.dp),
        )
    }
}

/**
 * La gamme en pavés de couleur : un par degré, de la tonique à son octave, bleu ou rouge selon
 * la gamme par tons de la note (voir ScaleArt). Le pavé atteint par un ton et demi est coupé en
 * deux : moitié haute de la couleur d'où l'on vient (familles 3, 4, 7). La même empreinte que sur la notification et
 * sur la fiche des 33 gammes.
 */
@Composable
fun ScaleTiles(detection: Detection, tile: Dp = 22.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((blue, leap) in ScaleArt.tiles(detection).zip(ScaleArt.leaps(detection))) {
            Box(
                Modifier
                    .size(tile)
                    .background(Color(if (blue) ScaleArt.PASTEL_BLUE else ScaleArt.PASTEL_RED), RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (leap) Box(
                    Modifier
                        .size(tile)
                        .background(Color(if (blue) ScaleArt.PASTEL_RED else ScaleArt.PASTEL_BLUE), UpperHalf)
                )
            }
        }
    }
}

/** Moitié haute d'un pavé à coins arrondis de 3 dp : la couleur « d'où l'on vient ». */
private val UpperHalf = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val corner = with(density) { 3.dp.toPx() }
        val tile = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner))) }
        val top = Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height / 2)) }
        return Outline.Generic(Path().apply { op(tile, top, PathOperation.Intersect) })
    }
}

/** Hyperlien vers la fiche PDF des 33 gammes, embarquée dans l'app. */
@Composable
fun SheetLink(onClick: () -> Unit) {
    Text(
        "📄 " + stringResource(R.string.scale_sheet_link),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}

/** « Dm », « B° », « F+ » : l'accord dans le système de notation de la langue. */
@Composable
fun chordName(chord: Chord): String =
    stringArrayResource(R.array.notes_primary)[chord.root] + when (chord.triad) {
        Triad.MAJOR -> ""
        Triad.MINOR -> "m"
        Triad.DIMINISHED -> "°"
        Triad.AUGMENTED -> "+"
    }

/**
 * Grille d'accords en chiffrage romain relatif à la tonique : « I – IV – V », avec les noms
 * d'accords en dessous, puis la longueur de la boucle (ou la fenêtre de repli).
 */
@Composable
fun ProgressionLines(state: HarmonyState, showChordNames: Boolean) {
    val tonic = state.current?.root ?: return
    val progression = state.progression ?: return
    if (progression.chords.isEmpty()) return
    Text(
        progression.chords.joinToString("  –  ") { romanNumeral(it.chord, tonic) },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (showChordNames) {
        val names = progression.chords.map { chordName(it.chord) }
        Text(names.joinToString("  –  "), style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        progression.cycleMs?.let { stringResource(R.string.harmony_cycle, ((it + 500) / 1000).toInt()) }
            ?: stringResource(R.string.harmony_no_cycle, (ProgressionTracker.FALLBACK_WINDOW_MS / 1000).toInt()),
        style = MaterialTheme.typography.labelMedium,
    )
}

/**
 * Panneau d'information : gamme courante, puis les séquences détectées dans le morceau avec
 * les cinq colonnes du tableau d'Oliver Prehn et, sous la famille, la tonalité. Liste sans
 * interaction : c'est un aide-mémoire du dernier morceau écouté.
 */
@Composable
fun HarmonyScreen(
    state: HarmonyState, source: SourceChoice, onRefresh: () -> Unit, onShowHistory: () -> Unit, onShowSheet: () -> Unit, onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            // Titre sur une seule ligne : à côté de deux boutons, un grand titre se coupait en deux
            Text(
                "♪ " + stringResource(R.string.harmony_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) { Text("⟳ " + stringResource(R.string.harmony_refresh)) }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(trackLabel(state.track), style = MaterialTheme.typography.labelLarge)
                CurrentScale(state, source = source)
                if (state.current != null) {
                    Text(
                        stringResource(R.string.harmony_confidence, (state.confidence * 100).roundToInt()),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(stringResource(R.string.harmony_hint), style = MaterialTheme.typography.bodySmall)
                SheetLink(onShowSheet)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.harmony_sequences), style = MaterialTheme.typography.titleMedium)
                if (state.segments.isEmpty()) {
                    // Sans capture possible, ne pas inviter à attendre « quelques secondes de musique »
                    Text(
                        stringResource(if (state.supported) R.string.harmony_empty else R.string.harmony_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    // Le tableau est plus large qu'un téléphone : il défile horizontalement
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        TableRow(
                            time = "",
                            family = stringResource(R.string.harmony_col_family),
                            tonic = null,
                            popular = stringResource(R.string.harmony_col_popular),
                            steps = stringResource(R.string.harmony_col_steps),
                            systematic = stringResource(R.string.harmony_col_systematic),
                            chords = stringResource(R.string.harmony_col_chords),
                            header = true,
                        )
                        for (segment in state.segments) {
                            HorizontalDivider()
                            SegmentRow(segment.startMs, segment.detection)
                        }
                    }
                }
            }
        }

        // Le bloc Progression ouvre l'historique : une section par morceau écouté
        Card(onClick = onShowHistory, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.harmony_progression), style = MaterialTheme.typography.titleMedium)
                ProgressionLines(state, showChordNames = true)
                Text(
                    stringResource(R.string.harmony_history_hint) + "  ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.harmony_how_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.harmony_how), style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(stringResource(R.string.harmony_privacy), style = MaterialTheme.typography.bodySmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.harmony_credits_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.harmony_credits), style = MaterialTheme.typography.bodySmall)
                LinkButton(stringResource(R.string.harmony_credits_patreon), PREHN_PATREON)
                LinkButton(stringResource(R.string.harmony_credits_video), PREHN_VIDEO)
                LinkButton(stringResource(R.string.harmony_credits_site), PREHN_SITE)
            }
        }
    }
}

@Composable
private fun SegmentRow(startMs: Long, detection: Detection) {
    val seconds = startMs / 1000
    TableRow(
        time = "%d:%02d".format(seconds / 60, seconds % 60),
        family = detection.scale.family.toString(),
        tonic = tonicLabel(detection.root),
        popular = detection.scale.popularName,
        steps = detection.scale.intervalSteps,
        systematic = detection.scale.systematicName,
        chords = detection.scale.chords,
        header = false,
    )
}

@Composable
private fun TableRow(
    time: String, family: String, tonic: String?, popular: String, steps: String,
    systematic: String, chords: String, header: Boolean,
) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    @Composable
    fun Cell(text: String, width: Dp) = Text(text, style = style, fontWeight = weight, modifier = Modifier.width(width))

    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Cell(time, 44.dp)
        Column(Modifier.width(96.dp)) {
            Text(family, style = style, fontWeight = if (header) weight else FontWeight.SemiBold)
            // Sous la famille : la tonalité détectée, dans les deux systèmes de notation
            if (tonic != null) Text(tonic, style = MaterialTheme.typography.labelMedium)
        }
        Cell(popular, 170.dp)
        Cell(steps, 150.dp)
        Cell(systematic, 130.dp)
        Cell(chords, 110.dp)
    }
}

@Composable
private fun LinkButton(label: String, url: String) {
    val context = LocalContext.current
    TextButton(onClick = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }) { Text(label) }
}
