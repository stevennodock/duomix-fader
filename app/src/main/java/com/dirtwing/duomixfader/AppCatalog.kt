// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader

/** Les deux voies du mixeur : le crossfader va de MUSIC (0) à VIDEO (1). */
enum class Slot { MUSIC, VIDEO }

/**
 * Une app que DuoMix sait mixer. Les noms de marque ne sont pas traduits.
 *
 * [toleratesFocusDenial] : l'app continue de jouer quand sa demande d'audio focus est
 * refusée (effet de `TAKE_AUDIO_FOCUS ignore`). Faux pour les apps dont le lecteur gère
 * lui-même le focus (Media3/ExoPlayer avec handleAudioFocus) : elles se mettent en pause
 * toutes seules. Pour celles-là, c'est l'AUTRE canal qui doit ignorer le focus.
 * Constaté sur appareil ; vrai par défaut pour les apps pas encore testées.
 *
 * Validées sur Pixel 11 Pro XL / Android 17 (2026-09-19) : YouTube Music, YouTube et
 * Telegram (tolèrent le refus de focus) ; X (ne le tolère pas, joue quand c'est l'autre
 * canal qui ignore le focus). Les autres entrées restent à valider.
 */
data class AppTarget(
    val pkg: String,
    val label: String,
    val toleratesFocusDenial: Boolean = true,
)

/**
 * Liste FERMÉE des apps mixables, source unique pour l'UI et pour le service shell :
 * [allowedPackages] est la liste blanche appliquée dans le processus privilégié, qui
 * refuse tout autre paquet. Ajouter une app = une ligne ici + une entrée <queries>
 * dans le manifest (visibilité des paquets) + incrémenter versionCode.
 */
object AppCatalog {

    val music = listOf(
        AppTarget("com.google.android.apps.youtube.music", "YouTube Music"),
        AppTarget("com.spotify.music", "Spotify"),
        AppTarget("deezer.android.app", "Deezer"),
        AppTarget("com.apple.android.music", "Apple Music"),
        AppTarget("com.amazon.mp3", "Amazon Music"),
        AppTarget("com.soundcloud.android", "SoundCloud"),
        AppTarget("com.aspiro.tidal", "Tidal"),
        AppTarget("com.qobuz.music", "Qobuz"),
    )

    val video = listOf(
        AppTarget("com.google.android.youtube", "YouTube"),
        // Media3 : se met en pause si le focus lui est refusé (constaté le 2026-09-19)
        AppTarget("com.twitter.android", "X", toleratesFocusDenial = false),
        AppTarget("org.telegram.messenger", "Telegram"),
        AppTarget("tv.twitch.android.app", "Twitch"),
        AppTarget("org.videolan.vlc", "VLC"),
        AppTarget("com.netflix.mediaclient", "Netflix"),
        AppTarget("au.com.shiftyjelly.pocketcasts", "Pocket Casts"),
        AppTarget("com.android.chrome", "Chrome"),
        AppTarget("org.mozilla.firefox", "Firefox"),
    )

    fun apps(slot: Slot): List<AppTarget> = if (slot == Slot.MUSIC) music else video

    fun find(pkg: String): AppTarget? = (music + video).firstOrNull { it.pkg == pkg }

    val allowedPackages: Set<String> = (music + video).map { it.pkg }.toSet()
}
