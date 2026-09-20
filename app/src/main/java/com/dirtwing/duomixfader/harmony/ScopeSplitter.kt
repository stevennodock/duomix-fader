// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.harmony

import kotlin.math.PI
import kotlin.math.exp

/** Une image de l'oscilloscope : trois traces de [ScopeSplitter.POINTS] points entre −1 et 1. */
class ScopeFrame(val low: FloatArray, val mid: FloatArray, val high: FloatArray)

/**
 * Oscilloscope de l'écoute par le micro : le signal, pris APRÈS le réglage de tonalité, est
 * séparé en trois bandes — graves (passe-bas), aigus (passe-haut), médiums (ce qui reste) — pour
 * que l'on voie ce que le micro entend et ce que font les curseurs.
 *
 * Pour que la trace se lise au lieu de trembler, l'image démarre sur un passage à zéro montant
 * (synchronisation, comme sur un vrai oscilloscope). L'échelle est FIXE : la trace montre le
 * niveau réel après le gain — plate, il faut monter le potentiomètre ; rabotée en haut et en bas,
 * il faut le baisser. [ZOOM] grossit un peu l'affichage : une musique bien captée reste loin
 * de la pleine échelle.
 */
class ScopeSplitter(sampleRate: Int) {

    companion object {
        const val POINTS = 128
        const val LOW_HZ = 250.0
        const val HIGH_HZ = 1_500.0
        /** Échantillons affichés par image : 16 ms à 16 kHz, soit un cycle et demi d'un la grave. */
        private const val WINDOW = 256
        /** Grossissement de l'affichage : une trace pleine hauteur vaut −12 dB de la pleine échelle. */
        private const val ZOOM = 4f
    }

    private val lowCoefficient = (1 - exp(-2 * PI * LOW_HZ / sampleRate)).toFloat()
    private val highCoefficient = (1 - exp(-2 * PI * HIGH_HZ / sampleRate)).toFloat()
    private var lowState = 0f
    private var lowMidState = 0f

    private var low = FloatArray(0)
    private var mid = FloatArray(0)
    private var high = FloatArray(0)

    /** Une image pour ce bloc d'échantillons, ou null s'il est trop court pour en faire une. */
    fun feed(samples: FloatArray, count: Int): ScopeFrame? {
        if (low.size < count) { low = FloatArray(count); mid = FloatArray(count); high = FloatArray(count) }
        for (i in 0 until count) {
            val x = samples[i]
            lowState += lowCoefficient * (x - lowState)
            lowMidState += highCoefficient * (x - lowMidState)
            low[i] = lowState
            mid[i] = lowMidState - lowState
            high[i] = x - lowMidState
        }
        if (count < WINDOW) return null

        // Synchronisation : premier passage à zéro montant du grave + médium, s'il y en a un
        var start = 0
        for (i in 1..count - WINDOW) {
            if (low[i - 1] + mid[i - 1] < 0f && low[i] + mid[i] >= 0f) { start = i; break }
        }
        val stride = WINDOW / POINTS
        fun trace(band: FloatArray) = FloatArray(POINTS) { (band[start + it * stride] * ZOOM).coerceIn(-1f, 1f) }
        return ScopeFrame(trace(low), trace(mid), trace(high))
    }
}
