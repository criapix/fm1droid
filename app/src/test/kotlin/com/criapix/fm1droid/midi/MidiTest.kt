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
