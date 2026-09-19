// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.harmony

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Morceau tel que l'app de musique l'annonce ; champs vides si elle ne dit rien. */
data class TrackInfo(val title: String, val artist: String, val album: String = "") {
    val isKnown: Boolean get() = title.isNotBlank() || artist.isNotBlank()
}

/** Ce que l'analyse a retenu d'un morceau : une section de l'historique. */
data class TrackRecord(
    val track: TrackInfo,
    /** App d'où venait le son (« YouTube Music »…). */
    val source: String,
    /** Début de l'écoute, en millisecondes depuis l'époque Unix. */
    val startedAt: Long,
    val detection: Detection?,
    val confidence: Float,
    val segments: List<Segment>,
    val progression: Progression?,
) {
    /** Un morceau dont on n'a rien tiré n'a pas sa place dans l'historique. */
    val isWorthKeeping: Boolean get() = detection != null
}

/**
 * Historique des détections, une section par morceau, du plus récent au plus ancien.
 * Conservé dans un fichier privé de l'app : uniquement des noms de morceaux, de gammes et
 * d'accords — jamais de son.
 */
class HarmonyHistory(private val file: File) {

    companion object {
        const val MAX_TRACKS = 50
    }

    fun load(): List<TrackRecord> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { runCatching { decode(array.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    fun save(records: List<TrackRecord>) {
        runCatching {
            val array = JSONArray()
            records.take(MAX_TRACKS).forEach { array.put(encode(it)) }
            file.writeText(array.toString())
        }
    }

    // Une gamme est désignée par son rang dans le catalogue : les 33 entrées et leur ordre
    // sont ceux du tableau, ils ne bougent pas.

    private fun encode(detection: Detection) = JSONObject()
        .put("scale", ScaleCatalog.scales.indexOf(detection.scale))
        .put("root", detection.root)

    private fun decodeDetection(json: JSONObject) =
        Detection(ScaleCatalog.scales[json.getInt("scale")], json.getInt("root"))

    private fun encode(record: TrackRecord) = JSONObject().apply {
        put("title", record.track.title)
        put("artist", record.track.artist)
        put("album", record.track.album)
        put("source", record.source)
        put("startedAt", record.startedAt)
        put("confidence", record.confidence.toDouble())
        record.detection?.let { put("detection", encode(it)) }
        put("segments", JSONArray().also { array ->
            record.segments.forEach { array.put(encode(it.detection).put("startMs", it.startMs)) }
        })
        record.progression?.let { progression ->
            put("progression", JSONObject().apply {
                progression.cycleMs?.let { put("cycleMs", it) }
                put("chords", JSONArray().also { array ->
                    progression.chords.forEach {
                        array.put(
                            JSONObject().put("root", it.chord.root).put("triad", it.chord.triad.name)
                                .put("durationMs", it.durationMs)
                        )
                    }
                })
            })
        }
    }

    private fun decode(json: JSONObject) = TrackRecord(
        track = TrackInfo(json.optString("title"), json.optString("artist"), json.optString("album")),
        source = json.optString("source"),
        startedAt = json.getLong("startedAt"),
        detection = json.optJSONObject("detection")?.let { decodeDetection(it) },
        confidence = json.optDouble("confidence", 0.0).toFloat(),
        segments = json.optJSONArray("segments").let { array ->
            (0 until (array?.length() ?: 0)).map {
                val item = array!!.getJSONObject(it)
                Segment(decodeDetection(item), item.getLong("startMs"))
            }
        },
        progression = json.optJSONObject("progression")?.let { p ->
            val chords = p.getJSONArray("chords")
            Progression(
                cycleMs = if (p.has("cycleMs")) p.getLong("cycleMs") else null,
                chords = (0 until chords.length()).map {
                    val c = chords.getJSONObject(it)
                    ChordSpan(Chord(c.getInt("root"), Triad.valueOf(c.getString("triad"))), c.getLong("durationMs"))
                },
            )
        },
    )
}
