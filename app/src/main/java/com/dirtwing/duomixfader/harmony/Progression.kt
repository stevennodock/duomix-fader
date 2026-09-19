// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.harmony

import kotlin.math.sqrt

/** Les quatre triades ; les deux dernières, plus rares, partent avec un léger handicap. */
enum class Triad(val third: Int, val fifth: Int, val handicap: Double) {
    MAJOR(4, 7, 0.0),
    MINOR(3, 7, 0.0),
    DIMINISHED(3, 6, 0.06),
    AUGMENTED(4, 8, 0.08),
}

data class Chord(val root: Int, val triad: Triad) {
    val mask: Int get() = (1 shl root) or (1 shl (root + triad.third) % 12) or (1 shl (root + triad.fifth) % 12)
}

data class ChordSpan(val chord: Chord, val durationMs: Long)

/**
 * La grille d'accords du morceau. [cycleMs] est la période à laquelle elle se répète ; nulle
 * si aucun cycle n'a pu être établi — [chords] est alors la suite des derniers accords
 * entendus sur la fenêtre de repli, et non une boucle.
 */
data class Progression(val cycleMs: Long?, val chords: List<ChordSpan>) {

    /** Part du temps passée sur chaque accord. */
    fun shares(): Map<Chord, Double> {
        val total = chords.sumOf { it.durationMs }.toDouble().takeIf { it > 0 } ?: return emptyMap()
        return chords.groupBy { it.chord }.mapValues { (_, spans) -> spans.sumOf { it.durationMs } / total }
    }

    /** Fait commencer la boucle sur l'accord de tonique (sinon sur le plus long) : « I … ». */
    fun startingOn(tonic: Int): Progression {
        if (cycleMs == null || chords.size < 2) return this
        val start = chords.indexOfFirst { it.chord.root == tonic }.takeIf { it >= 0 }
            ?: chords.indices.maxBy { chords[it].durationMs }
        return copy(chords = chords.drop(start) + chords.take(start))
    }
}

/**
 * Chiffrage romain d'un accord par rapport à la tonique, selon l'usage courant : le degré se
 * lit sur la gamme MAJEURE de la tonique, avec ♭ ou ♯ pour les autres hauteurs (en la mineur,
 * fa majeur s'écrit ♭VI). Majuscules = majeur ou augmenté (+), minuscules = mineur ou
 * diminué (°).
 */
fun romanNumeral(chord: Chord, tonic: Int): String {
    val degree = DEGREES[(chord.root - tonic).mod(12)]
    val accidental = degree.takeWhile { it == '♭' || it == '♯' }
    val numeral = degree.drop(accidental.length)
    return accidental + when (chord.triad) {
        Triad.MAJOR -> numeral
        Triad.AUGMENTED -> "$numeral+"
        Triad.MINOR -> numeral.lowercase()
        Triad.DIMINISHED -> "${numeral.lowercase()}°"
    }
}

private val DEGREES = arrayOf("I", "♭II", "II", "♭III", "III", "IV", "♯IV", "V", "♭VI", "VI", "♭VII", "VII")

/**
 * Suit la grille d'accords à partir des chromas de trames courtes de [ChromaAnalyzer].
 *
 * 1. CYCLE — autocorrélation de la suite des chromas : on cherche le décalage pour lequel la
 *    musique ressemble le plus à elle-même. Ce décalage est la longueur de la boucle, et
 *    devient la fenêtre d'écoute.
 * 2. REPLI — toutes les répétitions de la boucle sont superposées et moyennées, phase par
 *    phase. Ce qui revient à chaque tour (les accords) se renforce ; ce qui change (mélodie,
 *    voix, percussions) s'estompe.
 * 3. ACCORDS — chaque instant de la boucle repliée est comparé aux 48 triades. Le chroma
 *    ignore l'octave : un accord est reconnu quel que soit son renversement.
 *
 * Sans cycle établi (morceau trop court, pas de boucle, accord unique), heuristique de
 * repli : les accords sont lus tels quels sur les [FALLBACK_WINDOW_MS] dernières secondes.
 */
