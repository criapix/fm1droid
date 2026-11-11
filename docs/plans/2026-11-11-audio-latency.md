# Audio Latency Reduction Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Cut the delay between a key on an external keyboard (Yamaha CK61 over USB, which is also the audio output) and the sound, and show in the app where the remaining delay comes from.

**Architecture:** The delay the app adds is (a) the ring of audio the engine keeps ahead of the audio callback (`lead`, 768 frames = 17.4 ms by default) and (b) the AAudio stream's buffer (two bursts). Both become adaptive: the lead starts at "one callback's worth + 3 ms" and grows only when audio drops; the AAudio buffer starts at one burst and grows only on underruns. The engine thread gets audio priority so the smaller reserve holds. A new `Native.audioInfo()` reports rate, burst, buffer, performance mode, underruns and the output latency AAudio itself measures, shown in Settings.

**Tech Stack:** Rust (AAudio by FFI, no new crates), Kotlin/Compose, JUnit 4, `cargo test`.

---

## 0. Context the executor needs

**Repo:** `C:\Users\criap\OneDrive\Documentos\Github\mvave fm1` (the repository root; it was `C:\dev\fm1droid` until 2026-11-11). Before any `cargo`/`gradlew`: `. .\tools\vsenv.ps1`, JDK 17 in `JAVA_HOME`.

**The signal path today** (read these before touching anything):

| Stage | Where | Delay |
|---|---|---|
| Key → MIDI → `Native.midi` → engine command queue | `midi/`, `jni_api.rs`, `engine.rs` loop top | ≤ ~1 ms |
| Firmware's own I2S DMA buffer | inside the emulated FM-1 | as on the real device; **do not touch** |
| Ring ahead of the callback: engine runs until `fill >= lead` | `engine.rs` `run`, `Cmd::Lead` | **lead: 768 frames @ 44.1 kHz = 17.4 ms** (384 / 768 / 1536 selectable) |
| Resampler 44.1 kHz → device rate | `resample.rs` | 2 frames |
| AAudio stream buffer | `aaudio.rs` `Stream::open` | **2 bursts** |
| Android mixer + USB audio HAL + the CK61 | outside the app | unknown — Task 3 makes it visible |

**Why the lead cannot simply be set to a tiny number:** each callback takes one burst out of the ring at once, and the engine refills it at only ~1.4× real time on the S25 Ultra (see `docs/NOTES.md`). So the least fill is about `lead − burst`; the lead must stay above one burst (in 44.1 kHz frames) plus a margin for the engine thread's reaction. That is exactly what the automatic mode computes.

**Decisions (already made — do not reopen)**

| Topic | Decision |
|---|---|
| Lead, automatic (new default, stored as `lead = 0`) | start at `ceil(burst × 44100 / rate) + 128`, clamp 128..1024; +64 after each second with dropouts; recomputed whenever the stream (re)opens |
| Lead, manual | the three existing choices stay (384 / 768 / 1536) |
| Automatic clock step-down | only counts a bad second once the automatic lead is at its maximum (or the lead is manual) |
| After a boot or a stream (re)open | two monitor ticks are ignored (the ring is empty while booting; that is not a dropout to react to) |
| AAudio buffer | starts at 1 burst; +1 burst when `getXRunCount` grew since the last check; max 4 bursts |
| Engine thread | `setpriority(PRIO_PROCESS, 0, -19)` on Android (URGENT_AUDIO) |
| Sharing mode, usage, sample rate | unchanged (no EXCLUSIVE/MMAP request) |
| Engine wake-up | unchanged (500 µs poll) |

