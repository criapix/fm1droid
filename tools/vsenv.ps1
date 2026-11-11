# Dot-source before any cargo command: `. .\tools\vsenv.ps1`
# Forces the VS 2022 Build Tools MSVC environment (the machine also has a VS 18 Community
# without the C++ CRT libs, which rustc would otherwise pick and fail with LNK1104 msvcrt.lib).
$vc = "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
cmd /c "`"$vc`" >nul 2>&1 && set" | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { Set-Item -Path "env:$($Matches[1])" -Value $Matches[2] }
}
$env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"
$env:ANDROID_NDK_HOME = "$env:LOCALAPPDATA\Android\Sdk\ndk\30.0.16248370"
