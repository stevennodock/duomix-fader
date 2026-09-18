// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

/** Façade de l'écran sur le [MixerEngine], qu'il garde acquis tant que l'écran vit. */
class MixerViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = MixerEngine.get(application).also { it.acquire() }

    val state: StateFlow<MixerUiState> = engine.state

    override fun onCleared() = engine.release()

    /** À chaque retour au premier plan : Shizuku ou une app ont pu changer entre-temps. */
    fun refresh() {
        engine.refreshInstalledApps()
        engine.refreshShizukuState()
    }

    fun requestPermission() = engine.requestPermission()
    fun selectApp(slot: Slot, pkg: String) = engine.selectApp(slot, pkg)
    fun setFocusIgnored(pkg: String, ignored: Boolean) = engine.setFocusIgnored(pkg, ignored)
    fun setChannelVolume(slot: Slot, volume: Float) = engine.setChannelVolume(slot, volume)
    fun setCrossfader(x: Float) = engine.setCrossfader(x)
}
