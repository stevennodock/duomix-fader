// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.harmony

import kotlin.math.exp
import kotlin.math.sqrt

/** Une gamme reconnue : laquelle des 33, et sur quelle tonique (0 = do … 11 = si). */
data class Detection(val scale: ScaleDef, val root: Int)

/** Une séquence du morceau : gamme tenue à partir de [startMs] (temps écoulé dans le morceau). */
data class Segment(val detection: Detection, val startMs: Long)

data class HarmonyState(
    /** Analyse active (capture en cours). */
    val listening: Boolean = false,
    /** Gamme retenue, stable ; nulle tant que l'écoute n'a pas assez duré. */
    val current: Detection? = null,
    /** 0..1 : écart entre le meilleur ensemble de notes et le suivant. */
    val confidence: Float = 0f,
    /** Séquences détectées dans le morceau en cours, de la plus ancienne à la plus récente. */
    val segments: List<Segment> = emptyList(),
    /** Grille d'accords, commençant sur l'accord de tonique quand elle boucle. */
    val progression: Progression? = null,
)

/**
 * Estime la gamme d'un morceau à partir des chromas cumulés par [ChromaAnalyzer].
 *
 * C'est un outil d'accompagnement, pas un analyseur note à note. Deux moyennes glissantes,
 * deux rôles :
 *  - la moyenne LONGUE donne l'estimation du morceau, et surtout le MODE : parmi les gammes
 *    d'une famille qui partagent les mêmes notes, celle dont la tonique est la note la
 *    plus tenue par la LIGNE DE BASSE (confortée par sa quinte). La mélodie, plus variable,
 *    ne sert qu'à établir les notes de la gamme. Sur une fenêtre courte, ce choix suivrait
 *    les accords ;
 *  - la moyenne RAPIDE ne sert qu'à confirmer une MODULATION : un autre ensemble de notes,
 *    tenu pendant [HOLD_MS]. La moyenne longue, elle, mélange l'ancienne et la nouvelle
 *    gamme pendant la transition et ferait apparaître une gamme intermédiaire fictive.
 * Un silence prolongé marque un nouveau morceau et remet tout à zéro.
 *
 * Le choix de la famille (quelles notes) est nettement plus fiable que celui du mode
 * (quelle tonique), par nature. Sans dépendance Android ni horloge interne.
 */
class HarmonyDetector {

    companion object {
        private const val LONG_MS = 30_000.0
        private const val FAST_MS = 6_000.0
        /** Écoute minimale avant de proposer une gamme. */
        private const val WARMUP_MS = 8_000L
        /** Durée pendant laquelle un changement doit persister pour être retenu. */
        private const val HOLD_MS = 12_000L
        /** Avance minimale d'un nouvel ensemble de notes sur l'ensemble courant. */
        private const val SWITCH_MARGIN = 0.02
        /** Un changement de mode (mêmes notes, autre tonique) exige bien plus de constance. */
        private const val MODE_HOLD_MS = 30_000L
        /** … et une tonique au moins 20 % plus saillante que l'actuelle. */
        private const val MODE_LEAD = 1.2
        /** Silence qui marque la fin du morceau. */
        private const val TRACK_GAP_MS = 2_500L
        private const val MAX_SEGMENTS = 24
        /** En deçà, une gamme remplacée est tenue pour une erreur d'estimation. */
        private const val MIN_SEGMENT_MS = 25_000L

        /** Rasoir d'Occam : à score voisin, la famille la plus courante l'emporte. */
        private val FAMILY_PRIOR = doubleArrayOf(0.0, 0.0, -0.02, -0.02, -0.03, -0.045, -0.045, -0.045)

        private class Candidate(val mask: Int, val family: Int, val members: List<Detection>)

        /** Un candidat par ensemble de notes distinct (les modes d'une famille le partagent). */
        private val candidates: List<Candidate> = ScaleCatalog.scales
            .flatMap { scale -> (0 until 12).map { root -> Detection(scale, root) } }
            .groupBy { it.scale.pitchClassMask(it.root) }
            .map { (mask, members) -> Candidate(mask, members.first().scale.family, members) }
    }

    /** Moyenne glissante du chroma et du chroma des basses. */
    private class Average(private val timeConstantMs: Double) {
        val chroma = DoubleArray(12)
        val bass = DoubleArray(12)

        fun add(data: FloatArray, frames: Float, elapsedMs: Long) {
            val keep = exp(-elapsedMs / timeConstantMs)
            for (i in 0 until 12) {
                chroma[i] = chroma[i] * keep + data[i] / frames
                bass[i] = bass[i] * keep + data[12 + i] / frames
            }
        }

        fun copyFrom(other: Average) {
            other.chroma.copyInto(chroma)
            other.bass.copyInto(bass)
        }

        fun clear() {
            chroma.fill(0.0)
            bass.fill(0.0)
        }
    }

    private class Ranking(val best: Candidate, val bestScore: Double, val margin: Double, val scores: Map<Int, Double>)

