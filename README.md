# FM1 Droid

App Android que emula o sintetizador M-VAVE FM-1 em tela cheia, com painel tocável (multitoque), áudio de baixa latência por AAudio, entrada MIDI (USB/BLE) e memória flash persistente. Roda o firmware original da M-VAVE e os firmwares da comunidade (`.fwsc`).

O núcleo de emulação é o [fm1-emu](https://github.com/joakimeriksson/fm1-emu) de Joakim Eriksson e colaboradores (licença MIT, submódulo em `vendor/fm1-emu`). Este projeto o embrulha num app Android.

## Firmwares
Nenhum firmware vai dentro do APK (licenças diferentes, e o original é proprietário da M-VAVE). Pelo menu ⋮ → Firmwares você pode:
- instalar pelo **catálogo** (lista do fm1-emu; o original é baixado do servidor da própria M-VAVE);
- **importar** um arquivo `.fwsc`;
- abrir um `.fwsc` com "Abrir com" no gerenciador de arquivos.

Só a placa FM-1 é aceita (pacotes do SMK-37 são recusados).

## Tela
O painel (1280×820) é ajustado à tela: em aparelhos compridos (19,5:9) escala pela altura e fica centralizado, com faixas na cor de fundo do painel; em tablets escala pela largura. O app respeita o recorte da câmera.

## Desempenho
O firmware roda no clock real de 240 MHz emulado. No modo **Automático** (padrão), se o áudio falhar por 5 s seguidos o app reduz o clock sozinho (60–240 MHz); escolher um clock nas Configurações desliga o automático.

## Compilar (Windows)
Pré-requisitos: Rust + `cargo-ndk`, alvos `aarch64-linux-android` e `x86_64-linux-android`, NDK 30.0.16248370, JDK 17, VS 2022 Build Tools (C++).
```powershell
git submodule update --init
. .\tools\vsenv.ps1          # ambiente MSVC + NDK (veja docs/NOTES.md)
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17..."
.\gradlew.bat :app:assembleDebug
```
Testes: `cd native\fm1droid; cargo test` (precisa de `.\tools\fetch-testfw.ps1` antes) e `.\gradlew.bat :app:testDebugUnitTest`.

Release assinado: crie uma chave com `keytool` fora do repositório e defina em `local.properties` `release.keystore`, `release.storePassword`, `release.keyAlias`, `release.keyPassword`; depois `.\gradlew.bat :app:assembleRelease`.

## Avisos
M-VAVE e FM-1 são marcas de seus titulares. Este app não é afiliado à M-VAVE.
