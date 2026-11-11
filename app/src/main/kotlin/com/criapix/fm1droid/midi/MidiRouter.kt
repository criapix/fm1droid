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