    private val tracker = ProgressionTracker()
    private var progression: Progression? = null
    /** Part du temps passée sur chaque accord, dans la grille courante. */
    private var chordShares: Map<Chord, Double> = emptyMap()

    private val long = Average(LONG_MS)
    private val fast = Average(FAST_MS)
    private var lastUpdateMs = -1L
    private var pieceStartMs = -1L
    private var soundMs = 0L
    private var silenceMs = 0L

    private var current: Detection? = null
    private var pending: Detection? = null
    private var pendingSinceMs = 0L
    private var confidence = 0f
    private val segments = ArrayList<Segment>()

    /** Repart de zéro : nouveau morceau, ou bouton « rafraîchir ». */
    fun reset() {
        long.clear()
        fast.clear()
        tracker.clear()
        progression = null
        chordShares = emptyMap()
        lastUpdateMs = -1L
        pieceStartMs = -1L
        soundMs = 0L
        silenceMs = 0L
        current = null
        pending = null
        confidence = 0f
        segments.clear()
    }

    fun snapshot(listening: Boolean) =
        HarmonyState(listening, current, confidence, segments.toList(), current?.let { progression?.startingOn(it.root) })

    /**
     * Intègre un relevé de [ChromaAnalyzer.drain] : [0..11] chroma, [12..23] basses,
     * [24] trames sonores, [25] trames de silence.
     */
    fun update(data: FloatArray, nowMs: Long) {
        val elapsed = if (lastUpdateMs < 0) 0L else (nowMs - lastUpdateMs).coerceIn(0L, 5_000L)
        lastUpdateMs = nowMs
        // Trames d'accord : toutes, silences compris, pour que le temps reste régulier
        if (data.size > ChromaAnalyzer.HEADER_SIZE) {
            for (n in 0 until data[26].toInt()) {
                val from = ChromaAnalyzer.HEADER_SIZE + n * 12
                tracker.add(data.copyOfRange(from, from + 12), data[27])
            }
        }
        val frames = data[24]
        if (frames <= 0f) {
            if (data[25] > 0f) silenceMs += elapsed
            // Un blanc entre deux morceaux : l'analyse du suivant ne doit rien hériter
            if (silenceMs >= TRACK_GAP_MS && soundMs > 0L) reset()
            return
        }
        silenceMs = 0L
        if (pieceStartMs < 0) pieceStartMs = nowMs
        soundMs += elapsed
        long.add(data, frames, elapsed)
        fast.add(data, frames, elapsed)
        if (soundMs >= WARMUP_MS) decide(nowMs)
    }

    private fun decide(nowMs: Long) {
        val overall = rank(long) ?: return
        // La grille d'accords d'abord : c'est elle qui désigne la tonique
        progression = tracker.analyze(current?.let { it.scale.pitchClassMask(it.root) } ?: overall.best.mask)
        chordShares = progression?.shares().orEmpty()
        val held = current
        if (held == null) {
            confidence = confidenceOf(overall)
            adopt(pickMode(overall.best, long), nowMs)
            return
        }
        val heldMask = held.scale.pitchClassMask(held.root)

        val recent = rank(fast) ?: return
        val longLead = overall.bestScore - (overall.scores[heldMask] ?: 0.0)
        val fastLead = recent.bestScore - (recent.scores[heldMask] ?: 0.0)
        when {
            // 1) Mêmes notes : la moyenne longue peut faire glisser le mode (la tonique).
            overall.best.mask == heldMask && !(recent.best.mask != heldMask && fastLead >= SWITCH_MARGIN) -> {
                confidence = confidenceOf(overall)
                // Changer de mode sans changer de notes, c'est déplacer la tonique : or la basse
                // se promène avec les accords. On n'y croit que si la nouvelle tonique domine
                // nettement l'ancienne, elle et elle seule, pendant longtemps.
                val mode = pickMode(overall.best, long)
                val clearlyStronger = mode != held &&
                    salience(overall.best, long, mode.root) >= salience(overall.best, long, held.root) * MODE_LEAD
                if (clearlyStronger) propose(mode, nowMs, MODE_HOLD_MS, sameTonicOnly = true) {} else pending = null
            }
            // 2) Correction : la moyenne longue désigne d'autres notes et la moyenne rapide ne
            //    la contredit pas (elle dit pareil, ou hésite encore avec la gamme courante).
            //    C'est le cas d'une première estimation prise trop tôt.
            overall.best.mask != heldMask && longLead >= SWITCH_MARGIN / 2 &&
                (recent.best.mask == overall.best.mask || recent.best.mask == heldMask) ->
                propose(pickMode(overall.best, long), nowMs) { confidence = confidenceOf(overall) }
            // 3) Modulation : la moyenne rapide désigne un troisième ensemble de notes. La
            //    moyenne longue, qui mélange alors deux gammes, n'a plus voix au chapitre.
            recent.best.mask != heldMask && fastLead >= SWITCH_MARGIN ->
                propose(pickMode(recent.best, fast), nowMs) {
                    // Elle repart de la nouvelle gamme, sans traîner l'ancienne
                    long.copyFrom(fast)
                    confidence = confidenceOf(recent)
                }
            else -> pending = null
        }
    }

