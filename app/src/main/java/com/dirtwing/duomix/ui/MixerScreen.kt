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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirtwing.duomix.Channel
import com.dirtwing.duomix.MixerViewModel
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
        Text(
            "Mixeur YouTube / YouTube Music via Shizuku",
            style = MaterialTheme.typography.bodyMedium,
        )

        // --- Carte état Shizuku ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shizuku", style = MaterialTheme.typography.titleMedium)
                StatusLine("Service Shizuku détecté", state.shizukuAvailable)
                StatusLine("Permission accordée", state.shizukuGranted)
                StatusLine("Service mixeur connecté", state.serviceBound)
                if (!state.shizukuAvailable) {
                    Text(
                        "Lance l'app Shizuku et démarre-la via « Débogage sans fil ».",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (!state.shizukuGranted) {
                    Button(onClick = { viewModel.requestPermission() }) {
                        Text("Demander la permission Shizuku")
                    }
                }
            }
        }

        // --- Carte audio focus ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lecture simultanée (audio focus)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Quand une app « ignore » l'audio focus, elle n'est plus mise en pause " +
                        "par l'autre. Activer sur YouTube Music suffit en général.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FocusSwitch(state.ytm, enabled = state.serviceBound) {
                    viewModel.setFocusIgnored(state.ytm.pkg, it)
                }
                FocusSwitch(state.yt, enabled = state.serviceBound) {
                    viewModel.setFocusIgnored(state.yt.pkg, it)
                }
            }
        }

        // --- Carte mixeur ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Mixeur", style = MaterialTheme.typography.titleMedium)
                ChannelSlider(state.ytm, enabled = state.serviceBound) {
                    viewModel.setChannelVolume(state.ytm.pkg, it)
                }
                ChannelSlider(state.yt, enabled = state.serviceBound) {
                    viewModel.setChannelVolume(state.yt.pkg, it)
                }
                Text("Crossfader", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Music", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = state.crossfader,
                        onValueChange = { viewModel.setCrossfader(it) },
                        enabled = state.serviceBound,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text("Vidéo", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    "Le volume est appliqué aux flux actifs ; lance la lecture dans les deux " +
                        "apps pour voir les canaux passer en « lecture ».",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        state.lastError?.let {
            Text(
                "Erreur : $it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onShowLicenses) { Text("Licences open source") }
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
        Text(channel.label, modifier = Modifier.weight(1f))
        Switch(checked = channel.focusIgnored, onCheckedChange = onToggle, enabled = enabled)
    }
}

@Composable
private fun ChannelSlider(channel: Channel, enabled: Boolean, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(channel.label, modifier = Modifier.weight(1f))
            Text(
                if (channel.playing) "▶ lecture" else "· silencieux",
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
