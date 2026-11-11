# MIDI Control Mapping (MIDI Learn) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let an external MIDI keyboard (first target: Yamaha CK61 over USB) operate the FM-1 panel — its buttons, knobs and sliders drive the emulated 14 buttons, 7 encoders and the MASTER pot.

**Architecture:** A pure-Kotlin layer sits between `MidiInput` and `Native.midi`. `MidiParser` splits the byte stream into whole messages; `MidiRouter` looks each one up in a `MidiMap`: a mapped message becomes `Native.key` / `Native.enc` / `Native.pot`, everything else is forwarded to the firmware's MIDI IN exactly as today. Bindings are created by "learn" (pick a panel control in a list, move a control on the keyboard) and stored as one string in `Prefs`. No Rust changes.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), `android.media.midi`, JUnit 4 (JVM unit tests, no device needed for Tasks 1–3).

---

## 0. Context the executor needs

**Repo:** `C:\Users\criap\OneDrive\Documentos\Github\mvave fm1` (the repository root; it was `C:\dev\fm1droid` until 2026-11-11). Before any `gradlew` on this PC: `. .\tools\vsenv.ps1` and JDK 17 in `JAVA_HOME` (see `docs/NOTES.md`).

**What exists today**

- `app/src/main/kotlin/com/criapix/fm1droid/midi/MidiInput.kt` opens every MIDI device and calls `Native.midi(msg, offset, count)` for every packet. Notes from an external keyboard already play.
- `Native.key(id, down, vel)`: matrix id 0..13 = buttons, 14..40 = note keys. `Native.enc(i, clicks)`: `i` 0..6 is the index into `panel::ENCS`. `Native.pot(v)`: 0..1023. All three are safe to call from any thread.
- Button ids: 0 OCT-, 1 OCT+, 2 FX, 3 SCL, 4 ENV, 5 LFO, 6 EDIT, 7 GLO, 8 HOME, 9 SAVE, 10 ARP, 11 SEQ, 12 PLAY, 13 REC.
- Encoder indexes: 0 SELECT, 1 PRESETS, 2 ALGORITHM, 3 KNOB1, 4 KNOB2, 5 KNOB3, 6 KNOB4.
- Settings are plain `SharedPreferences` properties in `data/Prefs.kt`. Sheets are `ModalBottomSheet`s opened from the floating menu in `MainActivity.kt` (`Root`).
- Unit tests live in `app/src/test/kotlin/com/criapix/fm1droid/...` (JUnit 4, see `panel/PanelTest.kt` for the style: small fakes, a string log).

**Decisions (already made — do not reopen)**

| Topic | Decision |
|---|---|
| What can be a source | Control Change, Note, Program Change — each with its channel |
| CC 0, CC 32, CC 120–127 | Never learned, always forwarded (bank select / channel mode) |
| Button from a CC | Default **tap**: any message = press + release 80 ms later (keyboard panel buttons are usually toggles that send 127 / 0 on alternate presses). Per-button switch to **hold**: value ≥ 64 down, < 64 up |
| Button from a Note | Hold: note-on down, note-off up. The note is consumed (does not sound) |
| Button from a Program Change | Always tap |
| Encoder from a CC | Absolute → relative: the change in value, divided by "units per click" (1, 2 or 4; default 2). The first message after binding only records the position |
| Encoder / pot from a Note or PC | Not allowed (learn ignores it) |
| MASTER from a CC | `value * 1023 / 127`. Not saved to `Prefs.pot` (a physical slider keeps its own position) |
| One source | Exactly one target (rebinding replaces). One target may have several sources |
| Learn UI | A list of the 22 panel controls in a bottom sheet, with a "last message received" line as a MIDI monitor |

**Known limits (document, do not solve)**

- Knobs with end stops cannot turn an endless encoder past their ends; the user turns back and continues. Relative-mode (endless) controllers are out of scope.
- The on-screen MASTER drag keeps its own value, so after a MIDI move the next drag on screen jumps to the old on-screen value.
- We do not know which CK61 panel controls transmit MIDI, nor their numbers; the monitor line is how the user finds out. Record what is found in `docs/NOTES.md` (Task 6).

**Conventions:** 2-space indent, comments in English, UI strings in Portuguese, one commit per task.

---

### Task 1: `MidiMap` — the model and its text form

**Files:**
- Create: `app/src/main/kotlin/com/criapix/fm1droid/midi/MidiMap.kt`
- Test: `app/src/test/kotlin/com/criapix/fm1droid/midi/MidiTest.kt`

**Step 1: Write the failing tests**

