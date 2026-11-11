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
