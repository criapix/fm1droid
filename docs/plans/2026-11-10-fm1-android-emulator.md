# FM1 Droid: emulador nativo do M-VAVE FM-1 para Android — Plano de Implementação

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** App Android nativo, em tela cheia, que emula o hardware do M-VAVE FM-1 com fidelidade e roda qualquer firmware `.fwsc` (o original da M-VAVE e os comunitários), com painel tocável, áudio de baixa latência, MIDI e memória flash persistente.

**Architecture:** O núcleo de emulação NÃO é escrito por nós: é o projeto open-source `joakimeriksson/fm1-emu` (MIT, Rust), que emula a CPU pi32v2, o SoC, a tela, as teclas e o áudio, com JIT para AArch64. Nós escrevemos (1) um crate Rust fino, `fm1droid`, que roda a máquina numa thread própria, entrega áudio por AAudio e expõe uma API JNI; e (2) um app Kotlin + Jetpack Compose que desenha o painel (o mesmo desenho do fm1-emu), trata toque, biblioteca de firmwares, saves e MIDI.

**Tech Stack:** Rust stable + cargo-ndk, crates `fm1-core`/`fm1-soc` (submódulo git), `rtrb`, `jni` 0.21, AAudio por FFI direta; Kotlin, Jetpack Compose, kotlinx-serialization-json, `android.media.midi`; minSdk 26, ABIs `arm64-v8a` e `x86_64`.

---

## 0. Contexto que o executor precisa ter

### 0.1 O que é o FM-1 e o que já existe

- O M-VAVE FM-1 é um sintetizador FM de bolso (chip JieLi AC791N, CPU pi32v2 de dois núcleos, tela 240×240 RGB565, 27 teclas, 14 botões, 7 encoders, 1 potenciômetro MASTER, áudio I2S a 44,1 kHz).
- `vendor/fm1-emu` (submódulo, fixado no commit `3e3fb27df91239fe0e7c5bbf82b14c20ad7dd62a`) emula tudo isso. Roda o firmware original (V13, V15, V16) e ~156 firmwares comunitários, sem alterações, a partir do pacote de atualização `.fwsc`.
- **Leia antes de codar** (são a referência de cada parte do nosso crate):
  - `vendor/fm1-emu/crates/fm1-plugin/src/engine.rs`: a máquina numa thread, adiantada em relação ao áudio. Nosso `engine.rs` é uma versão reduzida dele.
  - `vendor/fm1-emu/crates/fm1-web/src/lib.rs`: API C completa (boot, entrada, painel, flash, banco DX7). Nossa API JNI espelha a dele.
  - `vendor/fm1-emu/crates/fm1-live/src/panel.rs`: desenho e hit-test do painel (1280×820). Incluímos esse arquivo por `#[path]`, como o `fm1-web` faz.
  - `vendor/fm1-emu/docs/stock-firmware.md`: particularidades do firmware original.

### 0.2 Decisões já tomadas (não reabrir)

| Decisão | Motivo |
|---|---|
| Embrulhar o `fm1-emu` em vez de emular o chip do zero | Já roda todos os firmwares, com validação contra hardware feita pelos autores. O usuário não tem o aparelho. |
| Nativo em **Rust**, sem C++ | O núcleo é Rust. Um único `libfm1droid.so` gerado por `cargo-ndk` dispensa CMake e Oboe. |
| Áudio por **AAudio via FFI direta**, na taxa nativa do aparelho, com reamostragem própria de 44,1 kHz | Pedir 44,1 kHz ao AAudio tira o stream do caminho de baixa latência na maioria dos aparelhos (48 kHz). |
| Painel desenhado pelo `panel.rs` do fm1-emu num bitmap 1280×820, escalado na tela | Fidelidade visual e de hit-test sem redesenhar o painel em Compose. |
| Só a placa FM-1 (pacotes do SMK-37 são recusados com mensagem) | YAGNI. |
| Nenhum firmware embutido no APK | Licenças (GPL de vários firmwares, firmware proprietário da M-VAVE). O app baixa do site do autor ou importa arquivo. |
| Nome do app "FM1 Droid", id `com.criapix.fm1droid`, com aviso de não afiliação à M-VAVE | Marca de terceiros. |

### 0.3 Riscos conhecidos e portões de verificação

1. **O código Rust/Kotlin deste plano não foi compilado** (a máquina de planejamento não tinha Rust nem NDK). As assinaturas do `fm1-emu` foram conferidas no código-fonte, mas espere pequenos ajustes de compilação. Em caso de divergência, a fonte da verdade é o código em `vendor/fm1-emu`.
2. **Desempenho no celular é desconhecido.** O fm1-emu roda o clock real de 240 MHz com JIT em Mac/PC; em celular ninguém mediu. Portão na Task 9: medir `speed` (tempo emulado / tempo gasto). Abaixo de 1,3× no clock escolhido, baixar o clock padrão (opções 60–240 MHz) antes de seguir.
3. **JIT no Android**: `fm1-core/src/jit.rs` tem `cfg(target_os = "android")` e usa `mmap` anônimo RWX. Não há registro de teste em Android. Portão na Task 9: se o processo morrer por SELinux/`mmap`, bootar com `cpu.jit_on = false` e clock menor, e registrar o achado em `docs/NOTES.md`.
4. **Caminho do repositório**: o diretório atual tem espaço no nome e fica dentro do OneDrive. Builds de NDK/Gradle/Cargo ali são lentos e sujeitos a travas de arquivo. A Task 1 move o trabalho para `C:\dev\fm1droid`.
5. **Sem hardware para comparar**: a fidelidade é a do fm1-emu. Não tente "corrigir" comportamento de firmware no nosso código.

### 0.4 Estrutura final do repositório

```
fm1droid/
├── vendor/fm1-emu/                  submódulo (não editar)
├── native/fm1droid/                 crate Rust
│   ├── Cargo.toml
│   ├── .cargo/config.toml
│   ├── testdata/                    (gitignored) firmwares de teste baixados
│   └── src/
│       ├── lib.rs
│       ├── resample.rs              44,1 kHz → taxa do aparelho
│       ├── flashrec.rs              registros de setores de flash (save)
│       ├── engine.rs                thread da máquina
│       ├── ui.rs                    estado e desenho do painel
│       ├── app.rs                   estado global, fila de comandos
│       ├── aaudio.rs                (android) stream de saída
│       └── jni_api.rs               (android) funções JNI
├── tools/fetch-testfw.ps1
├── app/                             app Android (Kotlin + Compose)
│   └── src/main/java/com/criapix/fm1droid/
│       ├── Native.kt  MainActivity.kt  Emu.kt
│       ├── panel/  PanelGeometry.kt  PanelTouch.kt  PanelScreen.kt
│       ├── data/   FirmwareStore.kt  SaveStore.kt  Catalog.kt  Prefs.kt
│       ├── midi/   MidiInput.kt
│       └── ui/     LibrarySheet.kt  SettingsSheet.kt
└── docs/  plans/  NOTES.md  DEVICE-TEST.md
```

### 0.5 Convenções

- TDD onde há lógica pura (Rust: `resample`, `flashrec`, `engine`; Kotlin: `PanelGeometry`, `PanelTouch`, `FirmwareStore`, `SaveStore`, `Catalog`). UI Compose e JNI são verificados no emulador/aparelho.
- Um commit por task, mensagem em inglês no estilo `feat: …` / `test: …` / `chore: …`.
- Comandos em PowerShell. `$REPO` = `C:\dev\fm1droid`.

---

## Marco M0 — Ambiente e esqueleto

### Task 1: Repositório, submódulo e ferramentas

**Step 1: Criar o repositório fora do OneDrive**

```powershell
New-Item -ItemType Directory -Force C:\dev\fm1droid | Out-Null
Copy-Item -Recurse "C:\Users\criap\OneDrive\Documentos\Github\mvave fm1\docs" C:\dev\fm1droid\
Set-Location C:\dev\fm1droid
git init -b main
git submodule add https://github.com/joakimeriksson/fm1-emu.git vendor/fm1-emu
git -C vendor/fm1-emu checkout 3e3fb27df91239fe0e7c5bbf82b14c20ad7dd62a
```

Se o usuário preferir manter o repositório no diretório original, pergunte antes; não decida sozinho.

**Step 2: Instalar ferramentas que faltam** (conferido em 2026-11-10: há JDK 11, Android SDK com `platforms;android-36` e `build-tools;36.0.0`, `adb`, e o CLI `android`; faltam Rust, NDK e JDK 17)

```powershell
winget install --id Rustlang.Rustup -e
winget install --id Microsoft.OpenJDK.17 -e
# novo terminal depois disto
rustup default stable
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
```

NDK: use a skill `android-cli` para instalar o pacote `ndk` mais recente (r27 ou posterior) e uma imagem de emulador `x86_64` API 34+. Anote a versão exata do NDK; ela vai em `ndkVersion` (Task 8).

**Step 3: Verificar**

```powershell
cargo --version; cargo ndk --version; java -version
Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\ndk"
```
Expected: versões impressas, JDK 17, uma pasta de NDK.

**Step 4: `.gitignore` e commit**

```gitignore
target/
native/fm1droid/testdata/
app/src/main/jniLibs/
.gradle/
build/
local.properties
*.jks
```

```powershell
git add -A; git commit -m "chore: repository skeleton with fm1-emu submodule"
```

### Task 2: Crate Rust vazio que compila contra o fm1-emu

**Files:**
- Create: `native/fm1droid/Cargo.toml`
- Create: `native/fm1droid/.cargo/config.toml`
- Create: `native/fm1droid/src/lib.rs`

**Step 1: `Cargo.toml`**

