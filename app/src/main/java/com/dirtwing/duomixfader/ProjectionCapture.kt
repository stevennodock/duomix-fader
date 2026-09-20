// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import com.dirtwing.duomixfader.harmony.ChromaAnalyzer
import kotlin.concurrent.thread

/**
 * Capture de lecture d'Android (MediaProjection), pour les appareils où le shell n'a pas le
 * droit de capter lui-même le son d'une app (Android 12). On reçoit une COPIE du son de la seule
 * app de musique, désignée par son uid : elle reste audible, casque compris, et l'autre canal
 * n'entre pas dans l'analyse. Même chaîne ensuite : 16 kHz mono, réduits en chroma sur place.
 *
 * Android n'accorde cette capture qu'aux apps qui l'acceptent — c'est le cas de celles du
 * catalogue vérifiées sur OnePlus 7 Pro (2026-09-20) — et exige la permission RECORD_AUDIO
 * ainsi qu'un service de premier plan de type « mediaProjection » déjà démarré.
 */
@SuppressLint("MissingPermission") // RECORD_AUDIO vérifiée par l'appelant
internal class ProjectionCapture(private val projection: MediaProjection, private val uid: Int) {

    private companion object {
        const val TAG = "DuoMixHarmony"
        const val SAMPLE_RATE = 16_000
    }

    val analyzer = ChromaAnalyzer(SAMPLE_RATE)

    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var reader: Thread? = null

    fun start(): Boolean = try {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUid(uid)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val started = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format)
            .setBufferSizeInBytes(SAMPLE_RATE) // une demi-seconde d'avance
            .build()
        check(started.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord non initialisé" }
        started.startRecording()
        check(started.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord n'a pas démarré" }
        record = started
        running = true
        reader = thread(name = "harmony-projection", isDaemon = true) { readLoop(started) }
        Log.i(TAG, "capture de lecture démarrée pour l'uid $uid")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "capture de lecture impossible pour l'uid $uid", t)
        stop()
        false
    }

    fun stop() {
        running = false
        reader?.join(1_500)
        reader = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    private fun readLoop(source: AudioRecord) {
        val pcm = ShortArray(1_600) // 100 ms
        val mono = FloatArray(pcm.size)
        while (running) {
            val read = source.read(pcm, 0, pcm.size)
            if (read <= 0) {
                Thread.sleep(50)
                continue
            }
            for (i in 0 until read) mono[i] = pcm[i] / 32_768f
            analyzer.push(mono, read)
        }
    }
}
