// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomix

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

/** Façade de l'écran sur le [MixerEngine], qu'il garde acquis tant que l'écran vit. */
class MixerViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = MixerEngine.get(application).also { it.acquire() }

    val state: StateFlow<MixerUiState> = engine.state

    override fun onCleared() = engine.release()

    fun refreshShizukuState() = engine.refreshShizukuState()
    fun requestPermission() = engine.requestPermission()
    fun setFocusIgnored(pkg: String, ignored: Boolean) = engine.setFocusIgnored(pkg, ignored)
    fun setChannelVolume(pkg: String, volume: Float) = engine.setChannelVolume(pkg, volume)
    fun setCrossfader(x: Float) = engine.setCrossfader(x)
}