```toml
[package]
name = "fm1droid"
version = "0.1.0"
edition = "2021"
license = "MIT"

[lib]
crate-type = ["cdylib", "rlib"]

[dependencies]
fm1-core = { path = "../../vendor/fm1-emu/crates/fm1-core" }
fm1-soc = { path = "../../vendor/fm1-emu/crates/fm1-soc" }
rtrb = "0.3"

[target.'cfg(target_os = "android")'.dependencies]
jni = "0.21"

[profile.release]
opt-level = 3
lto = true

# os testes de máquina rodam emulação pesada: otimizar também em `cargo test`
[profile.test]
opt-level = 3
```

**Step 2: `.cargo/config.toml`** (bibliotecas alinhadas a 16 KB, exigência de aparelhos Android 15+)

```toml
[target.aarch64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]

[target.x86_64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]
```

**Step 3: `src/lib.rs`**

```rust
//! FM1 Droid: the fm1-emu machine for Android (engine thread, audio, panel, JNI).

#[allow(dead_code)]
#[path = "../../../vendor/fm1-emu/crates/fm1-live/src/panel.rs"]
mod panel;

#[cfg(test)]
mod smoke {
    #[test]
    fn links_against_fm1_emu() {
        assert_eq!(crate::panel::W, 1280);
        assert_eq!(crate::panel::H, 820);
        assert_eq!(fm1_soc::FLASH_SIZE % 4096, 0);
    }
}
```

**Step 4: Rodar**

```powershell
Set-Location C:\dev\fm1droid\native\fm1droid; cargo test
```
Expected: `1 passed`. Se `panel.rs` reclamar de itens ausentes, veja como `vendor/fm1-emu/crates/fm1-web/src/lib.rs` (linhas 19–22) o inclui e replique.

**Step 5: Commit** — `git commit -am "chore: fm1droid crate links fm1-core and fm1-soc"`

---

## Marco M1 — Núcleo Rust validado no PC (sem Android)

Este marco prova, com `cargo test` no Windows, que conseguimos bootar um firmware real e tirar som e imagem dele. Só depois disso se toca em Android.

### Task 3: Reamostrador

**Files:** Create `native/fm1droid/src/resample.rs`; Modify `src/lib.rs` (`pub mod resample;`)

**Step 1: Testes (falham: módulo não existe)**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_rate_passes_samples_through_delayed() {
        let mut r = Resampler::new(44_100, 44_100);
        let mut src = (1..=100).map(|i| [i as f32, -(i as f32)]);
        let mut out = vec![0.0f32; 20];
        assert_eq!(r.process(&mut out, || src.next()), 0);
        // interpolação cúbica com t = 0 devolve a amostra de dois quadros atrás
        assert_eq!(&out[4..10], &[1.0, -1.0, 2.0, -2.0, 3.0, -3.0]);
    }

    #[test]
    fn upsampling_consumes_source_at_the_rate_ratio() {
        let mut r = Resampler::new(44_100, 48_000);
        let mut pulled = 0u32;
        let mut out = vec![0.0f32; 48_000 * 2];
        r.process(&mut out, || { pulled += 1; Some([0.5, 0.5]) });
        assert!((44_099..=44_101).contains(&pulled), "pulled {pulled}");
        assert!(out[1000..].iter().all(|s| (s - 0.5).abs() < 1e-4), "DC must stay DC");
    }

    #[test]
    fn starvation_is_counted_and_silent() {
        let mut r = Resampler::new(44_100, 44_100);
        let mut out = vec![1.0f32; 16];
        assert_eq!(r.process(&mut out, || None), 8);
        assert!(out.iter().all(|s| *s == 0.0));
    }
}
```

**Step 2:** `cargo test resample` → FAIL (não compila).

**Step 3: Implementação**

```rust
//! 44.1 kHz (the firmware's I2S rate) to the device's rate: Catmull-Rom over four frames.

pub struct Resampler {
    step: f64,
    frac: f64,
    h: [[f32; 2]; 4],
}

impl Resampler {
    pub fn new(src_hz: u32, dst_hz: u32) -> Self {
        Resampler { step: src_hz as f64 / dst_hz as f64, frac: 0.0, h: [[0.0; 2]; 4] }
    }

    /// Fill `out` (interleaved stereo). `pull` gives the next source frame, `None` when the
    /// ring is empty (a silent frame is used). Returns how many frames were missing.
    pub fn process(&mut self, out: &mut [f32], mut pull: impl FnMut() -> Option<[f32; 2]>) -> u32 {
        let mut starved = 0;
        for f in out.chunks_exact_mut(2) {
            self.frac += self.step;
            while self.frac >= 1.0 {
                self.frac -= 1.0;
                let s = pull().unwrap_or_else(|| {
                    starved += 1;
                    [0.0, 0.0]
                });
                self.h = [self.h[1], self.h[2], self.h[3], s];
            }
            let t = self.frac as f32;
            for c in 0..2 {
                let (p0, p1, p2, p3) = (self.h[0][c], self.h[1][c], self.h[2][c], self.h[3][c]);
                f[c] = p1 + 0.5 * t * (p2 - p0 + t * (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3 + t * (3.0 * (p1 - p2) + p3 - p0)));
            }
        }
        starved
    }
}
```

**Step 4:** `cargo test resample` → 3 passed. Se o primeiro teste falhar por um quadro de deslocamento, ajuste o índice esperado no teste (o atraso exato é detalhe de implementação), não a fórmula.

**Step 5:** Commit `feat: stereo resampler for the audio output`.

### Task 4: Registros de flash (formato do save)

O save é a lista dos setores de 4096 bytes que diferem do flash original do pacote. Formato (o mesmo dos saves do fm1-emu web): para cada setor, 4 bytes little-endian com o offset, seguidos dos 4096 bytes.

**Files:** Create `native/fm1droid/src/flashrec.rs`; Modify `src/lib.rs` (`pub mod flashrec;`)

**Step 1: Testes**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn diff_lists_only_changed_sectors() {
        let base = vec![0xAAu8; SECTOR * 4];
        let mut now = base.clone();
        now[SECTOR * 2 + 5] = 0x12;
        let rec = diff(&now, &base);
        assert_eq!(rec.len(), RECORD);
        assert_eq!(u32::from_le_bytes(rec[..4].try_into().unwrap()), (SECTOR * 2) as u32);
        assert_eq!(rec[4 + 5], 0x12);
    }

    #[test]
    fn parse_round_trips_and_rejects_garbage() {
        let base = vec![0u8; SECTOR * 4];
        let mut now = base.clone();
        now[0] = 1;
        now[SECTOR * 3] = 2;
        let rec = diff(&now, &base);
        let parsed = parse(&rec, SECTOR * 4).unwrap();
        assert_eq!(parsed.iter().map(|s| s.0).collect::<Vec<_>>(), vec![0, (SECTOR * 3) as u32]);
        assert!(parse(&rec[..rec.len() - 1], SECTOR * 4).is_err(), "truncated");
        assert!(parse(&rec, SECTOR * 2).is_err(), "offset past the flash");
        let mut dup = rec[..RECORD].to_vec();
        dup.extend_from_slice(&rec[..RECORD]);
        assert!(parse(&dup, SECTOR * 4).is_err(), "duplicate sector");
        assert!(parse(&[], SECTOR * 4).unwrap().is_empty());
    }
}
```

**Step 3: Implementação**

```rust
//! Flash saves: the 4096-byte sectors that differ from the package's flash, each as a
//! 4-byte little-endian offset followed by the sector.

pub const SECTOR: usize = 4096;
pub const RECORD: usize = SECTOR + 4;

pub fn diff(now: &[u8], base: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    for s in 0..now.len().min(base.len()) / SECTOR {
        let r = s * SECTOR..(s + 1) * SECTOR;
        if now[r.clone()] != base[r.clone()] {
            out.extend_from_slice(&((s * SECTOR) as u32).to_le_bytes());
            out.extend_from_slice(&now[r]);
        }
    }
    out
}

pub fn parse(bytes: &[u8], flash_size: usize) -> Result<Vec<(u32, Vec<u8>)>, String> {
    if bytes.len() % RECORD != 0 {
        return Err("save file has a wrong length".into());
    }
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::new();
    for rec in bytes.chunks_exact(RECORD) {
        let at = u32::from_le_bytes(rec[..4].try_into().unwrap());
        if at as usize % SECTOR != 0 || at as usize + SECTOR > flash_size || !seen.insert(at) {
            return Err("save file has a bad sector offset".into());
        }
        out.push((at, rec[4..].to_vec()));
    }
    Ok(out)
}
```

**Step 4/5:** `cargo test flashrec` → 2 passed. Commit `feat: flash sector records for saves`.

### Task 5: Baixar firmware de teste

**Files:** Create `tools/fetch-testfw.ps1`

```powershell
# Baixa firmwares de teste (não versionados) para native/fm1droid/testdata.
$dir = Join-Path $PSScriptRoot "..\native\fm1droid\testdata"
New-Item -ItemType Directory -Force $dir | Out-Null
Invoke-WebRequest "https://thefirmwarehub.com/fw/felucca-1.8.2-beta.fwsc" -OutFile (Join-Path $dir "felucca.fwsc")
Get-ChildItem $dir
```

Run: `.\tools\fetch-testfw.ps1` → Expected: `felucca.fwsc` com algumas centenas de KB. Se a URL tiver mudado, pegue a atual em `vendor/fm1-emu/web/firmwares.json` (campo `package` do id `felucca`). Commit `chore: script to fetch test firmware`.

### Task 6: Thread da máquina (`engine.rs`)

**Files:** Create `native/fm1droid/src/engine.rs`; Modify `src/lib.rs` (`pub mod engine;`)

