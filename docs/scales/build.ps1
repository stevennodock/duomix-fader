# SPDX-License-Identifier: Apache-2.0 — Copyright 2026 Steve Nodock <stb@outlook.fr>
# Produit les trois PDF de la fiche « The Scales of Harmonies » (en, fr, es) à partir de
# scales-of-harmonies.html, avec Edge sans interface, dans les assets de l'app.
$ErrorActionPreference = 'Stop'
$edge = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe", "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $edge) { throw 'Microsoft Edge introuvable' }
$source = (Join-Path $PSScriptRoot 'scales-of-harmonies.html') -replace '\\', '/'
$target = Join-Path $PSScriptRoot '..\..\app\src\main\assets\docs'
New-Item -ItemType Directory -Force $target | Out-Null
$profile = Join-Path $env:TEMP 'duomix-edge-pdf'
foreach ($lang in 'en', 'fr', 'es') {
    $pdf = Join-Path (Resolve-Path $target) "scales-of-harmonies-$lang.pdf"
    Start-Process -FilePath $edge -Wait -ArgumentList '--headless=new', '--disable-gpu', "--user-data-dir=$profile",
        '--no-pdf-header-footer', '--virtual-time-budget=3000', "--print-to-pdf=$pdf", "`"file:///$source`?lang=$lang`""
    $pages = ([regex]::Matches([IO.File]::ReadAllText($pdf, [Text.Encoding]::GetEncoding(28591)), '/Type\s*/Page[^s]')).Count
    '{0}  {1:N0} octets  {2} page(s)' -f (Split-Path $pdf -Leaf), (Get-Item $pdf).Length, $pages
    if ($pages -ne 1) { Write-Warning "$lang : la fiche doit tenir sur une page" }
}