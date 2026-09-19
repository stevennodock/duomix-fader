// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.harmony

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Transforme un flux PCM mono en « chroma » : l'énergie de chacune des 12 classes de
 * hauteur (do, do♯ … si), toutes octaves confondues. Deux vecteurs sont accumulés :
 * le chroma complet (quelles notes -> la famille) et la LIGNE DE BASSE, une note par
 * trame (quelle tonique -> la tonalité et le mode). Les séparer évite qu'une mélodie
 * changeante ou exotique ne fausse la tonalité.
 *
 * Aucune donnée audio n'est conservée : seuls ces cumuls sortent de l'analyseur.
 * Classe sans dépendance Android, testable sur JVM.
 */
class ChromaAnalyzer(private val sampleRate: Int) {

    /**
     * Plus petite puissance de 2 couvrant 0,65 s : 16384 points à 16 kHz (1 s, résolution
     * 0,98 Hz), 32768 à 48 kHz. Il faut cette finesse pour séparer les demi-tons des basses.
     */
    private val fftSize = Integer.highestOneBit((sampleRate * 0.65).toInt() - 1) shl 1
    private val hop = fftSize / 4

    /**
     * Seconde analyse, à fenêtre COURTE, réservée aux accords : 4096 points à 16 kHz, soit
     * 256 ms tous les 128 ms. Trop grossière pour les basses (3,9 Hz par case), suffisante
     * au-dessus de sol1 — et assez vive pour suivre une grille d'accords.
     */
    private val chordSize = fftSize / 4
    private val chordHop = chordSize / 2

    /** Durée entre deux trames d'accord, en millisecondes. */
    val chordFramePeriodMs: Float = chordHop * 1000f / sampleRate

