package com.criapix.fm1droid.panel

/** The 1280x820 panel fitted into a view, centred, aspect kept (any screen shape). */
class PanelGeometry(viewW: Float, viewH: Float) {
  val scale = minOf(viewW / W, viewH / H)
  val left = (viewW - W * scale) / 2f
  val top = (viewH - H * scale) / 2f
  fun toPanel(x: Float, y: Float) = Pair((x - left) / scale, (y - top) / scale)

  companion object {
    const val W = 1280f
    const val H = 820f
  }
}

interface PanelControls {
  fun hit(x: Float, y: Float): Int
  fun keyVel(id: Int, y: Float): Int
  fun key(id: Int, down: Boolean, vel: Int)
  fun enc(i: Int, clicks: Int)
  fun pot(v: Int)
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
