// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader.shizuku

import android.annotation.SuppressLint
import android.app.Application
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Process
import android.util.Log
import com.dirtwing.duomixfader.harmony.ChromaAnalyzer
import kotlin.concurrent.thread

/**
 * Capte, depuis le processus shell, le son joué par UNE app (filtrée par uid) et le
 * réduit aussitôt en chroma. Le son continue d'être joué normalement (LOOP_BACK_RENDER).
 *
 * Repose sur les permissions de l'identité shell (CAPTURE_MEDIA_OUTPUT,
 * MODIFY_AUDIO_ROUTING) et sur l'API cachée AudioPolicy, atteinte par réflexion.
 *
 * Confidentialité : les échantillons ne quittent jamais ce processus, ne sont ni
 * enregistrés ni transmis ; seuls les cumuls de [ChromaAnalyzer] en sortent.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "MissingPermission")
internal class PlaybackCapture(baseContext: Context, private val uid: Int) {

    private companion object {
        const val TAG = "DuoMixHarmony"
        const val SHELL_PACKAGE = "com.android.shell"
        /** Plafond imposé par Android à la capture privilégiée ; suffit au chroma (55 Hz - 2,1 kHz). */
        const val SAMPLE_RATE = 16_000
    }

    /**
     * Shizuku nous donne un Context au nom de NOTRE paquet alors que le processus tourne
     * sous l'uid shell : le couple (uid 2000, paquet DuoMix) est incohérent et AppOps le
     * refuse. Toute l'API audio doit voir le paquet qui appartient réellement à cet uid.
     */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
    }

    private val context: Context = ShellContext(baseContext)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val analyzer = ChromaAnalyzer(SAMPLE_RATE)

    private var policy: Any? = null
    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var reader: Thread? = null

    fun start(): Boolean = try {
        impersonateShellApplication()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            // Mono et 16 kHz : les deux plafonds qu'Android impose à la capture privilégiée
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilder = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder").getConstructor().newInstance()
        val matchUid = ruleClass.getField("RULE_MATCH_UID").getInt(null)
        ruleBuilder.javaClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            .invoke(ruleBuilder, matchUid, uid)
        // Capte aussi les apps qui refusent la capture ordinaire (permission CAPTURE_MEDIA_OUTPUT)
        ruleBuilder.javaClass.getMethod("allowPrivilegedPlaybackCapture", Boolean::class.javaPrimitiveType)
            .invoke(ruleBuilder, true)
        val rule = ruleBuilder.javaClass.getMethod("build").invoke(ruleBuilder)

        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilder = Class.forName("android.media.audiopolicy.AudioMix\$Builder").getConstructor(ruleClass).newInstance(rule)
        mixBuilder.javaClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        // LOOP_BACK_RENDER : on reçoit une copie, l'app reste audible
        val loopBackRender = mixClass.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null)
        mixBuilder.javaClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, loopBackRender)
        val mix = mixBuilder.javaClass.getMethod("build").invoke(mixBuilder)

        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val policyBuilder = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder").getConstructor(Context::class.java).newInstance(context)
        policyBuilder.javaClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        val builtPolicy = policyBuilder.javaClass.getMethod("build").invoke(policyBuilder)

        val status = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass).invoke(audioManager, builtPolicy) as Int
        check(status == 0) { "registerAudioPolicy a renvoyé $status" }
        policy = builtPolicy

        val sink = policyClass.getMethod("createAudioRecordSink", mixClass).invoke(builtPolicy, mix) as AudioRecord
        sink.startRecording()
        check(sink.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord n'a pas démarré" }
        record = sink
        running = true
        reader = thread(name = "harmony-capture", isDaemon = true) { readLoop(sink) }
        Log.i(TAG, "capture démarrée pour l'uid $uid")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "capture impossible pour l'uid $uid", t)
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
        policy?.let { p ->
            runCatching {
                AudioManager::class.java.getMethod("unregisterAudioPolicy", p.javaClass).invoke(audioManager, p)
            }
        }
        policy = null
    }

    private fun readLoop(sink: AudioRecord) {
        val pcm = ShortArray(1_600) // 100 ms
        val mono = FloatArray(pcm.size)
        while (running) {
            val read = sink.read(pcm, 0, pcm.size)
            if (read <= 0) {
                if (read < 0) Log.w(TAG, "lecture AudioRecord : $read")
                Thread.sleep(50)
                continue
            }
            for (i in 0 until read) mono[i] = pcm[i] / 32_768f
            analyzer.push(mono, read)
        }
    }

    /**
     * AudioRecord s'identifie via l'« application courante » du processus. Shizuku démarre
     * le nôtre avec ActivityThread.systemMain(), dont l'application se déclare « android » :
     * on la remplace par une coquille qui se déclare « com.android.shell ».
     */
    private fun impersonateShellApplication() {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null) ?: return
        val app = Application()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(app, context)
        activityThreadClass.getDeclaredField("mInitialApplication")
            .apply { isAccessible = true }
            .set(activityThread, app)
    }
}