```kotlin
package com.criapix.fm1droid.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiMapTest {
  @Test fun roundTripsThroughText() {
    val m = MidiMap()
    m.bind(Source.Cc(0, 74), Target.Enc(3), tap = false)
    m.bind(Source.Cc(1, 20), Target.Button(8), tap = true)
    m.bind(Source.Note(0, 36), Target.Button(12), tap = false)
    m.bind(Source.Pc(0, 2), Target.Button(9), tap = true)
    m.bind(Source.Cc(0, 7), Target.Pot, tap = false)
    val text = m.encode()
    assertEquals("cc.0.74=enc.3;cc.1.20=btn.8!;note.0.36=btn.12;pc.0.2=btn.9!;cc.0.7=pot", text)
    assertEquals(text, MidiMap.decode(text).encode())
  }

  @Test fun decodeSkipsGarbage() {
    val m = MidiMap.decode("nonsense;cc.0.74=enc.9;cc.99.1=pot;cc.0.200=pot;cc.0.74=enc.3;=;x=y")
    assertEquals("cc.0.74=enc.3", m.encode())
    assertEquals("", MidiMap.decode("").encode())
  }

  @Test fun aSourceHasOneTargetAndATargetManySources() {
    val m = MidiMap()
    m.bind(Source.Cc(0, 1), Target.Enc(0), false)
    m.bind(Source.Cc(0, 2), Target.Enc(0), false)
    assertEquals(2, m.bindingsOf(Target.Enc(0)).size)
    m.bind(Source.Cc(0, 1), Target.Enc(1), false) // rebinding moves it
    assertEquals(listOf(Source.Cc(0, 2)), m.bindingsOf(Target.Enc(0)).map { it.first })
    assertEquals(Target.Enc(1), m[Source.Cc(0, 1)]?.target)
  }

  @Test fun clearAndSetTap() {
    val m = MidiMap.decode("cc.0.20=btn.8!;cc.0.21=btn.9!")
    m.setTap(Target.Button(8), false)
    assertEquals("cc.0.20=btn.8;cc.0.21=btn.9!", m.encode())
    m.clear(Target.Button(8))
    assertNull(m[Source.Cc(0, 20)])
    m.clearAll()
    assertEquals("", m.encode())
  }

  @Test fun everyPanelControlIsListedOnce() {
    assertEquals(22, TARGETS.size)
    assertEquals(22, TARGETS.map { it.second }.toSet().size)
    assertTrue(TARGETS.any { it.first == "HOME" && it.second == Target.Button(8) })
    assertTrue(TARGETS.any { it.first == "KNOB1" && it.second == Target.Enc(3) })
  }

  @Test fun describesSourcesForPeople() {
    assertEquals("CC 74 · canal 1", describe(Source.Cc(0, 74)))
    assertEquals("Nota C2 · canal 1", describe(Source.Note(0, 36)))
    assertEquals("Programa 3 · canal 2", describe(Source.Pc(1, 2)))
  }
}
```

**Step 2: Run to see it fail**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.criapix.fm1droid.midi.*"`
Expected: compilation fails, `MidiMap` unresolved.

**Step 3: Implement**

