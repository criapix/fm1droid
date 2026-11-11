package com.criapix.fm1droid

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.criapix.fm1droid.data.Prefs
import com.criapix.fm1droid.panel.Haptics
import org.junit.Test
import org.junit.runner.RunWith

/** Plays every kind of pulse on the real motor; check `adb shell dumpsys vibrator_manager` afterwards. */
@RunWith(AndroidJUnit4::class)
class HapticsDeviceTest {
  @Test fun playsEveryPulseKind() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val prefs = Prefs(ctx)
    prefs.haptics = true
    val h = Haptics(ctx, prefs)
    for (level in 0..2) {
      prefs.hapticLevel = level
      Log.i("fm1droid", "haptics level=$level: 3 steps, button, soft key, hard key")
      repeat(3) { h.step(); Thread.sleep(160) }
      h.key(button = true, vel = 100); Thread.sleep(300)
      h.key(button = false, vel = 20); Thread.sleep(300)
      h.key(button = false, vel = 127); Thread.sleep(500)
    }
    prefs.hapticLevel = 1
    prefs.haptics = false
    h.step(); h.key(button = true, vel = 100) // must stay silent
    Thread.sleep(300)
    prefs.haptics = true
  }
}
