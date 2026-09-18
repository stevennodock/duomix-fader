// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dirtwing.duomix.ui.LicensesScreen
import com.dirtwing.duomix.ui.MixerScreen

/** Activité unique : héberge l'écran mixeur Compose. */
class MainActivity : ComponentActivity() {

    private val viewModel: MixerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showLicenses by rememberSaveable { mutableStateOf(false) }
                    if (showLicenses) {
                        LicensesScreen(onBack = { showLicenses = false })
                    } else {
                        MixerScreen(viewModel, onShowLicenses = { showLicenses = true })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Shizuku peut avoir été (re)démarré pendant que l'app était en arrière-plan
        viewModel.refreshShizukuState()
    }
}