```kotlin
package com.criapix.fm1droid.midi

/** What a controller sends; [ch] is 0..15. */
sealed interface Source {
  data class Cc(val ch: Int, val num: Int) : Source
  data class Note(val ch: Int, val note: Int) : Source
  data class Pc(val ch: Int, val program: Int) : Source
}

/** A control of the FM-1's panel. */
sealed interface Target {
  /** matrix id 0..13 */
  data class Button(val id: Int) : Target
  /** index into panel::ENCS, 0..6 */
  data class Enc(val i: Int) : Target
  data object Pot : Target
}

/** [tap]: any message presses and releases the button (for controls that send toggles). */
data class Binding(val target: Target, val tap: Boolean)

private val BUTTONS = listOf("OCT-", "OCT+", "FX", "SCL", "ENV", "LFO", "EDIT", "GLO", "HOME", "SAVE", "ARP", "SEQ", "PLAY", "REC")
private val ENCS = listOf("SELECT", "PRESETS", "ALGORITHM", "KNOB1", "KNOB2", "KNOB3", "KNOB4")

/** Every panel control that can be driven over MIDI, in the order the learn screen lists them. */
val TARGETS: List<Pair<String, Target>> =
  listOf<Pair<String, Target>>("MASTER" to Target.Pot) +
    ENCS.mapIndexed { i, n -> n to Target.Enc(i) } +
    BUTTONS.mapIndexed { i, n -> n to Target.Button(i) }

private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun describe(s: Source): String = when (s) {
  is Source.Cc -> "CC ${s.num} · canal ${s.ch + 1}"
  is Source.Note -> "Nota ${NOTE_NAMES[s.note % 12]}${s.note / 12 - 1} · canal ${s.ch + 1}"
  is Source.Pc -> "Programa ${s.program + 1} · canal ${s.ch + 1}"
}

/** Source -> panel control. Not thread-safe: MidiRouter guards it. */
class MidiMap {
  private val m = LinkedHashMap<Source, Binding>()

  operator fun get(s: Source): Binding? = m[s]
  fun bindingsOf(t: Target): List<Pair<Source, Binding>> = m.filterValues { it.target == t }.toList()
  fun bind(s: Source, t: Target, tap: Boolean) { m.remove(s); m[s] = Binding(t, tap) }
  fun setTap(t: Target, tap: Boolean) { for ((s, b) in m.toList()) if (b.target == t) m[s] = b.copy(tap = tap) }
  fun clear(t: Target) { m.entries.removeAll { it.value.target == t } }
  fun clearAll() = m.clear()

  /** "cc.0.74=enc.3;cc.1.20=btn.8!" — '!' marks a tap binding. */
  fun encode(): String = m.entries.joinToString(";") { (s, b) ->
    val src = when (s) {
      is Source.Cc -> "cc.${s.ch}.${s.num}"
      is Source.Note -> "note.${s.ch}.${s.note}"
      is Source.Pc -> "pc.${s.ch}.${s.program}"
    }
    val dst = when (val t = b.target) {
      is Target.Button -> "btn.${t.id}"
      is Target.Enc -> "enc.${t.i}"
      Target.Pot -> "pot"
    }
    "$src=$dst" + if (b.tap) "!" else ""
  }

  companion object {
    /** Entries that do not parse are skipped: a damaged preference never breaks MIDI. */
    fun decode(text: String): MidiMap {
      val map = MidiMap()
      for (entry in text.split(';')) {
        val parts = entry.split('=')
        if (parts.size != 2) continue
        val s = source(parts[0]) ?: continue
        val t = target(parts[1].removeSuffix("!")) ?: continue
        map.bind(s, t, parts[1].endsWith("!"))
      }
      return map
    }

    private fun source(text: String): Source? {
      val p = text.split('.')
      if (p.size != 3) return null
      val ch = p[1].toIntOrNull()?.takeIf { it in 0..15 } ?: return null
      val n = p[2].toIntOrNull()?.takeIf { it in 0..127 } ?: return null
      return when (p[0]) {
        "cc" -> Source.Cc(ch, n)
        "note" -> Source.Note(ch, n)
        "pc" -> Source.Pc(ch, n)
        else -> null
      }
    }

    private fun target(text: String): Target? {
      if (text == "pot") return Target.Pot
      val p = text.split('.')
      if (p.size != 2) return null
      val n = p[1].toIntOrNull() ?: return null
      return when {
        p[0] == "btn" && n in 0..13 -> Target.Button(n)
        p[0] == "enc" && n in 0..6 -> Target.Enc(n)
        else -> null
      }
    }
  }
}
```

**Step 4: Run to see it pass**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.criapix.fm1droid.midi.*"`
Expected: 6 tests pass.

**Step 5: Commit**

```
git add app/src/main/kotlin/com/criapix/fm1droid/midi/MidiMap.kt app/src/test/kotlin/com/criapix/fm1droid/midi/MidiTest.kt
git commit -m "feat: MIDI control map model and text form"
```

---

### Task 2: `MidiParser` — bytes into whole messages

**Files:**
- Create: `app/src/main/kotlin/com/criapix/fm1droid/midi/MidiParser.kt`
- Modify: `app/src/test/kotlin/com/criapix/fm1droid/midi/MidiTest.kt` (append a class)

Why it is needed: one `onSend` packet can carry several messages, a message can be split over two packets, and BLE/DIN sources use running status. The router must judge message by message and still forward the rest untouched.

**Step 1: Write the failing tests** (append to `MidiTest.kt`)

```kotlin
class MidiParserTest {
  private val got = mutableListOf<String>()
  private val parser = MidiParser { m, n -> got += (0 until n).joinToString(" ") { "%02X".format(m[it].toInt() and 0xFF) } }
  private fun feed(vararg b: Int) { val a = ByteArray(b.size) { b[it].toByte() }; parser.feed(a, 0, a.size) }

  @Test fun severalMessagesInOnePacket() {
    feed(0xB0, 0x4A, 0x10, 0xC0, 0x05, 0x90, 0x3C, 0x64)
    assertEquals(listOf("B0 4A 10", "C0 05", "90 3C 64"), got)
  }

  @Test fun runningStatusRepeatsTheStatusByte() {
    feed(0x90, 0x3C, 0x64, 0x3E, 0x64)
    assertEquals(listOf("90 3C 64", "90 3E 64"), got)
  }

  @Test fun aMessageSplitOverTwoPackets() {
    feed(0x90, 0x3C); assertTrue(got.isEmpty())
    feed(0x64); assertEquals(listOf("90 3C 64"), got)
  }

  @Test fun realTimeBytesPassThroughInTheMiddle() {
    feed(0x90, 0x3C, 0xF8, 0x64)
    assertEquals(listOf("F8", "90 3C 64"), got)
  }

  @Test fun sysExIsPassedByteByByteAndEndsRunningStatus() {
    feed(0x90, 0x3C, 0x64, 0xF0, 0x43, 0x10, 0xF7, 0x05)
    assertEquals(listOf("90 3C 64", "F0", "43", "10", "F7"), got) // the stray 05 is dropped
  }

  @Test fun honoursOffsetAndLength() {
    parser.feed(byteArrayOf(0x11, 0x90.toByte(), 0x3C, 0x64, 0x22), 1, 3)
    assertEquals(listOf("90 3C 64"), got)
  }
}
```

