package com.criapix.fm1droid.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelGeometryTest {
  @Test fun fitsAWidePhoneByHeightAndCentres() {
    val g = PanelGeometry(viewW = 2400f, viewH = 1080f)
    assertEquals(1080f / 820f, g.scale, 1e-4f)
    assertEquals((2400f - 1280f * g.scale) / 2f, g.left, 1e-3f)
    assertEquals(0f, g.top, 1e-3f)
    val (x, y) = g.toPanel(g.left + 640f * g.scale, 410f * g.scale)
    assertEquals(640f, x, 1e-2f); assertEquals(410f, y, 1e-2f)
  }

  @Test fun fitsATallViewByWidth() {
    val g = PanelGeometry(viewW = 1280f, viewH = 2000f)
    assertEquals(1f, g.scale, 1e-4f); assertEquals((2000f - 820f) / 2f, g.top, 1e-3f)
  }

  @Test fun theGalaxyS25UltraIsFilledEdgeToEdge() {
    // 3120 x 1440 (19.5:9): the layout widens to the screen's shape, so there are no bands
    val w = PanelGeometry.logicalWidthFor(3120f, 1440f)
    assertEquals(1777, w)
    val g = PanelGeometry(3120f, 1440f, w.toFloat())
    // the width is rounded to a whole logical pixel: well under one screen pixel of slack
    assertTrue("top band ${g.top}px", g.top < 1f)
    assertTrue("side band ${g.left}px", g.left < 3f)
  }

  @Test fun logicalWidthIsClamped() {
    assertEquals(1280, PanelGeometry.logicalWidthFor(1000f, 1000f)) // squarer than the panel: letterbox
    assertEquals(1900, PanelGeometry.logicalWidthFor(4000f, 1000f)) // absurdly wide: pillarbox
    assertEquals(1280, PanelGeometry.logicalWidthFor(1f, 0f))
  }
}

class PanelTouchTest {
  private class Fake : PanelControls {
    val log = mutableListOf<String>()
    var under = -1
    override fun hit(x: Float, y: Float) = under
    override fun keyVel(id: Int, y: Float) = 99
    override fun key(id: Int, down: Boolean, vel: Int) { log += "key $id $down $vel" }
    override fun enc(i: Int, clicks: Int) { log += "enc $i $clicks" }
    override fun pot(v: Int) { log += "pot $v" }
  }

  @Test fun aKeyGoesDownAndUpWithItsPointer() {
    val f = Fake(); val t = PanelTouch(f)
    f.under = 120; t.down(1, 0f, 0f); t.up(1)
    assertEquals(listOf("key 20 true 99", "key 20 false 0"), f.log)
  }

  @Test fun slidingAcrossNoteKeysPlaysEachOne() {
    val f = Fake(); val t = PanelTouch(f)
    f.under = 120; t.down(1, 0f, 0f)
    f.under = 121; t.move(1, 0f, 0f, 5f, 0f)
    assertEquals(listOf("key 20 true 99", "key 20 false 0", "key 21 true 99"), f.log)
  }

  @Test fun slidingOffAButtonDoesNotPressItsNeighbour() {
    val f = Fake(); val t = PanelTouch(f)
    f.under = 105; t.down(1, 0f, 0f) // botão (id < 14)
    f.under = 106; t.move(1, 0f, 0f, 5f, 0f)
    assertEquals(listOf("key 5 true 99"), f.log)
  }

  @Test fun draggingAnEncoderUpTurnsItClockwiseInWholeClicks() {
    val f = Fake(); val t = PanelTouch(f)
    f.under = 3; t.down(1, 0f, 0f)
    t.move(1, 0f, 0f, 0f, -PanelTouch.STEP * 2.5f)
    t.move(1, 0f, 0f, 0f, -PanelTouch.STEP * 0.5f)
    assertEquals(listOf("enc 3 2", "enc 3 1"), f.log)
  }

  @Test fun twoPointersAreIndependent() {
    val f = Fake(); val t = PanelTouch(f)
    f.under = 120; t.down(1, 0f, 0f)
    f.under = 3; t.down(2, 0f, 0f); t.move(2, 0f, 0f, PanelTouch.STEP, 0f)
    t.up(1)
    assertEquals(listOf("key 20 true 99", "enc 3 1", "key 20 false 0"), f.log)
  }

  @Test fun thePotFollowsADragAndClamps() {
    val f = Fake(); val t = PanelTouch(f, pot = 1000f)
    f.under = 7; t.down(1, 0f, 0f); t.move(1, 0f, 0f, 0f, -100f)
    assertEquals("pot 1023", f.log.last())
  }

  @Test fun releaseAllLetsGoOfHeldKeys() {
    val f = Fake(); val t = PanelTouch(f)
    f.under = 120; t.down(1, 0f, 0f); t.releaseAll()
    assertEquals("key 20 false 0", f.log.last())
  }
}
