# DuoMix Fader — Mixeur audio à deux apps pour Android

Mini-app Android (Kotlin + Jetpack Compose) qui contourne l'*audio focus* d'Android
pour permettre la **lecture simultanée** de deux apps — une de musique (YouTube Music,
Spotify, Deezer…) et une de vidéo ou de voix (YouTube, X, Telegram, VLC, Chrome…) — et offre
un **mixeur 2 canaux avec crossfader** à puissance constante, le tout **sans root**
grâce à [Shizuku](https://shizuku.rikka.app/).

## Principe

1. **Lecture simultanée** — un *UserService* Shizuku (processus shell, uid 2000) exécute
   `appops set <pkg> TAKE_AUDIO_FOCUS ignore` : l'app ciblée ignore les demandes de
   focus et n'est plus mise en pause par l'autre.
2. **Mixage per-app** — le même service lit les flux actifs
   (`AudioManager.getActivePlaybackConfigurations()`) et pilote le volume de chaque
   lecteur via l'API cachée `AudioPlaybackConfiguration.getPlayerProxy()` /
   `IPlayer.setVolume(float)` (même approche que VolumeManager, AppMixer, Mixer).
   Le contournement des restrictions d'API cachées est assuré par
   [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass).

## Structure

```
duomix/
├── app/src/main/aidl/com/dirtwing/duomix/IMixerService.aidl   # Interface app <-> service shell
├── app/src/main/java/com/dirtwing/duomix/
│   ├── MainActivity.kt              # Activité unique Compose
│   ├── AppCatalog.kt                # Liste FERMÉE des apps mixables = liste blanche du shell
│   ├── MixerEngine.kt               # Moteur partagé : Shizuku, polling, volumes, crossfader
│   ├── MixerNotificationService.kt  # Crossfader dans une notification (MediaSession)
│   ├── MixerViewModel.kt            # Façade de l'écran sur le moteur
│   ├── shizuku/MixerUserService.kt  # Côté shell : appops (liste blanche) + volumes (réflexion)
│   ├── ui/MixerScreen.kt            # UI : statut, toggles focus, sliders, crossfader
│   └── ui/LicensesScreen.kt         # Écran « Licences open source » (NOTICE + LICENSE)
├── docs/compliance/PLAY_STORE.md    # Checklist de conformité avant publication
├── LICENSE, NOTICE                  # Apache 2.0 + licences tierces (embarqués dans l'APK)
└── ...
```

Le service shell n'expose **aucune exécution de commande arbitraire** : seul
`appops … TAKE_AUDIO_FOCUS` est possible, et uniquement pour les apps d'`AppCatalog`.

**Ajouter une app** : une ligne dans `AppCatalog.kt`, une entrée `<queries>` dans le
manifest, et incrémenter `versionCode` (sinon Shizuku réutilise l'ancien service shell).
Toutes les apps ne sont pas pilotables : à valider une par une sur l'appareil.

## Build

Prérequis : JDK 17, SDK Android 36. `local.properties` (`sdk.dir=…`) est créé par
Android Studio, ou à la main.

1. Ouvrir le dossier `duomix/` dans Android Studio, ou en ligne de commande :
   `./gradlew :app:assembleDebug` → APK dans `app/build/outputs/apk/debug/`.
2. Installer : `adb install app-debug.apk`.

**Signature** : si un fichier `signing.properties` (hors dépôt) existe à la racine avec
`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEYSTORE_ALIAS`, `KEYSTORE_ALIAS_PASSWORD`,
les builds debug et release sont signés avec ce keystore ; sinon avec la clé de debug.

> **Note** : le projet compile, mais la réflexion sur `getPlayerProxy()`/`getIPlayer()`
> dépend de la version d'Android et reste à valider sur l'appareil.

## Utilisation sur le téléphone

1. Installer **Shizuku** (Play Store), le démarrer via *Options développeur →
   Débogage sans fil* (à refaire après chaque reboot).
2. Lancer **DuoMix Fader** → « Demander la permission Shizuku ».
3. Activer le switch *ignorer l'audio focus* pour **YouTube Music** (suffisant en général).
4. Lancer une musique dans YT Music, puis une vidéo dans YouTube (PiP ou à l'écran) :
   les deux jouent ensemble.
5. Mixer avec les deux sliders ou le **crossfader** (loi équi-énergie
   `cos/sin(x·π/2)`, comme une table DJ).

## Limites connues

- Shizuku doit être redémarré après un reboot du téléphone.
- Sans YouTube Premium, la vidéo YouTube doit rester visible (écran ou PiP).
- Le volume par flux repart à 100 % quand une app recrée son lecteur ;
  DuoMix ré-applique le réglage à chaque cycle de polling (800 ms).
- Après une mise à jour majeure d'Android, re-vérifier le mode appops.

## Licence

Copyright 2026 Steve Nodock <stb@outlook.fr> — distribué sous
[Apache License 2.0](LICENSE). Les licences des bibliothèques tierces
(Shizuku API : MIT ; HiddenApiBypass, AndroidX, Compose, Kotlin : Apache 2.0)
sont détaillées dans [NOTICE](NOTICE).

DuoMix est un projet indépendant, sans lien avec Google, YouTube ni le projet Shizuku.
