// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

package com.dirtwing.duomixfader

/**
 * Ce que l'identité shell a le droit de faire sur cet appareil. DuoMix agit par elle, et ses
 * permissions changent d'une version d'Android à l'autre et d'un constructeur à l'autre.
 * Constaté (2026-09-20) :
 *  - Pixel 11 / Android 17 : tout.
 *  - Android 12 (émulateur, OnePlus 7 Pro) : le shell n'a pas MODIFY_AUDIO_ROUTING — ni capture
 *    du son, ni volume par lecteur (Android ne livre alors que des lecteurs anonymisés).
 *  - OxygenOS / ColorOS : tant que l'option développeur « Désactiver la surveillance des
 *    autorisations » est éteinte, adb perd aussi le droit de régler les appops (audio focus).
 *
 * Une fonction que l'appareil ne permet pas est désactivée, et l'écran dit pourquoi.
 */
object ShellCapabilities {
    /** Régler TAKE_AUDIO_FOCUS par appops : la lecture simultanée. */
    const val FOCUS = 1
    /** Piloter le volume de chaque lecteur : curseurs et crossfader. */
    const val PLAYERS = 2
    /** Capter le son d'une app : l'analyse harmonique. */
    const val CAPTURE = 4

    /** Tant que le service shell n'a pas répondu, on ne bride rien. */
    const val ALL = FOCUS or PLAYERS or CAPTURE
}
