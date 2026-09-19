// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.harmony

/**
 * Une des 33 « Scales of Harmonies » d'Oliver Prehn (NewJazz, https://youtu.be/Vq2xt2D3e3E),
 * telle que décrite dans son tableau de synthèse : famille, nom usuel, pas d'intervalles,
 * nom systématique et accords. Les noms de gammes sont des termes musicaux internationaux,
 * ils ne sont pas traduits.
 */
class ScaleDef(
    val family: Int,
    val popularName: String,
    /** Pas en demi-tons : 1 = ½ ton, 2 = 1 ton, 3 = 1 ton ½. Leur somme vaut 12. */
    val semitones: IntArray,
    val systematicName: String,
    val chords: String,
) {
    /** Pas tels qu'écrits dans le tableau : « 1-1-½-1-1-1-½ ». */
    val intervalSteps: String = semitones.joinToString("-") { STEP_LABELS.getValue(it) }

    /** Degrés de la gamme en demi-tons depuis la tonique (0 inclus). */
    val degrees: IntArray = IntArray(semitones.size).also { d ->
        for (i in 1 until semitones.size) d[i] = d[i - 1] + semitones[i - 1]
    }

    /**
     * Triade que la gamme construit sur sa tonique — le début de la colonne « Chords » du
     * tableau (Ma7 -> majeure, mi7 et miMa7 -> mineure, mi7b5 et dim7 -> diminuée,
     * Ma7#5 et 6#5 -> augmentée). Déduite des degrés plutôt que lue dans le texte.
     */
    val tonicTriad: Triad = run {
        val has = { semitone: Int -> semitone in degrees }
        when {
            has(4) && has(7) -> Triad.MAJOR
            has(3) && has(7) -> Triad.MINOR
            has(3) && has(6) -> Triad.DIMINISHED
            has(4) && has(8) -> Triad.AUGMENTED
            has(4) -> Triad.MAJOR
            else -> Triad.MINOR
        }
    }

    /** Ensemble des classes de hauteur pour une tonique donnée, en masque de 12 bits. */
    fun pitchClassMask(root: Int): Int = degrees.fold(0) { mask, d -> mask or (1 shl ((root + d) % 12)) }

    private companion object {
        val STEP_LABELS = mapOf(1 to "½", 2 to "1", 3 to "1½")
    }
}

object ScaleCatalog {

    /** Les 33 gammes, dans l'ordre du tableau. */
    val scales: List<ScaleDef> = listOf(
        s(1, "Major", "2212221", "Ionian", "Ma7"),
        s(1, "Dorian", "2122212", "Dorian", "mi7"),
        s(1, "Phrygian", "1222122", "Phrygian", "mi7"),
        s(1, "Lydian", "2221221", "Lydian", "Ma7"),
        s(1, "Mixolydian", "2212212", "Mixolydian", "7"),
        s(1, "Natural minor", "2122122", "Aeolian", "mi7"),
        s(1, "Locrian", "1221222", "Locrian", "mi7b5"),

        s(2, "Altered / Super Locrian", "1212222", "Ionian #1", "7alt / mi7b5"),
        s(2, "Ascending mel. minor", "2122221", "Dorian #7", "miMa7"),
        s(2, "Dorian b2", "1222212", "Phrygian #6", "mi7"),
        s(2, "Lydian Augmented", "2222121", "Lydian #5", "Ma7#5"),
        s(2, "Lydian dominant", "2221212", "Mixolydian #4", "7"),
        s(2, "Aeolian dominant", "2212122", "Aeolian #3", "7"),
        s(2, "Half diminished", "2121222", "Locrian #2", "mi7b5"),

        s(3, "Major #5 / Major Aug.", "2213121", "Ionian #5", "Ma7#5"),
        s(3, "Dorian #4", "2131212", "Dorian #4", "mi7"),
        s(3, "Phrygian dominant", "1312122", "Phrygian #3", "7"),
        s(3, "Lydian #2", "3121221", "Lydian #2", "Ma7"),
        s(3, "Altered dominant bb7", "1212213", "Mixolydian #1", "dim7"),
        s(3, "Harmonic minor", "2122131", "Aeolian #7", "miMa7"),
        s(3, "Locrian ♮6", "1221312", "Locrian #6", "mi7b5"),

        s(4, "Harmonic Major", "2212131", "Ionian b6", "Ma7"),
        s(4, "Dorian b5", "2121312", "Dorian b5", "mi7b5"),
        s(4, "Phrygian b4", "1213122", "Phrygian b4", "mi7 / 7"),
        s(4, "Lydian b3", "2131221", "Lydian b3", "miMa7"),
        s(4, "Mixolydian b2", "1312212", "Mixolydian b2", "7"),
        s(4, "Lydian augmented #2", "3122121", "Aeolian b1", "Ma7#5 / dim7"),
        s(4, "Locrian bb7", "1221213", "Locrian b7", "dim7"),

        s(5, "Diminished", "21212121", "Diminished", "dim7"),
        s(5, "Dominant diminished", "12121212", "Inverted diminished", "7"),

        s(6, "Whole tone", "222222", "Whole tone", "7 #5/b5"),

        s(7, "Augmented", "313131", "Augmented", "Ma7"),
        s(7, "Inverted Augmented", "131313", "Inverted Augmented", "6#5"),
    )

    private fun s(family: Int, popular: String, steps: String, systematic: String, chords: String) =
        ScaleDef(family, popular, IntArray(steps.length) { steps[it] - '0' }, systematic, chords)
}

/** Nom des 12 toniques, en notation anglo-saxonne et en solfège, altérations usuelles. */
object NoteNames {
    private val letters = arrayOf("C", "D♭", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    private val solfege = arrayOf("Do", "Ré♭", "Ré", "Mi♭", "Mi", "Fa", "Fa♯", "Sol", "La♭", "La", "Si♭", "Si")

    fun letter(pitchClass: Int): String = letters[pitchClass.mod(12)]
    fun solfege(pitchClass: Int): String = solfege[pitchClass.mod(12)]

    /** « C (Do) » : les deux systèmes, comme demandé pour le panneau détaillé. */
    fun both(pitchClass: Int): String = "${letter(pitchClass)} (${solfege(pitchClass)})"
}