class ProgressionTracker {

    companion object {
        private const val HISTORY_MS = 64_000L
        private const val MIN_CYCLE_MS = 2_000L
        private const val MAX_CYCLE_MS = 32_000L
        /** Il faut avoir entendu la boucle au moins deux fois. */
        private const val MIN_REPEATS = 2
        /** Ressemblance minimale de la musique à elle-même, un cycle plus tard. */
        private const val MIN_SIMILARITY = 0.45
        /** … et nettement au-dessus de la ressemblance moyenne (sinon : accord unique, bourdon). */
        private const val MIN_CONTRAST = 0.10
        /** Parmi les décalages presque aussi bons, le plus court : évite de doubler la boucle. */
        private const val SHORTEST_WITHIN = 0.92
        const val FALLBACK_WINDOW_MS = 20_000L
        private const val FALLBACK_MAX_CHORDS = 8
        /** Lissage avant de nommer l'accord, et durée en deçà de laquelle ce n'en est pas un. */
        private const val SMOOTHING_MS = 640L
        private const val MIN_CHORD_MS = 600L
        private const val MIN_CHORD_SCORE = 0.55
        private const val OUT_OF_SCALE_HANDICAP = 0.08

        private val allChords = Triad.entries.flatMap { triad -> (0 until 12).map { Chord(it, triad) } }
    }

    private val frames = ArrayDeque<FloatArray>()
    private var periodMs = 128f

    fun clear() = frames.clear()

    fun add(frame: FloatArray, framePeriodMs: Float) {
        periodMs = framePeriodMs
        frames.addLast(frame)
        while (frames.size * periodMs > HISTORY_MS) frames.removeFirst()
    }

    private fun framesFor(ms: Long) = (ms / periodMs).toInt().coerceAtLeast(1)

    /** [scaleMask] : notes de la gamme retenue, qui favorise les accords diatoniques. */
    fun analyze(scaleMask: Int?): Progression? {
        val history = frames.toList()
        if (history.size < framesFor(MIN_CYCLE_MS * MIN_REPEATS)) return null
        val cycle = detectCycle(history)
        if (cycle != null) {
            val folded = Array(cycle) { DoubleArray(12) }
            for (t in history.indices) {
                // Phase comptée depuis la fin : la trame la plus récente ferme la boucle
                val phase = (cycle - 1) - ((history.size - 1 - t) % cycle)
                for (i in 0 until 12) folded[phase][i] += history[t][i]
            }
            val spans = label(folded, scaleMask, circular = true)
            if (spans.isNotEmpty()) return Progression((cycle * periodMs).toLong(), spans)
        }
        val recent = history.takeLast(framesFor(FALLBACK_WINDOW_MS))
        val linear = Array(recent.size) { t -> DoubleArray(12) { recent[t][it].toDouble() } }
        val spans = label(linear, scaleMask, circular = false).takeLast(FALLBACK_MAX_CHORDS)
        return if (spans.isEmpty()) null else Progression(null, spans)
    }

