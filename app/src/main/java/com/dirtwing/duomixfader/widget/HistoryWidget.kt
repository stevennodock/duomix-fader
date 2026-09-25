// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dirtwing.duomixfader.MainActivity
import com.dirtwing.duomixfader.R
import com.dirtwing.duomixfader.harmony.Chord
import com.dirtwing.duomixfader.harmony.HarmonyHistory
import com.dirtwing.duomixfader.harmony.TrackRecord
import com.dirtwing.duomixfader.harmony.romanNumeral
import com.dirtwing.duomixfader.ui.ScaleArt
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Le système demande le widget (pose, redémarrage, changement de taille) : on le redessine. */
class HistoryWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = HistoryWidget.refresh(context)
}

/**
 * Widget « Historique des détections » : la liste défilante des morceaux, avec pour chacun les
 * trois étages de l'écran Historique — gamme principale et ses pavés, gammes tenues tour à
 * tour en colonnes alignées, grille d'accords en pied. Un appui ouvre l'historique de l'app.
 *
 * Aucune mise à jour périodique : le service du fader pousse le contenu quand il change. Le
 * widget ne montre que des noms de morceaux, de gammes et d'accords, comme l'historique.
 */
object HistoryWidget {

    /** Les vues distantes ont une taille limitée : on s'en tient aux morceaux les plus récents. */
    private const val MAX_TRACKS = 20

    // Dernier contenu poussé par le service : le système peut redemander le widget entre deux
    // poussées, et le fichier d'historique ne contient pas le morceau en cours.
    @Volatile private var lastLive: TrackRecord? = null
    @Volatile private var lastHistory: List<TrackRecord>? = null

    /** Poussée par le service du fader. [live] est le morceau en cours d'écoute, s'il y en a un. */
    fun push(context: Context, live: TrackRecord?, history: List<TrackRecord>) {
        lastLive = live
        lastHistory = history
        refresh(context)
    }

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, HistoryWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val history = lastHistory ?: HarmonyHistory(File(context.filesDir, "harmony_history.json")).load()
        val live = lastLive?.takeIf { it.isWorthKeeping }
        val records = (listOfNotNull(live) + history).filter { it.detection != null }.take(MAX_TRACKS)

        val openHistory = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_HISTORY, true),
            // Modifiable : c'est le gabarit que complètent les éléments de la liste
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        for (record in records) items.addItem(record.startedAt, item(context, record, isLive = record === live))

        val views = RemoteViews(context.packageName, R.layout.widget_history).apply {
            setRemoteAdapter(R.id.widget_list, items.build())
            setEmptyView(R.id.widget_list, R.id.widget_empty)
            setPendingIntentTemplate(R.id.widget_list, openHistory)
            setOnClickPendingIntent(R.id.widget_title, openHistory)
            setOnClickPendingIntent(R.id.widget_empty, openHistory)
        }
        manager.updateAppWidget(ids, views)
    }

    private fun item(context: Context, record: TrackRecord, isLive: Boolean): RemoteViews {
        val notes = context.resources.getStringArray(R.array.notes_primary)
        val main = record.detection!!   // isWorthKeeping : seuls les morceaux analysés sont listés
        return RemoteViews(context.packageName, R.layout.widget_history_item).apply {
            setTextViewText(R.id.item_title, trackLabel(context, record))
            setTextViewText(
                R.id.item_meta,
                listOfNotNull(
                    record.source.takeIf { it.isNotBlank() },
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.startedAt)),
                    context.getString(R.string.harmony_now).takeIf { isLive },
                ).joinToString(" · "),
            )
            setTextViewText(R.id.item_tonic, notes[main.root])
            setTextViewText(R.id.item_scale, "${main.scale.popularName}  ·  F${main.scale.family}")
            setImageViewBitmap(R.id.item_tiles, ScaleArt.tileRow(main, tile = 36f))

            // Une seule gamme : la ligne du haut dit déjà tout
            if (record.segments.size > 1) {
                for (segment in record.segments) {
                    val detection = segment.detection
                    val seconds = segment.startMs / 1000
                    addView(R.id.item_segments, RemoteViews(context.packageName, R.layout.widget_history_segment).apply {
                        setTextViewText(R.id.seg_time, "%d:%02d".format(seconds / 60, seconds % 60))
                        setTextViewText(R.id.seg_degree, romanNumeral(Chord(detection.root, detection.scale.tonicTriad), main.root))
                        setTextViewText(R.id.seg_tonic, notes[detection.root])
                        setImageViewBitmap(R.id.seg_tiles, ScaleArt.tileRow(detection, tile = 26f))
                        setTextViewText(R.id.seg_name, detection.scale.popularName)
                    })
                }
            }

            val progression = record.progression?.takeIf { it.chords.isNotEmpty() }
            setTextViewText(
                R.id.item_progression,
                progression?.let { found ->
                    found.chords.joinToString("  –  ") { romanNumeral(it.chord, main.root) } + "    ·  " +
                        (found.cycleMs?.let { context.getString(R.string.harmony_cycle, ((it + 500) / 1000).toInt()) }
                            ?: context.getString(R.string.harmony_no_cycle_short))
                } ?: "",
            )
            setOnClickFillInIntent(R.id.item_root, Intent())
        }
    }

    private fun trackLabel(context: Context, record: TrackRecord): String = with(record.track) {
        when {
            !isKnown -> context.getString(R.string.harmony_unknown_track)
            artist.isBlank() -> title
            title.isBlank() -> artist
            else -> "$artist — $title"
        }
    }
}
