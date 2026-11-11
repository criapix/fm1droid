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
    assertNull(Native.boot(pkg, ByteArray(0), ByteArray(0), 240))
    assertTrue(Native.audioStart() > 0)
    Thread.sleep(8000)
    val speed = Native.speed()
    Log.i("fm1droid", "speed=$speed starved=${Native.starved()}")
    assertEquals(1, Native.status()) // booted, not halted
    assertTrue("too slow: $speed", speed > 1.0f)
    Native.audioStop()
  }

  @Test fun garbageIsRefused() {
    assertNotNull(Native.boot(ByteArray(64), ByteArray(0), ByteArray(0), 240))
    assertTrue(Native.probe(ByteArray(64)).startsWith("err:"))
  }
}
