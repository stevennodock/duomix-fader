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

    // Analyse harmonique : capte le son d'un paquet de la liste blanche et le réduit en
    // chroma DANS le processus shell. Aucun échantillon audio ne franchit cette interface.
    boolean startHarmony(String pkg) = 6;
    void stopHarmony() = 7;

    // Cumuls depuis le dernier appel : [0..11] chroma, [12..23] basses, [24] trames
    // sonores, [25] trames de silence. Null si aucune capture n'est active.
    float[] readHarmony() = 8;

    // Referme le volet de notifications et ouvre le panneau Harmonie de DuoMix Fader.
    // Aucun paramètre : ni la commande ni l'écran visé ne dépendent de l'appelant.
    void showHarmonyPanel() = 9;

    // Morceau en cours d'un paquet de la liste blanche, tel que l'app le publie elle-même
    // dans sa session multimédia : {"title","artist","album"} en JSON, ou null.
    String nowPlaying(String pkg) = 10;

    // Ce que l'identité shell a le droit de faire sur CET appareil (voir ShellCapabilities) :
    // bit 0 régler l'audio focus, bit 1 piloter le volume des lecteurs, bit 2 capter le son.
    // Selon la version d'Android et le constructeur, le shell n'a pas les mêmes permissions.
    int capabilities() = 11;

    // Mode coupure, pour les appareils où le volume par lecteur est inaccessible : coupe ou
    // rétablit le son d'un paquet de la liste blanche (appops PLAY_AUDIO). Le service rétablit
    // de lui-même tout ce qu'il a coupé quand il s'arrête.
    boolean setMuted(String pkg, boolean muted) = 12;
}