**Step 1: Teste de integração (falha: módulo não existe)** — no fim de `engine.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    fn start() -> (mpsc::Sender<Cmd>, Arc<Shared>, rtrb::Consumer<[f32; 2]>) {
        let (tx, rx) = mpsc::channel();
        let shared = Arc::new(Shared::new());
        let (prod, cons) = rtrb::RingBuffer::new(8192);
        let s = shared.clone();
        std::thread::spawn(move || run(s, rx, prod));
        (tx, shared, cons)
    }

    fn boot(tx: &mpsc::Sender<Cmd>, pkg: Vec<u8>, saved: Vec<u8>) -> Result<(), String> {
        let (rtx, rrx) = mpsc::channel();
        tx.send(Cmd::Boot { pkg, saved, syx: vec![], mhz: 240, reply: rtx }).unwrap();
        rrx.recv_timeout(Duration::from_secs(30)).expect("boot reply")
    }

    /// Take `frames` frames as fast as the machine makes them; returns the peak level.
    fn drain(cons: &mut rtrb::Consumer<[f32; 2]>, frames: usize) -> f32 {
        let (mut got, mut peak, t0) = (0, 0f32, Instant::now());
        while got < frames {
            assert!(t0.elapsed() < Duration::from_secs(120), "machine too slow: {got}/{frames}");
            match cons.pop() {
                Ok(f) => {
                    got += 1;
                    peak = peak.max(f[0].abs()).max(f[1].abs());
                }
                Err(_) => std::thread::sleep(Duration::from_millis(1)),
            }
        }
        peak
    }

    fn felucca() -> Vec<u8> {
        std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/testdata/felucca.fwsc"))
            .expect("run tools/fetch-testfw.ps1 first")
    }

    #[test]
    fn garbage_is_refused_without_killing_the_thread() {
        let (tx, shared, _cons) = start();
        assert!(boot(&tx, vec![1, 2, 3], vec![]).is_err());
        assert!(!shared.view.lock().unwrap().booted);
        assert!(boot(&tx, vec![0; 100_000], vec![]).is_err());
    }

    #[test]
    fn a_real_firmware_boots_draws_and_plays_a_key() {
        let (tx, shared, mut cons) = start();
        boot(&tx, felucca(), vec![]).unwrap();
        drain(&mut cons, RATE as usize * 4); // 4 s emulados: boot e tela inicial
        {
            let v = shared.view.lock().unwrap();
            assert!(v.booted && !v.halted);
            assert!(v.lcd.iter().any(|&p| p != 0), "the screen stayed black");
        }
        tx.send(Cmd::Act(Action::Adc { ch: 4, value: 900 })).unwrap(); // MASTER
        tx.send(Cmd::Act(Action::KeyVel { vel: 110 })).unwrap();
        tx.send(Cmd::Act(Action::Key { id: 21, down: true })).unwrap();
        let peak = drain(&mut cons, RATE as usize * 2);
        tx.send(Cmd::Act(Action::Key { id: 21, down: false })).unwrap();
        assert!(peak > 0.005, "a held key made no sound (peak {peak})");
        tx.send(Cmd::Quit).unwrap();
    }
}
```

Se o segundo teste falhar só na asserção de som, investigue antes de afrouxar: confira no `vendor/fm1-emu/crates/fm1-live/src/main.rs` (perto das linhas 1440–1520) como uma tecla é enviada (ordem `KeyVel` → `Key`) e se o Felucca exige alguma tela/modo. O objetivo do teste é "um firmware real produz áudio quando se toca", não um valor exato.

**Step 3: Implementação**

```rust
//! The emulated FM-1 on a thread of its own, keeping `lead` frames of its 44.1 kHz audio in a
//! ring ahead of the audio callback. A cut-down vendor/fm1-emu/crates/fm1-plugin/src/engine.rs.

use crate::flashrec;
use fm1_core::{Bus, Cpu};
use fm1_soc::{machine, Action, Board, Soc};
use std::sync::atomic::AtomicU64;
use std::sync::{mpsc, Arc, Mutex};
use std::time::{Duration, Instant};

const RAM_BASE: u32 = 0x01c0_0000;
const RAM_SIZE: usize = 1 << 20;
/// The firmware's output rate.
pub const RATE: u32 = 44_100;

pub enum Cmd {
    /// Boot a package (.fwsc) with a save (flashrec records, may be empty) and optionally a
    /// DX7 32-voice bank (.syx, may be empty) for the stock firmware's slots 1-32.
    Boot { pkg: Vec<u8>, saved: Vec<u8>, syx: Vec<u8>, mhz: u32, reply: mpsc::Sender<Result<(), String>> },
    Act(Action),
    /// Frames to keep in the ring.
    Lead(usize),
    Pause(bool),
    Quit,
}

pub struct View {
    pub lcd: Vec<u16>,
    pub leds: [f32; 41],
    pub booted: bool,
    pub halted: bool,
    /// emulated time over time spent emulating, over the last second
    pub speed: f32,
    /// the save (flashrec records) and a counter bumped each time it changes
    pub flash: Vec<u8>,
    pub flash_rev: u32,
}

pub struct Shared {
    pub view: Mutex<View>,
    /// frames the audio callback found missing
    pub starved: AtomicU64,
}

impl Shared {
    pub fn new() -> Self {
        Shared {
            view: Mutex::new(View {
                lcd: vec![0; 240 * 240], leds: [0.0; 41], booted: false, halted: false,
                speed: 0.0, flash: Vec::new(), flash_rev: 0,
            }),
            starved: AtomicU64::new(0),
        }
    }
}

struct Machine {
    cpu: Cpu,
    cpu1: Option<Cpu>,
    soc: Soc,
    pending_tick: bool,
    halted: bool,
    /// the flash as the package gives it, to find what the firmware wrote since
    flash0: Vec<u8>,
}

/// Write whole sectors to the flash and to the memory-mapped (XIP) view of it.
fn write_sector(soc: &mut Soc, at: usize, data: &[u8]) {
    soc.flash.mem[at..at + data.len()].copy_from_slice(data);
    let from = at.max(fm1_soc::XIP_FLASH_OFFSET);
    let end = (at + data.len()).min(fm1_soc::XIP_FLASH_OFFSET + soc.xip.len());
    if from < end {
        soc.xip[from - fm1_soc::XIP_FLASH_OFFSET..end - fm1_soc::XIP_FLASH_OFFSET]
            .copy_from_slice(&data[from - at..end - at]);
    }
}

impl Machine {
    fn boot(pkg: &[u8], saved: &[u8], syx: &[u8], mhz: u32) -> Result<Self, String> {
        let sectors = flashrec::parse(saved, fm1_soc::FLASH_SIZE)?;
        let st = std::panic::catch_unwind(|| fm1_soc::stock_from_package(pkg, None))
            .map_err(|_| "malformed firmware package".to_owned())??;
        if st.board != Board::Fm1 {
            return Err("this package is for another device (SMK-37), not the FM-1".into());
        }
        let mut soc = Soc::new(RAM_SIZE, st.xip);
        soc.periph.hz = mhz as u64 * 1_000_000;
        soc.periph.skip_idle = true;
        let mut cpu = Cpu::new(st.entry);
        cpu.jit_on = true;
        cpu.user = true; // the SPL hands cpu0 over in user mode
        let params = RAM_BASE + 0x7fc00;
        soc.write32(params, params + 0x40);
        cpu.regs[0] = fm1_soc::stock_boot(&mut soc, &st.flash, params, st.board);
        let flash0 = soc.flash.mem.clone();
        for (at, data) in &sectors {
            write_sector(&mut soc, *at as usize, data);
        }
        if !syx.is_empty() {
            let usr = st.usr.ok_or("this firmware has no DX7 patch bank")?;
            let mut flash = soc.flash.mem.clone();
            fm1_soc::fwsc::usr_load_syx(&mut flash, usr, st.usr_layout, syx, 0)?;
            for at in (0..flash.len()).step_by(flashrec::SECTOR) {
                if flash[at..at + flashrec::SECTOR] != soc.flash.mem[at..at + flashrec::SECTOR] {
                    let sector = flash[at..at + flashrec::SECTOR].to_vec();
                    write_sector(&mut soc, at, &sector);
                }
            }
        }
        Ok(Machine { cpu, cpu1: None, soc, pending_tick: false, halted: false, flash0 })
    }

    fn run(&mut self, n: u32) {
        let jit = self.cpu.jit_on;
        let Machine { cpu, cpu1, soc, pending_tick, .. } = self;
        if machine::run_dual(cpu1, soc, n, RAM_SIZE, jit, |soc, k| machine::run_blocks(cpu, soc, k, pending_tick)).is_err() {
            self.halted = true;
        }
    }
}

pub fn run(shared: Arc<Shared>, cmds: mpsc::Receiver<Cmd>, mut out: rtrb::Producer<[f32; 2]>) {
    let mut m: Option<Machine> = None;
    let (mut lead, mut paused) = (768usize, false);
    let (mut last_view, mut last_flash) = (Instant::now(), Instant::now());
    let mut last_rec: Vec<u8> = Vec::new();
    let (mut speed_t, mut speed_ms, mut busy) = (Instant::now(), 0u64, Duration::ZERO);
    loop {
        loop {
            match cmds.try_recv() {
                Ok(Cmd::Boot { pkg, saved, syx, mhz, reply }) => {
                    let result = Machine::boot(&pkg, &saved, &syx, mhz).map(|next| {
                        m = Some(next);
                        last_rec.clear();
                        let mut v = shared.view.lock().unwrap();
                        (v.booted, v.halted, v.speed) = (true, false, 0.0);
                        (speed_t, speed_ms, busy) = (Instant::now(), 0, Duration::ZERO);
                    });
                    let _ = reply.send(result);
                }
                Ok(Cmd::Act(a)) => {
                    if let Some(m) = m.as_mut() {
                        m.soc.periph.apply(&a);
                    }
                }
                Ok(Cmd::Lead(n)) => lead = n.clamp(128, 4096),
                Ok(Cmd::Pause(p)) => paused = p,
                Ok(Cmd::Quit) | Err(mpsc::TryRecvError::Disconnected) => return,
                Err(mpsc::TryRecvError::Empty) => break,
            }
        }
        let Some(mm) = m.as_mut() else {
            std::thread::sleep(Duration::from_millis(5));
            continue;
        };
        if paused {
            std::thread::sleep(Duration::from_millis(5));
            continue;
        }
        let fill = out.buffer().capacity() - out.slots();
        if mm.halted || fill >= lead {
            std::thread::sleep(Duration::from_micros(500));
        } else {
            let t0 = Instant::now();
            mm.run(20_000);
            busy += t0.elapsed();
            for f in std::mem::take(&mut mm.soc.periph.audio).chunks_exact(2) {
                let _ = out.push([f[0] as f32 / 32768.0, f[1] as f32 / 32768.0]);
            }
        }
        if last_view.elapsed() >= Duration::from_millis(16) {
            last_view = Instant::now();
            let mut v = shared.view.lock().unwrap();
            v.lcd.copy_from_slice(&mm.soc.lcd.fb);
            v.leds = mm.soc.periph.led_take();
            v.halted = mm.halted;
            if speed_t.elapsed() >= Duration::from_secs(1) {
                let ms = mm.soc.periph.ms();
                if speed_ms > 0 && busy > Duration::ZERO {
                    v.speed = ms.saturating_sub(speed_ms) as f32 / busy.as_secs_f32() / 1000.0;
                }
                (speed_t, speed_ms, busy) = (Instant::now(), ms.max(1), Duration::ZERO);
            }
        }
        if last_flash.elapsed() >= Duration::from_secs(2) && !mm.soc.flash.busy() {
            last_flash = Instant::now();
            let rec = flashrec::diff(&mm.soc.flash.mem, &mm.flash0);
            if rec != last_rec {
                let mut v = shared.view.lock().unwrap();
                v.flash = rec.clone();
                v.flash_rev = v.flash_rev.wrapping_add(1);
                last_rec = rec;
            }
        }
    }
}
```

