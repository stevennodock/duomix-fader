# DuoMix Fader — Privacy Policy

*Last updated: 23 September 2026 · Steve Nodock <stb@outlook.fr>*

DuoMix Fader is a free, open-source Android app (Apache License 2.0) that lets two apps play
at once, mixes them with a crossfader, and estimates the musical scale and chord progression of
the piece being played. This page states what it does with data. The source code is public and
is the reference: https://github.com/stevennodock/duomix-fader

## The short version

- **The app has no network permission.** Nothing it hears, reads or stores can leave your phone.
- **It never records sound.** Audio is reduced on the spot to twelve numbers per instant (the
  energy of the twelve pitch classes) and, for the microphone, to the picture of a small
  oscilloscope, then discarded.
- **No account, no analytics, no advertising, no third-party SDK** other than the Shizuku client
  library, which only talks to the Shizuku app on your phone.

## What it listens to, and when

| Source | When | What happens to the sound |
| --- | --- | --- |
| The sound of the music app you chose (Android 13 and later) | While the app or its notification runs and that app plays | Captured by the privileged service, inside your phone, reduced to pitch-class energies, discarded |
| The sound of the music app you chose (Android 12) | Only after you choose *App sound* and Android grants its playback capture | Same reduction; the screen is never captured; the other app's sound is not read |
| The microphone | Only after you choose *Microphone* and grant the permission; a second tap stops it | Same reduction, plus the oscilloscope picture; the microphone is never switched on by itself |

The **recording permission** (`RECORD_AUDIO`) is asked only for the two last cases, never at
launch. You can revoke it in Android's settings at any time.

## What it stores

- **Detection history** — the title and artist that the music app itself publishes for the lock
  screen, the scale, the chords and the time you listened, for up to fifty pieces. It is a
  private file of the app on your phone. **Clear** in the History screen deletes it; uninstalling
  the app removes everything.
- **Settings** — the apps chosen for each channel, the fader position, the microphone gain and
  tone, and which source you chose. Private to the app.

Nothing is sent anywhere, backed up by the app, or shared with other apps.

## The privileged service (Shizuku)

DuoMix Fader relies on [Shizuku](https://shizuku.rikka.app/), a separate free app that you start
yourself through Wireless debugging. Through it, DuoMix Fader runs a small service under Android's
*shell* identity — the rights a computer gets when you connect the phone for debugging. That
service accepts a short, fixed list of orders, only for the apps of DuoMix Fader's built-in
catalogue: change an app's audio-focus setting, set the volume of its players, read the sound of
the music app for analysis, read the title of the current piece, open DuoMix Fader's own screen,
and, on Android 12, mute or unmute an app and allow DuoMix Fader to use Android's playback
capture. It runs no arbitrary command and stops by itself when the app stops. The exact list, with
the Android rights each order needs, is shown in the app's **About** screen and in the source code
(`shizuku/MixerUserService.kt`).

## Third parties

- **Shizuku** (RikkaApps, MIT licence) — the privilege broker described above. DuoMix Fader is not
  affiliated with the Shizuku project.
- **Oliver Prehn (NewJazz)** — the music-theory method used by the harmony feature, with his written
  permission. No data is exchanged with him.
- DuoMix Fader is not affiliated with Google, YouTube, or any of the apps it can mix.

## Contact

Questions about this policy: Steve Nodock <stb@outlook.fr>, or an issue on the project's GitHub page.
