# Baixa firmwares de teste (não versionados) para native/fm1droid/testdata.
$dir = Join-Path $PSScriptRoot "..\native\fm1droid\testdata"
New-Item -ItemType Directory -Force $dir | Out-Null
Invoke-WebRequest "https://thefirmwarehub.com/fw/felucca-1.8.2-beta.fwsc" -OutFile (Join-Path $dir "felucca.fwsc")
Get-ChildItem $dir
