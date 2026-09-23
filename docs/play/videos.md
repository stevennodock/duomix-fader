# Vidéos de démonstration pour la Play Console

Google demande une courte vidéo par type de service de premier plan déclaré, et une pour la
permission micro. Ce que le relecteur doit voir : **le geste de l'utilisateur** qui déclenche la
fonction, puis la fonction qui tourne. Aucune voix, aucun montage ; 20 à 35 secondes ; en anglais.

Enregistrement : `record-videos.ps1 -Scenario <media|mic|projection> [-Serial …]`, qui pilote
l'appareil par adb et range la vidéo dans `stb-duomix\releases\play-videos\` (hors dépôt).
**Toujours visionner la vidéo avant de l'envoyer** : le volet de notifications y apparaît.

## Avant d'enregistrer

1. Téléphone **déverrouillé**, écran allumé, luminosité normale.
2. Volet de notifications **vidé** de toute notification personnelle (messagerie, mail, photos…) :
   le script refuse d'enregistrer tant qu'il en reste. Les notifications de DuoMix Fader, Shizuku,
   YouTube Music et YouTube peuvent rester.
3. Casque Bluetooth **déconnecté** : son nom s'affiche sur la carte du fader.
4. **YouTube Music en lecture** (et YouTube pour la vidéo du fader), interrupteur « Lecture
   simultanée » de YouTube Music activé, trois voyants verts.
5. Ne pas toucher le téléphone pendant l'enregistrement.

## 1. `mediaPlayback` — le fader dans le volet (Android 13+, Pixel)

| s | À l'image |
| --- | --- |
| 0–3 | Écran principal de DuoMix Fader, voyants verts. |
| 3–7 | Le volet s'ouvre : la carte « DuoMix fader » avec le nom du mode, la tonalité et les pavés. |
| 7–16 | La barre de progression est glissée vers la droite, puis vers la gauche, puis recentrée : c'est le crossfader. |
| 16–24 | Le volet se referme ; l'écran défile jusqu'au mixeur : les curseurs ont suivi la barre. |

Justification à saisir avec la vidéo : *The media session is the control surface of the
crossfader: its progress bar is the fader, previous/next nudge it. The app plays no sound itself;
it sets the volume of two playback apps, which must keep working when the screen is off.*

## 2. `microphone` + permission `RECORD_AUDIO` (tout appareil)

| s | À l'image |
| --- | --- |
| 0–3 | Écran principal, bloc Harmony, ligne « Source : App sound · Microphone ». |
| 3–6 | Appui sur **Microphone** ; la boîte de permission d'Android apparaît ; « While using the app ». |
| 6–13 | L'oscilloscope s'anime, le gain et les filtres apparaissent, l'indicateur micro d'Android s'allume. |
| 13–17 | Retour à l'accueil : l'indicateur micro reste allumé (le service porte le type microphone). |
| 17–26 | Retour dans l'app ; second appui sur **Microphone** : l'écoute s'arrête, l'indicateur s'éteint. |

Justification : *The microphone is read only after the user chooses the "Microphone" source, to
analyse what the phone hears (a stereo in the room, an instrument). A second tap stops it. Without
the microphone foreground service type, Android cuts the microphone as soon as the app leaves
the screen. Nothing is recorded: sound is reduced to pitch-class energies and discarded.*

## 3. `mediaProjection` — « Son de l'app » sur Android 12 (OnePlus)

| s | À l'image |
| --- | --- |
| 0–3 | Écran principal ; le bloc Harmony explique que ce téléphone ne permet pas la capture directe. |
| 3–8 | Appui sur **App sound** ; permission d'enregistrement si besoin ; la boîte « Start recording or casting? » d'Android ; « Start now ». |
| 8–24 | « Listening… », puis la gamme et ses pavés s'affichent ; YouTube Music continue de jouer. |

Justification : *On Android 12 the privileged service cannot capture app sound, so the app uses
Android's own playback capture, limited to the music app's uid. The screen is never captured.
The user grants it explicitly; the app then analyses the sound on the spot and records nothing.*

Le script retire l'accord `PROJECT_MEDIA` avant d'enregistrer pour que la boîte d'Android
apparaisse ; l'app le redemande au shell ensuite, comme au premier usage.