**Step 2: Run** — expected: `MidiParser` unresolved.

**Step 3: Implement**

```kotlin
package com.criapix.fm1droid.midi

/**
 * Splits a MIDI byte stream into whole messages, handed to [out] as (buffer, length). The buffer is
 * reused: read it before returning. Running status is expanded; real-time bytes and SysEx (one
 * byte at a time) pass straight through.
 */
class MidiParser(private val out: (ByteArray, Int) -> Unit) {
  private val buf = ByteArray(3)
  private val one = ByteArray(1)
  private var n = 0     // bytes of the current message in buf
  private var need = 0  // its full length; 0 = no status yet
  private var sysex = false

  fun feed(data: ByteArray, off: Int, len: Int) {
    for (i in off until off + len) byte(data[i].toInt() and 0xFF)
  }

  private fun single(b: Int) { one[0] = b.toByte(); out(one, 1) }

  private fun byte(b: Int) {
    when {
      b >= 0xF8 -> single(b) // real-time: may sit inside any message
      b == 0xF0 -> { sysex = true; n = 0; need = 0; single(b) }
      b == 0xF7 -> { sysex = false; n = 0; need = 0; single(b) }
      b >= 0x80 -> {
        sysex = false
        buf[0] = b.toByte(); n = 1; need = length(b)
        if (need == 1) { out(buf, 1); n = 0; need = 0 }
      }
      sysex -> single(b)
      need == 0 -> {} // data with no status: nothing to attach it to
      else -> {
        buf[n++] = b.toByte()
        if (n == need) {
          out(buf, n)
          // channel messages keep their status for running status; system common ones do not
          if ((buf[0].toInt() and 0xFF) < 0xF0) n = 1 else { n = 0; need = 0 }
        }
      }
    }
  }

  private fun length(status: Int): Int = when (status and 0xF0) {
    0xC0, 0xD0 -> 2
    0xF0 -> when (status) { 0xF1, 0xF3 -> 2; 0xF2 -> 3; else -> 1 }
    else -> 3
  }
}
```

**Step 4: Run** — expected: all `midi` tests pass (12).

**Step 5: Commit**

```
git add -A app/src
git commit -m "feat: MIDI stream parser"
```

---

### Task 3: `MidiRouter` — learn, translate, forward

**Files:**
- Create: `app/src/main/kotlin/com/criapix/fm1droid/midi/MidiRouter.kt`
- Modify: `app/src/test/kotlin/com/criapix/fm1droid/midi/MidiTest.kt` (append a class)

**Step 1: Write the failing tests** (append to `MidiTest.kt`)

