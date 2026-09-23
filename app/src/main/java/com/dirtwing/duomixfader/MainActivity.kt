// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirtwing.duomixfader.ui.HarmonyScreen
import com.dirtwing.duomixfader.ui.HistoryScreen
import com.dirtwing.duomixfader.ui.AboutScreen
import com.dirtwing.duomixfader.ui.MixerScreen
import com.dirtwing.duomixfader.ui.ScaleSheetScreen
import com.dirtwing.duomixfader.ui.sourceChoice

/** Activité unique : héberge l'écran mixeur Compose. */
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_HARMONY = "open_harmony"
        const val EXTRA_OPEN_HISTORY = "open_history"
    }

    private val viewModel: MixerViewModel by viewModels()

    private enum class Screen { MIXER, HARMONY, HISTORY, SHEET, ABOUT }

    /** Écran d'où la fiche PDF a été ouverte, pour y revenir. */
    private var sheetOrigin = Screen.MIXER

    private fun openSheet(from: Screen) {
        sheetOrigin = from
        screen = Screen.SHEET
    }

    private var screen by mutableStateOf(Screen.MIXER)

    /**
     * Accord de capture de lecture (appareils sans capture par le shell) : Android ne le donne
     * qu'à un écran. Une fois l'op PROJECT_MEDIA autorisée par le shell, il répond sans rien
     * afficher ; sinon il montre sa fenêtre d'accord habituelle.
     */
    private val askProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onProjectionResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openRequestedScreen(intent)
        lifecycleScope.launch {
            // Seulement écran visible : le moteur réclame l'accord quand « Son de l'app » est choisi
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.projectionWanted.collect { wanted ->
                    if (wanted) askProjection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                }
            }
        }
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Android 15+ dessine l'app sous la barre d'état et la barre de navigation :
                    // sans cette marge, la rangée du haut (Retour, Effacer…) est hors d'atteinte.
                    Box(Modifier.safeDrawingPadding()) {
                        when (screen) {
                            Screen.ABOUT -> {
                                val mixer by viewModel.state.collectAsStateWithLifecycle()
                                AboutScreen(capabilities = mixer.capabilities, onBack = { screen = Screen.MIXER })
                            }
                            Screen.HARMONY -> {
                                val harmony by viewModel.harmony.collectAsStateWithLifecycle()
                                val mixer by viewModel.state.collectAsStateWithLifecycle()
                                HarmonyScreen(
                                    harmony,
                                    source = sourceChoice(mixer, viewModel),
                                    onRefresh = viewModel::refreshHarmony,
                                    onShowHistory = { screen = Screen.HISTORY },
                                onShowSheet = { openSheet(Screen.HARMONY) },
                                    onBack = { screen = Screen.MIXER },
                                )
                            }
                            Screen.SHEET -> ScaleSheetScreen(onBack = { screen = sheetOrigin })
                            Screen.HISTORY -> {
                                val live by viewModel.liveRecord.collectAsStateWithLifecycle()
                                val history by viewModel.history.collectAsStateWithLifecycle()
                                HistoryScreen(live, history, onClear = viewModel::clearHistory, onBack = { screen = Screen.HARMONY })
                            }
                            Screen.MIXER -> MixerScreen(
                                viewModel,
                                onShowAbout = { screen = Screen.ABOUT },
                                onShowHarmony = { screen = Screen.HARMONY },
                                onShowSheet = { openSheet(Screen.MIXER) },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequestedScreen(intent)
    }

    /** La notification (bouton note de musique) ouvre directement le panneau des gammes. */
    private fun openRequestedScreen(intent: Intent?) {
        android.util.Log.d("DuoMixNav", "intent extras=${intent?.extras?.keySet()?.joinToString { k -> "$k=${intent.extras?.get(k)} (${intent.extras?.get(k)?.javaClass?.simpleName})" }} ecran=$screen")
        if (intent?.getBooleanExtra(EXTRA_OPEN_HARMONY, false) == true) screen = Screen.HARMONY
        // Le widget ouvre l'historique des détections
        if (intent?.getBooleanExtra(EXTRA_OPEN_HISTORY, false) == true) screen = Screen.HISTORY
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
