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