**Step 4:** `cargo test engine -- --nocapture` → 2 passed (o segundo leva dezenas de segundos). Anote em `docs/NOTES.md` quanto tempo de parede levaram os 6 s emulados: é a primeira medida de desempenho.

**Step 5:** Commit `feat: engine thread boots .fwsc packages and streams audio`.

### Task 7: Persistência verificada (teste de save)

**Files:** Modify `native/fm1droid/src/engine.rs` (testes)

**Step 1: Teste** — um save restaurado tem de aparecer no flash da máquina e voltar no próximo snapshot:

```rust
    #[test]
    fn a_restored_save_comes_back_in_the_next_snapshot() {
        let (tx, shared, mut cons) = start();
        // um setor na área de dados do fim do flash, que o pacote não ocupa
        let at = (fm1_soc::FLASH_SIZE - flashrec::SECTOR) as u32;
        let mut saved = at.to_le_bytes().to_vec();
        saved.extend(vec![0x5A; flashrec::SECTOR]);
        boot(&tx, felucca(), saved.clone()).unwrap();
        drain(&mut cons, RATE as usize * 3); // passa dos 2 s do primeiro snapshot
        let v = shared.view.lock().unwrap();
        assert!(v.flash_rev >= 1);
        let found = flashrec::parse(&v.flash, fm1_soc::FLASH_SIZE).unwrap();
        assert!(found.iter().any(|(a, d)| *a == at && d.iter().all(|b| *b == 0x5A)));
    }
```

Se o firmware apagar esse setor no boot (improvável), escolha outro setor livre lendo o mapa de flash em `vendor/fm1-emu/docs/stock-firmware.md`.

**Step 4/5:** `cargo test engine` → 3 passed. Commit `test: saved flash sectors survive a reboot`.

---

## Marco M2 — Som no Android

### Task 8: Projeto Android + build do Rust pelo Gradle

**Step 1: Gerar o projeto.** Use a skill `android-cli` para criar, na raiz do repositório, um projeto com uma Activity Compose vazia: nome "FM1 Droid", pacote `com.criapix.fm1droid`, minSdk 26, linguagem Kotlin, build Kotlin DSL. Mantenha as versões de AGP/Kotlin/Compose que o gerador escolher; exige JDK 17.

**Step 2: `app/build.gradle.kts`** — acrescente:

```kotlin
plugins {
    // …os existentes…
    kotlin("plugin.serialization") version "<mesma versão do Kotlin do projeto>"
}

android {
    ndkVersion = "<versão instalada na Task 1>"
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3") // ou a mais recente
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:<versão do lifecycle do projeto>")
    testImplementation("junit:junit:4.13.2")
}

val cargoNdk = tasks.register<Exec>("cargoNdk") {
    workingDir = rootProject.file("native/fm1droid")
    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "--platform", "26",
        "-o", rootProject.file("app/src/main/jniLibs").absolutePath,
        "build", "--release",
    )
}
tasks.named("preBuild") { dependsOn(cargoNdk) }
```

**Step 3: `AndroidManifest.xml`**

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-feature android:name="android.software.midi" android:required="false" />
<!-- na <activity> principal: -->
android:screenOrientation="sensorLandscape"
android:configChanges="orientation|screenSize|screenLayout|keyboard|keyboardHidden|navigation"
android:launchMode="singleTask"
```

**Step 4:** `.\gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL e `app/src/main/jniLibs/arm64-v8a/libfm1droid.so` existe. **Step 5:** Commit `chore: android project builds the rust library with cargo-ndk`.

### Task 9: Estado global, AAudio e JNI mínimos; primeiro som no emulador

**Files:**
- Create: `native/fm1droid/src/app.rs`, `src/aaudio.rs`, `src/jni_api.rs`
- Modify: `src/lib.rs`
- Create: `app/src/main/java/com/criapix/fm1droid/Native.kt`
- Create: `app/src/androidTest/java/com/criapix/fm1droid/EngineDeviceTest.kt`

**Step 1: `app.rs`** (compila também no PC)

```rust
//! The process-wide engine: command queue, shared view, the ring's audio end.

use crate::engine::{self, Cmd, Shared};
use crate::resample::Resampler;
use std::sync::atomic::Ordering;
use std::sync::{mpsc, Arc, Mutex, OnceLock};

pub struct AudioSink {
    pub rx: rtrb::Consumer<[f32; 2]>,
    pub rs: Resampler,
}

pub struct App {
    pub cmds: mpsc::Sender<Cmd>,
    pub shared: Arc<Shared>,
    pub sink: Arc<Mutex<AudioSink>>,
}

static APP: OnceLock<App> = OnceLock::new();

pub fn app() -> &'static App {
    APP.get_or_init(|| {
        let (tx, rx) = mpsc::channel();
        let shared = Arc::new(Shared::new());
        let (prod, cons) = rtrb::RingBuffer::new(8192);
        let s = shared.clone();
        std::thread::Builder::new().name("fm1-engine".into()).spawn(move || engine::run(s, rx, prod)).unwrap();
        App { cmds: tx, shared, sink: Arc::new(Mutex::new(AudioSink { rx: cons, rs: Resampler::new(engine::RATE, 48_000) })) }
    })
}

impl App {
    pub fn boot(&self, pkg: Vec<u8>, saved: Vec<u8>, syx: Vec<u8>, mhz: u32) -> Result<(), String> {
        let (reply, got) = mpsc::channel();
        self.cmds.send(Cmd::Boot { pkg, saved, syx, mhz, reply }).map_err(|_| "engine stopped".to_owned())?;
        got.recv().map_err(|_| "engine stopped".to_owned())?
    }

    /// The audio callback's body: never blocks.
    pub fn fill(sink: &Mutex<AudioSink>, shared: &Shared, out: &mut [f32]) {
        match sink.try_lock() {
            Ok(mut s) => {
                let AudioSink { rx, rs } = &mut *s;
                let missing = rs.process(out, || rx.pop().ok());
                if missing > 0 {
                    shared.starved.fetch_add(missing as u64, Ordering::Relaxed);
                }
            }
            Err(_) => out.fill(0.0),
        }
    }
}
```

**Step 2: `aaudio.rs`** (só Android; `#[cfg(target_os = "android")] mod aaudio;` em `lib.rs`)

