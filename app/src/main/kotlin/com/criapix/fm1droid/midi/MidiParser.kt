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
