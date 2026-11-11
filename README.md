# FM1 Droid

App Android que emula o sintetizador M-VAVE FM-1 em tela cheia, com painel tocável (multitoque), áudio de baixa latência por AAudio, entrada MIDI (USB/BLE) e memória flash persistente. Roda o firmware original da M-VAVE e os firmwares da comunidade (`.fwsc`).

O núcleo de emulação é o [fm1-emu](https://github.com/joakimeriksson/fm1-emu) de Joakim Eriksson e colaboradores (licença MIT, submódulo em `vendor/fm1-emu`). Este projeto o embrulha num app Android.

## Instalar
Baixe o APK da [última release](https://github.com/criapix/fm1droid/releases/latest) no celular Android (8.0 ou mais novo, arm64) e abra o arquivo, permitindo a instalação de apps desta fonte. O `.sha256` ao lado serve para conferir o download. As releases são compiladas e assinadas pelo GitHub Actions (`.github/workflows/release.yml`).

## Firmwares
Nenhum firmware vai dentro do APK (licenças diferentes, e o original é proprietário da M-VAVE). Pelo menu ⋮ → Firmwares você pode:
- instalar pelo **catálogo** (lista do fm1-emu; o original é baixado do servidor da própria M-VAVE);
- **importar** um arquivo `.fwsc`;
- abrir um `.fwsc` com "Abrir com" no gerenciador de arquivos.

Só a placa FM-1 é aceita (pacotes do SMK-37 são recusados).

## Tela
O painel tem sempre 820 px lógicos de altura e a **largura acompanha o formato da tela** (1280 a 1900): num celular comprido (19,5:9, como o Galaxy S25 Ultra) os blocos são distribuídos de ponta a ponta — knobs e botões laterais maiores, LCD no centro, teclado esticado por toda a largura —, sem faixas vazias. Em telas mais "quadradas" (tablets) vale o arranjo clássico, centralizado, sem distorcer. O app respeita o recorte da câmera e o botão de menu fica num espaço livre do layout. O desenho e o toque vêm do mesmo layout (`native/fm1droid/src/layout.rs`).

## Feedback tátil
Vibração ao tocar teclas (a força acompanha a altura do toque, ou seja, a velocidade MIDI) e botões, e um tick a cada passo dos encoders com passos (SELECT, PRESETS, ALGORITHM, KNOB1–4). O MASTER é contínuo e não vibra. Liga/desliga e intensidade (Leve/Normal/Forte) ficam nas Configurações. Usa os primitivos de haptics do Android 11+ (`CLICK`/`TICK`), com fallback para versões antigas.

## Controlador MIDI externo
Um teclado MIDI *class-compliant* ligado por USB (no Yamaha CK61: cabo USB-B → USB-C na porta USB TO HOST) toca o FM-1 na hora: as notas e o que mais ele enviar vão para o MIDI IN do firmware. Em ⋮ → **Controle MIDI** dá para associar botões, knobs e sliders do teclado aos controles do painel (MASTER, os 7 encoders e os 14 botões): toque em **Aprender** na linha do controle e mexa no teclado. A linha "Última mensagem recebida" funciona como monitor MIDI.

- **Knob/slider → encoder**: o movimento vira passos do encoder; "Curso dos knobs" define quantos passos cabem numa volta (127, 63 ou 31). **Slider → MASTER**: segue o valor de 0 a 1023.
- **Botão ou programa (PC) → botão do painel**: por padrão cada mensagem é um toque (serve para botões do teclado que alternam entre 127 e 0); **Segurar** faz o botão ficar apertado enquanto o controle estiver "ligado" (valor ≥ 64). Uma **tecla** associada a um botão o segura enquanto estiver pressionada e não soa.
- Bank Select (CC 0 e 32) e mensagens de modo de canal (CC 120–127) nunca são associados. Tudo que não estiver associado continua indo ao firmware como MIDI normal. As associações ficam salvas.

Limites: knobs com batente não giram um encoder sem fim além das pontas (volte o knob e continue); controles em modo relativo, NRPN, pitch bend e aftertouch não são suportados; o arrasto do MASTER na tela guarda o próprio valor, então depois de mexer por MIDI o próximo arrasto na tela pula para o valor antigo da tela.

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

## Publicar uma release
Suba `versionCode`/`versionName` em `app/build.gradle.kts`, faça commit e envie uma tag igual à versão: `git tag v0.2.0 && git push origin v0.2.0`. O workflow compila, assina e cria a release com o APK. Ele usa os secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` e `RELEASE_KEY_PASSWORD` do repositório. **Guarde uma cópia da keystore** (`~/.fm1droid-release/fm1droid-release.jks`): sem a mesma chave, quem já instalou não consegue atualizar. "Run workflow" no GitHub só compila e anexa o APK à execução, sem publicar.

## Avisos
M-VAVE e FM-1 são marcas de seus titulares. Este app não é afiliado à M-VAVE.
