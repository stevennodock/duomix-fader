// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.harmony

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Réglage de tonalité de l'écoute par le micro : un plateau pour les graves, un pour les aigus,
 * comme les deux curseurs d'un amplificateur. Un micro de téléphone entend mal les basses d'une
 * enceinte et beaucoup le souffle de la pièce : remonter les graves aide la ligne de basse et la
 * tonique, baisser les aigus écarte le bruit. À 0 dB des deux côtés, le signal passe intact.
 *
 * Les fréquences charnières tiennent dans la plage que lit l'analyse (55 Hz – 2,1 kHz).
 * Filtres biquadratiques en plateau (formules de R. Bristow-Johnson, pente S = 1).
 */
class ToneFilter(private val sampleRate: Int) {

    companion object {
        const val BASS_HZ = 200.0
        const val TREBLE_HZ = 1_200.0
        /** Course des deux curseurs, en décibels. */
        const val RANGE_DB = 12f
    }

    private class Biquad {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private val bass = Biquad()
    private val treble = Biquad()
    private var bassDb = 0f
    private var trebleDb = 0f

    /** Règle les deux plateaux ; sans effet si rien n'a changé. */
    @Synchronized
    fun set(bassDb: Float, trebleDb: Float) {
        if (bassDb == this.bassDb && trebleDb == this.trebleDb) return
        this.bassDb = bassDb
        this.trebleDb = trebleDb
        shelf(bass, BASS_HZ, bassDb, high = false)
        shelf(treble, TREBLE_HZ, trebleDb, high = true)
    }

    /** Filtre [count] échantillons sur place. */
    @Synchronized
    fun process(samples: FloatArray, count: Int) {
        if (bassDb == 0f && trebleDb == 0f) return
        for (i in 0 until count) samples[i] = treble.process(bass.process(samples[i].toDouble())).toFloat()
    }

    private fun shelf(filter: Biquad, hz: Double, gainDb: Float, high: Boolean) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * hz / sampleRate
        val cosW = cos(w0)
        val beta = 2 * sqrt(a) * (sin(w0) / 2 * sqrt(2.0))   // 2·√A·α, avec S = 1
        // Le plateau des aigus est le miroir de celui des graves : le signe de cos(w0) s'inverse
        val sign = if (high) -1.0 else 1.0
        val a0 = (a + 1) + sign * (a - 1) * cosW + beta
        filter.b0 = a * ((a + 1) - sign * (a - 1) * cosW + beta) / a0
        filter.b1 = sign * 2 * a * ((a - 1) - sign * (a + 1) * cosW) / a0
        filter.b2 = a * ((a + 1) - sign * (a - 1) * cosW - beta) / a0
        filter.a1 = sign * -2 * ((a - 1) + sign * (a + 1) * cosW) / a0
        filter.a2 = ((a + 1) + sign * (a - 1) * cosW - beta) / a0
    }
}