```rust
//! A low-latency AAudio output stream at the device's own rate (API 26+), by plain FFI.

use crate::app::{App, AudioSink};
use crate::engine::{Shared, RATE};
use crate::resample::Resampler;
use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

const FORMAT_PCM_FLOAT: i32 = 2;
const PERFORMANCE_MODE_LOW_LATENCY: i32 = 12;
const CALLBACK_CONTINUE: i32 = 0;

type DataCb = extern "C" fn(stream: *mut c_void, user: *mut c_void, data: *mut c_void, frames: i32) -> i32;
type ErrorCb = extern "C" fn(stream: *mut c_void, user: *mut c_void, error: i32);

#[link(name = "aaudio")]
extern "C" {
    fn AAudio_createStreamBuilder(builder: *mut *mut c_void) -> i32;
    fn AAudioStreamBuilder_setFormat(b: *mut c_void, format: i32);
    fn AAudioStreamBuilder_setChannelCount(b: *mut c_void, channels: i32);
    fn AAudioStreamBuilder_setPerformanceMode(b: *mut c_void, mode: i32);
    fn AAudioStreamBuilder_setDataCallback(b: *mut c_void, cb: DataCb, user: *mut c_void);
    fn AAudioStreamBuilder_setErrorCallback(b: *mut c_void, cb: ErrorCb, user: *mut c_void);
    fn AAudioStreamBuilder_openStream(b: *mut c_void, stream: *mut *mut c_void) -> i32;
    fn AAudioStreamBuilder_delete(b: *mut c_void) -> i32;
    fn AAudioStream_getSampleRate(s: *mut c_void) -> i32;
    fn AAudioStream_getFramesPerBurst(s: *mut c_void) -> i32;
    fn AAudioStream_setBufferSizeInFrames(s: *mut c_void, frames: i32) -> i32;
    fn AAudioStream_requestStart(s: *mut c_void) -> i32;
    fn AAudioStream_requestStop(s: *mut c_void) -> i32;
    fn AAudioStream_close(s: *mut c_void) -> i32;
}

struct Ctx {
    sink: Arc<Mutex<AudioSink>>,
    shared: Arc<Shared>,
}

/// Set by the error callback (a headset unplugged, the device changed): the app reopens.
pub static LOST: AtomicBool = AtomicBool::new(false);

pub struct Stream {
    raw: *mut c_void,
    ctx: *mut Ctx,
    pub rate: i32,
    pub burst: i32,
}
// SAFETY: the pointers are only used under the mutex that owns the Stream.
unsafe impl Send for Stream {}

extern "C" fn on_data(_s: *mut c_void, user: *mut c_void, data: *mut c_void, frames: i32) -> i32 {
    // SAFETY: `user` is the Ctx the Stream owns until after AAudioStream_close returns;
    // `data` holds `frames` stereo float frames (the format and channel count we asked for).
    let (ctx, out) = unsafe { (&*(user as *const Ctx), std::slice::from_raw_parts_mut(data as *mut f32, frames as usize * 2)) };
    App::fill(&ctx.sink, &ctx.shared, out);
    CALLBACK_CONTINUE
}

extern "C" fn on_error(_s: *mut c_void, _user: *mut c_void, _error: i32) {
    LOST.store(true, Ordering::Relaxed);
}

impl Stream {
    pub fn open(sink: Arc<Mutex<AudioSink>>, shared: Arc<Shared>) -> Result<Stream, String> {
        unsafe {
            let mut b = std::ptr::null_mut();
            if AAudio_createStreamBuilder(&mut b) != 0 {
                return Err("AAudio: no stream builder".into());
            }
            let ctx = Box::into_raw(Box::new(Ctx { sink: sink.clone(), shared }));
            AAudioStreamBuilder_setFormat(b, FORMAT_PCM_FLOAT);
            AAudioStreamBuilder_setChannelCount(b, 2);
            AAudioStreamBuilder_setPerformanceMode(b, PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_setDataCallback(b, on_data, ctx as *mut c_void);
            AAudioStreamBuilder_setErrorCallback(b, on_error, ctx as *mut c_void);
            let mut raw = std::ptr::null_mut();
            let r = AAudioStreamBuilder_openStream(b, &mut raw);
            AAudioStreamBuilder_delete(b);
            if r != 0 {
                drop(Box::from_raw(ctx));
                return Err(format!("AAudio: open failed ({r})"));
            }
            let (rate, burst) = (AAudioStream_getSampleRate(raw), AAudioStream_getFramesPerBurst(raw));
            AAudioStream_setBufferSizeInFrames(raw, burst * 2);
            sink.lock().unwrap().rs = Resampler::new(RATE, rate as u32);
            LOST.store(false, Ordering::Relaxed);
            let r = AAudioStream_requestStart(raw);
            if r != 0 {
                AAudioStream_close(raw);
                drop(Box::from_raw(ctx));
                return Err(format!("AAudio: start failed ({r})"));
            }
            Ok(Stream { raw, ctx, rate, burst })
        }
    }
}

impl Drop for Stream {
    fn drop(&mut self) {
        unsafe {
            AAudioStream_requestStop(self.raw);
            AAudioStream_close(self.raw); // no callback runs after this returns
            drop(Box::from_raw(self.ctx));
        }
    }
}
```

**Step 3: `jni_api.rs`** (só Android) — parte mínima; o resto entra nas Tasks 10 e 12:

```rust
//! JNI for com.criapix.fm1droid.Native.

use crate::aaudio::{self, Stream};
use crate::app::app;
use crate::engine::Cmd;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring};
use jni::JNIEnv;
use std::sync::atomic::Ordering;
use std::sync::Mutex;

static STREAM: Mutex<Option<Stream>> = Mutex::new(None);

fn bytes(env: &JNIEnv, a: &JByteArray) -> Vec<u8> {
    env.convert_byte_array(a).unwrap_or_default()
}

/// null on success, the reason otherwise.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_boot<'l>(
    env: JNIEnv<'l>, _c: JClass<'l>, pkg: JByteArray<'l>, saved: JByteArray<'l>, syx: JByteArray<'l>, mhz: jint,
) -> jstring {
    let (pkg, saved, syx) = (bytes(&env, &pkg), bytes(&env, &saved), bytes(&env, &syx));
    match app().boot(pkg, saved, syx, mhz as u32) {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => env.new_string(e).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
    }
}

/// The device's sample rate, or a negative number when the stream did not open.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_audioStart(_env: JNIEnv, _c: JClass) -> jint {
    let mut slot = STREAM.lock().unwrap();
    *slot = None;
    match Stream::open(app().sink.clone(), app().shared.clone()) {
        Ok(s) => {
            let rate = s.rate;
            *slot = Some(s);
            let _ = app().cmds.send(Cmd::Pause(false));
            rate
        }
        Err(_) => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_audioStop(_env: JNIEnv, _c: JClass) {
    *STREAM.lock().unwrap() = None;
    let _ = app().cmds.send(Cmd::Pause(true));
}

#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_audioLost(_env: JNIEnv, _c: JClass) -> jboolean {
    aaudio::LOST.load(Ordering::Relaxed) as jboolean
}

/// Emulated time over time spent emulating (>= 1.3 keeps up comfortably).
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_speed(_env: JNIEnv, _c: JClass) -> jfloat {
    app().shared.view.lock().unwrap().speed
}

#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_starved(_env: JNIEnv, _c: JClass) -> jlong {
    app().shared.starved.load(Ordering::Relaxed) as jlong
}

/// bit 0: booted, bit 1: halted
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_status(_env: JNIEnv, _c: JClass) -> jint {
    let v = app().shared.view.lock().unwrap();
    v.booted as jint | (v.halted as jint) << 1
}
```

**Step 4: `Native.kt`**

```kotlin
package com.criapix.fm1droid

import java.nio.ByteBuffer

object Native {
    init { System.loadLibrary("fm1droid") }

    external fun boot(pkg: ByteArray, saved: ByteArray, syx: ByteArray, mhz: Int): String?
    external fun audioStart(): Int
    external fun audioStop()
    external fun audioLost(): Boolean
    external fun speed(): Float
    external fun starved(): Long
    external fun status(): Int
}
```

**Step 5: Teste instrumentado** — o firmware de teste vai como asset só de `androidTest` (copie `native/fm1droid/testdata/felucca.fwsc` para `app/src/androidTest/assets/`, pasta no `.gitignore`):

```kotlin
@RunWith(AndroidJUnit4::class)
class EngineDeviceTest {
    @Test fun bootsAndKeepsUpWithRealTime() {
        val pkg = InstrumentationRegistry.getInstrumentation().context.assets.open("felucca.fwsc").readBytes()
        assertNull(Native.boot(pkg, ByteArray(0), ByteArray(0), 240))
        assertTrue(Native.audioStart() > 0)
        Thread.sleep(8000)
        val speed = Native.speed()
        Log.i("fm1droid", "speed=$speed starved=${Native.starved()}")
        assertEquals(1, Native.status())      // booted, not halted
        assertTrue("too slow: $speed", speed > 1.0f)
        Native.audioStop()
    }
    @Test fun garbageIsRefused() { assertNotNull(Native.boot(ByteArray(64), ByteArray(0), ByteArray(0), 240)) }
}
```

**Step 6: Rodar** no emulador x86_64 e, se houver aparelho arm64 conectado, nele também:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
adb logcat -d -s fm1droid
```

**PORTÃO (riscos 2 e 3):** registre em `docs/NOTES.md` a `speed` medida em cada alvo. Se o processo morrer ao bootar (procure `SIGSEGV`/`avc: denied { execmem }` no logcat), mude `cpu.jit_on = true` para um parâmetro de `Cmd::Boot`, boote com `false` e repita. Se `speed < 1.3` a 240 MHz no aparelho arm64, teste 160 e 120 MHz e adote como padrão o maior clock com `speed ≥ 1.3`. Não avance sem um clock que acompanhe o tempo real.

**Step 7:** Commit `feat: AAudio output and minimal JNI; engine verified on device`.

---

## Marco M3 — Painel em tela cheia com toque

### Task 10: Estado e desenho do painel no Rust + JNI de entrada

**Files:** Create `native/fm1droid/src/ui.rs`; Modify `src/app.rs`, `src/jni_api.rs`, `src/lib.rs`, `Native.kt`

**Step 1: Teste (PC)** em `ui.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn draws_the_lcd_where_the_panel_puts_it() {
        let mut ui = Ui::new();
        let lcd = vec![0xF800u16; 240 * 240]; // vermelho puro em RGB565
        let mut out = vec![0u8; W * H * 4];
        ui.draw(&lcd, &[0.0; 41], &mut out);
        // lê a origem e o tamanho do LCD no painel em panel.rs (LCD_X, LCD_Y e a escala usada
        // em `draw`) e teste um pixel bem no meio da tela
        let (cx, cy) = lcd_centre();
        let px = &out[(cy * W + cx) * 4..][..4];
        assert!(px[0] > 200 && px[1] < 60 && px[2] < 60 && px[3] == 255, "{px:?}");
    }

    #[test]
    fn a_turned_knob_glows_and_fades() {
        let mut ui = Ui::new();
        ui.turn(3, 2);
        assert_eq!((ui.enc_clicks[3], ui.turning[3]), (2, 10));
        let mut out = vec![0u8; W * H * 4];
        ui.draw(&vec![0; 240 * 240], &[0.0; 41], &mut out);
        assert_eq!(ui.turning[3], 9);
    }
}
```

`lcd_centre()` é um helper do teste: calcule a partir de `panel::LCD_X`, `panel::LCD_Y` e do tamanho com que `panel::draw` desenha o LCD (leia a função; a tela é ampliada).

**Step 3: Implementação**

```rust
//! The panel as drawn (vendor fm1-live's panel.rs) and the state the drawing needs.

