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
    class Enc(val i: Int, var acc: Float = 0f) : Grab
    data object Pot : Grab
  }

  private val grabs = HashMap<Long, Grab>()
  var pot = pot
    private set

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
        g.acc += dx - dy // right or up = clockwise
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
    const val STEP = 14f // panel units of drag per encoder click
    const val POT_GAIN = 4f
    const val FIRST_NOTE = 14 // matrix ids 14..40 are the note keys; 0..13 the buttons
  }
}