```kotlin
class MidiRouterTest {
  private class Rig(text: String = "") {
    val log = mutableListOf<String>()
    val pending = mutableListOf<() -> Unit>()
    var saved = "unsaved"
    val router = MidiRouter(
      sink = object : PanelSink {
        override fun key(id: Int, down: Boolean, vel: Int) { log += "key $id $down" }
        override fun enc(i: Int, clicks: Int) { log += "enc $i $clicks" }
        override fun pot(v: Int) { log += "pot $v" }
      },
      forward = { d, off, len -> log += "fwd " + (off until off + len).joinToString(" ") { "%02X".format(d[it].toInt() and 0xFF) } },
      later = { _, f -> pending += f },
      map = MidiMap.decode(text),
      onChange = { saved = it.encode() },
    )
    fun send(vararg b: Int) { val a = ByteArray(b.size) { b[it].toByte() }; router.feed(a, 0, a.size) }
  }

  @Test fun unmappedMessagesReachTheFirmwareUntouched() {
    val r = Rig()
    r.send(0x90, 0x3C, 0x64, 0xB0, 0x40, 0x7F, 0xF8)
    assertEquals(listOf("fwd 90 3C 64", "fwd B0 40 7F", "fwd F8"), r.log)
  }

  @Test fun learnsAKnobForAnEncoderAndTurnsItByTheChange() {
    val r = Rig()
    r.router.learn(Target.Enc(3))
    r.send(0xB0, 74, 10)
    assertNull(r.router.learning.value)
    assertEquals("cc.0.74=enc.3", r.saved)
    assertTrue(r.log.isEmpty()) // the learning message is swallowed and turns nothing
    r.send(0xB0, 74, 14); assertEquals(listOf("enc 3 2"), r.log) // +4 at 2 units per click
    r.send(0xB0, 74, 13); assertEquals(1, r.log.size)             // -1: not a full click yet
    r.send(0xB0, 74, 12); assertEquals("enc 3 -1", r.log.last())
  }

  @Test fun unitsPerClickSetsTheSensitivity() {
    val r = Rig("cc.0.74=enc.0")
    r.router.unitsPerClick = 1
    r.send(0xB0, 74, 50, 0xB0, 74, 53)
    assertEquals(listOf("enc 0 3"), r.log)
  }

  @Test fun aTapBindingPressesAndReleasesOnEveryMessage() {
    val r = Rig("cc.0.20=btn.8!")
    r.send(0xB0, 20, 127)
    assertEquals(listOf("key 8 true"), r.log)
    r.pending.removeAt(0)()
    assertEquals(listOf("key 8 true", "key 8 false"), r.log)
    r.send(0xB0, 20, 0) // a toggle button's second press sends 0: still a press
    assertEquals("key 8 true", r.log.last())
  }

  @Test fun aHoldBindingFollowsTheValue() {
    val r = Rig("cc.0.20=btn.8")
    r.send(0xB0, 20, 127, 0xB0, 20, 0)
    assertEquals(listOf("key 8 true", "key 8 false"), r.log)
    assertTrue(r.pending.isEmpty())
  }

  @Test fun aNoteHoldsAButtonAndDoesNotSound() {
    val r = Rig("note.0.36=btn.12")
    r.send(0x90, 36, 100, 0x80, 36, 0, 0x90, 36, 100, 0x90, 36, 0)
    assertEquals(listOf("key 12 true", "key 12 false", "key 12 true", "key 12 false"), r.log)
  }

  @Test fun aProgramChangeTapsAButton() {
    val r = Rig("pc.0.2=btn.9!")
    r.send(0xC0, 2)
    assertEquals(listOf("key 9 true"), r.log)
    assertEquals(1, r.pending.size)
    r.send(0xC0, 3)
    assertEquals("fwd C0 03", r.log.last())
  }

  @Test fun theMasterFollowsASlider() {
    val r = Rig("cc.0.7=pot")
    r.send(0xB0, 7, 127, 0xB0, 7, 0, 0xB0, 7, 64)
    assertEquals(listOf("pot 1023", "pot 0", "pot 515"), r.log)
  }

  @Test fun channelsAreDistinct() {
    val r = Rig("cc.0.20=btn.8")
    r.send(0xB1, 20, 127)
    assertEquals(listOf("fwd B1 14 7F"), r.log)
  }

  @Test fun learningAnEncoderIgnoresNotesAndBankSelect() {
    val r = Rig()
    r.router.learn(Target.Enc(0))
    r.send(0x90, 60, 100, 0xB0, 0, 1, 0xB0, 32, 0, 0xB0, 123, 0, 0xC0, 5)
    assertEquals(Target.Enc(0), r.router.learning.value)
    assertEquals(5, r.log.size) // all forwarded
  }

  @Test fun learningAButtonTakesANoteAProgramOrACc() {
    val a = Rig(); a.router.learn(Target.Button(2)); a.send(0x90, 36, 100)
    assertEquals("note.0.36=btn.2", a.saved)
    a.send(0x80, 36, 0) // the release of the learning press: harmless
    val b = Rig(); b.router.learn(Target.Button(2)); b.send(0xC0, 4)
    assertEquals("pc.0.4=btn.2!", b.saved)
    val c = Rig(); c.router.learn(Target.Button(2)); c.send(0xB0, 20, 127)
    assertEquals("cc.0.20=btn.2!", c.saved)
  }

  @Test fun clearingGivesTheMessagesBackToTheFirmware() {
    val r = Rig("cc.0.20=btn.8;cc.0.21=btn.9")
    r.router.clear(Target.Button(8))
    assertEquals("cc.0.21=btn.9", r.saved)
    r.send(0xB0, 20, 127)
    assertEquals(listOf("fwd B0 14 7F"), r.log)
    r.router.clearAll()
    assertEquals("", r.saved)
  }

  @Test fun releaseAllLetsGoOfHeldButtons() {
    val r = Rig("cc.0.20=btn.8")
    r.send(0xB0, 20, 127)
    r.router.releaseAll()
    assertEquals(listOf("key 8 true", "key 8 false"), r.log)
    r.router.releaseAll()
    assertEquals(2, r.log.size)
  }

  @Test fun theMonitorShowsTheLastMessage() {
    val r = Rig()
    r.send(0xB0, 74, 10)
    assertEquals("CC 74 · canal 1 = 10", r.router.lastMessage.value)
  }
}
```

**Step 2: Run** — expected: `MidiRouter`, `PanelSink` unresolved.