use crate::panel;

pub const W: usize = panel::W;
pub const H: usize = panel::H;

pub struct Ui {
    base: Vec<u32>,
    buf: Vec<u32>,
    pub down: [bool; 41],
    pub enc_clicks: [i32; 7],
    pub pot: u16,
    /// frames of glow left: the seven encoders, then the pot
    pub turning: [u32; 8],
}

impl Ui {
    pub fn new() -> Self {
        let base = panel::base();
        Ui { buf: vec![0; base.len()], base, down: [false; 41], enc_clicks: [0; 7], pot: 900, turning: [0; 8] }
    }

    pub fn turn(&mut self, i: usize, clicks: i32) {
        self.enc_clicks[i] += clicks;
        self.turning[i] = 10;
    }

    /// Draw into `out` as RGBA bytes (W * H * 4), the order of an ARGB_8888 Bitmap's buffer.
    pub fn draw(&mut self, lcd: &[u16], leds: &[f32; 41], out: &mut [u8]) {
        let st = panel::State { lcd, enc_clicks: &self.enc_clicks, pot: self.pot, down: &self.down, leds, turning: &self.turning };
        panel::draw(&mut self.buf, &self.base, &st);
        for (px, o) in self.buf.iter().zip(out.chunks_exact_mut(4)) {
            o[0] = (px >> 16) as u8;
            o[1] = (px >> 8) as u8;
            o[2] = *px as u8;
            o[3] = 255;
        }
        for t in self.turning.iter_mut() {
            *t = t.saturating_sub(1);
        }
    }
}
```

Em `app.rs`: acrescente `pub ui: Mutex<Ui>` ao `App` (inicializado com `Ui::new()`).

**Step 4: JNI de entrada e desenho** — acrescente a `jni_api.rs` (imports: `fm1_soc::Action`, `crate::panel`, `jni::objects::JByteBuffer`):

```rust
/// A key or button (matrix id 0..40) down / up, with the velocity (1..127) of a key going down.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_key(_env: JNIEnv, _c: JClass, id: jint, down: jboolean, vel: jint) {
    if !(0..41).contains(&id) {
        return;
    }
    let down = down != 0;
    app().ui.lock().unwrap().down[id as usize] = down;
    if down {
        let _ = app().cmds.send(Cmd::Act(Action::KeyVel { vel: vel.clamp(1, 127) as u8 }));
    }
    let _ = app().cmds.send(Cmd::Act(Action::Key { id: id as u8, down }));
}

/// Turn panel encoder `i` (index into panel::ENCS) by `clicks` (+ = clockwise).
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_enc(_env: JNIEnv, _c: JClass, i: jint, clicks: jint) {
    let Some(e) = panel::ENCS.get(i as usize) else { return };
    app().ui.lock().unwrap().turn(i as usize, clicks);
    let _ = app().cmds.send(Cmd::Act(Action::Enc { enc: e.1, clicks }));
}

/// The MASTER pot, 0..1023.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_pot(_env: JNIEnv, _c: JClass, v: jint) {
    let v = v.clamp(0, 1023) as u16;
    {
        let mut ui = app().ui.lock().unwrap();
        ui.pot = v;
        ui.turning[7] = 10;
    }
    let _ = app().cmds.send(Cmd::Act(Action::Adc { ch: 4, value: v }));
}

/// MIDI bytes into the MIDI IN jack.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_midi<'l>(env: JNIEnv<'l>, _c: JClass<'l>, data: JByteArray<'l>, off: jint, len: jint) {
    let all = bytes(&env, &data);
    let (off, len) = (off.max(0) as usize, len.max(0) as usize);
    for &b in all.iter().skip(off).take(len) {
        let _ = app().cmds.send(Cmd::Act(Action::Midi { b }));
    }
}

/// What is at panel point (x, y): -1 nothing, 0..6 an encoder, 7 the pot, 100 + id a button or key.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_hit(_env: JNIEnv, _c: JClass, x: jfloat, y: jfloat) -> jint {
    match panel::hit(x, y) {
        Some(panel::Ctl::Enc(i)) => i as jint,
        Some(panel::Ctl::Pot) => 7,
        Some(panel::Ctl::Btn(id)) | Some(panel::Ctl::Key(id)) => 100 + id as jint,
        None => -1,
    }
}

/// The velocity of a touch on key `id` at panel height `y` (1 at its top .. 127 at its bottom).
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_keyVel(_env: JNIEnv, _c: JClass, id: jint, y: jfloat) -> jint {
    panel::key_vel(id as u8, y) as jint
}

/// Draw the panel into a direct ByteBuffer of 1280 * 820 * 4 bytes (RGBA).
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_draw<'l>(env: JNIEnv<'l>, _c: JClass<'l>, buf: JByteBuffer<'l>) {
    let (Ok(ptr), Ok(cap)) = (env.get_direct_buffer_address(&buf), env.get_direct_buffer_capacity(&buf)) else { return };
    if cap < crate::ui::W * crate::ui::H * 4 {
        return;
    }
    // SAFETY: a direct buffer of at least that many bytes, owned by the caller for this call.
    let out = unsafe { std::slice::from_raw_parts_mut(ptr, crate::ui::W * crate::ui::H * 4) };
    let (lcd, leds) = {
        let v = app().shared.view.lock().unwrap();
        (v.lcd.clone(), v.leds)
    };
    app().ui.lock().unwrap().draw(&lcd, &leds, out);
}
```

`Native.kt` ganha: `key(id: Int, down: Boolean, vel: Int)`, `enc(i: Int, clicks: Int)`, `pot(v: Int)`, `midi(data: ByteArray, off: Int, len: Int)`, `hit(x: Float, y: Float): Int`, `keyVel(id: Int, y: Float): Int`, `draw(buf: ByteBuffer)`.

**Step 5:** `cargo test` (PC) passa; `.\gradlew.bat :app:assembleDebug` compila. Commit `feat: panel drawing and input over JNI`.

### Task 11: Geometria e gestos em Kotlin puro (TDD)

**Files:**
- Create: `app/src/main/java/com/criapix/fm1droid/panel/PanelGeometry.kt`, `PanelTouch.kt`
- Test: `app/src/test/java/com/criapix/fm1droid/panel/PanelGeometryTest.kt`, `PanelTouchTest.kt`

**Step 1: Testes**

```kotlin
class PanelGeometryTest {
    @Test fun fitsAWidePhoneByHeightAndCentres() {
        val g = PanelGeometry(viewW = 2400f, viewH = 1080f)
        assertEquals(1080f / 820f, g.scale, 1e-4f)
        assertEquals((2400f - 1280f * g.scale) / 2f, g.left, 1e-3f)
        assertEquals(0f, g.top, 1e-3f)
        val (x, y) = g.toPanel(g.left + 640f * g.scale, 410f * g.scale)
        assertEquals(640f, x, 1e-2f); assertEquals(410f, y, 1e-2f)
    }
    @Test fun fitsATallViewByWidth() {
        val g = PanelGeometry(viewW = 1280f, viewH = 2000f)
        assertEquals(1f, g.scale, 1e-4f); assertEquals((2000f - 820f) / 2f, g.top, 1e-3f)
    }
}

class PanelTouchTest {
    private class Fake : PanelControls {
        val log = mutableListOf<String>()
        var under = -1
        override fun hit(x: Float, y: Float) = under
        override fun keyVel(id: Int, y: Float) = 99
        override fun key(id: Int, down: Boolean, vel: Int) { log += "key $id $down $vel" }
        override fun enc(i: Int, clicks: Int) { log += "enc $i $clicks" }
        override fun pot(v: Int) { log += "pot $v" }
    }

    @Test fun aKeyGoesDownAndUpWithItsPointer() {
        val f = Fake(); val t = PanelTouch(f)
        f.under = 120; t.down(1, 0f, 0f); t.up(1)
        assertEquals(listOf("key 20 true 99", "key 20 false 0"), f.log)
    }
    @Test fun slidingAcrossNoteKeysPlaysEachOne() {
        val f = Fake(); val t = PanelTouch(f)
        f.under = 120; t.down(1, 0f, 0f)
        f.under = 121; t.move(1, 0f, 0f, 5f, 0f)
        assertEquals(listOf("key 20 true 99", "key 20 false 0", "key 21 true 99"), f.log)
    }
    @Test fun slidingOffAButtonDoesNotPressItsNeighbour() {
        val f = Fake(); val t = PanelTouch(f)
        f.under = 105; t.down(1, 0f, 0f)          // botão (id < 14)
        f.under = 106; t.move(1, 0f, 0f, 5f, 0f)
        assertEquals(listOf("key 5 true 99"), f.log)
    }
    @Test fun draggingAnEncoderUpTurnsItClockwiseInWholeClicks() {
        val f = Fake(); val t = PanelTouch(f)
        f.under = 3; t.down(1, 0f, 0f)
        t.move(1, 0f, 0f, 0f, -PanelTouch.STEP * 2.5f)
        t.move(1, 0f, 0f, 0f, -PanelTouch.STEP * 0.5f)
        assertEquals(listOf("enc 3 2", "enc 3 1"), f.log)
    }
    @Test fun twoPointersAreIndependent() {
        val f = Fake(); val t = PanelTouch(f)
        f.under = 120; t.down(1, 0f, 0f)
        f.under = 3; t.down(2, 0f, 0f); t.move(2, 0f, 0f, PanelTouch.STEP, 0f)
        t.up(1)
        assertEquals(listOf("key 20 true 99", "enc 3 1", "key 20 false 0"), f.log)
    }
    @Test fun thePotFollowsADragAndClamps() {
        val f = Fake(); val t = PanelTouch(f, pot = 1000f)
        f.under = 7; t.down(1, 0f, 0f); t.move(1, 0f, 0f, 0f, -100f)
        assertEquals("pot 1023", f.log.last())
    }
    @Test fun releaseAllLetsGoOfHeldKeys() {
        val f = Fake(); val t = PanelTouch(f)
        f.under = 120; t.down(1, 0f, 0f); t.releaseAll()
        assertEquals("key 20 false 0", f.log.last())
    }
}
```

**Step 3: Implementação**

```kotlin
package com.criapix.fm1droid.panel

