# SPDX-License-Identifier: Apache-2.0 — Copyright 2026 Steve Nodock <stb@outlook.fr>
# Visuels de la fiche Google Play, produits depuis l'icône et les captures du guide :
#  - feature-graphic.png : bannière 1024 x 500 (icône + titre + accroche) ;
#  - screenshot-NN.png   : captures téléphone au format 9:16 (1080 x 1920) exigé par Play —
#    la capture d'origine (rognée à notre app seule) est posée sur un fond sombre, avec une
#    légende en haut. Rien d'autre que nos propres écrans n'y figure.
# Compte aussi les caractères des textes de listing.md (limites de la console).
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$here = $PSScriptRoot
$out = Join-Path $here 'out'
New-Item -ItemType Directory -Force $out | Out-Null
$img = Join-Path $here '..\user-guide\img'
$icon = [Drawing.Image]::FromFile((Join-Path $here '..\icon\duomix-fader-icon-512.png'))
$bg = [Drawing.Color]::FromArgb(255, 16, 15, 20)
$ink = [Drawing.Color]::FromArgb(255, 230, 225, 233)
$soft = [Drawing.Color]::FromArgb(255, 170, 165, 175)
$blue = [Drawing.Color]::FromArgb(255, 156, 195, 255)
$red = [Drawing.Color]::FromArgb(255, 255, 158, 148)

function Canvas([int]$w, [int]$h) {
    $b = New-Object Drawing.Bitmap($w, $h)
    $g = [Drawing.Graphics]::FromImage($b)
    $g.SmoothingMode = 'AntiAlias'; $g.InterpolationMode = 'HighQualityBicubic'; $g.TextRenderingHint = 'AntiAliasGridFit'
    $g.Clear($bg)
    return $b, $g
}
function Tiles($g, [float]$x, [float]$y, [float]$size, [string]$code) {
    # Une rangée de pavés, comme dans l'app : B = bleu, R = rouge
    $i = 0
    foreach ($c in $code.ToCharArray()) {
        $brush = New-Object Drawing.SolidBrush($(if ($c -eq 'B') { $blue } else { $red }))
        $g.FillRectangle($brush, $x + $i * ($size * 1.18), $y, $size, $size)
        $brush.Dispose(); $i++
    }
}

# --- Bannière 1024 x 500 ---
$b, $g = Canvas 1024 500
$g.DrawImage($icon, (New-Object Drawing.Rectangle(70, 110, 280, 280)))
$title = New-Object Drawing.Font('Segoe UI', 54, [Drawing.FontStyle]::Bold)
$sub = New-Object Drawing.Font('Segoe UI', 24)
$g.DrawString('DuoMix Fader', $title, (New-Object Drawing.SolidBrush($ink)), 390, 118)
$g.DrawString('Two apps at once, one crossfader.', $sub, (New-Object Drawing.SolidBrush($soft)), 396, 215)
$g.DrawString('Scale and chords, while the music plays.', $sub, (New-Object Drawing.SolidBrush($soft)), 396, 258)
Tiles $g 400 330 44 'RRBBBRRR'
$g.Dispose(); $b.Save((Join-Path $out 'feature-graphic.png')); $b.Dispose()
'feature-graphic.png  1024 x 500'

# --- Captures 9:16 : [fichier source, légende] ---
$shots = @(
    @('01-mixer-top.png',        'Shizuku, Harmony, apps to mix'),
    @('02-mixer-bottom.png',     'Two sliders and a crossfader'),
    @('03-harmony-top.png',      'The scale, its colour tiles, the chords'),
    @('06-history.png',          'History, one section per piece'),
    @('09-notification-card.png','The fader in your notifications'),
    @('07-scale-sheet.png',      'The 33 Scales of Harmonies, colour-coded')
)
$n = 0
foreach ($s in $shots) {
    $src = [Drawing.Image]::FromFile((Join-Path $img $s[0]))
    $b, $g = Canvas 1080 1920
    $cap = New-Object Drawing.Font('Segoe UI', 34, [Drawing.FontStyle]::Bold)
    $fmt = New-Object Drawing.StringFormat; $fmt.Alignment = 'Center'
    $g.DrawString($s[1], $cap, (New-Object Drawing.SolidBrush($ink)), (New-Object Drawing.RectangleF(60, 70, 960, 120)), $fmt)
    # L'image d'origine, à l'échelle, centrée sous la légende, coins arrondis par un masque simple
    $maxW = 900; $maxH = 1640
    $scale = [Math]::Min($maxW / $src.Width, $maxH / $src.Height)
    $w = [int]($src.Width * $scale); $h = [int]($src.Height * $scale)
    $x = [int]((1080 - $w) / 2); $y = 210 + [int](($maxH - $h) / 2)
    $path = New-Object Drawing.Drawing2D.GraphicsPath
    $r = 36
    $path.AddArc($x, $y, $r, $r, 180, 90); $path.AddArc($x + $w - $r, $y, $r, $r, 270, 90)
    $path.AddArc($x + $w - $r, $y + $h - $r, $r, $r, 0, 90); $path.AddArc($x, $y + $h - $r, $r, $r, 90, 90)
    $path.CloseFigure()
    $g.SetClip($path)
    $g.DrawImage($src, (New-Object Drawing.Rectangle($x, $y, $w, $h)))
    $g.ResetClip()
    $g.Dispose(); $n++
    $name = 'screenshot-{0:00}.png' -f $n
    $b.Save((Join-Path $out $name)); $b.Dispose(); $src.Dispose()
    "$name  1080 x 1920  <- $($s[0])"
}
$icon.Dispose()

# --- Longueur des textes de la fiche ---
# Limites par nom de section (accents tolérés par les regex)
function Limit([string]$name) {
    switch -Regex ($name) {
        '^(Title|Titre|T.+tulo)$'                                        { 30 }
        '^(Short description|Description courte|Descripci.+n breve)$'    { 80 }
        '^(Full description|Description compl.+te|Descripci.+n completa)$' { 4000 }
        '^(What.s new|Nouveaut.+s|Novedades)'                            { 500 }
        default                                                          { 0 }
    }
}
$text = Get-Content (Join-Path $here 'listing.md') -Raw -Encoding UTF8
$sections = [regex]::Split($text, '(?m)^### ')
''; 'Textes de listing.md (longueur / limite) :'
foreach ($sec in $sections | Select-Object -Skip 1) {
    $lines = $sec -split "`n", 2
    $name = $lines[0].Trim()
    $body = ($lines[1] -split '(?m)^---')[0].Trim()
    $limit = Limit $name
    if ($limit -gt 0) {
        $len = $body.Length
        $ok = if ($len -le $limit) { 'ok' } else { 'TROP LONG' }
        '  {0,-24} {1,5} / {2,-5} {3}' -f $name, $len, $limit, $ok
    }
}
