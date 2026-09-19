# SPDX-License-Identifier: Apache-2.0 — Copyright 2026 Steve Nodock <stb@outlook.fr>
# Produit le guide utilisateur en PDF à partir de user-guide.html, avec Edge sans interface.
# L'image de la fiche des gammes (img/08-sheet-en.png) est rendue depuis sa source HTML.
# Les captures de l'app (img/01..07) viennent de capture.ps1, sur un appareil connecté.
$ErrorActionPreference = 'Stop'
$edge = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe", "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $edge) { throw 'Microsoft Edge introuvable' }
$profile = Join-Path $env:TEMP 'duomix-edge-pdf'
$here = $PSScriptRoot -replace '\\', '/'

$sheetSource = ((Resolve-Path (Join-Path $PSScriptRoot '..\scales\scales-of-harmonies.html')).Path) -replace '\\', '/'
$sheetImage = Join-Path $PSScriptRoot 'img\08-sheet-en.png'
Start-Process -FilePath $edge -Wait -ArgumentList '--headless=new', '--disable-gpu', '--hide-scrollbars', "--user-data-dir=$profile",
    '--window-size=794,1123', '--force-device-scale-factor=2', '--virtual-time-budget=3000', "--screenshot=$sheetImage", "`"file:///$sheetSource`?lang=en`""

$pdf = Join-Path $PSScriptRoot 'DuoMix-Fader-User-Guide.pdf'
Start-Process -FilePath $edge -Wait -ArgumentList '--headless=new', '--disable-gpu', "--user-data-dir=$profile",
    '--no-pdf-header-footer', '--virtual-time-budget=4000', "--print-to-pdf=$pdf", "`"file:///$here/user-guide.html`""
$pages = ([regex]::Matches([IO.File]::ReadAllText($pdf, [Text.Encoding]::GetEncoding(28591)), '/Type\s*/Page[^s]')).Count
'{0}  {1:N0} octets  {2} pages' -f (Split-Path $pdf -Leaf), (Get-Item $pdf).Length, $pages
