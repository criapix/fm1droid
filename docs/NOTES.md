# Notas de execução

## Ambiente (Windows)
- Rust precisa do MSVC do **VS 2022 Build Tools**. A máquina também tem um VS 18 Community sem as libs C++ que o rustc escolhe por padrão (LNK1104 msvcrt.lib). Sempre: `. .\tools\vsenv.ps1` antes de `cargo`/`gradlew`.
- NDK: `30.0.16248370`. Emulador: system image `android-35/google_apis/x86_64`.

## Desempenho (Task 6, PC Windows x86_64, JIT ligado, 240 MHz)
- 6 s emulados (boot + 4 s + tecla 2 s) do Felucca em ~2,6 s de parede (~2,3x tempo real).

## Android (Tasks 8-20)
- Gradle chama `cargo ndk`; precisa ser executado depois de `. .\tools\vsenv.ps1` e com `JAVA_HOME` = JDK 17.
- `compileSdk = 36` (o gerador usou 37, que não está disponível no repositório de SDK).
- Emulador x86_64 (API 36), Felucca, 6-7 s por ponto: 60 MHz 1,24x; 120 MHz 0,57x; 160 MHz 0,52x; 240 MHz 0,36x. JIT on/off a 60 MHz: 1,24x / 1,39x. Sem `avc: denied`/SIGSEGV: o `mmap` RWX do JIT funciona no Android. O emulador não representa o arm64; o `clockSweep` (ver DEVICE-TEST.md) mede no aparelho.
- O parâmetro `jit` foi adicionado a `Native.boot`/`Cmd::Boot` como saída de emergência (`Prefs.jit`).
- O modo de clock Automático (padrão) reduz o clock quando há 5 s seguidos de falhas de áudio; escolher um clock manualmente desliga.
- Tela do S25 Ultra simulada no emulador com `wm size 1440x3120` / `wm density 560` (reverter: `adb shell wm size reset; adb shell wm density reset`).
- Task 20 (desenhar só quando muda) foi feita junto com a Task 10: `Ui::draw` devolve `false` sem mudança, com teste.