**Out of scope:** driving the USB audio interface directly (bypassing Android's audio stack), changing the vendored `fm1-emu`, the firmware's own buffer.

**Honesty rule for this plan:** nothing here can be measured without the phone and the CK61. Tasks 1–5 are verified by unit tests and a build; the real result is the user's reading in Task 6. Do not claim a latency number you did not see.

---

### Task 1: Pure tuning arithmetic (`tune.rs`)

**Files:**
- Create: `native/fm1droid/src/tune.rs`
- Modify: `native/fm1droid/src/lib.rs` (add `pub mod tune;` after `pub mod resample;`)

**Step 1: Write the module with its failing tests first** (functions as `todo!()`), run, see them fail, then fill in:

```rust
//! The arithmetic of the audio output's tuning, kept apart from AAudio so it runs in host tests.

/// How late a frame handed over now will be heard, in microseconds. AAudio says frame `at_frame`
/// left the device at `at_ns`; `written` frames have been handed over so far; it is `now_ns`.
pub fn latency_us(written: i64, at_frame: i64, at_ns: i64, now_ns: i64, rate: i32) -> i64 {
    let ahead_ns = (written - at_frame) * 1_000_000_000 / rate.max(1) as i64;
    (at_ns + ahead_ns - now_ns) / 1000
}

/// The stream's buffer after a check: one burst more when the device reported new underruns,
/// never more than four bursts.
pub fn next_buffer(burst: i32, current: i32, new_xruns: i32) -> i32 {
    if new_xruns > 0 {
        (current + burst).min(burst * 4).max(current)
    } else {
        current
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn latency_is_what_is_still_queued_minus_the_time_gone_by() {
        // 4800 frames at 48 kHz are 100 ms ahead of frame 0, which played 50 ms ago
        assert_eq!(latency_us(4800, 0, 1_000_000_000, 1_050_000_000, 48_000), 50_000);
        // nothing queued and the timestamp is now: no latency
        assert_eq!(latency_us(100, 100, 5_000, 5_000, 44_100), 0);
        // a zero rate must not divide by zero
        let _ = latency_us(10, 0, 0, 0, 0);
    }

    #[test]
    fn the_buffer_grows_a_burst_per_bad_check_up_to_four() {
        assert_eq!(next_buffer(192, 192, 0), 192);
        assert_eq!(next_buffer(192, 192, 3), 384);
        assert_eq!(next_buffer(192, 576, 1), 768);
        assert_eq!(next_buffer(192, 768, 1), 768);
        assert_eq!(next_buffer(192, 1000, 1), 1000); // already above the cap: never shrinks
    }
}
```

**Step 2: Run**

Run: `cargo test --manifest-path native/fm1droid/Cargo.toml tune`
Expected: 2 passed.

**Step 3: Commit**

```
git add native/fm1droid/src/tune.rs native/fm1droid/src/lib.rs
git commit -m "feat: audio tuning arithmetic (latency estimate, buffer growth)"
```

---

### Task 2: Engine — audio priority, least ring fill, lower lead floor

**Files:**
- Modify: `native/fm1droid/src/engine.rs`
- Modify: `native/fm1droid/src/app.rs`

**Step 1: `engine.rs`**

- Import `AtomicU32`: `use std::sync::atomic::{AtomicU32, AtomicU64};`
- `Shared` gets a field, initialised to `AtomicU32::new(u32::MAX)` in `Shared::new`:
  ```rust
      /// the least the audio callback found in the ring since someone last asked (u32::MAX = no callback yet)
      pub fill_min: AtomicU32,
  ```
- Above `pub fn run`, add:
  ```rust
  /// The engine feeds the audio callback: it gets the priority Android gives audio threads.
  #[cfg(target_os = "android")]
  fn raise_priority() {
      extern "C" {
          fn setpriority(which: i32, who: u32, prio: i32) -> i32;
      }
      // PRIO_PROCESS (0) with who = 0 is the calling thread on Linux; -19 is THREAD_PRIORITY_URGENT_AUDIO
      unsafe {
          setpriority(0, 0, -19);
      }
  }

  #[cfg(not(target_os = "android"))]
  fn raise_priority() {}
  ```
- First line of `run`: `raise_priority();`
- In the command loop, change the clamp: `Ok(Cmd::Lead(n)) => lead = n.clamp(64, 4096),`

**Step 2: `app.rs`** — in `App::fill`, after the `if missing > 0 { ... }` block and still inside `Ok(mut s) => { ... }`:

```rust
                shared.fill_min.fetch_min(rx.slots() as u32, Ordering::Relaxed);
```

**Step 3: A host test** — add to `engine.rs` tests:

```rust
    #[test]
    fn the_audio_callback_records_the_least_fill() {
        let shared = Shared::new();
        let (mut prod, cons) = rtrb::RingBuffer::new(64);
        for _ in 0..10 {
            prod.push([0.0f32; 2]).unwrap();
        }
        let sink = Mutex::new(crate::app::AudioSink { rx: cons, rs: crate::resample::Resampler::new(RATE, RATE) });
        let mut out = [0.0f32; 8]; // 4 frames
        crate::app::App::fill(&sink, &shared, &mut out);
        assert_eq!(shared.fill_min.load(std::sync::atomic::Ordering::Relaxed), 6);
        crate::app::App::fill(&sink, &shared, &mut out);
        assert_eq!(shared.fill_min.load(std::sync::atomic::Ordering::Relaxed), 2);
    }
```

**Step 4: Run**

Run: `cargo test --manifest-path native/fm1droid/Cargo.toml`
Expected: all pass (the machine tests need `native/fm1droid/testdata/felucca.fwsc`; run `tools/fetch-testfw.ps1` if it is missing).

**Step 5: Commit**

```
git add native/fm1droid/src
git commit -m "feat: engine thread at audio priority; record the ring's least fill"
```

---

### Task 3: AAudio — one-burst buffer that grows on underruns, and `audioInfo`

**Files:**
- Modify: `native/fm1droid/src/aaudio.rs`
- Modify: `native/fm1droid/src/jni_api.rs`
- Modify: `app/src/main/kotlin/com/criapix/fm1droid/Native.kt`

**Step 1: `aaudio.rs`**

- `use crate::tune;`
- Add to the `extern "C"` block:
  ```rust
      fn AAudioStream_getBufferSizeInFrames(s: *mut c_void) -> i32;
      fn AAudioStream_getPerformanceMode(s: *mut c_void) -> i32;
      fn AAudioStream_getXRunCount(s: *mut c_void) -> i32;
      fn AAudioStream_getFramesWritten(s: *mut c_void) -> i64;
      fn AAudioStream_getTimestamp(s: *mut c_void, clock: i32, frame: *mut i64, time_ns: *mut i64) -> i32;
  ```
- Below that block:
  ```rust
  const CLOCK_MONOTONIC: i32 = 1;

  /// `struct timespec` of the 64-bit Android ABIs (the only ones this app ships).
  #[repr(C)]
  struct Timespec {
      sec: i64,
      nsec: i64,
  }

  extern "C" {
      fn clock_gettime(clock: i32, ts: *mut Timespec) -> i32;
  }
  ```
- `Stream` gets two private fields: `buffer: i32` (frames in effect) and `xruns: i32` (count at the last check).
- In `open`, replace `AAudioStream_setBufferSizeInFrames(raw, burst * 2);` with:
  ```rust
              // one burst: the callback only copies from the ring, so it is never late on its own;
              // `info` adds a burst whenever the device reports an underrun
              let set = AAudioStream_setBufferSizeInFrames(raw, burst);
              let buffer = if set > 0 { set } else { AAudioStream_getBufferSizeInFrames(raw) };
  ```
  and build the struct with `Ok(Stream { raw, ctx, rate, burst, buffer, xruns: 0 })`.
- Add to `impl Stream`:
  ```rust
      /// [rate, burst, buffer frames, performance mode (12 = low latency), underruns so far,
      /// output latency in microseconds or -1 when the device gives no timestamp yet].
      /// Also the tuning step: call it about once a second.
      pub fn info(&mut self) -> [i32; 6] {
          unsafe {
              let xruns = AAudioStream_getXRunCount(self.raw).max(0);
              let want = tune::next_buffer(self.burst, self.buffer, xruns - self.xruns);
              self.xruns = xruns;
              if want != self.buffer {
                  let set = AAudioStream_setBufferSizeInFrames(self.raw, want);
                  if set > 0 {
                      self.buffer = set;
                  }
              }
              let (mut frame, mut at) = (0i64, 0i64);
              let mut now = Timespec { sec: 0, nsec: 0 };
              let latency = if AAudioStream_getTimestamp(self.raw, CLOCK_MONOTONIC, &mut frame, &mut at) == 0
                  && clock_gettime(CLOCK_MONOTONIC, &mut now) == 0
              {
                  let now_ns = now.sec * 1_000_000_000 + now.nsec;
                  tune::latency_us(AAudioStream_getFramesWritten(self.raw), frame, at, now_ns, self.rate)
                      .clamp(0, i32::MAX as i64) as i32
              } else {
                  -1
              };
              [self.rate, self.burst, self.buffer, AAudioStream_getPerformanceMode(self.raw), xruns, latency]
          }
      }
  ```

**Step 2: `jni_api.rs`** — after `Native_audioLost`:

```rust
/// [rate, burst, buffer frames, performance mode, underruns, output latency (µs, -1 unknown),
/// least ring fill since the last call (frames at 44.1 kHz, -1 unknown)]; null with no stream.
/// Calling it is also the stream's tuning step: once a second.
#[no_mangle]
pub extern "system" fn Java_com_criapix_fm1droid_Native_audioInfo(env: JNIEnv, _c: JClass) -> jni::sys::jintArray {
    let Some(info) = STREAM.lock().unwrap().as_mut().map(|s| s.info()) else { return std::ptr::null_mut() };
    let least = app().shared.fill_min.swap(u32::MAX, Ordering::Relaxed);
    let all = [info[0], info[1], info[2], info[3], info[4], info[5], if least == u32::MAX { -1 } else { least as jint }];
    match env.new_int_array(all.len() as jint) {
        Ok(a) => {
            let _ = env.set_int_array_region(&a, 0, &all);
            a.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}
```

(Same shape as `Native_menuPos` at the bottom of the file; if the `jni` crate wants `mut env` here, follow the compiler.)

**Step 3: `Native.kt`** — after `audioLost()`:

```kotlin
  /** [rate, burst, buffer, performance mode (12 = low latency), underruns, output latency µs, least ring fill]; null with no stream. Call once a second: it also tunes the buffer. */
  external fun audioInfo(): IntArray?
```

**Step 4: Build for Android**

Run: `.\gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (this is the only check that `aaudio.rs` compiles; it is not built on the host).

**Step 5: Commit**

```
git add native/fm1droid/src app/src/main/kotlin/com/criapix/fm1droid/Native.kt
git commit -m "feat: one-burst AAudio buffer that grows on underruns; audioInfo"
```

---

### Task 4: Automatic lead (Kotlin)

**Files:**
- Create: `app/src/main/kotlin/com/criapix/fm1droid/LeadPolicy.kt`
- Create: `app/src/test/kotlin/com/criapix/fm1droid/LeadPolicyTest.kt`
- Modify: `app/src/main/kotlin/com/criapix/fm1droid/data/Prefs.kt`
- Modify: `app/src/main/kotlin/com/criapix/fm1droid/Emu.kt`

**Step 1: Failing test**

```kotlin
package com.criapix.fm1droid

import org.junit.Assert.assertEquals
import org.junit.Test

class LeadPolicyTest {
  @Test fun startsAtOneCallbackPlusAMargin() {
    assertEquals(177 + 128, LeadPolicy.initial(burst = 192, rate = 48_000)) // 192 frames at 48 kHz = 176.4 at 44.1 kHz
    assertEquals(480 + 128, LeadPolicy.initial(burst = 480, rate = 44_100))
  }

  @Test fun staysWithinItsLimits() {
    assertEquals(1024, LeadPolicy.initial(burst = 4000, rate = 48_000))
    assertEquals(128, LeadPolicy.initial(burst = 0, rate = 0))
  }

  @Test fun risesAStepPerBadSecondUpToTheMaximum() {
    assertEquals(369, LeadPolicy.raised(305))
    assertEquals(1024, LeadPolicy.raised(1000))
    assertEquals(1024, LeadPolicy.raised(1024))
  }

  @Test fun readsTheNativeArray() {
    val a = AudioInfo.of(intArrayOf(44_100, 480, 480, 12, 3, 21_500, 96))!!
    assertEquals(AudioInfo(44_100, 480, 480, true, 3, 21_500, 96), a)
    assertEquals(null, AudioInfo.of(null))
    assertEquals(null, AudioInfo.of(intArrayOf(1, 2)))
  }
}
```

Run: `.\gradlew :app:testDebugUnitTest --tests "com.criapix.fm1droid.LeadPolicyTest"` → fails to compile.

**Step 2: `LeadPolicy.kt`**

```kotlin
package com.criapix.fm1droid

/** The audio output as `Native.audioInfo()` reports it. */
data class AudioInfo(
  val rate: Int,
  val burst: Int,
  val buffer: Int,
  val lowLatency: Boolean,
  val xruns: Int,
  /** what Android says a frame waits before it is heard, in microseconds; -1 unknown */
  val latencyUs: Int,
  /** the least the callback found in the ring over the last second (frames at 44.1 kHz); -1 unknown */
  val ringMin: Int,
) {
  companion object {
    fun of(a: IntArray?): AudioInfo? =
      if (a == null || a.size < 7) null else AudioInfo(a[0], a[1], a[2], a[3] == 12, a[4], a[5], a[6])
  }
}

/** The frames (44.1 kHz) the engine keeps ahead of the audio callback when the choice is automatic. */
object LeadPolicy {
  const val MARGIN = 128 // ~3 ms for the engine thread to notice and refill
  const val STEP = 64
  const val MAX = 1024

  /** The least that can work: what one callback takes at once, plus the margin. */
  fun initial(burst: Int, rate: Int): Int {
    val bite = if (rate > 0) ((burst.toLong() * 44_100 + rate - 1) / rate).toInt() else 0
    return (bite + MARGIN).coerceIn(MARGIN, MAX)
  }

  /** After a second with dropouts. */
  fun raised(lead: Int): Int = (lead + STEP).coerceAtMost(MAX)
}
```

Run the test → 4 pass.

**Step 3: `Prefs.kt`** — the lead's comment and default:

```kotlin
  /** frames of audio the engine keeps ahead; 0 = automatic (LeadPolicy) */
  var lead: Int
    get() = p.getInt("lead", 0)
    set(v) = p.edit().putInt("lead", v).apply()
```

**Step 4: `Emu.kt`**

- `EmuState`: add `val autoLead: Boolean = true,` and `val audio: AudioInfo? = null,` (keep `lead`: it now means the frames in effect).
- Initial state: `lead = if (prefs.lead > 0) prefs.lead else 768, autoLead = prefs.lead == 0`.
- New members next to `suggestionDismissed`:
  ```kotlin
    /** the automatic lead in effect; recomputed when the audio stream opens */
    @Volatile private var autoLead = 768
    /** monitor ticks to ignore: the ring is empty while booting, that is not a dropout */
    @Volatile private var settle = 0

    private fun effectiveLead() = if (prefs.lead > 0) prefs.lead else autoLead

    /** A new stream (the first, or after the output device changed): start the automatic lead over. */
    private fun resetAutoLead() {
      val a = AudioInfo.of(Native.audioInfo())
      autoLead = if (a != null) LeadPolicy.initial(a.burst, a.rate) else 768
      settle = 2
      Native.setLead(effectiveLead())
      _state.update { it.copy(lead = effectiveLead(), autoLead = prefs.lead == 0, audio = a) }
    }
  ```
- In `boot`, success branch: replace `Native.setLead(prefs.lead)` with `Native.setLead(effectiveLead()); settle = 2`, and in the `_state.update` use `lead = effectiveLead()`.
- Replace `setLead`:
  ```kotlin
    /** [frames] 0 = automatic. */
    fun setLead(frames: Int) {
      prefs.lead = frames
      Native.setLead(effectiveLead())
      _state.update { it.copy(lead = effectiveLead(), autoLead = frames == 0) }
    }
  ```
- In `onStart`: after `Native.audioStart()` add `resetAutoLead()`. In the monitor loop, change the reopen line to
  `if (Native.audioLost()) { Native.audioStop(); Native.audioStart(); resetAutoLead() }`.
- In the monitor loop, replace the line `bad = if (per > 0) bad + 1 else 0` with:
  ```kotlin
          val info = AudioInfo.of(Native.audioInfo()) // also the AAudio buffer's tuning step
          val live = _state.value.current != null && !_state.value.booting
          when {
            settle > 0 -> { settle--; bad = 0 }
            per > 0 && live && prefs.lead == 0 && autoLead < LeadPolicy.MAX -> {
              // dropouts with room to grow: more reserve first, the clock is the last resort
              autoLead = LeadPolicy.raised(autoLead)
              Native.setLead(autoLead)
              bad = 0
            }
            else -> bad = if (per > 0) bad + 1 else 0
          }
  ```
- In the monitor's final `_state.update { it.copy(...) }` add `lead = effectiveLead(), autoLead = prefs.lead == 0, audio = info,`.

**Step 5: Build and test**

Run: `.\gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all unit tests pass.

**Step 6: Commit**

```
git add -A app/src
git commit -m "feat: automatic audio lead that starts minimal and grows on dropouts"
```

---

### Task 5: Settings — the automatic choice and where the delay is

**Files:**
- Modify: `app/src/main/kotlin/com/criapix/fm1droid/ui/SettingsSheet.kt`

**Step 1:** Replace the "Latência" block (title + `FlowRow`) with:

```kotlin
      Text("Latência", style = MaterialTheme.typography.titleMedium)
      Text(
        "Automática começa com a menor reserva de áudio que o aparelho aguenta e só aumenta se o som falhar. " +
          "Reserva atual: ${"%.1f".format(state.lead * 1000f / 44_100f)} ms.",
        style = MaterialTheme.typography.bodySmall,
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.autoLead, onClick = { emu.setLead(0) }, label = { Text("Automática") })
        listOf("Baixa" to 384, "Normal" to 768, "Segura" to 1536).forEach { (label, frames) ->
          FilterChip(selected = !state.autoLead && state.lead == frames, onClick = { emu.setLead(frames) }, label = { Text(label) })
        }
      }
```

**Step 2:** In the "Desempenho" block, after the existing speed line, add:

```kotlin
      state.audio?.let { a ->
        val bufferMs = if (a.rate > 0) a.buffer * 1000f / a.rate else 0f
        Text(
          "Saída de áudio: ${a.rate} Hz · bloco de ${a.burst} quadros · buffer de ${a.buffer} (${"%.1f".format(bufferMs)} ms) · " +
            (if (a.lowLatency) "modo de baixa latência" else "SEM modo de baixa latência") + " · estouros: ${a.xruns}",
          style = MaterialTheme.typography.bodySmall,
        )
        Text(
          "Atraso estimado: emulador ${"%.1f".format(state.lead * 1000f / 44_100f)} ms + saída do Android " +
            (if (a.latencyUs >= 0) "%.1f".format(a.latencyUs / 1000f) else "?") + " ms" +
            (if (a.ringMin >= 0) " · folga mínima da reserva: ${a.ringMin} quadros" else ""),
          style = MaterialTheme.typography.bodySmall,
        )
      }
```

**Step 3: Build, install, look**

Run: `.\gradlew :app:assembleDebug :app:installDebug` (emulator or phone). Open ⋮ → Configurações. Expected: four latency chips with "Automática" selected on a fresh install, the reserve in ms, and the two new lines under "Desempenho" with numbers that update every second. Take a screenshot with the `android` CLI and check it.

On the x86_64 emulator the engine is slower than real time, so the automatic lead will climb to its maximum and dropouts will continue: that is expected there and proves the growth path. Do not tune anything to the emulator.

**Step 4: Commit**

```
git add -A app/src
git commit -m "feat: automatic latency setting and audio path diagnostics in settings"
```

---

### Task 6: Device reading (the user, with the CK61) and notes

**Files:**
- Modify: `docs/DEVICE-TEST.md`
- Modify: `docs/NOTES.md`
- Modify: `README.md` (one paragraph on the latency setting)

**Step 1: `docs/DEVICE-TEST.md`** — add:

```markdown
## Latência com o CK61 como interface de áudio

Com o CK61 ligado por USB e o som saindo por ele, abrir ⋮ → Configurações e anotar em NOTES.md:

- [ ] Linha "Saída de áudio": taxa, bloco, buffer, e se diz "modo de baixa latência" ou "SEM".
- [ ] Linha "Atraso estimado": emulador (ms) e saída do Android (ms).
- [ ] Tocar por 2 minutos em "Automática": a reserva subiu? Para quanto? Houve estalos depois do primeiro minuto?
- [ ] Repetir as três leituras com o som saindo pelo alto-falante do celular (sem o CK), para comparar.
- [ ] Impressão ao tocar: o atraso diminuiu em relação à versão anterior? Ainda incomoda?
```

**Step 2: `docs/NOTES.md`** — add a dated section "Latência de áudio" with: what changed (automatic lead, one-burst buffer, engine priority, `audioInfo`), that it was verified only by unit tests and a build, and this reading guide for whoever gets the user's numbers:

- "emulador" + "saída do Android" ≈ what the app and the system add; the firmware's own buffer and the CK61's converter are on top and cannot be seen.
- If the output says **SEM modo de baixa latência**, or "saída do Android" stays above ~30 ms with the CK61 while the phone speaker shows much less: the remaining delay is Android's USB audio path. The app cannot lower it through AAudio. The only route left is driving the USB audio interface directly from the app — a separate, larger piece of work: **stop and take the numbers back to the planning session** rather than trying it.
- If the reserve keeps climbing to 1024 on the phone: the engine is not keeping up; try a lower clock in Settings and note the result.

**Step 3: Commit**

```
git add README.md docs
git commit -m "docs: latency setting, diagnostics and the CK61 reading checklist"
```
