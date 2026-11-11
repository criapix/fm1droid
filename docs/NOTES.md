# Notas de execução

## Ambiente (Windows)
- Rust precisa do MSVC do **VS 2022 Build Tools**. A máquina também tem um VS 18 Community sem as libs C++ que o rustc escolhe por padrão (LNK1104 msvcrt.lib). Sempre: `. .\tools\vsenv.ps1` antes de `cargo`/`gradlew`.
- NDK: `30.0.16248370`. Emulador: system image `android-35/google_apis/x86_64`.

## Desempenho (Task 6, PC Windows x86_64, JIT ligado, 240 MHz)
- 6 s emulados (boot + 4 s + tecla 2 s) do Felucca em ~2,6 s de parede (~2,3x tempo real).