    /** Retient [candidate] s'il est proposé sans interruption pendant [holdMs]. */
    private inline fun propose(
        candidate: Detection, nowMs: Long, holdMs: Long = HOLD_MS, sameTonicOnly: Boolean = false,
        onAdopt: () -> Unit,
    ) {
        val sameNotes = pending?.let { it.scale.pitchClassMask(it.root) == candidate.scale.pitchClassMask(candidate.root) }
        if (pending == null || sameNotes != true || (sameTonicOnly && pending != candidate)) {
            pending = candidate
            pendingSinceMs = nowMs
        } else {
            // Le mode peut s'affiner pendant l'attente sans relancer le chronomètre
            pending = candidate
            if (nowMs - pendingSinceMs >= holdMs) {
                onAdopt()
                adopt(candidate, nowMs)
            }
        }
    }

    private fun rank(average: Average): Ranking? {
        // Le plancher commun aux 12 notes (bruit, percussions) ne dit rien de la gamme.
        // Racine carrée : une gamme se reconnaît à la PRÉSENCE de ses notes, pas à leur
        // poids ; sans elle, une tonique martelée à la basse écrase tout le reste et
        // deux gammes qui ne diffèrent que d'une note deviennent indiscernables.
        val floor = average.chroma.min()
        val c = DoubleArray(12) { sqrt(average.chroma[it] - floor) }
        val norm = sqrt(c.sumOf { it * it })
        if (norm <= 0.0) return null
        val scores = HashMap<Int, Double>(candidates.size)
        for (candidate in candidates) {
            var inside = 0.0
            var size = 0
            for (pc in 0 until 12) if (candidate.mask shr pc and 1 == 1) {
                inside += c[pc]
                size++
            }
            // Similarité cosinus avec le gabarit binaire de la gamme, plus l'a priori de famille
            scores[candidate.mask] = inside / (norm * sqrt(size.toDouble())) + FAMILY_PRIOR[candidate.family]
        }
        val ordered = candidates.sortedByDescending { scores.getValue(it.mask) }
        val bestScore = scores.getValue(ordered[0].mask)
        return Ranking(ordered[0], bestScore, bestScore - scores.getValue(ordered[1].mask), scores)
    }

    private fun confidenceOf(ranking: Ranking) = (ranking.margin * 8).coerceIn(0.0, 1.0).toFloat()

    /** Parmi les gammes de même ensemble de notes, celle dont la tonique ressort le plus. */
    private fun pickMode(candidate: Candidate, average: Average): Detection =
        candidate.members.maxBy { salience(candidate, average, it.root) }

    /**
     * Saillance d'une tonique. D'abord les ACCORDS : la part du temps que le morceau passe
     * sur la triade que ce mode construit sur sa tonique (mineure pour un dorien, majeure
     * pour un lydien…) — indice insensible au renversement, puisque le chroma ignore l'octave.
     * Puis la LIGNE DE BASSE, sur cette note et sur sa quinte : indispensable quand l'accord
     * est joué sans sa fondamentale, qu'il est alors seul à donner. La mélodie départage.
     */
    private fun salience(candidate: Candidate, average: Average, root: Int): Double {
        val floor = average.chroma.min()
        val cMax = (average.chroma.max() - floor).takeIf { it > 0 } ?: 1.0
        val bMax = average.bass.max().takeIf { it > 0 } ?: 1.0
        fun presence(pc: Int) = (average.chroma[pc] - floor) / cMax
        val fifth = (root + 7) % 12
        val hasFifth = candidate.mask shr fifth and 1 == 1
        val tonicChord = candidate.members.first { it.root == root }.scale.tonicTriad
        val chordTime = chordShares[Chord(root, tonicChord)] ?: 0.0
        val topChordTime = chordShares.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        return 0.45 * chordTime / topChordTime +
            0.30 * average.bass[root] / bMax +
            (if (hasFifth) 0.10 * average.bass[fifth] / bMax else 0.0) +
            0.15 * presence(root)
    }

    private fun adopt(detection: Detection, nowMs: Long) {
        current = detection
        pending = null
        val at = nowMs - pieceStartMs
        // Une gamme détrônée presque aussitôt était une erreur d'estimation, pas une séquence
        // du morceau : on la remplace au lieu d'allonger la liste.
        val last = segments.lastOrNull()
        if (last != null && at - last.startMs < MIN_SEGMENT_MS) {
            segments[segments.lastIndex] = Segment(detection, last.startMs)
            return
        }
        segments.add(Segment(detection, at))
        if (segments.size > MAX_SEGMENTS) segments.removeAt(0)
    }
}