**Step 3: Implement**

```kotlin
package com.criapix.fm1droid.midi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The panel as the router drives it (Native.key / enc / pot in the app). */
interface PanelSink {
  fun key(id: Int, down: Boolean, vel: Int)
  fun enc(i: Int, clicks: Int)
  fun pot(v: Int)
}

/**
 * Between the MIDI devices and the FM-1's MIDI IN: a message bound in [map] operates the panel,
 * the rest goes to [forward] unchanged. [later] runs a block after some milliseconds (the release
 * of a tap). Called from MIDI threads and from the UI: every entry point is synchronized.
 */
class MidiRouter(
  private val sink: PanelSink,
  private val forward: (ByteArray, Int, Int) -> Unit,
  private val later: (Long, () -> Unit) -> Unit,
  private val map: MidiMap = MidiMap(),
  private val onChange: (MidiMap) -> Unit = {},
) {
  /** CC units of knob travel per encoder click: 1, 2 or 4. */
  @Volatile var unitsPerClick = 2

  private val parser = MidiParser(::message)
  private val last = HashMap<Source, Int>() // last value seen from an encoder's source
  private val acc = HashMap<Source, Int>()  // travel not yet turned into clicks
  private val held = HashSet<Int>()         // buttons a hold binding has down

  private val _learning = MutableStateFlow<Target?>(null)
  /** The control waiting for a message, or null. */
  val learning: StateFlow<Target?> = _learning
  private val _revision = MutableStateFlow(0)
  /** Bumps whenever the bindings change. */
  val revision: StateFlow<Int> = _revision
  private val _lastMessage = MutableStateFlow("")
  /** The last note / CC / program change received, for people. */
  val lastMessage: StateFlow<String> = _lastMessage

  @Synchronized fun feed(data: ByteArray, off: Int, len: Int) = parser.feed(data, off, len)
  @Synchronized fun learn(t: Target) { _learning.value = t }
  @Synchronized fun cancelLearn() { _learning.value = null }
  @Synchronized fun bindings(t: Target): List<Pair<Source, Binding>> = map.bindingsOf(t)
  @Synchronized fun setTap(t: Target, tap: Boolean) { map.setTap(t, tap); changed() }
  @Synchronized fun clear(t: Target) { map.clear(t); changed() }
  @Synchronized fun clearAll() { map.clearAll(); changed() }

  /** Let go of every button a hold binding has down (the device went away, the app stopped). */
  @Synchronized fun releaseAll() {
    held.forEach { sink.key(it, false, 0) }
    held.clear()
  }

  private fun changed() {
    last.clear(); acc.clear()
    _revision.value = _revision.value + 1
    onChange(map)
  }

  private fun message(m: ByteArray, n: Int) {
    val status = m[0].toInt() and 0xFF
    val ch = status and 0x0F
    val d1 = if (n > 1) m[1].toInt() and 0x7F else 0
    val d2 = if (n > 2) m[2].toInt() and 0x7F else 0
    var value = d2
    val src: Source? = when (status and 0xF0) {
      0x90 -> Source.Note(ch, d1)
      0x80 -> { value = 0; Source.Note(ch, d1) }
      0xB0 -> if (d1 == 0 || d1 == 32 || d1 >= 120) null else Source.Cc(ch, d1)
      0xC0 -> { value = 127; Source.Pc(ch, d1) }
      else -> null
    }
    if (src == null) { forward(m, 0, n); return }
    _lastMessage.value = describe(src) + if (src is Source.Pc) "" else " = $value"

    val want = _learning.value
    if (want != null) {
      val fits = when (src) {
        is Source.Cc -> true
        is Source.Note -> want is Target.Button && value > 0
        is Source.Pc -> want is Target.Button
      }
      if (fits) {
        map.bind(src, want, tap = want is Target.Button && src !is Source.Note)
        _learning.value = null
        changed()
        last[src] = value // an encoder starts from where the knob is now
        return
      }
    }

    val b = map[src]
    if (b == null) { forward(m, 0, n); return }
    when (val t = b.target) {
      is Target.Button -> {
        val down = if (src is Source.Note) value > 0 else value >= 64
        if (b.tap || src is Source.Pc) {
          if (src !is Source.Note || down) {
            sink.key(t.id, true, VEL)
            later(TAP_MS) { sink.key(t.id, false, 0) }
          }
        } else {
          if (down) held += t.id else held -= t.id
          sink.key(t.id, down, if (down) VEL else 0)
        }
      }
      is Target.Enc -> {
        val prev = last.put(src, value)
        if (prev != null) {
          val travel = (acc[src] ?: 0) + (value - prev)
          val clicks = travel / unitsPerClick // truncates toward zero, both ways
          acc[src] = travel - clicks * unitsPerClick
          if (clicks != 0) sink.enc(t.i, clicks)
        }
      }
      Target.Pot -> sink.pot(value * 1023 / 127)
    }
  }

  companion object {
    const val TAP_MS = 80L
    const val VEL = 100
  }
}
```

