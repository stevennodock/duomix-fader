# SPDX-License-Identifier: Apache-2.0 — Copyright 2026 Steve Nodock <stb@outlook.fr>
# Capture de la SEULE carte multimédia du volet de notifications. Le volet contient les
# notifications des autres apps : on lit la hiérarchie de l'écran uniquement pour trouver
# les coordonnées de la carte (aucun texte n'est affiché ni conservé), et l'image est rognée
# à ces coordonnées avant d'être enregistrée. La capture complète est détruite aussitôt.
param([string]$Serial = '', [string]$Name = '09-notification')
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$target = if ($Serial) { @('-s', $Serial) } else { @() }
$remote = '/sdcard/Download/dmf_capture_tmp'
function Sh([string]$command) { & $adb @target shell $command 2>$null }
Add-Type -AssemblyName System.Drawing

Sh 'cmd statusbar expand-notifications' | Out-Null
Start-Sleep 3
# uiautomator exige un écran « au repos » ; la barre ondulée animée de la carte l'en empêche
# par intermittence : on réessaie.
$xml = ''
for ($try = 0; $try -lt 12 -and $xml -notmatch 'media_carousel'; $try++) {
    Sh "uiautomator dump $remote.xml" | Out-Null
    $xml = (Sh "cat $remote.xml") -join ''
    if ($xml -notmatch 'media_carousel') { Start-Sleep 1 }
}
# Carte(s) multimédia : on garde la plus grande zone dont l'identifiant évoque le lecteur
$best = $null
foreach ($m in [regex]::Matches($xml, 'resource-id="com\.android\.systemui:id/([^"]*media[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $x1, $y1, $x2, $y2 = 2..5 | ForEach-Object { [int]$m.Groups[$_].Value }
    $area = ($x2 - $x1) * ($y2 - $y1)
    if ($area -gt 0 -and ($null -eq $best -or $area -gt $best.area)) { $best = @{ id = $m.Groups[1].Value; x = $x1; y = $y1; w = $x2 - $x1; h = $y2 - $y1; area = $area } }
}
$xml = $null
Sh "screencap -p $remote.png" | Out-Null
$tmp = Join-Path $env:TEMP 'dmf_shade.png'
& $adb @target pull "$remote.png" $tmp 2>$null | Out-Null
$src = [Drawing.Bitmap]::FromFile($tmp)
if ($null -eq $best) {
    # Repli : la barre ondulée animée de la carte empêche souvent uiautomator d'obtenir un écran
    # « au repos ». La carte occupe une place fixe dans le volet (mesurée sur Pixel 11 Pro XL,
    # 1080 x 2404) ; on ne s'y fie que si l'on y reconnaît son fond sombre uni, à plusieurs
    # endroits vides de la carte. Au moindre doute, on ne garde rien.
    $guess = @{ id = 'position connue'; x = 49; y = 864; w = 982; h = 564 }
    $looksRight = $true
    foreach ($p in @(@(500, 420), @(30, 300), @(950, 300), @(500, 40))) {
        $c = $src.GetPixel($guess.x + $p[0], $guess.y + $p[1])
        if ([Math]::Abs($c.R - 28) -gt 10 -or [Math]::Abs($c.G - 27) -gt 10 -or [Math]::Abs($c.B - 26) -gt 10) { $looksRight = $false }
    }
    if ($looksRight) { $best = $guess }
}
if ($null -eq $best) {
    'carte multimédia introuvable dans le volet : rien de conservé'
    $src.Dispose(); [IO.File]::Delete($tmp)
    Sh "find /sdcard/Download -maxdepth 1 -name 'dmf_capture_tmp*' -delete" | Out-Null
    Sh 'cmd statusbar collapse' | Out-Null
    return
}
"carte : $($best.id)  $($best.w) x $($best.h) px"
$dst = New-Object Drawing.Bitmap($best.w, $best.h)
$g = [Drawing.Graphics]::FromImage($dst)
$g.DrawImage($src, (New-Object Drawing.Rectangle(0, 0, $best.w, $best.h)), (New-Object Drawing.Rectangle($best.x, $best.y, $best.w, $best.h)), [Drawing.GraphicsUnit]::Pixel)
# La pastille de sortie audio (haut droit) porte le nom d'un appareil personnel : on la recouvre
# du fond de la carte. Position relevée sur une carte de 982 x 564 px, mise à l'échelle sinon.
$sx = $best.w / 982.0; $sy = $best.h / 564.0
$mask = New-Object Drawing.SolidBrush($dst.GetPixel([int](500 * $sx), [int](420 * $sy)))
$g.FillRectangle($mask, [int](605 * $sx), [int](40 * $sy), [int](345 * $sx), [int](110 * $sy))
$mask.Dispose()
$out = Join-Path $PSScriptRoot "img\$Name.png"
$dst.Save($out); $g.Dispose(); $dst.Dispose(); $src.Dispose()
[IO.File]::Delete($tmp)
Sh "find /sdcard/Download -maxdepth 1 -name 'dmf_capture_tmp*' -delete" | Out-Null
Sh 'cmd statusbar collapse' | Out-Null
"enregistré : $out"
