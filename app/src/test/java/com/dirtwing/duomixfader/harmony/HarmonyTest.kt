// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.harmony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class HarmonyTest {

    /** Fréquence réelle de la capture sur l'appareil. */
    private val sampleRate = 16_000

    // ------------------------------------------------------------------
    // Catalogue : fidélité au tableau d'Oliver Prehn
    // ------------------------------------------------------------------

    @Test
    fun catalogHas33ScalesIn7Families() {
        assertEquals(33, ScaleCatalog.scales.size)
        val perFamily = ScaleCatalog.scales.groupingBy { it.family }.eachCount()
        assertEquals(mapOf(1 to 7, 2 to 7, 3 to 7, 4 to 7, 5 to 2, 6 to 1, 7 to 2), perFamily)
    }

    @Test
    fun everyScaleSpansAnOctaveAndObeysPrehnRules() {
        for (scale in ScaleCatalog.scales) {
            val steps = scale.semitones
            assertEquals(scale.popularName, 12, steps.sum())
            for (i in steps.indices) {
                val a = steps[i]
                val b = steps[(i + 1) % steps.size]
                assertTrue("${scale.popularName} : demi-tons voisins", !(a == 1 && b == 1))
                assertTrue("${scale.popularName} : 1 et 1½ voisins", a + b != 5)
                assertTrue("${scale.popularName} : 1½ voisins", !(a == 3 && b == 3))
            }
        }
    }

    @Test
    fun scalesOfAFamilyAreRotationsOfOnePattern() {
        fun canonical(steps: IntArray): String =
            steps.indices.minOf { r -> steps.indices.joinToString("") { steps[(it + r) % steps.size].toString() } }
        for ((family, scales) in ScaleCatalog.scales.groupBy { it.family }) {
            assertEquals("famille $family", 1, scales.map { canonical(it.semitones) }.distinct().size)
        }
    }

    @Test
    fun theColourCodeOfAScaleFollowsTheTwoWholeToneSets() {
        fun code(name: String, root: Int) =
            com.dirtwing.duomixfader.ui.ScaleArt.colourCode(Detection(ScaleCatalog.scales.first { it.popularName == name }, root))
        // Exemple de référence : la mineur = la si | do ré mi | fa sol la
        assertEquals("RRBBBRRR", code("Natural minor", 9))
        assertEquals("do majeur : do ré mi | fa sol la si | do", "BBBRRRRB", code("Major", 0))
        assertEquals("gamme par tons : une seule couleur", "BBBBBBB", code("Whole tone", 0))
    }

    @Test
    fun intervalStepsAreWrittenAsInTheTable() {
        assertEquals("1-1-½-1-1-1-½", ScaleCatalog.scales.first { it.popularName == "Major" }.intervalSteps)
        assertEquals("1-½-1-1-½-1½-½", ScaleCatalog.scales.first { it.popularName == "Harmonic minor" }.intervalSteps)
    }

    // ------------------------------------------------------------------
    // Synthèse d'un signal de test
    // ------------------------------------------------------------------

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** Note avec 4 harmoniques décroissants, comme un instrument réel. */
    private fun note(midi: Int, seconds: Double, gain: Double = 0.2): FloatArray {
        val f = hz(midi)
        return FloatArray((seconds * sampleRate).toInt()) { i ->
            val t = i.toDouble() / sampleRate
            (gain * (1..4).sumOf { h -> sin(2 * PI * f * h * t) / (h * h) }).toFloat()
        }
    }

    private fun mix(vararg parts: FloatArray) =
        FloatArray(parts.minOf { it.size }) { i -> parts.sumOf { it[i].toDouble() }.toFloat() }

    /**
     * Joue les degrés de la gamme en boucle sur une basse tenue à la tonique, et fait
     * tourner la chaîne complète analyseur -> détecteur, relevée une fois par seconde.
     */
    private fun play(
        scale: ScaleDef, root: Int, seconds: Int,
        analyzer: ChromaAnalyzer, detector: HarmonyDetector, startMs: Long = 0L,
    ): Long {
        var now = startMs
        val degrees = scale.degrees
        for (s in 0 until seconds) {
            val melody = note(60 + root + degrees[s % degrees.size], 1.0)
            val third = note(60 + root + degrees[(s + 2) % degrees.size], 1.0, gain = 0.12)
            val bassNote = note(36 + root, 1.0, gain = 0.25)
            val second = mix(melody, third, bassNote)
            analyzer.push(second, second.size)
            now += 1_000
            detector.update(analyzer.drain(), now)
        }
        return now
    }

    private fun scale(name: String) = ScaleCatalog.scales.first { it.popularName == name }

    // ------------------------------------------------------------------
    // Analyse spectrale
    // ------------------------------------------------------------------

    @Test
    fun chromaOfASingleNotePeaksOnItsPitchClass() {
        val analyzer = ChromaAnalyzer(sampleRate)
        val a3 = note(57, 2.0)
        analyzer.push(a3, a3.size)
        val out = analyzer.drain()
        assertTrue("des trames ont été analysées", out[24] > 0f)
        assertEquals("la (9) domine le chroma", 9, (0 until 12).maxBy { out[it] })
        assertEquals("la (9) domine les basses", 9, (0 until 12).maxBy { out[12 + it] })
    }

    @Test
    fun silenceIsReportedAsSilence() {
        val analyzer = ChromaAnalyzer(sampleRate)
        analyzer.push(FloatArray(sampleRate * 2), sampleRate * 2)
        val out = analyzer.drain()
        assertEquals(0f, out[24])
        assertTrue(out[25] > 0f)
    }

    // ------------------------------------------------------------------
    // Détection de bout en bout
    // ------------------------------------------------------------------

    private fun assertDetects(name: String, root: Int) {
        val detector = HarmonyDetector()
        play(scale(name), root, 30, ChromaAnalyzer(sampleRate), detector)
        val found = detector.snapshot(true).current
        assertNotNull("$name : rien détecté", found)
        assertEquals("$name : famille", scale(name).family, found!!.scale.family)
        assertEquals("$name : mode", name, found.scale.popularName)
        assertEquals("$name : tonique", root, found.root)
    }

    @Test fun detectsCMajor() = assertDetects("Major", 0)
    @Test fun detectsDDorian() = assertDetects("Dorian", 2)
    @Test fun detectsAHarmonicMinor() = assertDetects("Harmonic minor", 9)
    @Test fun detectsFLydianDominant() = assertDetects("Lydian dominant", 5)
    @Test fun detectsEPhrygianDominant() = assertDetects("Phrygian dominant", 4)
    @Test fun detectsGHarmonicMajor() = assertDetects("Harmonic Major", 7)
    @Test fun detectsWholeTone() = assertDetects("Whole tone", 0)

    // ------------------------------------------------------------------
    // La tonalité se lit sur la ligne de basse, pas sur la mélodie
    // ------------------------------------------------------------------

    /** Joue une mélodie et une ligne de basse données, note par note, une seconde chacune. */
    private fun playLines(melodyMidi: List<Int>, bassMidi: List<Int>, seconds: Int): HarmonyDetector {
        val analyzer = ChromaAnalyzer(sampleRate)
        val detector = HarmonyDetector()
        var now = 0L
        for (s in 0 until seconds) {
            val second = mix(
                note(melodyMidi[s % melodyMidi.size], 1.0),
                note(bassMidi[s % bassMidi.size], 1.0, gain = 0.25),
            )
            analyzer.push(second, second.size)
            now += 1_000
            detector.update(analyzer.drain(), now)
        }
        return detector
    }

    @Test
    fun theTonicComesFromTheBassLineEvenWhenTheMelodyInsistsElsewhere() {
        // Notes de do majeur ; la mélodie martèle mi (une note sur deux), la basse tient la.
        val melody = listOf(64, 62, 64, 65, 64, 67, 64, 69, 64, 71, 64, 72)
        val bassLine = listOf(45, 45, 45, 45)
        val found = playLines(melody, bassLine, 36).snapshot(true).current!!
        assertEquals("Natural minor", found.scale.popularName)
        assertEquals("la", 9, found.root)
    }

    @Test
    fun aWalkingBassStillPointsToTheTonic() {
        // Basse : tonique deux temps sur quatre, puis quinte et quarte (sol : sol, ré, do).
        val melody = listOf(67, 69, 71, 72, 74, 76, 78)
        val bassLine = listOf(43, 43, 50, 48)
        val found = playLines(melody, bassLine, 40).snapshot(true).current!!
        assertEquals("Major", found.scale.popularName)
        assertEquals("sol", 7, found.root)
    }
    // ------------------------------------------------------------------
    // Grille d'accords : cycle, renversements, chiffrage
    // ------------------------------------------------------------------

    /** Joue une suite d'accords (listes de notes MIDI), [secondsEach] secondes chacun, en boucle. */
    private fun playChords(voicings: List<List<Int>>, secondsEach: Int, totalSeconds: Int): HarmonyDetector {
        val analyzer = ChromaAnalyzer(sampleRate)
        val detector = HarmonyDetector()
        var now = 0L
        for (s in 0 until totalSeconds) {
            val voicing = voicings[(s / secondsEach) % voicings.size]
            val second = mix(*voicing.map { note(it, 1.0, gain = 0.15) }.toTypedArray())
            analyzer.push(second, second.size)
            now += 1_000
            detector.update(analyzer.drain(), now)
        }
        return detector
    }

    private fun numerals(state: HarmonyState) =
        state.progression!!.chords.map { romanNumeral(it.chord, state.current!!.root) }

    @Test
    fun romanNumeralsFollowTheMajorScaleOfTheTonic() {
        assertEquals("vi", romanNumeral(Chord(9, Triad.MINOR), tonic = 0))
        assertEquals("♭VI", romanNumeral(Chord(5, Triad.MAJOR), tonic = 9))
        assertEquals("vii°", romanNumeral(Chord(11, Triad.DIMINISHED), tonic = 0))
        assertEquals("♭III+", romanNumeral(Chord(3, Triad.AUGMENTED), tonic = 0))
    }

    @Test
    fun invertedChordsStillGiveTheTonicAndTheProgression() {
        // I - IV - V - I en do, TOUS en premier renversement : la note grave est la tierce
        // (mi, la, si, mi), jamais la fondamentale. La basse seule désignerait mi.
        val firstInversions = listOf(
            listOf(40, 64, 67, 72), // do majeur / mi
            listOf(45, 69, 72, 77), // fa majeur / la
            listOf(47, 71, 74, 79), // sol majeur / si
            listOf(40, 64, 67, 72), // do majeur / mi
        )
        val state = playChords(firstInversions, secondsEach = 2, totalSeconds = 48).snapshot(true)
        assertEquals("Major", state.current!!.scale.popularName)
        assertEquals("do, malgré une basse sur mi", 0, state.current!!.root)
        val cycle = state.progression!!.cycleMs
        assertNotNull("un cycle est détecté", cycle)
        assertTrue("cycle de 8 s, trouvé $cycle ms", cycle!! in 7_500..8_500)
        assertEquals(listOf("I", "IV", "V"), numerals(state))
    }

    @Test
    fun aMinorLoopIsFoldedIntoItsFourChords() {
        // la mineur - fa - do - sol, positions fondamentales, 2 s par accord
        val loop = listOf(
            listOf(45, 57, 60, 64), listOf(41, 57, 60, 65),
            listOf(48, 60, 64, 67), listOf(43, 59, 62, 67),
        )
        val state = playChords(loop, secondsEach = 2, totalSeconds = 48).snapshot(true)
        assertEquals(1, state.current!!.scale.family)
        assertTrue("cycle de 8 s", state.progression!!.cycleMs!! in 7_500..8_500)
        assertEquals(
            setOf(Chord(9, Triad.MINOR), Chord(5, Triad.MAJOR), Chord(0, Triad.MAJOR), Chord(7, Triad.MAJOR)),
            state.progression!!.chords.map { it.chord }.toSet(),
        )
    }

    @Test
    fun withoutALoopTheFallbackWindowIsUsed() {
        // Un seul accord tenu : rien ne se répète, il n'y a pas de cycle à trouver
        val state = playChords(listOf(listOf(48, 60, 64, 67)), secondsEach = 2, totalSeconds = 30).snapshot(true)
        assertNull("pas de cycle", state.progression!!.cycleMs)
        assertEquals(listOf(Chord(0, Triad.MAJOR)), state.progression!!.chords.map { it.chord })
    }
    @Test
    fun nothingIsProposedBeforeTheWarmup() {
        val detector = HarmonyDetector()
        play(scale("Major"), 0, 4, ChromaAnalyzer(sampleRate), detector)
        assertNull(detector.snapshot(true).current)
    }

    @Test
    fun aBriefExcursionDoesNotChangeTheDisplayedScale() {
        val analyzer = ChromaAnalyzer(sampleRate)
        val detector = HarmonyDetector()
        var now = play(scale("Major"), 0, 40, analyzer, detector)
        now = play(scale("Whole tone"), 1, 4, analyzer, detector, now)
        play(scale("Major"), 0, 6, analyzer, detector, now)
        val state = detector.snapshot(true)
        assertEquals("Major", state.current!!.scale.popularName)
        assertEquals("une seule séquence retenue", 1, state.segments.size)
    }

    @Test
    fun aLastingModulationAddsASegment() {
        val analyzer = ChromaAnalyzer(sampleRate)
        val detector = HarmonyDetector()
        val now = play(scale("Major"), 0, 40, analyzer, detector)
        play(scale("Harmonic minor"), 4, 90, analyzer, detector, now)
        val state = detector.snapshot(true)
        assertEquals("Harmonic minor", state.current!!.scale.popularName)
        assertEquals(4, state.current!!.root)
        assertEquals(2, state.segments.size)
    }

    @Test
    fun aLongSilenceStartsANewPiece() {
        val analyzer = ChromaAnalyzer(sampleRate)
        val detector = HarmonyDetector()
        var now = play(scale("Major"), 0, 30, analyzer, detector)
        repeat(5) {
            analyzer.push(FloatArray(sampleRate), sampleRate)
            now += 1_000
            detector.update(analyzer.drain(), now)
        }
        val state = detector.snapshot(true)
        assertNull(state.current)
        assertTrue(state.segments.isEmpty())
    }

    // ------------------------------------------------------------------
    // Écoute par le micro : réglage de tonalité
    // ------------------------------------------------------------------

    /** Gain, en décibels, qu'un [filter] applique à une sinusoïde de [hz] (régime établi). */
    private fun gainDb(filter: ToneFilter, hz: Double): Double {
        val samples = FloatArray(sampleRate) { (0.5 * sin(2 * PI * hz * it / sampleRate)).toFloat() }
        val before = samples.drop(sampleRate / 2).sumOf { (it * it).toDouble() }
        filter.process(samples, samples.size)
        val after = samples.drop(sampleRate / 2).sumOf { (it * it).toDouble() }
        return 10 * kotlin.math.log10(after / before)
    }

    @Test
    fun aFlatToneFilterLeavesTheSignalUntouched() {
        val filter = ToneFilter(sampleRate).apply { set(0f, 0f) }
        assertEquals(0.0, gainDb(filter, 110.0), 0.01)
        assertEquals(0.0, gainDb(filter, 1_760.0), 0.01)
    }

    @Test
    fun theBassShelfLiftsTheLowNotesAndSparesTheHighOnes() {
        assertEquals(12.0, gainDb(ToneFilter(sampleRate).apply { set(12f, 0f) }, 55.0), 1.0)
        assertEquals(0.0, gainDb(ToneFilter(sampleRate).apply { set(12f, 0f) }, 1_760.0), 1.0)
    }

    @Test
    fun theTrebleShelfCutsTheHighNotesAndSparesTheLowOnes() {
        assertEquals(-12.0, gainDb(ToneFilter(sampleRate).apply { set(0f, -12f) }, 4_000.0), 1.0)
        assertEquals(0.0, gainDb(ToneFilter(sampleRate).apply { set(0f, -12f) }, 110.0), 1.0)
    }
}