    /** Longueur de la boucle en trames, ou null si la musique ne se répète pas nettement. */
    private fun detectCycle(history: List<FloatArray>): Int? {
        val n = history.size
        // Chaque trame centrée et normée : seule compte la FORME du chroma, pas son niveau
        val shapes = smooth(Array(n) { t -> DoubleArray(12) { history[t][it].toDouble() } }, circular = false).map { v ->
            val mean = v.average()
            val centered = DoubleArray(12) { v[it] - mean }
            val norm = sqrt(centered.sumOf { it * it })
            if (norm > 1e-9) DoubleArray(12) { centered[it] / norm } else DoubleArray(12)
        }
        val minLag = framesFor(MIN_CYCLE_MS)
        val maxLag = minOf(framesFor(MAX_CYCLE_MS), n / MIN_REPEATS)
        if (maxLag <= minLag) return null
        val similarity = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (t in lag until n) for (i in 0 until 12) sum += shapes[t][i] * shapes[t - lag][i]
            similarity[lag] = sum / (n - lag)
        }
        val lags = minLag..maxLag
        val best = lags.maxBy { similarity[it] }
        val mean = lags.sumOf { similarity[it] } / lags.count()
        if (similarity[best] < MIN_SIMILARITY || similarity[best] - mean < MIN_CONTRAST) return null
        return lags.first { lag ->
            similarity[lag] >= similarity[best] * SHORTEST_WITHIN &&
                similarity[lag] >= similarity.getOrElse(lag - 1) { 0.0 } &&
                similarity[lag] >= similarity.getOrElse(lag + 1) { 0.0 }
        }
    }

    /** Moyenne glissante sur [SMOOTHING_MS], qui gomme les attaques et les notes de passage. */
    private fun smooth(series: Array<DoubleArray>, circular: Boolean): List<DoubleArray> {
        val half = framesFor(SMOOTHING_MS) / 2
        val n = series.size
        return List(n) { t ->
            val out = DoubleArray(12)
            for (d in -half..half) {
                val index = if (circular) (t + d).mod(n) else (t + d).coerceIn(0, n - 1)
                for (i in 0 until 12) out[i] += series[index][i]
            }
            out
        }
    }

    private fun label(series: Array<DoubleArray>, scaleMask: Int?, circular: Boolean): List<ChordSpan> {
        val names = smooth(series, circular).map { bestChord(it, scaleMask) }
        // Suites d'instants portant le même accord
        val runs = ArrayList<Pair<Chord?, Int>>()
        for (name in names) {
            if (runs.isNotEmpty() && runs.last().first == name) runs[runs.lastIndex] = name to runs.last().second + 1
            else runs.add(name to 1)
        }
        if (circular && runs.size > 1 && runs.first().first == runs.last().first) {
            runs[0] = runs[0].first to runs[0].second + runs.last().second
            runs.removeAt(runs.lastIndex)
        }
        // Un « accord » trop bref est une transition : il est rendu à son voisin le plus long
        val minFrames = framesFor(MIN_CHORD_MS)
        while (runs.size > 1) {
            val shortest = runs.indices.minBy { runs[it].second }
            if (runs[shortest].second >= minFrames && runs[shortest].first != null) break
            val before = if (shortest > 0) shortest - 1 else if (circular) runs.lastIndex else 1
            val after = if (shortest < runs.lastIndex) shortest + 1 else if (circular) 0 else shortest - 1
            val heir = if (runs[before].second >= runs[after].second) before else after
            runs[heir] = runs[heir].first to runs[heir].second + runs[shortest].second
            runs.removeAt(shortest)
            // Deux voisins réunis peuvent porter le même accord
            var i = 0
            while (i < runs.size - 1) {
                if (runs[i].first == runs[i + 1].first) {
                    runs[i] = runs[i].first to runs[i].second + runs[i + 1].second
                    runs.removeAt(i + 1)
                } else i++
            }
        }
        // Une boucle n'a ni début ni fin : le même accord aux deux bouts n'en fait qu'un
        if (circular && runs.size > 1 && runs.first().first == runs.last().first) {
            runs[0] = runs[0].first to runs[0].second + runs.last().second
            runs.removeAt(runs.lastIndex)
        }
        return runs.mapNotNull { (chord, count) -> chord?.let { ChordSpan(it, (count * periodMs).toLong()) } }
    }

    private fun bestChord(chroma: DoubleArray, scaleMask: Int?): Chord? {
        val floor = chroma.min()
        val c = DoubleArray(12) { chroma[it] - floor }
        val norm = sqrt(c.sumOf { it * it })
        if (norm <= 1e-9) return null
        var best: Chord? = null
        var bestScore = MIN_CHORD_SCORE
        for (chord in allChords) {
            val inside = c[chord.root] + c[(chord.root + chord.triad.third) % 12] + c[(chord.root + chord.triad.fifth) % 12]
            var score = inside / (norm * sqrt(3.0)) - chord.triad.handicap
            if (scaleMask != null && chord.mask and scaleMask != chord.mask) score -= OUT_OF_SCALE_HANDICAP
            if (score > bestScore) {
                bestScore = score
                best = chord
            }
        }
        return best
    }
}