/** The 1280x820 panel fitted into a view, centred, aspect kept. */
class PanelGeometry(viewW: Float, viewH: Float) {
    val scale = minOf(viewW / W, viewH / H)
    val left = (viewW - W * scale) / 2f
    val top = (viewH - H * scale) / 2f
    fun toPanel(x: Float, y: Float) = Pair((x - left) / scale, (y - top) / scale)
    companion object { const val W = 1280f; const val H = 820f }
}

interface PanelControls {
    fun hit(x: Float, y: Float): Int
    fun keyVel(id: Int, y: Float): Int
    fun key(id: Int, down: Boolean, vel: Int)
    fun enc(i: Int, clicks: Int)
    fun pot(v: Int)
}

/** Multi-touch on the panel, in panel coordinates. One grab per pointer. */
class PanelTouch(private val c: PanelControls, pot: Float = 900f) {
    private sealed interface Grab {
        class Key(var id: Int) : Grab
        class Enc(val i: Int, var acc: Float = 0f) : Grab
        data object Pot : Grab
    }
    private val grabs = HashMap<Long, Grab>()
    var pot = pot; private set

    fun down(pointer: Long, x: Float, y: Float) {
        val h = c.hit(x, y)
        when {
            h >= 100 -> { c.key(h - 100, true, c.keyVel(h - 100, y)); grabs[pointer] = Grab.Key(h - 100) }
            h == 7 -> grabs[pointer] = Grab.Pot
            h in 0..6 -> grabs[pointer] = Grab.Enc(h)
        }
    }

    /** dx, dy: the pointer's movement in panel units since the last event. */
    fun move(pointer: Long, x: Float, y: Float, dx: Float, dy: Float) {
        when (val g = grabs[pointer]) {
            is Grab.Key -> if (g.id >= FIRST_NOTE) {
                val h = c.hit(x, y) - 100
                if (h >= FIRST_NOTE && h != g.id) {
                    c.key(g.id, false, 0); g.id = h; c.key(h, true, c.keyVel(h, y))
                }
            }
            is Grab.Enc -> {
                g.acc += dx - dy                      // right or up = clockwise
                val clicks = (g.acc / STEP).toInt()
                if (clicks != 0) { g.acc -= clicks * STEP; c.enc(g.i, clicks) }
            }
            Grab.Pot -> { pot = (pot + (dx - dy) * POT_GAIN).coerceIn(0f, 1023f); c.pot(pot.toInt()) }
            null -> {}
        }
    }

    fun up(pointer: Long) { (grabs.remove(pointer) as? Grab.Key)?.let { c.key(it.id, false, 0) } }

    fun releaseAll() { grabs.keys.toList().forEach(::up) }

    companion object {
        const val STEP = 14f        // panel units of drag per encoder click
        const val POT_GAIN = 4f
        const val FIRST_NOTE = 14   // matrix ids 14..40 are the note keys; 0..13 the buttons
    }
}
```

**Step 4:** `.\gradlew.bat :app:testDebugUnitTest` → todos passam. **Step 5:** Commit `feat: panel geometry and multi-touch gestures`.

### Task 12: Tela do painel, imersiva

**Files:** Create `panel/PanelScreen.kt`; Modify `MainActivity.kt`

**Step 1: `MainActivity.kt`** — imersivo e tela sempre ligada:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        setContent { App() }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
```

**Step 2: `PanelScreen.kt`**

```kotlin
private object NativeControls : PanelControls {
    override fun hit(x: Float, y: Float) = Native.hit(x, y)
    override fun keyVel(id: Int, y: Float) = Native.keyVel(id, y)
    override fun key(id: Int, down: Boolean, vel: Int) = Native.key(id, down, vel)
    override fun enc(i: Int, clicks: Int) = Native.enc(i, clicks)
    override fun pot(v: Int) = Native.pot(v)
}

@Composable
fun PanelScreen(modifier: Modifier = Modifier, initialPot: Float = 900f, onPot: (Int) -> Unit = {}) {
    val bitmap = remember { Bitmap.createBitmap(1280, 820, Bitmap.Config.ARGB_8888) }
    val image = remember { bitmap.asImageBitmap() }
    val buffer = remember { ByteBuffer.allocateDirect(1280 * 820 * 4) }
    val touch = remember { PanelTouch(NativeControls, initialPot) }
    var frame by remember { mutableIntStateOf(0) }
    var geometry by remember { mutableStateOf(PanelGeometry(1280f, 820f)) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            Native.draw(buffer)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            frame++
        }
    }
    DisposableEffect(Unit) { onDispose { touch.releaseAll(); onPot(touch.pot.toInt()) } }

    Canvas(
        modifier
            .fillMaxSize()
            .background(Color(0xFF18191B))               // panel.rs ROOM
            .onSizeChanged { geometry = PanelGeometry(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val g = geometry
                        for (ch in event.changes) {
                            val (x, y) = g.toPanel(ch.position.x, ch.position.y)
                            val id = ch.id.value
                            when {
                                ch.changedToDown() -> touch.down(id, x, y)
                                ch.changedToUp() -> touch.up(id)
                                ch.pressed -> {
                                    val d = ch.positionChange()
                                    touch.move(id, x, y, d.x / g.scale, d.y / g.scale)
                                }
                            }
                            ch.consume()
                        }
                    }
                }
            }
    ) {
        frame // lido para redesenhar a cada quadro
        val g = geometry
        drawImage(
            image,
            dstOffset = IntOffset(g.left.roundToInt(), g.top.roundToInt()),
            dstSize = IntSize((1280 * g.scale).roundToInt(), (820 * g.scale).roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}
```

**Step 3: Verificação manual no emulador** — para ter um firmware antes da biblioteca (Task 14), faça um `App()` provisório que lê `filesDir/dev.fwsc` (envie com `adb push felucca.fwsc /data/local/tmp/` e `adb shell run-as com.criapix.fm1droid cp /data/local/tmp/felucca.fwsc files/dev.fwsc`), chama `Native.boot` numa corrotina `Dispatchers.IO`, depois `Native.audioStart()` e `Native.pot(900)`.

Confira: tela cheia sem barras; a tela do FM-1 aparece e anima; tocar teclas faz som; dois dedos tocam duas notas; arrastar um knob gira e acende; o painel não distorce (barras laterais na cor de fundo). Tire um screenshot (`adb exec-out screencap -p > shot.png`) e olhe.

**Step 4:** Commit `feat: fullscreen panel with multi-touch`.

---

## Marco M4 — Instalar qualquer firmware, com saves

### Task 13: JNI de save e de inspeção de pacote

**Files:** Modify `native/fm1droid/src/jni_api.rs`, `src/engine.rs`, `Native.kt`

Acrescente:
- `Native.flashRev(): Int` e `Native.flashSnapshot(): ByteArray` — leem `view.flash_rev` e `view.flash` (`env.byte_array_from_slice(&v.flash)`, devolvendo `.into_raw()`; em erro, `null`).
- `Native.probe(pkg: ByteArray): String?` — função pública `engine::probe(pkg: &[u8]) -> Result<String, String>` que roda `stock_from_package` sob `catch_unwind`, recusa placa ≠ FM-1 e devolve `st.identity`. No JNI, devolve `"ok:<identity>"` ou `"err:<motivo>"`. Teste no PC: `probe(felucca)` é `Ok`, `probe(&[0; 64])` é `Err`.
- `Native.setLead(frames: Int)` → `Cmd::Lead`.

Commit `feat: JNI for flash snapshots and package probing`.

### Task 14: Biblioteca de firmwares (TDD, Kotlin puro)

**Files:** Create `data/FirmwareStore.kt`; Test `app/src/test/.../data/FirmwareStoreTest.kt`

Contrato:

```kotlin
@Serializable
data class Installed(
    val id: String,            // primeiros 16 hex do SHA-256 do pacote
    val name: String,
    val version: String = "",
    val author: String = "",
    val source: String = "",   // "file" ou "catalog:<id do catálogo>"
    val addedAt: Long = 0,
)

class FirmwareStore(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    fun list(): List<Installed>                       // do mais recente para o mais antigo
    fun install(bytes: ByteArray, name: String, version: String = "", author: String = "", source: String = "file"): Installed
    fun bytes(id: String): ByteArray?
    fun remove(id: String)
    var lastUsed: String?                             // id do último firmware bootado
}
```

Armazenamento: `dir/<id>.fwsc` e `dir/index.json` (kotlinx-serialization, `ignoreUnknownKeys = true`), escritos em arquivo temporário e renomeados (`File.renameTo` após gravar `index.json.tmp`).

Testes (com `TemporaryFolder`): instalar dois pacotes distintos lista os dois, mais novo primeiro; reinstalar os mesmos bytes não duplica e atualiza nome/versão; `bytes(id)` devolve o conteúdo; `remove` apaga arquivo e entrada; um `index.json` corrompido resulta em lista vazia, sem exceção; `lastUsed` persiste entre instâncias.

Commit `feat: firmware library on disk`.

### Task 15: Saves em disco (TDD)

**Files:** Create `data/SaveStore.kt`; Test `SaveStoreTest.kt`

```kotlin
class SaveStore(private val dir: File) {
    fun load(firmwareId: String): ByteArray           // vazio se não houver
    fun write(firmwareId: String, records: ByteArray) // tmp + rename; o anterior vira <id>.flash.prev
    fun clear(firmwareId: String)
}
```

Testes: `load` de id desconhecido devolve vazio; `write` + `load` devolve os mesmos bytes; segundo `write` deixa o primeiro em `.prev`; `write` de array vazio apaga o save (não deixa arquivo de 0 bytes); `clear` remove os dois arquivos.