Note on `theMasterFollowsASlider`: `64 * 1023 / 127` is 515 in integer arithmetic.

**Step 4: Run** — expected: all `midi` tests pass (26). If a test fails, fix the implementation, not the test, unless the test contradicts the decisions table in section 0.

**Step 5: Commit**

```
git add -A app/src
git commit -m "feat: MIDI router with learn, tap/hold buttons and absolute-to-relative encoders"
```

---

### Task 4: Wire the router into the app

**Files:**
- Modify: `app/src/main/kotlin/com/criapix/fm1droid/data/Prefs.kt`
- Modify: `app/src/main/kotlin/com/criapix/fm1droid/midi/MidiInput.kt`
- Modify: `app/src/main/kotlin/com/criapix/fm1droid/MainActivity.kt`

**Step 1: `Prefs.kt`** — add before the `companion object`:

```kotlin
  /** MIDI controller bindings, in MidiMap's text form */
  var midiMap: String
    get() = p.getString("midiMap", "") ?: ""
    set(v) = p.edit().putString("midiMap", v).apply()

  /** CC units of knob travel per encoder click */
  var midiUnits: Int
    get() = p.getInt("midiUnits", 2)
    set(v) = p.edit().putInt("midiUnits", v).apply()
```

**Step 2: `MidiInput.kt`** — the class takes where the bytes go instead of calling `Native` itself.

- Change the class header to:
  ```kotlin
  /** Every MIDI device that sends (USB and paired BLE keyboards) feeds [sink] (the MidiRouter). */
  class MidiInput(context: Context, private val sink: (ByteArray, Int, Int) -> Unit) {
  ```
- Change the receiver body to `= sink(msg, offset, count)`.
- Remove the now unused `import com.criapix.fm1droid.Native`.

**Step 3: `MainActivity.kt`**

- Add imports: `android.os.Handler`, `android.os.Looper`, `com.criapix.fm1droid.midi.MidiMap`, `com.criapix.fm1droid.midi.MidiRouter`, `com.criapix.fm1droid.midi.PanelSink`.
- Add the field `private lateinit var router: MidiRouter`.
- Replace `midi = MidiInput(this)` with:

  ```kotlin
    val main = Handler(Looper.getMainLooper())
    router = MidiRouter(
      sink = object : PanelSink {
        override fun key(id: Int, down: Boolean, vel: Int) = Native.key(id, down, vel)
        override fun enc(i: Int, clicks: Int) = Native.enc(i, clicks)
        override fun pot(v: Int) = Native.pot(v)
      },
      forward = { data, off, len -> Native.midi(data, off, len) },
      later = { ms, block -> main.postDelayed({ block() }, ms) },
      map = MidiMap.decode(emu.prefs.midiMap),
      onChange = { emu.prefs.midiMap = it.encode() },
    )
    router.unitsPerClick = emu.prefs.midiUnits
    midi = MidiInput(this, router::feed)
  ```
- In the lifecycle observer's `onStop`, release held buttons first: `{ midi.stop(); router.releaseAll(); emu.onStop() }`.
- Pass the router down: `setContent { Root(emu, midi, router, haptics) }` and add the parameter `router: MidiRouter` to `Root` (used in Task 5).

**Step 4: Build and run the tests**

Run: `.\gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; behaviour unchanged (the map is empty, so everything is forwarded).

**Step 5: Commit**

```
git add -A app/src
git commit -m "feat: route incoming MIDI through the control map"
```

---

### Task 5: The learn screen

**Files:**
- Create: `app/src/main/kotlin/com/criapix/fm1droid/ui/MidiMapSheet.kt`
- Modify: `app/src/main/kotlin/com/criapix/fm1droid/MainActivity.kt`

**Step 1: `MidiMapSheet.kt`**

```kotlin
package com.criapix.fm1droid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.criapix.fm1droid.data.Prefs
import com.criapix.fm1droid.midi.MidiRouter
import com.criapix.fm1droid.midi.Source
import com.criapix.fm1droid.midi.TARGETS
import com.criapix.fm1droid.midi.Target
import com.criapix.fm1droid.midi.describe

