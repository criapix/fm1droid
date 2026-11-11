package com.criapix.fm1droid

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineDeviceTest {
  @Test fun bootsAndKeepsUpWithRealTime() {
    val pkg = InstrumentationRegistry.getInstrumentation().context.assets.open("felucca.fwsc").readBytes()
    assertTrue(Native.probe(pkg).startsWith("ok:"))
    assertNull(Native.boot(pkg, ByteArray(0), ByteArray(0), 240, true))
    assertTrue(Native.audioStart() > 0)
    Thread.sleep(8000)
    val speed = Native.speed()
    Log.i("fm1droid", "speed=$speed starved=${Native.starved()}")
    assertEquals(1, Native.status()) // booted, not halted
    assertTrue("too slow: $speed", speed > 1.0f)
    Native.audioStop()
  }

  /** Diagnostic, no assertion on speed: logs `clock=… speed=… starved=…` (adb logcat -s fm1droid). */
  @Test fun clockSweep() {
    val pkg = InstrumentationRegistry.getInstrumentation().context.assets.open("felucca.fwsc").readBytes()
    assertTrue(Native.audioStart() > 0)
    for ((mhz, jit) in listOf(60 to true, 60 to false, 120 to true, 160 to true, 240 to true)) {
      assertNull(Native.boot(pkg, ByteArray(0), ByteArray(0), mhz, jit))
      val s0 = Native.starved()
      Thread.sleep(7000)
      Log.i("fm1droid", "clock=$mhz jit=$jit speed=${Native.speed()} starved=${Native.starved() - s0} abi=${android.os.Build.SUPPORTED_ABIS[0]}")
    }
    Native.audioStop()
  }

  @Test fun garbageIsRefused() {
    assertNotNull(Native.boot(ByteArray(64), ByteArray(0), ByteArray(0), 240, true))
    assertTrue(Native.probe(ByteArray(64)).startsWith("err:"))
  }
}
