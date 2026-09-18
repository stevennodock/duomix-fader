// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomix.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.IBinder
import android.os.RemoteException
import androidx.annotation.Keep
import com.dirtwing.duomix.AppCatalog
import com.dirtwing.duomix.IMixerService
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * Service exécuté par Shizuku dans un processus séparé sous l'uid shell (2000).
 *
 * C'est ici que résident les deux capacités privilégiées :
 *  1. setFocusIgnored — lancer `appops set <pkg> TAKE_AUDIO_FOCUS ignore` (le hack anti-focus),
 *     uniquement pour les paquets de la liste blanche
 *  2. listPlaybacks / setVolume — mixer les flux actifs via les API cachées
 *     AudioPlaybackConfiguration.getPlayerProxy() / IPlayer.setVolume(float),
 *     accessibles depuis le shell (même principe que VolumeManager / AppMixer).
 */
@Keep
class MixerUserService() : IMixerService.Stub() {

    private companion object {
        const val FOCUS_OP = "TAKE_AUDIO_FOCUS"
        /** Liste blanche appliquée côté shell : l'appelant ne peut viser aucun autre paquet. */
        val ALLOWED_PACKAGES = AppCatalog.allowedPackages
    }

    private var context: Context? = null

    /** Cache piid -> objet lecteur (PlayerProxy ou IPlayer) rafraîchi à chaque listPlaybacks. */
    private val players = ConcurrentHashMap<Int, Any>()

    init {
        // Lève les restrictions sur les API cachées pour ce processus
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
    }

    /** Constructeur avec Context, fourni par Shizuku >= v13. */
    @Keep
    constructor(context: Context) : this() {
        this.context = context
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun attachClient(token: IBinder) {
        // Shizuku arrête normalement ce processus (daemon = false), mais s'il est lui-même
        // tué, plus personne ne le fait : on ne laisse jamais un processus shell orphelin.
        try {
            token.linkToDeath({ exitProcess(0) }, 0)
        } catch (e: RemoteException) {
            // Le client est déjà mort
            exitProcess(0)
        }
    }

    override fun setFocusIgnored(pkg: String, ignored: Boolean): Boolean {
        if (pkg !in ALLOWED_PACKAGES) return false
        val mode = if (ignored) "ignore" else "allow"
        val out = appops("set", pkg, FOCUS_OP, mode) ?: return false
        // appops set est silencieux en cas de succès
        return out.isEmpty()
    }

    override fun isFocusIgnored(pkg: String): Boolean {
        if (pkg !in ALLOWED_PACKAGES) return false
        return appops("get", pkg, FOCUS_OP)?.contains("ignore") == true
    }

    /**
     * Seule commande que ce processus shell accepte de lancer : `appops` avec des
     * arguments fixes, sans passer par `sh -c` (aucune interprétation de chaîne).
     */
    private fun appops(vararg args: String): String? {
        return try {
            val process = ProcessBuilder(listOf("appops") + args)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) out.trim() else null
        } catch (t: Throwable) {
            null
        }
    }

    @SuppressLint("PrivateApi")
    override fun listPlaybacks(): String {
        val result = JSONArray()
        try {
            val configs = activePlaybackConfigurations()
            players.clear()
            for (config in configs) {
                config ?: continue
                val cls = config.javaClass
                val piid = cls.getMethod("getPlayerInterfaceId").invoke(config) as Int
                val uid = cls.getMethod("getClientUid").invoke(config) as Int
                val state = cls.getMethod("getPlayerState").invoke(config) as Int
                playerControl(config)?.let { players[piid] = it }
                result.put(JSONObject().apply {
                    put("piid", piid)
                    put("uid", uid)
                    put("pkg", packageNameForUid(uid))
                    put("state", state)
                    put("controllable", players.containsKey(piid))
                })
            }
        } catch (t: Throwable) {
            return JSONObject().put("error", t.toString()).toString()
        }
        return result.toString()
    }

    override fun setVolume(piid: Int, volume: Float): Boolean {
        val player = players[piid] ?: return false
        return runCatching {
            // PlayerProxy et IPlayer exposent tous deux setVolume(float)
            val method = player.javaClass.methods.first {
                it.name == "setVolume" && it.parameterTypes.size == 1
            }
            method.invoke(player, volume.coerceIn(0f, 1f))
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // Accès aux configurations de lecture actives (toutes apps confondues)
    // ------------------------------------------------------------------

    private fun activePlaybackConfigurations(): List<*> {
        // Voie 1 : AudioManager public, via le Context fourni par Shizuku.
        context?.let { ctx ->
            runCatching {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                return am.activePlaybackConfigurations
            }
        }
        // Voie 2 (secours) : IAudioService via ServiceManager, en réflexion pure.
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "audio") as IBinder
        val stub = Class.forName("android.media.IAudioService\$Stub")
        val audioService = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val method = audioService.javaClass.methods.first { it.name == "getActivePlaybackConfigurations" }
        return method.invoke(audioService) as List<*>
    }

    /** Retourne l'objet de contrôle du lecteur : PlayerProxy (SystemApi) ou IPlayer brut. */
    private fun playerControl(config: Any): Any? {
        runCatching {
            config.javaClass.getMethod("getPlayerProxy").invoke(config)
        }.getOrNull()?.let { return it }
        return runCatching {
            config.javaClass.getMethod("getIPlayer").invoke(config)
        }.getOrNull()
    }

    private fun packageNameForUid(uid: Int): String {
        context?.let { ctx ->
            runCatching {
                ctx.packageManager.getNameForUid(uid)?.let { return it }
            }
        }
        return "uid:$uid"
    }
}
