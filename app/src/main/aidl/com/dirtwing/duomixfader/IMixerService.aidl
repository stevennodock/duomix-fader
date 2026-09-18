// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Steve Nodock <stb@outlook.fr>

// Interface du UserService exécuté par Shizuku dans le processus shell (uid 2000).
// Les retours sont en JSON (String) pour éviter des Parcelable custom.
package com.dirtwing.duomixfader;

interface IMixerService {
    // Méthode de destruction réservée Shizuku (id imposé par la lib)
    void destroy() = 16777114;

    // Bascule TAKE_AUDIO_FOCUS (ignore/allow) pour un paquet de la liste blanche.
    // Retourne false si le paquet est refusé ou si appops échoue.
    boolean setFocusIgnored(String pkg, boolean ignored) = 1;

    // Liste les lectures audio actives : [{"piid":int,"uid":int,"pkg":str,"state":int}]
    String listPlaybacks() = 2;

    // Applique un volume [0..1] au lecteur identifié par son piid
    boolean setVolume(int piid, float volume) = 3;

    // Indique si TAKE_AUDIO_FOCUS est en mode ignore pour un paquet de la liste blanche
    boolean isFocusIgnored(String pkg) = 4;

    // Lie la vie du service à celle de l'app : à la mort du jeton (processus client
    // tué), le service shell se termine de lui-même, même si Shizuku n'est plus là.
    void attachClient(IBinder token) = 5;
}
