# SPDX-License-Identifier: Apache-2.0 — Copyright 2026 Steve Nodock <stb@outlook.fr>
# Captures d'écran du guide utilisateur, prises sur un appareil connecté en adb.
# - passe DuoMix Fader (et lui seul) en anglais le temps des captures, puis rétablit la langue ;
# - ne capture que si l'app est au premier plan, et rogne la barre d'état : aucune notification
#   ni icône d'une autre app ne doit apparaître dans le guide.
param([string]$Serial = '')
$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$target = if ($Serial) { @('-s', $Serial) } else { @() }
$pkg = 'com.dirtwing.duomixfader'
$img = Join-Path $PSScriptRoot 'img'
New-Item -ItemType Directory -Force $img | Out-Null
Add-Type -AssemblyName System.Drawing
$remote = '/sdcard/Download/dmf_capture_tmp'

function Sh([string]$command) { & $adb @target shell $command 2>$null }
function Focus { (Sh "dumpsys window | grep -E 'mCurrentFocus'") -join '' }

function Shot([string]$name) {
    if ((Focus) -notmatch 'duomixfader') { "  SAUTE $name : l'app n'est pas au premier plan"; return }
    Sh "screencap -p $remote.png" | Out-Null
    $tmp = Join-Path $env:TEMP 'dmf_shot.png'
    & $adb @target pull "$remote.png" $tmp 2>$null | Out-Null
    $src = [Drawing.Image]::FromFile($tmp)
    $top = 150                                   # barre d'état
    $h = $src.Height - $top; $w = 540; $hh = [int]($h * $w / $src.Width)
    $dst = New-Object Drawing.Bitmap($w, $hh)
    $g = [Drawing.Graphics]::FromImage($dst); $g.InterpolationMode = 'HighQualityBicubic'
    $g.DrawImage($src, (New-Object Drawing.Rectangle(0, 0, $w, $hh)), (New-Object Drawing.Rectangle(0, $top, $src.Width, $h)), [Drawing.GraphicsUnit]::Pixel)
    $dst.Save((Join-Path $img "$name.png")); $g.Dispose(); $dst.Dispose(); $src.Dispose()
    [IO.File]::Delete($tmp)
    "  ok $name"
}

# Touche le premier élément de NOTRE écran dont le texte contient $pattern
function TapText([string]$pattern) {
    Sh "uiautomator dump $remote.xml" | Out-Null
    $xml = (Sh "cat $remote.xml") -join ''
    # Write-Host et non une chaîne nue : tout ce qu'une fonction PowerShell « émet » fait partie
    # de sa valeur de retour, et ("message", $false) est évalué VRAI par un if.
    if ($xml -notmatch 'package="com.dirtwing.duomixfader"') { Write-Host "  '$pattern' : écran DuoMix Fader non lu"; return $false }
    $m = [regex]::Match($xml, 'text="[^"]*' + $pattern + '[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { Write-Host "  '$pattern' introuvable"; return $false }
    $x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
    $y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    Sh "input tap $x $y" | Out-Null
    return $true
}

Sh "cmd locale set-app-locales $pkg --locales en" | Out-Null
Sh 'cmd statusbar collapse' | Out-Null
Sh "am start -n $pkg/.MainActivity" | Out-Null
Start-Sleep 8   # le changement de langue recrée l'activité : lui laisser le temps de revenir
"langue de l'app : " + (Sh "cmd locale get-app-locales $pkg")

Shot '01-mixer-top'
Sh 'input swipe 540 1900 540 600 400' | Out-Null; Start-Sleep 2
Shot '02-mixer-bottom'

# On navigue comme un utilisateur, en touchant les boutons : retour en haut du mixeur d'abord
Sh 'input swipe 540 600 540 2000 300' | Out-Null; Start-Sleep 1
Sh 'input swipe 540 600 540 2000 300' | Out-Null; Start-Sleep 2

if (TapText 'Scale details') {
    Start-Sleep 3
    Shot '03-harmony-top'
    Sh 'input swipe 540 1900 540 700 400' | Out-Null; Start-Sleep 2
    Shot '04-harmony-middle'
    Sh 'input swipe 540 1900 540 500 400' | Out-Null; Start-Sleep 2
    Shot '05-harmony-bottom'
    Sh 'input swipe 540 600 540 2000 300' | Out-Null; Start-Sleep 1
    Sh 'input swipe 540 600 540 2000 300' | Out-Null; Start-Sleep 2

    if (TapText 'Tap to see the history') { Start-Sleep 3; Shot '06-history'; Sh 'input keyevent 4' | Out-Null; Start-Sleep 2 }
    if (TapText 'colour-coded') { Start-Sleep 6; Shot '07-scale-sheet'; Sh 'input keyevent 4' | Out-Null; Start-Sleep 2 }
    Sh 'input keyevent 4' | Out-Null   # du panneau Harmonie vers le mixeur
}

# Fichiers temporaires du téléphone, puis langue de l'app rendue au système
Sh "find /sdcard/Download -maxdepth 1 -name 'dmf_capture_tmp*' -delete" | Out-Null
Sh "cmd locale set-app-locales $pkg" | Out-Null
"langue rétablie : " + (Sh "cmd locale get-app-locales $pkg")
Get-ChildItem $img | ForEach-Object { '{0}  {1:N0} octets' -f $_.Name, $_.Length }