    companion object {
        private const val MIN_HZ = 55.0
        /** Ligne de basse : de mi0 (41 Hz, basse 5 cordes, sub-basses) à do3. */
        private const val BASS_MIN_HZ = 40.0
        private const val BASS_MAX_HZ = 260.0
        /** La note de basse doit atteindre 10 % du pic le plus fort de la trame. */
        private const val BASS_PRESENCE = 0.1
        private const val MAX_HZ = 2100.0
        /** Les accords se lisent au-dessus de sol1 : en dessous, c'est le domaine de la basse. */
        private const val CHORD_MIN_HZ = 98.0
        /** Taille de l'en-tête de [drain], avant les trames d'accord. */
        const val HEADER_SIZE = 28
        /** Une minute de trames d'accord au plus, si personne ne vient les relever. */
        private const val MAX_PENDING_CHORD_FRAMES = 480
        /** Sous ce niveau RMS (pleine échelle = 1), la trame est considérée comme du silence. */
        private const val SILENCE_RMS = 0.0015
        /** -30 dB : en deçà, un pic spectral n'est pas compté comme une note. */
        private const val PEAK_THRESHOLD = 0.0316
        /** Part du niveau d'une fondamentale retirée à ses harmoniques (rang -> part). */
        private val HARMONIC_SHARES = listOf(3 to 0.30, 5 to 0.18, 6 to 0.12)
    }

    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1)) }
    private val ring = FloatArray(fftSize)
    private var writePos = 0
    private var filled = 0
    private var sinceLastFrame = 0

    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val mag = DoubleArray(fftSize / 2)

    /** Classe de hauteur de chaque case de la FFT, ou -1 hors de la plage utile. */
    private val binPitchClass = IntArray(fftSize / 2) { bin ->
        val hz = bin.toDouble() * sampleRate / fftSize
        if (hz < MIN_HZ || hz > MAX_HZ) -1 else (69 + 12 * log2(hz / 440.0)).roundToInt().mod(12)
    }
    private val bassMinBin = (BASS_MIN_HZ * fftSize / sampleRate).toInt().coerceAtLeast(2)
    private val bassMaxBin = (BASS_MAX_HZ * fftSize / sampleRate).toInt()

    private val chordWindow = DoubleArray(chordSize) { 0.5 - 0.5 * cos(2 * PI * it / (chordSize - 1)) }
    private val chordRe = DoubleArray(chordSize)
    private val chordIm = DoubleArray(chordSize)
    private val chordBinPitchClass = IntArray(chordSize / 2) { bin ->
        val hz = bin.toDouble() * sampleRate / chordSize
        if (hz < CHORD_MIN_HZ || hz > MAX_HZ) -1 else (69 + 12 * log2(hz / 440.0)).roundToInt().mod(12)
    }
    private var sinceLastChordFrame = 0
    /** Chromas d'accord en attente de relevé, un par trame courte (nuls pendant un silence). */
    private val chordFrames = ArrayList<FloatArray>()

    private val chroma = DoubleArray(12)
    private val bass = DoubleArray(12)
    private var frames = 0
    private var silentFrames = 0

    /** Ajoute des échantillons mono dans [-1, 1]. */
    @Synchronized
    fun push(samples: FloatArray, count: Int) {
        for (i in 0 until count) {
            ring[writePos] = samples[i]
            writePos = (writePos + 1) % fftSize
            if (filled < fftSize) filled++
            if (++sinceLastFrame >= hop && filled == fftSize) {
                sinceLastFrame = 0
                analyzeFrame()
            }
            if (++sinceLastChordFrame >= chordHop && filled >= chordSize) {
                sinceLastChordFrame = 0
                analyzeChordFrame()
            }
        }
    }

    /**
     * Rend les cumuls depuis le dernier appel puis les remet à zéro :
     * [0..11] chroma, [12..23] ligne de basse (nombre de trames par note), [24] trames
     * sonores, [25] trames de silence, [26] nombre n de trames d'accord, [27] leur période en
     * millisecondes, puis n × 12 valeurs : le chroma de chaque trame d'accord, dans l'ordre.
     */
    @Synchronized
    fun drain(): FloatArray {
        val out = FloatArray(HEADER_SIZE + chordFrames.size * 12)
        out[26] = chordFrames.size.toFloat()
        out[27] = chordFramePeriodMs
        chordFrames.forEachIndexed { n, frame -> frame.copyInto(out, HEADER_SIZE + n * 12) }
        chordFrames.clear()
        for (i in 0 until 12) {
            out[i] = chroma[i].toFloat()
            out[12 + i] = bass[i].toFloat()
        }
        out[24] = frames.toFloat()
        out[25] = silentFrames.toFloat()
        chroma.fill(0.0)
        bass.fill(0.0)
        frames = 0
        silentFrames = 0
        return out
    }

    private fun analyzeFrame() {
        var energy = 0.0
        for (i in 0 until fftSize) {
            val v = ring[(writePos + i) % fftSize].toDouble()
            energy += v * v
            re[i] = v * window[i]
            im[i] = 0.0
        }
        if (sqrt(energy / fftSize) < SILENCE_RMS) {
            silentFrames++
            return
        }
        fft(re, im)
        for (k in mag.indices) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
        suppressHarmonics()

        // Seuls les pics spectraux comptent (un partiel = un maximum local). Compression en
        // racine carrée : une note forte n'écrase pas les autres, mais les harmoniques (la
        // 3e sonne à la quinte, souvent hors gamme) restent nettement sous les fondamentales.
        // Un logarithme les ramenait presque au niveau des notes et faussait la famille.
        val frameChroma = DoubleArray(12)
        val frameBass = DoubleArray(12)
        // Sous -30 dB du pic le plus fort, ce ne sont plus des notes mais les éclaboussures
        // des attaques et des transitions : des milliers de micro-pics qui, une fois
        // compressés, noient la gamme sous un plancher uniforme.
        var strongest = 0.0
        for (k in 1 until mag.size - 1) if (binPitchClass[k] >= 0 && mag[k] > strongest) strongest = mag[k]
        val threshold = strongest * PEAK_THRESHOLD
        for (k in 1 until mag.size - 1) {
            val pc = binPitchClass[k]
            if (pc < 0 || mag[k] < threshold || mag[k] <= mag[k - 1] || mag[k] < mag[k + 1]) continue
            val weight = sqrt(mag[k])
            frameChroma[pc] += weight
        }
        bassNote(strongest)?.let { frameBass[it] = 1.0 }
        // Chaque trame pèse pareil, quel que soit son volume
        val total = frameChroma.sum()
        if (total <= 0.0) return
        val bassTotal = frameBass.sum()
        for (i in 0 until 12) {
            chroma[i] += frameChroma[i] / total
            if (bassTotal > 0.0) bass[i] += frameBass[i] / bassTotal
        }
        frames++
    }

    /** Chroma d'une trame courte, normalisé ; nul si la trame est silencieuse. */
    private fun analyzeChordFrame() {
        var energy = 0.0
        val start = writePos - chordSize + fftSize
        for (i in 0 until chordSize) {
            val v = ring[(start + i) % fftSize].toDouble()
            energy += v * v
            chordRe[i] = v * chordWindow[i]
            chordIm[i] = 0.0
        }
        val frame = FloatArray(12)
        if (chordFrames.size >= MAX_PENDING_CHORD_FRAMES) chordFrames.removeAt(0)
        chordFrames.add(frame)
        if (sqrt(energy / chordSize) < SILENCE_RMS) return
        fft(chordRe, chordIm)
        val half = chordSize / 2
        var strongest = 0.0
        for (k in 1 until half - 1) {
            chordRe[k] = sqrt(chordRe[k] * chordRe[k] + chordIm[k] * chordIm[k]) // module, en place
            if (chordBinPitchClass[k] >= 0 && chordRe[k] > strongest) strongest = chordRe[k]
        }
        val threshold = strongest * PEAK_THRESHOLD
        var total = 0.0
        val sum = DoubleArray(12)
        for (k in 2 until half - 2) {
            val pc = chordBinPitchClass[k]
            if (pc < 0 || chordRe[k] < threshold || chordRe[k] <= chordRe[k - 1] || chordRe[k] < chordRe[k + 1]) continue
            val weight = sqrt(chordRe[k])
            sum[pc] += weight
            total += weight
        }
        if (total > 0.0) for (i in 0 until 12) frame[i] = (sum[i] / total).toFloat()
    }

    /**
     * Ligne de basse : LA note grave de la trame, ou null s'il n'y en a pas de franche.
     * Règle : le pic le plus fort entre 40 et 260 Hz ; s'il existe au tiers de sa fréquence
     * un pic d'au moins 20 % de son niveau, c'est que le premier n'était que le 3e
     * harmonique d'une note plus grave, et c'est celle-ci qu'on retient. (Se tromper
     * d'octave est sans conséquence : la classe de hauteur est la même.)
     */
    private fun bassNote(strongestOverall: Double): Int? {
        var best = -1
        for (k in bassMinBin..bassMaxBin) {
            if (mag[k] > mag[k - 1] && mag[k] >= mag[k + 1] && (best < 0 || mag[k] > mag[best])) best = k
        }
        // Une basse inaudible à côté du reste n'est pas une ligne de basse
        if (best < 0 || mag[best] < strongestOverall * BASS_PRESENCE) return null
        val third = best / 3
        if (third >= bassMinBin) {
            var lower = third - 1
            for (k in third - 1..third + 1) if (mag[k] > mag[lower]) lower = k
            if (mag[lower] >= mag[best] * 0.2) best = lower
        }
        val hz = best.toDouble() * sampleRate / fftSize
        return (69 + 12 * log2(hz / 440.0)).roundToInt().mod(12)
    }

    /**
     * Retire de chaque pic la part attendue de ses harmoniques 3, 5 et 6 : ce sont les seuls
     * (parmi les premiers) à tomber sur une AUTRE note — la quinte et la tierce majeure —,
     * donc à faire croire à des notes que personne ne joue. Les harmoniques 2 et 4 tombent
     * sur la même note, inutile d'y toucher. Parcours des graves vers les aigus : une
     * fondamentale est traitée avant ses propres harmoniques.
     */
    private fun suppressHarmonics() {
        val last = (MAX_HZ * fftSize / sampleRate).toInt().coerceAtMost(mag.size - 3)
        val first = (MIN_HZ * fftSize / sampleRate).toInt().coerceAtLeast(1)
        for (k in first..last / 3) {
            if (mag[k] <= mag[k - 1] || mag[k] < mag[k + 1]) continue
            val level = mag[k]
            for ((harmonic, share) in HARMONIC_SHARES) {
                val center = k * harmonic
                if (center + 2 >= mag.size) break
                for (b in center - 2..center + 2) mag[b] = (mag[b] - level * share).coerceAtLeast(0.0)
            }
        }
    }

    /** FFT radix-2 itérative en place (la taille est une puissance de 2). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val a = start + k
                    val b = a + len / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += len
            }
            len = len shl 1
        }
    }
}