Commit `feat: flash save files per firmware`.

### Task 16: Catálogo de firmwares (TDD no parser)

**Files:** Create `data/Catalog.kt`; Test `CatalogTest.kt`

- Fonte: `https://raw.githubusercontent.com/joakimeriksson/fm1-emu/master/web/firmwares.json`.
- Formato (conferido): objeto com `"firmwares": [ { "id", "name", "version", "author", "license", "about", "repo", "package"?, "byo"?, "download"? } ]`. Entradas com `"byo": true` (o firmware original da M-VAVE) não têm `package`; têm `download`, o endereço no servidor da própria M-VAVE.

```kotlin
@Serializable
data class CatalogEntry(
    val id: String, val name: String, val version: String = "", val author: String = "",
    val license: String = "", val about: String = "", val repo: String = "",
    @SerialName("package") val pkg: String? = null,
    val byo: Boolean = false, val download: String? = null,
) { val url: String? get() = pkg ?: download }

object Catalog {
    fun parse(json: String): List<CatalogEntry>      // ignoreUnknownKeys; entradas sem url ficam de fora
    suspend fun fetch(): List<CatalogEntry>          // HttpURLConnection em Dispatchers.IO, timeout 15 s
    suspend fun download(url: String): ByteArray     // idem, limite de 4 MB, só https
}
```

Teste do parser com um JSON de três entradas copiado de `vendor/fm1-emu/web/firmwares.json` (uma com `package`, a `mvave` com `byo`/`download`, uma sem nenhum dos dois): resultam duas entradas; a da M-VAVE tem `url` igual ao `download`; campos desconhecidos (`hub`, `installer`) não quebram.

Na UI, a entrada da M-VAVE deve dizer: "Firmware original da M-VAVE, baixado do servidor da M-VAVE. Começa com um banco de timbres do emulador; carregue um banco DX7 (.syx) nas configurações."

Commit `feat: firmware catalog parsing and download`.

### Task 17: Controlador do emulador e telas de biblioteca

**Files:** Create `Emu.kt`, `data/Prefs.kt`, `ui/LibrarySheet.kt`; Modify `MainActivity.kt` (remover o `App()` provisório)

**`Prefs`** (SharedPreferences): `mhz: Int` (padrão: o clock aprovado no portão da Task 9), `lead: Int` (768), `pot: Int` (900).

**`Emu`** (um `ViewModel`): estado `StateFlow<EmuState>` com `current: Installed?`, `booting: Boolean`, `error: String?`, `speed: Float`, `starvedPerSec: Long`, `halted: Boolean`.

- `boot(fw: Installed, syx: ByteArray = ByteArray(0))`, em `Dispatchers.IO`: grava o save pendente do firmware anterior; `Native.boot(store.bytes(fw.id), saves.load(fw.id), syx, prefs.mhz)`; em sucesso, `store.lastUsed = fw.id`, `Native.setLead(prefs.lead)`, `Native.pot(prefs.pot)`; em erro, `error = motivo`.
- Laço a cada 1 s enquanto em primeiro plano: atualiza `speed`, `starvedPerSec`, `halted`; se `Native.audioLost()`, chama `Native.audioStop()` e `Native.audioStart()`; se `Native.flashRev()` mudou, `saves.write(current.id, Native.flashSnapshot())`.
- `onStart` (via `LifecycleEventObserver`): `Native.audioStart()`. `onStop`: grava o save pendente, `Native.audioStop()`.
- Ao abrir o app: se `store.lastUsed` existe, boota; senão abre a biblioteca.

**`LibrarySheet`** (ModalBottomSheet de altura total, duas abas):
- **Instalados**: lista (`name`, `version`, `author`); toque = boot; menu por item: "Apagar save", "Remover".
- **Catálogo**: busca por nome; cada item mostra nome, autor, licença e `about`; botão "Instalar" baixa, valida com `Native.probe`, instala e boota. Erros de rede e de pacote inválido aparecem como texto no item.
- Botão "Importar arquivo .fwsc": `ActivityResultContracts.OpenDocument()` com `arrayOf("*/*")`; valida com `Native.probe`; nome = nome do arquivo sem extensão.

**Acesso em tela cheia:** um botão circular translúcido de 40 dp no canto superior direito, sobre o painel, abre um menu com "Firmwares", "Configurações" e o indicador de desempenho.

**"Abrir com":** `intent-filter` `VIEW` para `content://` e `file://` com `android:mimeType="*/*"` e `android:pathPattern=".*\\.fwsc"`; em `onNewIntent`/`onCreate`, lê o `Uri`, valida, instala e boota.

**Verificação manual (emulador):** instalar pelo catálogo o Felucca e mais dois firmwares; alternar entre eles; importar um `.fwsc` por arquivo; instalar o original da M-VAVE pelo catálogo e conferir que boota e toca; salvar um preset num firmware (use o SAVE do próprio firmware), fechar o app pelo multitarefa, reabrir e conferir que o preset continua lá.

Commit `feat: firmware library, catalog install and persistent saves`.

---

## Marco M5 — MIDI, ajustes e acabamento de desempenho

### Task 18: Entrada MIDI

**Files:** Create `midi/MidiInput.kt`

Com `MidiManager` (`getSystemService`): no início, e a cada `DeviceCallback.onDeviceAdded`, abre todo dispositivo com `outputPortCount > 0` (teclados USB e BLE já pareados aparecem aqui), abre cada porta de saída e conecta um `MidiReceiver` cujo `onSend(msg, offset, count, timestamp)` chama `Native.midi(msg, offset, count)`. Fecha tudo em `onStop` e em `onDeviceRemoved`. Mostre no menu quantos dispositivos estão conectados.

Verificação: com teclado MIDI USB (ou, sem um, o dispositivo MIDI virtual do emulador), notas tocam no firmware. Sem dispositivo, o app não mostra erro. Commit `feat: MIDI input from USB and Bluetooth devices`.

### Task 19: Configurações

**Files:** Create `ui/SettingsSheet.kt`

- **Clock**: 60, 80, 100, 120, 160, 200, 240 MHz (os mesmos do fm1-emu web). Mudar reinicia a máquina com o save atual. Texto: "240 MHz é o clock real do FM-1. Reduza se o som falhar."
- **Latência**: Baixa (384), Normal (768), Segura (1536) quadros → `Native.setLead`.
- **Banco DX7 (.syx)**: visível quando o firmware atual é o original; escolhe um arquivo e reinicia com `boot(fw, syx)`. Erro do núcleo aparece em texto.
- **Reiniciar firmware** e **Apagar save** (com confirmação).
- **Desempenho**: velocidade (`speed`×) e falhas de áudio por segundo. Se `starvedPerSec > 0` por 5 s seguidos, mostre uma sugestão única: "Seu aparelho não acompanha este firmware a N MHz. Reduzir para M MHz?".
- **Sobre**: versão; "Emulação: fm1-emu, © Joakim Eriksson e colaboradores, licença MIT" com o texto da licença (`vendor/fm1-emu/LICENSE`, copiado para `app/src/main/assets/licenses/`); "M-VAVE e FM-1 são marcas de seus titulares. Este app não é afiliado à M-VAVE."

Commit `feat: settings for clock, latency, DX7 bank and saves`.

### Task 20: Desenho só quando algo muda

Meça primeiro: com o app aberto e parado, `adb shell dumpsys gfxinfo com.criapix.fm1droid` e uso de CPU no `top`. Se o laço de desenho custar mais de ~15 % de um núcleo, implemente:

- `engine.rs`: `View.rev: u32`, incrementado quando `soc.lcd.revision` muda ou quando os LEDs (quantizados em 1/16) mudam.
- `ui.rs`: `Ui.dirty: bool`, marcado por `turn`, mudança de `down`/`pot`, e enquanto algum `turning > 0`.
- `Native.draw` devolve `Boolean` (desenhou ou não); o Kotlin só copia o bitmap e incrementa `frame` quando `true`.

Teste no PC: dois `draw` seguidos sem mudança, o segundo devolve `false`. Commit `perf: redraw the panel only when it changes`.

---

## Marco M6 — Entrega

### Task 21: Roteiro de teste em aparelho e release

**Files:** Create `docs/DEVICE-TEST.md`, `README.md`

`docs/DEVICE-TEST.md` (o usuário executa num celular arm64 real e anota os resultados):

1. Instalação limpa; abre na biblioteca; catálogo carrega.
2. Felucca: boota em menos de 5 s; teclas, dois dedos, knobs, MASTER.
3. Firmware original da M-VAVE: boota; troca de preset com o encoder PRESETS; som em 240 MHz; `speed` anotada.
4. Latência percebida ao tocar (Baixa/Normal/Segura); falhas de áudio por segundo.
5. Fone com fio, alto-falante e fone Bluetooth; plugar e desplugar com som tocando.
6. Save: salvar preset, matar o app, reabrir.
7. Girar o aparelho 180°; bloquear e desbloquear a tela; atender uma chamada.
8. Teclado MIDI USB (se houver).
9. Três firmwares comunitários pesados (por exemplo Melodee, DUELL, SLOOP): anotar `speed` e clock necessário.

Release: gere uma chave de assinatura fora do repositório (`keytool`, guardada pelo usuário), configure `signingConfigs` lendo de `local.properties`, `.\gradlew.bat :app:assembleRelease`, confira com `apksigner verify --print-certs`. `README.md` explica o que é o app, como instalar o APK, de onde vêm os firmwares, os créditos ao fm1-emu e o aviso de marcas. Não publique nada sem o usuário pedir.

Commit `docs: device test checklist and release notes`.

---

## Fora do escopo desta versão

Saída MIDI e USB do firmware para outros apps, Bluetooth/WiFi emulados (o fm1-emu não emula o rádio), placa SMK-37, serviço em segundo plano com a tela apagada, gravação de áudio, layout retrato, publicação na Play Store.
