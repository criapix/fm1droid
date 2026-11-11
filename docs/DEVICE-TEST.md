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
| 2 | Painel na tela | Painel ocupa a tela de ponta a ponta (sem faixas), controles distribuídos, nada atrás do recorte da câmera, botão ⋮ ao lado do LCD | |
| 3 | Felucca: boot, teclas, 2 dedos, knobs, MASTER | Boota em < 5 s; som; dois dedos tocam duas notas; arrastar knob gira e acende | |
| 4 | Firmware original da M-VAVE | Boota; troca de preset com o encoder PRESETS; `speed` anotada | |
| 5 | Clock | Anote qual clock o modo Automático escolheu e a `speed` do menu ⋮ | |
| 6 | Latência (Baixa/Normal/Segura) | Percepção ao tocar; falhas de áudio por segundo | |
| 7 | Saídas de áudio | Alto-falante, fone com fio, fone Bluetooth; plugar/desplugar tocando | |
| 8 | Save | Salvar preset (botão SAVE do firmware), matar o app no multitarefa, reabrir: preset continua | |
| 9 | Rotação e ciclo de vida | Girar 180°; bloquear/desbloquear; atender uma chamada | |
| 10 | MIDI | Teclado USB: notas tocam; o menu ⋮ mostra "MIDI: 1" | |
| 11 | Feedback tátil | Teclas e botões vibram ao tocar (tecla tocada mais embaixo = mais forte); cada passo de SELECT/PRESETS/ALGORITHM/KNOB1–4 dá um tick; o MASTER não vibra; Configurações liga/desliga e muda Leve/Normal/Forte | |
| 12 | Firmwares pesados (Melodee, DUELL, SLOOP) | Anotar `speed` e o clock necessário | |

## Controlador MIDI externo (Yamaha CK61)

- [ ] Cabo USB-B → USB-C no USB TO HOST: o menu ⋮ mostra "MIDI: 1" e as teclas tocam o FM-1.
- [ ] De onde sai o som (celular ou alto-falantes do CK, que também é interface de áudio USB)? Anotar. Se o áudio cair ao conectar, anotar.
- [ ] O CK toca os próprios timbres junto? Desligar as seções ou o Local Control e anotar o que funcionou.
- [ ] Controle MIDI → mexer em cada controle do CK e anotar em NOTES.md o que aparece em "Última mensagem recebida" (drawbars, knobs, botões de seção, botões de Live Set, rodas, pedal).
- [ ] Aprender um drawbar em KNOB1: gira nos dois sentidos, um passo por vez em "127 passos".
- [ ] Aprender um slider em MASTER: o volume acompanha de 0 ao máximo.
- [ ] Aprender um botão do CK em HOME: um toque = um HOME. Se disparar duas vezes por toque, ligar "Segurar".
- [ ] Aprender uma tecla grave em PLAY: a tecla não soa mais e aciona o PLAY.
- [ ] Fechar e reabrir o app: as associações continuam.
- [ ] "Limpar tudo": tudo volta a ser MIDI normal.

## Latência com o CK61 como interface de áudio

Com o CK61 ligado por USB e o som saindo por ele, abrir ⋮ → Configurações e anotar em NOTES.md:

- [ ] Linha "Saída de áudio": taxa, bloco, buffer, e se diz "modo de baixa latência" ou "SEM".
- [ ] Linha "Atraso estimado": emulador (ms) e saída do Android (ms).
- [ ] Tocar por 2 minutos em "Automática": a reserva subiu? Para quanto? Houve estalos depois do primeiro minuto?
- [ ] Repetir as três leituras com o som saindo pelo alto-falante do celular (sem o CK), para comparar.
- [ ] Impressão ao tocar: o atraso diminuiu em relação à versão anterior? Ainda incomoda?

## Medições do emulador (referência, não representa o aparelho)
Emulador x86_64 (API 36) sobre PC Windows, Felucca: 60 MHz → 1,24×, 120 → 0,57×, 160 → 0,52×, 240 → 0,36×. JIT ligado/desligado a 60 MHz: 1,24× / 1,39× (sem ganho aparente em x86_64). O ponto de partida real é o resultado do `clockSweep` no arm64.
