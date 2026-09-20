// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.dirtwing.duomixfader.harmony.ChromaAnalyzer
import com.dirtwing.duomixfader.harmony.ScopeFrame
import com.dirtwing.duomixfader.harmony.ScopeSplitter
import com.dirtwing.duomixfader.harmony.ToneFilter
import kotlin.concurrent.thread
import kotlin.math.pow

/**
 * Analyse harmonique par le micro, pour les appareils où le shell n'a pas le droit de capter
 * le son d'une app (Android 12). Même chaîne que la capture directe — 16 kHz mono, réduits en
 * chroma sur place par [ChromaAnalyzer] — mais la source est ce que le téléphone ENTEND : sa
 * propre enceinte, une chaîne hi-fi, un instrument. Ne peut rien entendre d'une écoute au casque.
 *
 * Rien n'est enregistré : chaque bloc de 32 ms est réduit à douze nombres et à une image
 * d'oscilloscope, puis oublié.
 * Demande la permission RECORD_AUDIO, que l'utilisateur accorde en choisissant ce mode.
 */
@SuppressLint("MissingPermission") // vérifiée par l'appelant (MixerEngine.microphoneAllowed)
internal class MicCapture(private val context: Context, private val onScope: (ScopeFrame) -> Unit = {}) {

    private companion object {
        const val TAG = "DuoMixHarmony"
        const val SAMPLE_RATE = 16_000
    }

    val analyzer = ChromaAnalyzer(SAMPLE_RATE)

    /** Graves et aigus de l'écoute, réglables pendant qu'elle tourne (voir ToneFilter). */
    val tone = ToneFilter(SAMPLE_RATE)

    /**
     * Amplification avant toute analyse, en décibels. La source « brute » d'un micro de téléphone
     * est souvent très faible : sans gain, la musique passe sous le seuil de silence de l'analyse.
     */
    @Volatile var gainDb = 0f

    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var reader: Thread? = null

    fun start(): Boolean = try {
        // Source « brute » si l'appareil l'offre, sinon celle de la reconnaissance vocale : ce
        // sont les deux qui échappent au traitement téléphonique (réduction de bruit, contrôle
        // de gain) qui écraserait la musique.
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val started = AudioRecord(
            source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, SAMPLE_RATE), // une demi-seconde d'avance
        )
        check(started.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord non initialisé" }
        started.startRecording()
        check(started.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord n'a pas démarré" }
        record = started
        running = true
        reader = thread(name = "harmony-mic", isDaemon = true) { readLoop(started) }
        Log.i(TAG, "écoute par le micro démarrée (source ${if (unprocessed) "brute" else "reconnaissance vocale"})")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "écoute par le micro impossible", t)
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
        val pcm = ShortArray(512) // 32 ms : une trentaine d'images par seconde pour l'oscilloscope
        val scope = ScopeSplitter(SAMPLE_RATE)
        val mono = FloatArray(pcm.size)
        while (running) {
            val read = source.read(pcm, 0, pcm.size)
            if (read <= 0) {
                Thread.sleep(50)
                continue
            }
            val gain = 10f.pow(gainDb / 20f) / 32_768f
            // Écrêtage franc à pleine échelle : l'oscilloscope le montre, il suffit de baisser le gain
            for (i in 0 until read) mono[i] = (pcm[i] * gain).coerceIn(-1f, 1f)
            tone.process(mono, read)
            analyzer.push(mono, read)
            scope.feed(mono, read)?.let(onScope)
        }
    }
}
