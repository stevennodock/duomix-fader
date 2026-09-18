// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader

import android.Manifest
import android.content.pm.PackageManager
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
import com.dirtwing.duomixfader.ui.LicensesScreen
import com.dirtwing.duomixfader.ui.MixerScreen

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

    override fun onStart() {
        super.onStart()
        // Un service de premier plan ne peut être lancé que depuis une app visible.
        // Les notifications de lecteur multimédia sont exemptées de POST_NOTIFICATIONS,
        // on la demande quand même pour que la notification apparaisse aussi dans le volet.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        MixerNotificationService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // Shizuku peut avoir été (re)démarré, ou une app installée, pendant l'arrière-plan
        viewModel.refresh()
    }
}
