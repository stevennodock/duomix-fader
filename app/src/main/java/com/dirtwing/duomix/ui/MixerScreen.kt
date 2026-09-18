// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomix.ui

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
import com.dirtwing.duomix.AppTarget
import com.dirtwing.duomix.Channel
import com.dirtwing.duomix.MixerViewModel
import com.dirtwing.duomix.R
import com.dirtwing.duomix.Slot
import kotlin.math.roundToInt

/** Écran unique : état Shizuku, bascules audio focus, mixeur 2 canaux + crossfader. */
@Composable
fun MixerScreen(viewModel: MixerViewModel, onShowLicenses: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DuoMix", style = MaterialTheme.typography.headlineMedium)
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
                }
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
                FocusSwitch(state.music, enabled = state.serviceBound) {
                    viewModel.setFocusIgnored(state.music.pkg, it)
                }
                FocusSwitch(state.video, enabled = state.serviceBound) {
                    viewModel.setFocusIgnored(state.video.pkg, it)
                }
            }
        }

        // --- Carte mixeur ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.mixer_title), style = MaterialTheme.typography.titleMedium)
                ChannelSlider(state.music, enabled = state.serviceBound) {
                    viewModel.setChannelVolume(Slot.MUSIC, it)
                }
                ChannelSlider(state.video, enabled = state.serviceBound) {
                    viewModel.setChannelVolume(Slot.VIDEO, it)
                }
                Text(stringResource(R.string.crossfader_title), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.music.label, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = state.crossfader,
                        onValueChange = { viewModel.setCrossfader(it) },
                        enabled = state.serviceBound,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(state.video.label, style = MaterialTheme.typography.labelMedium)
                }
                Text(
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
private fun ChannelSlider(channel: Channel, enabled: Boolean, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(channel.label, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (channel.playing) R.string.state_playing else R.string.state_silent),
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
