# SPDX-License-Identifier: Apache-2.0 — Copyright 2026 Steve Nodock <stb@outlook.fr>
# Vidéos de démonstration pour les déclarations Google Play (services de premier plan et
# permission micro), enregistrées sur l'appareil par `screenrecord` et pilotées par adb.
#   -Scenario media       : la carte du fader dans le volet (type mediaPlayback)     — Android 13+
#   -Scenario mic         : la source Micro, permission puis oscilloscope (microphone) — tout appareil
#   -Scenario projection  : « Son de l'app » par la capture de lecture (mediaProjection) — Android 12
# Garde-fou : deux scénarios filment le volet de notifications ; le script REFUSE d'enregistrer si
# une notification d'une app hors liste (messagerie, mail, photos…) s'y trouve. La vidéo est rangée
# hors dépôt (stb-duomix\releases\play-videos\), la copie du téléphone est effacée.
param(
    [Parameter(Mandatory)][ValidateSet('media', 'mic', 'projection')][string]$Scenario,
    [string]$Serial = ''
)
$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$target = if ($Serial) { @('-s', $Serial) } else { @() }
$pkg = 'com.dirtwing.duomixfader'
$remote = '/sdcard/Download/dmf_video.mp4'
$outDir = Join-Path $PSScriptRoot '..\..\..\releases\play-videos'
New-Item -ItemType Directory -Force $outDir | Out-Null
function Sh([string]$command) { & $adb @target shell $command 2>$null }

# --- Préalables : écran allumé et déverrouillé, volet sans notification privée ---
if ((Sh "dumpsys window") -join '' -match 'isKeyguardShowing=true') { "Téléphone verrouillé : déverrouillez-le d'abord."; return }
$allowed = @('android', $pkg, 'moe.shizuku.privileged.api', 'com.google.android.apps.youtube.music',
    'com.google.android.youtube', 'com.google.android.gms', 'com.google.android.apps.wellbeing',
    'com.google.android.googlequicksearchbox', 'com.google.android.apps.weather')
# Seules les notifications ACTIVES (lignes NotificationRecord) : le dump liste aussi les préférences
# par paquet et l'historique, qui feraient croire à un volet plein
$present = (Sh "dumpsys notification --noredact") | Where-Object { $_ -match '^\s*NotificationRecord\(' } |
    ForEach-Object { [regex]::Match($_, 'pkg=([a-zA-Z0-9._]+)').Groups[1].Value } | Sort-Object -Unique
$private = $present | Where-Object { $_ -notin $allowed }
if ($Scenario -ne 'mic' -and $private) { "Notifications d'autres apps dans le volet : $($private -join ', '). Effacez-les, puis relancez."; return }

# Vidéos en anglais (langue de la relecture), langue de l'app rétablie à la fin
Sh "cmd locale set-app-locales $pkg --locales en" | Out-Null
Sh 'cmd statusbar collapse' | Out-Null

# Touche le premier élément de NOTRE écran (ou d'une boîte de dialogue système) dont le texte contient $pattern
function TapText([string]$pattern, [int]$tries = 6) {
    for ($i = 0; $i -lt $tries; $i++) {
        Sh "uiautomator dump /sdcard/Download/dmf_ui.xml" | Out-Null
        $xml = (Sh "cat /sdcard/Download/dmf_ui.xml") -join ''
        Sh "find /sdcard/Download -maxdepth 1 -name dmf_ui.xml -delete" | Out-Null
        $m = [regex]::Match($xml, 'text="[^"]*' + $pattern + '[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($m.Success) {
            Sh ("input tap {0} {1}" -f (([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2), (([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)) | Out-Null
            return $true
        }
        Start-Sleep 1
    }
    Write-Host "  '$pattern' introuvable"; return $false
}

function StartRecording([int]$seconds) {
    # screenrecord tourne sur le téléphone ; on ne l'attend pas
    Start-Process -FilePath $adb -ArgumentList ($target + @('shell', 'screenrecord', '--time-limit', $seconds, '--bit-rate', '6000000', $remote)) -WindowStyle Hidden | Out-Null
    Start-Sleep 2
}

switch ($Scenario) {
    'media' {
        # Le fader dans le volet : ouvrir, déplacer la barre, refermer, voir les volumes suivre
        Sh "am start -n $pkg/.MainActivity" | Out-Null; Start-Sleep 3
        StartRecording 28
        Start-Sleep 2
        Sh 'cmd statusbar expand-notifications' | Out-Null; Start-Sleep 4
        # Carte du fader d'un Pixel 11 Pro XL : relevé en 49,864 (982 x 564) ; barre à y ≈ 1359
        Sh 'input swipe 394 1359 570 1359 1200' | Out-Null; Start-Sleep 3
        Sh 'input swipe 570 1359 250 1359 1200' | Out-Null; Start-Sleep 3
        Sh 'input swipe 250 1359 394 1359 1200' | Out-Null; Start-Sleep 3
        Sh 'cmd statusbar collapse' | Out-Null; Start-Sleep 2
        Sh 'input swipe 540 2200 540 900 400' | Out-Null   # jusqu'au mixeur : les volumes ont suivi
        Start-Sleep 5
    }
    'mic' {
        # La permission est retirée avant : la vidéo montre la demande, puis l'écoute, puis l'arrêt
        Sh "pm revoke $pkg android.permission.RECORD_AUDIO" | Out-Null
        Sh "am force-stop $pkg" | Out-Null
        Sh "am start -n $pkg/.MainActivity" | Out-Null; Start-Sleep 4
        StartRecording 34
        Start-Sleep 2
        if (TapText 'Microphone') {
            Start-Sleep 2
            TapText 'While using the app' | Out-Null      # boîte de dialogue de permission d'Android
            Start-Sleep 7                                 # l'oscilloscope s'anime, l'indicateur micro s'allume
            Sh 'input keyevent 3' | Out-Null; Start-Sleep 4   # accueil : l'écoute continue (service microphone)
            Sh "am start -n $pkg/.MainActivity" | Out-Null; Start-Sleep 4
            TapText 'Microphone' | Out-Null               # second appui : arrêt, l'indicateur s'éteint
            Start-Sleep 5
        }
    }
    'projection' {
        # Android 12 seulement : l'accord de capture est retiré avant, pour que la boîte d'Android apparaisse
        Sh "appops set $pkg PROJECT_MEDIA ignore" | Out-Null
        Sh "am force-stop $pkg" | Out-Null
        Sh "am start -n $pkg/.MainActivity" | Out-Null; Start-Sleep 4
        StartRecording 30
        Start-Sleep 2
        if (TapText 'App sound') {
            Start-Sleep 2
            TapText 'While using the app' | Out-Null      # permission d'enregistrement, si pas encore accordée
            Start-Sleep 2
            TapText 'Start now' | Out-Null                # accord de capture d'Android
            Start-Sleep 14                                # « Listening… » puis la gamme
        }
    }
}

Start-Sleep 3
# screenrecord finit d'écrire après la limite de temps
Start-Sleep 4
$out = Join-Path $outDir ("fgs-$Scenario-" + (Get-Date -Format 'yyyyMMdd-HHmm') + '.mp4')
& $adb @target pull $remote $out 2>$null | Out-Null
Sh "find /sdcard/Download -maxdepth 1 -name dmf_video.mp4 -delete" | Out-Null
Sh "cmd locale set-app-locales $pkg" | Out-Null
if (Test-Path $out) { "enregistré : $out ($([int]((Get-Item $out).Length / 1KB)) Ko) — à visionner avant tout envoi" } else { "échec : aucune vidéo récupérée" }
