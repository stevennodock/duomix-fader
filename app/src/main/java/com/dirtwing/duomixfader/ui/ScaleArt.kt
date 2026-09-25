// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import com.dirtwing.duomixfader.harmony.Detection
import com.dirtwing.duomixfader.harmony.NoteNames

/**
 * Le code couleur des gammes. Bleu et rouge sont les deux gammes par tons du clavier
 * (do, ré, mi, fa♯, sol♯, la♯ / do♯, ré♯, fa, sol, la, si) : un ton reste dans la couleur,
 * un demi-ton ou un ton et demi passe dans l'autre. La couleur est ABSOLUE — celle de la note
 * réellement jouée, tonique comprise : la mineur donne R R B B B R R R, do majeur B B B R R R R B.
 *
 * Une gamme se lit comme une suite de pavés, un par degré, de la tonique à son octave. Pour
 * l'accompagnateur, cette succession de deux couleurs est l'empreinte du mode.
 */
object ScaleArt {

    const val PASTEL_BLUE = 0xFF9CC3FF.toInt()
    const val PASTEL_RED = 0xFFFF9E94.toInt()

    private fun isBlue(pitchClass: Int) = pitchClass.mod(12) % 2 == 0

    /** Couleur de chaque pavé : vrai = bleu, faux = rouge. Huit pavés pour une gamme de sept notes. */
    fun tiles(detection: Detection): List<Boolean> =
        (detection.scale.degrees.toList() + 12).map { isBlue(detection.root + it) }

    /** La même succession en lettres, pour les tests et les journaux : « RRBBBRRR ». */
    fun colourCode(detection: Detection): String = tiles(detection).joinToString("") { if (it) "B" else "R" }

    /**
     * Vrai pour chaque pavé où l'on arrive par un TON ET DEMI. Un changement de couleur dit « pas
     * impair », sans dire lequel ; or le demi-ton et le ton et demi se jouent tout autrement. Ces
     * pavés sont donc coupés HORIZONTALEMENT : moitié haute de la couleur d'où l'on vient, moitié
     * basse de celle où l'on arrive. Choix de l'utilisateur (2026-09-25) après essais d'un rond et
     * d'une diagonale ; le même partout, carte de notification comprise, où seul ce découpage se
     * dessine proprement. Seules les familles 3, 4 et 7 en contiennent.
     */
    fun leaps(detection: Detection): List<Boolean> {
        val positions = detection.scale.degrees.toList() + 12
        return positions.indices.map { i -> i > 0 && positions[i] - positions[i - 1] == 3 }
    }

    /** Couleurs et sauts en lettres, pour les tests : un « o » suit le pavé atteint par un ton et demi. */
    fun tileCode(detection: Detection): String =
        tiles(detection).zip(leaps(detection)).joinToString("") { (blue, leap) -> (if (blue) "B" else "R") + if (leap) "o" else "" }

    /**
     * « A ██ ██ ██ … » pour la carte de notification : la tonalité en notation anglaise, puis
     * les pavés. Un seul système de notation : avec les deux, « Ré♭ (D♭) » allongeait la ligne
     * au point de décaler le dernier pavé.
     *
     * Des pavés « █ » et non des ronds : vérifié sur Pixel, la carte conserve la couleur du
     * texte mais ignore tout agrandissement, et « ⬤ » n'y est pas plus gros que « ● » (33 px).
     * Le pavé plein occupe toute la hauteur de la ligne : c'est le plus gros signe coloré
     * qu'on puisse y mettre.
     */
    fun pastilles(detection: Detection): CharSequence =
        SpannableStringBuilder(NoteNames.letter(detection.root) + "  ").append(tilesText(detection))

    /**
     * Vrai là où la ligne de texte de la carte multimédia est étroite : la carte compacte
     * d'Android 12, où l'illustration est une vignette à gauche du texte au lieu d'un fond.
     * Constaté sur OnePlus 7 Pro : la ligne de texte n'y montre qu'un ou deux pavés, quelle que
     * soit leur forme, et une grille dans la vignette ne se lit pas comme une suite. Sur ces
     * versions, la carte de lecteur laisse donc la place à une notification dessinée par nous
     * (voir [tileRow]).
     *
     * Surtout pas de critère sur la largeur de l'écran en dp : le texte grossit avec elle, la
     * place relative ne change donc pas — et un Pixel réglé en affichage agrandi (353 dp)
     * passait à tort aux pavés étroits.
     */
    fun isCompactCard(sdkInt: Int): Boolean = sdkInt < 33

