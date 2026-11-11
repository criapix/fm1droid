package com.criapix.fm1droid.panel

/**
 * The panel (always 820 logical px tall, [logicalW] wide) fitted into a view, centred, aspect kept.
 * With [logicalWidthFor] the width follows the screen's shape, so the fit leaves no bands.
 */
class PanelGeometry(viewW: Float, viewH: Float, logicalW: Float = W) {
  val scale = minOf(viewW / logicalW, viewH / H)
  val left = (viewW - logicalW * scale) / 2f
  val top = (viewH - H * scale) / 2f
  fun toPanel(x: Float, y: Float) = Pair((x - left) / scale, (y - top) / scale)

  companion object {
    const val W = 1280f
    const val H = 820f
    const val MAX_W = 1900

    /** The logical width that makes the panel's shape match a view's (clamped to what the layout supports). */
    fun logicalWidthFor(viewW: Float, viewH: Float): Int =
      if (viewH <= 0f) W.toInt() else Math.round(H * viewW / viewH).coerceIn(W.toInt(), MAX_W)
  }
}

interface PanelControls {
  fun hit(x: Float, y: Float): Int
  fun keyVel(id: Int, y: Float): Int
  fun key(id: Int, down: Boolean, vel: Int)
  fun enc(i: Int, clicks: Int)
  fun pot(v: Int)
}

/** What the touch layer asks the phone's vibration motor for. */
interface HapticSink {
  /** a key or button went down; [button] is false for the note keys, whose strength follows [vel] (1..127) */
  fun key(button: Boolean, vel: Int)
  /** one detent of a stepped encoder */
  fun step()
}

/** Adds haptics to any [PanelControls]: a pulse when a key goes down, a tick per encoder step. */
class HapticControls(private val inner: PanelControls, private val haptics: HapticSink) : PanelControls by inner {
  override fun key(id: Int, down: Boolean, vel: Int) {
    if (down) haptics.key(id < PanelTouch.FIRST_NOTE, vel)
    inner.key(id, down, vel)
  }

  override fun enc(i: Int, clicks: Int) {
    if (clicks != 0) haptics.step()
    inner.enc(i, clicks)
  }
  // the MASTER pot is continuous: no steps, so no haptics
}

/** Multi-touch on the panel, in panel coordinates. One grab per pointer. */
class PanelTouch(private val c: PanelControls, pot: Float = 900f) {
  private sealed interface Grab {
    class Key(var id: Int) : Grab
    /** [acc]: clicks not yet sent (a fraction); [speed]: smoothed drag speed, panel units per ms */
    class Enc(val i: Int, var lastMs: Long, var acc: Float = 0f, var speed: Float = 0f) : Grab
    data object Pot : Grab
  }

  private val grabs = HashMap<Long, Grab>()
  var pot = pot
    private set

  /** [timeMs]: the event's time (any monotonic clock); it sets how fast an encoder drag counts as. */
  fun down(pointer: Long, x: Float, y: Float, timeMs: Long = 0) {
    val h = c.hit(x, y)
    when {
      h >= 100 -> { c.key(h - 100, true, c.keyVel(h - 100, y)); grabs[pointer] = Grab.Key(h - 100) }
      h == 7 -> grabs[pointer] = Grab.Pot
      h in 0..6 -> grabs[pointer] = Grab.Enc(h, timeMs)
    }
  }

  /** dx, dy: the pointer's movement in panel units since the last event, which happened at [timeMs]. */
  fun move(pointer: Long, x: Float, y: Float, dx: Float, dy: Float, timeMs: Long = 0) {
    when (val g = grabs[pointer]) {
      is Grab.Key -> if (g.id >= FIRST_NOTE) {
        val h = c.hit(x, y) - 100
        if (h >= FIRST_NOTE && h != g.id) {
          c.key(g.id, false, 0); g.id = h; c.key(h, true, c.keyVel(h, y))
        }
      }
      is Grab.Enc -> {
        val d = dx - dy // right or up = clockwise
        val dt = (timeMs - g.lastMs).coerceIn(1, 100)
        g.lastMs = timeMs
        g.speed += (kotlin.math.abs(d) / dt - g.speed) * SMOOTH
        // turning back starts from zero: the way to the previous option is a whole step, never a hair
        if (d * g.acc < 0f) g.acc = 0f
        // each bit of travel counts at the step of the speed it was made at: slow = fine, fast = coarse
        g.acc += d / stepFor(g.speed)
        val clicks = g.acc.toInt()
        if (clicks != 0) { g.acc -= clicks; c.enc(g.i, clicks) }
      }
      Grab.Pot -> { pot = (pot + (dx - dy) * POT_GAIN).coerceIn(0f, 1023f); c.pot(pot.toInt()) }
      null -> {}
    }
  }

  fun up(pointer: Long) { (grabs.remove(pointer) as? Grab.Key)?.let { c.key(it.id, false, 0) } }

  fun releaseAll() { grabs.keys.toList().forEach(::up) }

  companion object {
    const val STEP = 14f // panel units of drag per encoder click in a fast drag
    const val SLOW_STEP = 64f // ... in a slow, careful one (about 6 mm on a phone)
    const val SLOW = 0.15f // panel units per ms: at or below this a drag is careful (~1.5 cm/s)
    const val FAST = 1.5f // at or above this it is a sweep (~15 cm/s)
    const val SMOOTH = 0.4f // how much of each event's speed enters the running estimate

    /** Panel units of drag per encoder click at a drag speed: gradual between careful and sweeping. */
    fun stepFor(speed: Float): Float {
      val t = ((speed - SLOW) / (FAST - SLOW)).coerceIn(0f, 1f)
      return SLOW_STEP + (STEP - SLOW_STEP) * t
    }

    const val POT_GAIN = 4f
    const val FIRST_NOTE = 14 // matrix ids 14..40 are the note keys; 0..13 the buttons
  }
}
