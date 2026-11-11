package com.criapix.fm1droid.panel

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.criapix.fm1droid.Native
import java.nio.ByteBuffer
import kotlin.math.roundToInt

private object NativeControls : PanelControls {
  override fun hit(x: Float, y: Float) = Native.hit(x, y)
  override fun keyVel(id: Int, y: Float) = Native.keyVel(id, y)
  override fun key(id: Int, down: Boolean, vel: Int) = Native.key(id, down, vel)
  override fun enc(i: Int, clicks: Int) = Native.enc(i, clicks)
  override fun pot(v: Int) = Native.pot(v)
}

/**
 * The FM-1's panel, filling whatever screen it gets: scaled to fit (a tall 19.5:9 phone is limited
 * by height, a tablet by width), centred, the leftover bands in the panel's own background colour.
 */
@Composable
fun PanelScreen(modifier: Modifier = Modifier, initialPot: Float = 900f, onPot: (Int) -> Unit = {}) {
  val bitmap = remember { Bitmap.createBitmap(1280, 820, Bitmap.Config.ARGB_8888) }
  val image = remember { bitmap.asImageBitmap() }
  val buffer = remember { ByteBuffer.allocateDirect(1280 * 820 * 4) }
  val touch = remember { PanelTouch(NativeControls, initialPot) }
  var frame by remember { mutableIntStateOf(0) }
  var geometry by remember { mutableStateOf(PanelGeometry(1280f, 820f)) }

  LaunchedEffect(Unit) {
    while (true) {
      androidx.compose.runtime.withFrameNanos { }
      if (Native.draw(buffer)) {
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        buffer.rewind()
        frame++
      }
    }
  }
  DisposableEffect(Unit) { onDispose { touch.releaseAll(); onPot(touch.pot.toInt()) } }

  Canvas(
    modifier
      .fillMaxSize()
      .background(Color(0xFF18191B)) // panel.rs ROOM
      .onSizeChanged { geometry = PanelGeometry(it.width.toFloat(), it.height.toFloat()) }
      .pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent()
            val g = geometry
            for (ch in event.changes) {
              val (x, y) = g.toPanel(ch.position.x, ch.position.y)
              val id = ch.id.value
              when {
                ch.changedToDown() -> touch.down(id, x, y)
                ch.changedToUp() -> touch.up(id)
                ch.pressed -> {
                  val d = ch.positionChange()
                  touch.move(id, x, y, d.x / g.scale, d.y / g.scale)
                }
              }
              ch.consume()
            }
          }
        }
      },
  ) {
    frame // read so the canvas redraws whenever the bitmap changed
    val g = geometry
    drawImage(
      image,
      dstOffset = IntOffset(g.left.roundToInt(), g.top.roundToInt()),
      dstSize = IntSize((1280 * g.scale).roundToInt(), (820 * g.scale).roundToInt()),
      filterQuality = FilterQuality.Medium,
    )
  }
}
