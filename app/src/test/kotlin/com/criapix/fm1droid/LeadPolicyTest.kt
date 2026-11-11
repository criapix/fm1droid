package com.criapix.fm1droid

import org.junit.Assert.assertEquals
import org.junit.Test

class LeadPolicyTest {
  @Test fun startsAtOneCallbackPlusAMargin() {
    assertEquals(177 + 128, LeadPolicy.initial(burst = 192, rate = 48_000)) // 192 frames at 48 kHz = 176.4 at 44.1 kHz
    assertEquals(480 + 128, LeadPolicy.initial(burst = 480, rate = 44_100))
  }

  @Test fun staysWithinItsLimits() {
    assertEquals(1024, LeadPolicy.initial(burst = 4000, rate = 48_000))
    assertEquals(128, LeadPolicy.initial(burst = 0, rate = 0))
  }

  @Test fun risesAStepPerBadSecondUpToTheMaximum() {
    assertEquals(369, LeadPolicy.raised(305))
    assertEquals(1024, LeadPolicy.raised(1000))
    assertEquals(1024, LeadPolicy.raised(1024))
  }

  @Test fun readsTheNativeArray() {
    val a = AudioInfo.of(intArrayOf(44_100, 480, 480, 12, 3, 21_500, 96))!!
    assertEquals(AudioInfo(44_100, 480, 480, true, 3, 21_500, 96), a)
    assertEquals(null, AudioInfo.of(null))
    assertEquals(null, AudioInfo.of(intArrayOf(1, 2)))
  }
}