/** Bind the controls of an external MIDI keyboard to the FM-1's buttons, encoders and MASTER. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MidiMapSheet(router: MidiRouter, prefs: Prefs, devices: Int, onDismiss: () -> Unit) {
  val learning by router.learning.collectAsState()
  val revision by router.revision.collectAsState()
  val last by router.lastMessage.collectAsState()
  var units by remember { mutableIntStateOf(router.unitsPerClick) }
  DisposableEffect(Unit) { onDispose { router.cancelLearn() } }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Controle MIDI", style = MaterialTheme.typography.titleLarge)
      Text(
        if (devices == 0) "Nenhum dispositivo MIDI conectado. Ligue o teclado pela porta USB."
        else "Dispositivos MIDI conectados: $devices",
        style = MaterialTheme.typography.bodySmall,
      )
      Text("Última mensagem recebida: ${last.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
      Text(
        "Toque em Aprender e mexa num botão, knob ou slider do teclado. Botões também aceitam uma tecla. " +
          "O que não estiver associado continua indo para o FM-1 como MIDI normal.",
        style = MaterialTheme.typography.bodySmall,
      )

      Text("Curso dos knobs", style = MaterialTheme.typography.titleMedium)
      Text("Quantos passos do encoder cabem em uma volta completa do knob ou slider do teclado.", style = MaterialTheme.typography.bodySmall)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("127 passos" to 1, "63 passos" to 2, "31 passos" to 4).forEach { (label, u) ->
          FilterChip(selected = units == u, onClick = { units = u; router.unitsPerClick = u; prefs.midiUnits = u }, label = { Text(label) })
        }
      }
      HorizontalDivider()

      TARGETS.forEach { (label, target) ->
        val bound = remember(revision, target) { router.bindings(target) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Column(Modifier.weight(1f)) {
            Text(label)
            Text(
              when {
                learning == target -> "Mexa no controle do teclado…"
                bound.isEmpty() -> "—"
                else -> bound.joinToString { describe(it.first) }
              },
              style = MaterialTheme.typography.bodySmall,
            )
          }
          val cc = bound.firstOrNull { it.first is Source.Cc }
          if (target is Target.Button && cc != null) {
            // off = tap (toggle buttons), on = the button stays down while the control is "on"
            FilterChip(selected = !cc.second.tap, onClick = { router.setTap(target, !cc.second.tap) }, label = { Text("Segurar") })
          }
          if (bound.isNotEmpty()) TextButton(onClick = { router.clear(target) }) { Text("Limpar") }
          if (learning == target) OutlinedButton(onClick = { router.cancelLearn() }) { Text("Cancelar") }
          else Button(onClick = { router.learn(target) }) { Text("Aprender") }
        }
      }

      HorizontalDivider()
      OutlinedButton(onClick = { router.clearAll() }) { Text("Limpar tudo") }
      Text("")
    }
  }
}
```

**Step 2: `MainActivity.kt` (`Root`)**

- Add `import com.criapix.fm1droid.ui.MidiMapSheet`.
- Add `var midiMap by remember { mutableStateOf(false) }` next to `settings`.
- Add a menu item between "Firmwares" and "Configurações":
  ```kotlin
            DropdownMenuItem(text = { Text("Controle MIDI") }, onClick = { menu = false; midiMap = true })
  ```
- After the `SettingsSheet` line: `if (midiMap) MidiMapSheet(router, emu.prefs, devices) { midiMap = false }`

**Step 3: Build**

Run: `.\gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

**Step 4: Check on the emulator or phone (no keyboard needed)**

`.\gradlew :app:installDebug`, open the app → ⋮ → Controle MIDI. Expected: 22 rows (MASTER, 7 encoders, 14 buttons); "Aprender" turns the row into "Mexa no controle do teclado…" with a "Cancelar" button; closing the sheet cancels the learn. Take a screenshot with the `android` CLI and confirm the rows are readable in landscape.

**Step 5: Commit**

```
git add -A app/src
git commit -m "feat: MIDI learn screen for external controllers"
```

---

### Task 6: Docs and the CK61 checklist

**Files:**
- Modify: `README.md` (new section "Controlador MIDI externo")
- Modify: `docs/DEVICE-TEST.md` (new section)
- Modify: `docs/NOTES.md` (what is not yet known)

**Step 1: `README.md`** — a short section: connect a class-compliant keyboard by USB (USB-B → USB-C cable for the Yamaha CK61's USB TO HOST port); notes play at once; ⋮ → Controle MIDI binds the keyboard's buttons/knobs/sliders to the panel; tap vs "Segurar"; the limits from section 0.

**Step 2: `docs/DEVICE-TEST.md`** — add this checklist (to be run by the user, who has the CK61):

```markdown
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
```

**Step 3: `docs/NOTES.md`** — add a dated entry: the feature exists, was verified only by unit tests and without a physical controller; the CK61 CC numbers are unknown until the checklist above is run; known limits from section 0.

**Step 4: Commit**

```
git add README.md docs
git commit -m "docs: external MIDI controller mapping and CK61 checklist"
```

---

## Out of scope

- Relative-mode (endless) encoders, NRPN, pitch bend or aftertouch as sources.
- Per-firmware maps or shipped presets for specific keyboards (revisit once the CK61 numbers are in `NOTES.md`).
- MIDI out from the FM-1 back to the keyboard.
- Learning by touching the control on the panel image instead of the list.
