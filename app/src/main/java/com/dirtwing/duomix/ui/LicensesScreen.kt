// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Écran « Licences open source » : affiche NOTICE puis LICENSE, copiés depuis la racine
 * du projet dans assets/licenses/ par la tâche Gradle copyLicenseAssets.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val assets = LocalContext.current.assets
    val texts = remember {
        listOf("NOTICE", "LICENSE").map { name ->
            runCatching {
                assets.open("licenses/$name").bufferedReader().use { it.readText() }
            }.getOrDefault("$name introuvable dans l'APK")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Retour") }
        Text("Licences open source", style = MaterialTheme.typography.headlineSmall)
        for (text in texts) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
