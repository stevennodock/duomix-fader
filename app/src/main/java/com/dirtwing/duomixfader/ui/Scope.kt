// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirtwing.duomixfader.R
import com.dirtwing.duomixfader.harmony.ScopeFrame
import kotlinx.coroutines.flow.StateFlow

private val SCREEN = Color(0xFF100F14)
private val GRID = Color(0x14FFFFFF)
private val LOW = Color(ScaleArt.PASTEL_RED)     // graves : le corail des pavés rouges
private val MID = Color(0xFFFFD27A)              // médiums : ambre
private val HIGH = Color(ScaleArt.PASTEL_BLUE)   // aigus : le bleu des pavés bleus

/**
 * Oscilloscope de l'écoute par le micro : trois couloirs — aigus, médiums, graves — sur un écran
 * sombre, chacun avec sa trace lumineuse. Les traces sont prises après le réglage de tonalité :
 * pousser « Graves » fait gonfler le couloir du bas. L'image est synchronisée (voir
 * ScopeSplitter) : la forme d'onde se pose au lieu de défiler.
 *
 * Le flux n'est lu que tant que l'écran est visible.
 */
@Composable
fun MicrophoneScope(frames: StateFlow<ScopeFrame?>, modifier: Modifier = Modifier) {
    val frame by frames.collectAsStateWithLifecycle()
    Box(
        modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SCREEN)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val lane = size.height / 3
            // Graticule : une ligne médiane par couloir, huit divisions verticales
            for (k in 0 until 3) drawLine(GRID, Offset(0f, lane * (k + 0.5f)), Offset(size.width, lane * (k + 0.5f)), 1f)
            for (k in 1 until 8) drawLine(GRID, Offset(size.width * k / 8, 0f), Offset(size.width * k / 8, size.height), 1f)
            frame?.let {
                trace(it.high, HIGH, lane * 0.5f, lane * 0.44f)
                trace(it.mid, MID, lane * 1.5f, lane * 0.44f)
                trace(it.low, LOW, lane * 2.5f, lane * 0.44f)
            }
        }
        // Le nom de chaque couloir, dans sa couleur
        Column(Modifier.fillMaxSize().padding(start = 8.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            for ((label, colour) in listOf(R.string.scope_high to HIGH, R.string.scope_mid to MID, R.string.scope_low to LOW)) {
                Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = colour.copy(alpha = 0.75f))
            }
        }
    }
}

/** Une trace : trois passes du même tracé, de la plus large et pâle à la plus fine et vive — le halo du phosphore. */
private fun DrawScope.trace(points: FloatArray, colour: Color, centre: Float, amplitude: Float) {
    if (points.size < 2) return
    val step = size.width / (points.size - 1)
    val path = Path().apply {
        moveTo(0f, centre - points[0] * amplitude)
        for (i in 1 until points.size) lineTo(i * step, centre - points[i] * amplitude)
    }
    for ((width, alpha) in listOf(9f to 0.10f, 4.5f to 0.28f, 1.8f to 1f)) {
        drawPath(path, colour.copy(alpha = alpha), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
