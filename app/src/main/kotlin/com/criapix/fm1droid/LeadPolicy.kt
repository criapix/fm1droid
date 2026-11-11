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
