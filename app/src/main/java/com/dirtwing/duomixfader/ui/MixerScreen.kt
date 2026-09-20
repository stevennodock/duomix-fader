// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirtwing.duomixfader.AppTarget
import com.dirtwing.duomixfader.Channel
import com.dirtwing.duomixfader.MixerUiState
import com.dirtwing.duomixfader.MixerViewModel
import com.dirtwing.duomixfader.R
import com.dirtwing.duomixfader.Slot
import kotlin.math.roundToInt

/** Le choix de la source de l'analyse harmonique, tel que cet appareil le permet. */
fun sourceChoice(state: MixerUiState, viewModel: MixerViewModel) = SourceChoice(
    directNeedsPermission = !state.canCapture,
    // Capture par le shell là où elle existe ; ailleurs, la capture de lecture d'Android
    useDirect = if (state.canCapture) viewModel::disableMicrophoneHarmony else viewModel::enableProjectionHarmony,
    stopMicrophone = viewModel::disableMicrophoneHarmony,
    useMicrophone = viewModel::enableMicrophoneHarmony,
    bassDb = state.micBassDb,
    trebleDb = state.micTrebleDb,
    setTone = viewModel::setMicrophoneTone,
    gainDb = state.micGainDb,
    setGain = viewModel::setMicrophoneGain,
    scope = viewModel.micScope,
)

/** Écran unique : état Shizuku, bascules audio focus, mixeur 2 canaux + crossfader. */
@Composable
fun MixerScreen(
    viewModel: MixerViewModel, onShowLicenses: () -> Unit, onShowHarmony: () -> Unit, onShowSheet: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val harmony by viewModel.harmony.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodyMedium)

        // --- Carte état Shizuku ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shizuku", style = MaterialTheme.typography.titleMedium)
                StatusLine(stringResource(R.string.status_shizuku_detected), state.shizukuAvailable)
                StatusLine(stringResource(R.string.status_permission_granted), state.shizukuGranted)
                StatusLine(stringResource(R.string.status_service_bound), state.serviceBound)
                if (!state.shizukuAvailable) {
                    Text(
                        stringResource(R.string.hint_start_shizuku),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (!state.shizukuGranted) {
                    Button(onClick = { viewModel.requestPermission() }) {
                        Text(stringResource(R.string.action_request_permission))
                    }
                    // Sur ces marques, Shizuku refuse tant qu'adb est bridé : dire quoi activer
                    if (restrictsAdb) Text(stringResource(R.string.hint_adb_restricted), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // --- Carte harmonie : gamme estimée du morceau joué sur le canal musique ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("♪ " + stringResource(R.string.harmony_title), style = MaterialTheme.typography.titleMedium)
                Text(trackLabel(harmony.track), style = MaterialTheme.typography.labelLarge)
                CurrentScale(harmony, source = sourceChoice(state, viewModel))
                ProgressionLines(harmony, showChordNames = false)
                TextButton(onClick = onShowHarmony) { Text(stringResource(R.string.harmony_open)) }
                SheetLink(onShowSheet)
            }
        }

        // --- Carte choix des apps ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.apps_title), style = MaterialTheme.typography.titleMedium)
                AppPicker(stringResource(R.string.slot_music), state.music, state.installedMusic) {
                    viewModel.selectApp(Slot.MUSIC, it)
                }
                AppPicker(stringResource(R.string.slot_video), state.video, state.installedVideo) {
                    viewModel.selectApp(Slot.VIDEO, it)
                }
            }
        }

        // --- Carte audio focus ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.focus_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.focus_description),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!state.canSetFocus) Limitation(stringResource(R.string.hint_adb_restricted))
                FocusSwitch(state.music, enabled = state.serviceBound && state.canSetFocus) {
                    viewModel.setFocusIgnored(state.music.pkg, it)
                }
                FocusSwitch(state.video, enabled = state.serviceBound && state.canSetFocus) {
                    viewModel.setFocusIgnored(state.video.pkg, it)
                }
            }
        }

        // --- Carte mixeur ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.mixer_title), style = MaterialTheme.typography.titleMedium)
                // Sans accès aux lecteurs des autres apps, curseurs et crossfader n'auraient aucun effet
                // Le mode coupure garde le fader utile : il bascule le son des apps au lieu de le doser
                val mixable = state.serviceBound && (state.canControlPlayers || state.cutMode)
                if (state.cutMode) Limitation(stringResource(R.string.mixer_cut_mode))
                else if (!state.canControlPlayers) Limitation(stringResource(R.string.mixer_unsupported))
                ChannelSlider(state.music, enabled = mixable, cut = state.isCut(state.music).takeIf { state.cutMode }) {
                    viewModel.setChannelVolume(Slot.MUSIC, it)
                }
                ChannelSlider(state.video, enabled = mixable, cut = state.isCut(state.video).takeIf { state.cutMode }) {
                    viewModel.setChannelVolume(Slot.VIDEO, it)
                }
                Text(stringResource(R.string.crossfader_title), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.music.label, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = state.crossfader,
                        onValueChange = { viewModel.setCrossfader(it) },
                        enabled = mixable,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(state.video.label, style = MaterialTheme.typography.labelMedium)
                }
                // L'état « lecture » des canaux n'est connu que là où les lecteurs sont accessibles
                if (state.canControlPlayers) Text(
                    stringResource(R.string.mixer_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        state.lastError?.let {
            Text(
                stringResource(R.string.error_prefix, it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onShowLicenses) { Text(stringResource(R.string.action_licenses)) }
    }
}

/** Sélecteur d'app d'un canal : ne propose que les apps du catalogue installées. */
@Composable
private fun AppPicker(
    title: String,
    channel: Channel,
    installed: List<AppTarget>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        if (installed.isEmpty()) {
            Text(
                stringResource(R.string.apps_none_installed),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            TextButton(onClick = { expanded = true }) { Text("${channel.label} ▾") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (app in installed) {
                    DropdownMenuItem(
                        text = { Text(app.label) },
                        onClick = {
                            expanded = false
                            onSelect(app.pkg)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Marques dont la surcouche (OxygenOS, ColorOS, realme UI) retire des droits à adb, donc à
 * Shizuku, tant que l'option développeur « Désactiver la surveillance des autorisations »
 * est éteinte. Ne sert qu'à afficher un indice ; n'a aucun effet sur les autres appareils.
 */
private val restrictsAdb: Boolean =
    Build.MANUFACTURER.lowercase() in setOf("oneplus", "oppo", "realme")

/** Une fonction que cet appareil ne permet pas : encadré discret, dans la carte concernée. */
@Composable
private fun Limitation(text: String) {
    Text(
        "ⓘ  $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "🟢" else "🔴")
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FocusSwitch(channel: Channel, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(channel.label)
            if (!channel.toleratesFocusDenial) {
                Text(
                    stringResource(R.string.focus_not_supported),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Switch(
            checked = channel.focusIgnored,
            onCheckedChange = onToggle,
            enabled = enabled && channel.toleratesFocusDenial,
        )
    }
}

@Composable
private fun ChannelSlider(channel: Channel, enabled: Boolean, cut: Boolean? = null, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(channel.label, modifier = Modifier.weight(1f))
            Text(
                // Mode coupure ([cut] non nul) : on ne sait pas si l'app joue, on dit si on la laisse passer
                stringResource(
                    when (cut) {
                        true -> R.string.state_cut
                        false -> R.string.state_open
                        null -> if (channel.playing) R.string.state_playing else R.string.state_silent
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = channel.volume,
                onValueChange = onChange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(channel.volume * 100).roundToInt()}%",
                modifier = Modifier.width(48.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
