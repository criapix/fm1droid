# Roteiro de teste em aparelho (arm64)

Pensado para o Galaxy S25 Ultra (tela 19,5:9, ~3120×1440). Anote os resultados na coluna da direita.

## Preparação
1. Ative "Depuração USB" no aparelho e conecte ao PC.
2. No PowerShell: `. .\tools\vsenv.ps1` e depois `.\gradlew.bat :app:installDebug`.
3. Para medir o desempenho puro: `.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.criapix.fm1droid.EngineDeviceTest#clockSweep"` e `adb logcat -d -s fm1droid`.

## Roteiro
| # | Teste | Esperado | Resultado |
|---|---|---|---|
| 1 | Instalação limpa | Abre na biblioteca; o catálogo carrega | |
| 2 | Painel na tela | Painel inteiro, centralizado, sem distorção; faixas laterais escuras; nada atrás do recorte da câmera | |
| 3 | Felucca: boot, teclas, 2 dedos, knobs, MASTER | Boota em < 5 s; som; dois dedos tocam duas notas; arrastar knob gira e acende | |
| 4 | Firmware original da M-VAVE | Boota; troca de preset com o encoder PRESETS; `speed` anotada | |
| 5 | Clock | Anote qual clock o modo Automático escolheu e a `speed` do menu ⋮ | |
| 6 | Latência (Baixa/Normal/Segura) | Percepção ao tocar; falhas de áudio por segundo | |
| 7 | Saídas de áudio | Alto-falante, fone com fio, fone Bluetooth; plugar/desplugar tocando | |
| 8 | Save | Salvar preset (botão SAVE do firmware), matar o app no multitarefa, reabrir: preset continua | |
| 9 | Rotação e ciclo de vida | Girar 180°; bloquear/desbloquear; atender uma chamada | |
| 10 | MIDI | Teclado USB: notas tocam; o menu ⋮ mostra "MIDI: 1" | |
| 11 | Firmwares pesados (Melodee, DUELL, SLOOP) | Anotar `speed` e o clock necessário | |

## Medições do emulador (referência, não representa o aparelho)
Emulador x86_64 (API 36) sobre PC Windows, Felucca: 60 MHz → 1,24×, 120 → 0,57×, 160 → 0,52×, 240 → 0,36×. JIT ligado/desligado a 60 MHz: 1,24× / 1,39× (sem ganho aparente em x86_64). O ponto de partida real é o resultado do `clockSweep` no arm64.