    /** Les pavés seuls, en texte coloré : pour la carte de notification et pour le widget. */
    fun tilesText(detection: Detection): CharSequence {
        val text = SpannableStringBuilder()
        val colours = tiles(detection)
        val leaps = leaps(detection)
        // Deux pavés par note ; un et demi pour les gammes de huit notes, sinon la ligne déborde
        val tile = if (colours.size <= 8) "██" else "█▌"
        colours.forEachIndexed { index, blue ->
            val start = text.length
            val own = if (blue) PASTEL_BLUE else PASTEL_RED
            val other = if (blue) PASTEL_RED else PASTEL_BLUE
            if (leaps[index]) {
                // Ton et demi : moitié haute de la couleur d'où l'on vient (le FOND du texte), moitié
                // basse de celle où l'on arrive (« ▄▄ »). La carte du Pixel garde la couleur de fond,
                // mais refuse les images, et aucun caractère n'y trace une diagonale sur un pavé
                // entier : essayés (triangles, escalier, rond) et jugés illisibles par l'utilisateur.
                text.append("\u2584\u2584")
                text.setSpan(BackgroundColorSpan(other), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(own), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (index < colours.lastIndex) text.append(' ')
                return@forEachIndexed
            }
            text.append(tile)
            text.setSpan(ForegroundColorSpan(own), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (index < colours.lastIndex) text.append(' ') // espace fine : un joint entre deux pavés
        }
        return text
    }

    /**
     * La rangée de pavés en image, pour la notification d'Android 12 (voir
     * MixerNotificationService.compactNotification) : de vrais carrés, en vraies couleurs, de la
     * tonique à son octave, sur fond transparent. La carte de lecteur de cette version ne sait
     * pas les montrer — sa ligne de texte n'en laisse passer que deux, et son illustration est
     * une vignette carrée où une suite ne se lit pas.
     */
    fun tileRow(detection: Detection, tile: Float = 72f): Bitmap {
        val colours = tiles(detection)
        val gap = tile * 0.14f
        val radius = tile * 0.14f
        val bitmap = Bitmap.createBitmap((colours.size * tile + (colours.size - 1) * gap).toInt(), tile.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val leaps = leaps(detection)
        colours.forEachIndexed { index, blue ->
            val left = index * (tile + gap)
            paint.color = if (blue) PASTEL_BLUE else PASTEL_RED
            canvas.drawRoundRect(left, 0f, left + tile, tile, radius, radius, paint)
            if (leaps[index]) {
                // Moitié haute : la couleur d'où l'on vient
                paint.color = if (blue) PASTEL_RED else PASTEL_BLUE
                val half = android.graphics.Path().apply { addRect(left, 0f, left + tile, tile / 2, android.graphics.Path.Direction.CW) }
                canvas.save()
                canvas.clipPath(android.graphics.Path().apply {
                    addRoundRect(left, 0f, left + tile, tile, radius, radius, android.graphics.Path.Direction.CW)
                })
                canvas.drawPath(half, paint)
                canvas.restore()
            }
        }
        return bitmap
    }
    /**
     * Illustration de la carte multimédia : ce qui est secondaire, en petit. Les flux et leur
     * balance à droite de l'icône de l'app ; dessous, l'artiste puis le titre.
     *
     * Une carte multimédia n'offre que deux lignes de texte — réservées à l'essentiel, le nom
     * du mode et les pavés de couleur ; l'illustration est le seul autre endroit où écrire.
     * Mesuré sur la carte d'un Pixel (982 x 564 px) : le système recadre l'image au format de
     * la carte, l'assombrit (~35 % de luminosité) et la recouvre de son texte (milieu), des
     * commandes (bas), de l'icône de l'app (haut gauche) et de la sortie audio (haut droit).
     * Sous les pavés, avant les commandes, une bande d'environ 80 px reste libre : la grille
     * d'accords en chiffres romains y tient sur une ligne, en plus gros pour compenser
     * l'assombrissement.
     * Un texte trop long est coupé avec « … » : rien ne défile ni ne clignote sur cette carte.
     */
    fun artwork(streams: String, artist: String?, title: String?, progression: String?): Bitmap {
        val width = 1200
        val height = 690                       // format de la carte : aucun recadrage
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(0xFF1B1A19.toInt())
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.LEFT

        fun line(text: String, x: Float, baseline: Float, size: Float, room: Float, bold: Boolean) {
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            var shown = text
            while (shown.length > 4 && paint.measureText(shown) > room) shown = shown.dropLast(2).trimEnd() + "…"
            canvas.drawText(shown, x, baseline, paint)
        }

        // Entre l'icône de l'app et la pastille de sortie audio
        line(streams, 205f, 112f, 44f, 535f, bold = true)
        // Bande libre sous la rangée du haut, sur toute la largeur : deux lignes
        artist?.takeIf { it.isNotBlank() }?.let { line(it, 62f, 205f, 46f, width - 124f, bold = true) }
        title?.takeIf { it.isNotBlank() }?.let { line(it, 62f, 262f, 46f, width - 124f, bold = false) }
        // Bande libre entre les pavés et les commandes (y 375 à 455 sur la carte de 564 px)
        progression?.takeIf { it.isNotBlank() }?.let { line(it, 62f, 528f, 58f, width - 124f, bold = true) }
        return bitmap
    }
}
